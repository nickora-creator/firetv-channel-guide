package com.nickora.firetv.channelguide.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Loads Twin Cities EPG JSON: filesDir cache → GitHub raw feed → SampleEpgData fallback.
 */
class EpgRepository(private val context: Context) {

    suspend fun loadGuide(forceRefresh: Boolean = false): EpgGuide = withContext(Dispatchers.IO) {
        val cacheFile = cacheFile()
        val now = System.currentTimeMillis()

        if (!forceRefresh && cacheFile.exists() && !isStale(cacheFile, now)) {
            parseGuide(cacheFile.readText())?.let { guide ->
                Log.i(TAG, "Loaded EPG from cache (${cacheFile.length()} bytes)")
                return@withContext guide.withFreshWindow(now)
            }
        }

        try {
            val body = downloadRemote()
            cacheFile.writeText(body)
            parseGuide(body)?.let { guide ->
                Log.i(TAG, "Loaded EPG from remote (${body.length} chars)")
                return@withContext guide.withFreshWindow(now)
            }
            Log.w(TAG, "Remote EPG parsed empty; falling back")
        } catch (t: Throwable) {
            Log.w(TAG, "Remote EPG fetch failed: ${t.message}")
            if (cacheFile.exists()) {
                parseGuide(cacheFile.readText())?.let { guide ->
                    Log.i(TAG, "Using stale cache after remote failure")
                    return@withContext guide.withFreshWindow(now)
                }
            }
        }

        // Bundled asset shipped with the APK (may be a day behind remote)
        try {
            val asset = context.assets.open(CACHE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseGuide(asset)?.let { guide ->
                Log.i(TAG, "Loaded EPG from APK assets")
                return@withContext guide.withFreshWindow(now)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Asset EPG unavailable: ${t.message}")
        }

        Log.i(TAG, "Using SampleEpgData fallback")
        SampleEpgData.build(nowMs = now, days = 14)
    }

    /** Force download + cache update (used by daily worker). */
    suspend fun refreshFromRemote(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = downloadRemote()
            cacheFile().writeText(body)
            parseGuide(body) != null
        } catch (t: Throwable) {
            Log.w(TAG, "refreshFromRemote failed: ${t.message}")
            false
        }
    }

    private fun cacheFile(): File = File(context.filesDir, CACHE_NAME)

    private fun isStale(file: File, nowMs: Long): Boolean {
        val age = nowMs - file.lastModified()
        if (age > STALE_AFTER_MS) return true
        return try {
            val generated = JSONObject(file.readText()).optLong("generatedAtMs", 0L)
            generated > 0L && (nowMs - generated) > STALE_AFTER_MS
        } catch (_: Throwable) {
            false
        }
    }

    private fun downloadRemote(): String {
        var lastError: Throwable? = null
        for (url in REMOTE_URLS) {
            try {
                return downloadUrl(url)
            } catch (t: Throwable) {
                Log.w(TAG, "Download failed for $url: ${t.message}")
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("No remote EPG URLs configured")
    }

    private fun downloadUrl(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseGuide(jsonText: String): EpgGuide? {
        val root = JSONObject(jsonText)
        val channelsArr = root.optJSONArray("channels") ?: return null
        val programsArr = root.optJSONArray("programs") ?: JSONArray()
        if (channelsArr.length() == 0) return null

        val channels = mutableListOf<Channel>()
        for (i in 0 until channelsArr.length()) {
            val o = channelsArr.getJSONObject(i)
            val catsArr = o.optJSONArray("categories")
            val cats = mutableSetOf<String>()
            if (catsArr != null) {
                for (c in 0 until catsArr.length()) {
                    cats += catsArr.getString(c)
                }
            }
            channels += Channel(
                id = o.getString("id"),
                number = o.getString("number"),
                callSign = o.getString("callSign"),
                name = o.getString("name"),
                network = o.optString("network", ""),
                favorite = o.optBoolean("favorite", false),
                categories = cats
            )
        }

        val programs = mutableListOf<Program>()
        for (i in 0 until programsArr.length()) {
            val o = programsArr.getJSONObject(i)
            programs += Program(
                id = o.getString("id"),
                channelId = o.getString("channelId"),
                title = o.getString("title"),
                description = o.optString("description", ""),
                startEpochMs = o.getLong("startEpochMs"),
                endEpochMs = o.getLong("endEpochMs"),
                rating = o.optString("rating", "TV-PG"),
                hd = o.optBoolean("hd", true),
                category = o.optString("category", "TV Shows")
            )
        }

        val now = System.currentTimeMillis()
        return EpgGuide(
            channels = channels,
            programs = programs,
            windowStartMs = alignDown(now, HALF_HOUR_MS),
            windowEndMs = alignDown(now, HALF_HOUR_MS) + TimeUnit.DAYS.toMillis(14)
        )
    }

    private fun EpgGuide.withFreshWindow(nowMs: Long): EpgGuide {
        val start = alignDown(nowMs, HALF_HOUR_MS)
        val end = start + TimeUnit.DAYS.toMillis(14)
        val filtered = programs.filter { it.endEpochMs > start && it.startEpochMs < end }
        return copy(
            programs = filtered,
            windowStartMs = start,
            windowEndMs = end
        )
    }

    private fun alignDown(timeMs: Long, intervalMs: Long): Long =
        timeMs - (timeMs % intervalMs)

    companion object {
        private const val TAG = "EpgRepository"
        const val CACHE_NAME = "msp-epg.json"
        const val REMOTE_URL =
            "https://raw.githubusercontent.com/nickora-creator/firetv-channel-guide/main/epg/msp-epg.json"
        private val REMOTE_URLS = listOf(
            REMOTE_URL,
            // jsDelivr mirrors public GitHub content (helps when raw.githubusercontent CDN lags)
            "https://cdn.jsdelivr.net/gh/nickora-creator/firetv-channel-guide@main/epg/msp-epg.json"
        )
        private val STALE_AFTER_MS = TimeUnit.HOURS.toMillis(20)
        private val HALF_HOUR_MS = TimeUnit.MINUTES.toMillis(30)
    }
}

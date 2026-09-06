package com.nickora.firetv.channelguide.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.util.Log
import android.widget.Toast
import com.nickora.firetv.channelguide.data.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Maps guide channels to system TV provider rows (Recast / Hedwig preferred)
 * and tunes via Live TV VIEW intents.
 */
class RecastTuner(private val context: Context) {

    data class SystemChannel(
        val id: Long,
        val displayNumber: String,
        val displayName: String?,
        val inputId: String?,
        val browsable: Boolean
    )

    data class LoadResult(
        val count: Int,
        val samples: List<String>,
        val error: String? = null
    )

    @Volatile
    private var byNumber: Map<String, Long> = emptyMap()

    @Volatile
    private var byName: Map<String, Long> = emptyMap()

    @Volatile
    private var lastError: String? = null

    private val loaded = AtomicBoolean(false)

    fun isLoaded(): Boolean = loaded.get()

    fun channelCount(): Int = byNumber.size

    suspend fun loadChannelMap(): LoadResult = withContext(Dispatchers.IO) {
        val hedwigByNumber = linkedMapOf<String, Long>()
        val anyByNumber = linkedMapOf<String, Long>()
        val hedwigByName = linkedMapOf<String, Long>()
        val anyByName = linkedMapOf<String, Long>()
        val samples = mutableListOf<String>()

        try {
            val projection = arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_INPUT_ID,
                TvContract.Channels.COLUMN_BROWSABLE
            )
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(TvContract.Channels._ID)
                val numIdx = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NAME)
                val inputIdx = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INPUT_ID)
                val browsableIdx = cursor.getColumnIndex(TvContract.Channels.COLUMN_BROWSABLE)

                while (cursor.moveToNext()) {
                    val browsable = if (browsableIdx >= 0) cursor.getInt(browsableIdx) == 1 else true
                    if (!browsable) continue

                    val id = cursor.getLong(idIdx)
                    val displayNumber = cursor.getString(numIdx)?.trim().orEmpty()
                    val displayName = cursor.getString(nameIdx)?.trim()
                    val inputId = cursor.getString(inputIdx)
                    val hedwig = inputId?.contains(HEDWIG_HINT, ignoreCase = true) == true

                    if (displayNumber.isNotEmpty()) {
                        val key = normalizeNumber(displayNumber)
                        if (key.isNotEmpty()) {
                            if (hedwig) {
                                hedwigByNumber.putIfAbsent(key, id)
                            } else {
                                anyByNumber.putIfAbsent(key, id)
                            }
                        }
                    }

                    displayName?.takeIf { it.isNotEmpty() }?.let { name ->
                        val nameKey = normalizeName(name)
                        if (hedwig) {
                            hedwigByName.putIfAbsent(nameKey, id)
                        } else {
                            anyByName.putIfAbsent(nameKey, id)
                        }
                    }

                    if (samples.size < 8) {
                        samples += "$displayNumber -> $id (${displayName ?: "?"}; ${inputId ?: "no-input"})"
                    }
                }
            } ?: run {
                lastError = "TV provider returned no cursor"
                loaded.set(true)
                Log.w(TAG, "Channel query returned null cursor (missing permission?)")
                return@withContext LoadResult(0, emptyList(), lastError)
            }

            // Prefer Recast/Hedwig mappings; fill gaps from any browsable channel.
            val mergedNumbers = LinkedHashMap<String, Long>()
            mergedNumbers.putAll(anyByNumber)
            mergedNumbers.putAll(hedwigByNumber)

            val mergedNames = LinkedHashMap<String, Long>()
            mergedNames.putAll(anyByName)
            mergedNames.putAll(hedwigByName)

            byNumber = mergedNumbers
            byName = mergedNames
            lastError = null
            loaded.set(true)

            Log.i(
                TAG,
                "Loaded ${mergedNumbers.size} channel number mappings " +
                    "(${hedwigByNumber.size} hedwig). Samples: ${samples.take(5)}"
            )
            LoadResult(mergedNumbers.size, samples.take(5), null)
        } catch (se: SecurityException) {
            lastError = "Missing TV listings permission"
            loaded.set(true)
            byNumber = emptyMap()
            byName = emptyMap()
            Log.e(TAG, "SecurityException querying TvContract.Channels", se)
            LoadResult(0, emptyList(), lastError)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            loaded.set(true)
            byNumber = emptyMap()
            byName = emptyMap()
            Log.e(TAG, "Failed to load TV channels", t)
            LoadResult(0, emptyList(), lastError)
        }
    }

    /**
     * Tune to [channel] via Live TV. Returns true if an intent was started
     * for a matched system channel; false if only a fallback / Toast path ran.
     *
     * Fire OS package-locks Recast/Hedwig TvContract rows, so the common path
     * is: miss → open Live TV player + show an Alexa phrase Nick can say aloud.
     */
    fun tune(channel: Channel): Boolean {
        val systemId = resolveSystemChannelId(channel)
        if (systemId != null) {
            return try {
                startLiveTv(systemId)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Live TV for channelId=$systemId", t)
                showAlexaTuneHint(channel)
                launchLiveTvFallback()
                false
            }
        }

        val reason = when {
            lastError != null -> lastError
            !loaded.get() -> "Channel map still loading"
            byNumber.isEmpty() -> "No system TV channels found (Amazon locks Recast rows from 3P apps)"
            else -> "No Recast/Live TV match for ${channel.number} ${channel.callSign}"
        }
        Log.w(TAG, "Tune miss: $reason")
        showAlexaTuneHint(channel)
        launchLiveTvFallback()
        return false
    }

    /** Toast text Nick can literally say to the Cube, e.g. "Alexa, tune to channel 4.1". */
    fun alexaTunePhrase(channel: Channel): String {
        val number = channel.number.trim()
        return if (number.isNotEmpty()) {
            "Alexa, tune to channel $number"
        } else {
            val name = channel.callSign.trim().ifEmpty { channel.name.trim() }
            "Alexa, tune to $name"
        }
    }

    private fun showAlexaTuneHint(channel: Channel) {
        val phrase = alexaTunePhrase(channel)
        Toast.makeText(context, phrase, Toast.LENGTH_LONG).show()
    }

    fun resolveSystemChannelId(channel: Channel): Long? {
        val numKey = normalizeNumber(channel.number)
        byNumber[numKey]?.let { return it }

        // Alternate separators already covered by normalize; try raw variants.
        listOf(channel.number, channel.number.replace('-', '.'), channel.number.replace('.', '-'))
            .map { normalizeNumber(it) }
            .distinct()
            .forEach { key ->
                byNumber[key]?.let { return it }
            }

        normalizeName(channel.callSign).takeIf { it.isNotEmpty() }?.let { key ->
            byName[key]?.let { return it }
        }
        normalizeName(channel.name).takeIf { it.isNotEmpty() }?.let { key ->
            byName[key]?.let { return it }
        }
        // Partial: callSign contained in display name keys
        val call = normalizeName(channel.callSign)
        if (call.length >= 3) {
            byName.entries.firstOrNull { (name, _) -> name.contains(call) || call.contains(name) }
                ?.value
                ?.let { return it }
        }
        return null
    }

    private fun startLiveTv(channelId: Long) {
        val uri = TvContract.buildChannelUri(channelId)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isLiveTvAliasResolvable()) {
                component = ComponentName(LIVE_TV_PACKAGE, LIVE_TV_ACTIVITY)
            }
        }
        // Prefer explicit component when resolvable; otherwise implicit VIEW.
        if (intent.component != null) {
            context.startActivity(intent)
        } else {
            val implicit = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(implicit)
        }
        Log.i(TAG, "Started Live TV VIEW for $uri component=${intent.component}")
    }

    private fun launchLiveTvFallback() {
        try {
            val launch = if (isLiveTvAliasResolvable()) {
                Intent().apply {
                    component = ComponentName(LIVE_TV_PACKAGE, LIVE_TV_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                context.packageManager.getLeanbackLaunchIntentForPackage(LIVE_TV_PACKAGE)
                    ?: context.packageManager.getLaunchIntentForPackage(LIVE_TV_PACKAGE)
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Live TV fallback launch failed", t)
        }
    }

    private fun isLiveTvAliasResolvable(): Boolean {
        return try {
            val probe = Intent().setComponent(ComponentName(LIVE_TV_PACKAGE, LIVE_TV_ACTIVITY))
            context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "RecastTuner"
        private const val LIVE_TV_PACKAGE = "com.amazon.tv.livetv"
        private const val LIVE_TV_ACTIVITY = "com.amazon.tv.livetv.TvChannelsPlayerActivityAlias"
        private const val HEDWIG_HINT = "hedwig"

        /** Normalize "4.1", "4-1", "04.1" → comparable key. */
        fun normalizeNumber(raw: String): String {
            val cleaned = raw.trim()
                .replace('\u2013', '-') // en-dash
                .replace('\u2014', '-')
                .replace('–', '-')
                .replace('—', '-')
            if (cleaned.isEmpty()) return ""
            val parts = cleaned.split('.', '-', ' ')
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            val normalizedParts = parts.map { part ->
                part.trimStart('0').ifEmpty { "0" }
            }
            return normalizedParts.joinToString(".")
        }

        fun normalizeName(raw: String): String =
            raw.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
    }
}

package com.nickora.firetv.channelguide.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nickora.firetv.channelguide.data.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Maps guide channels to system TV provider rows (Recast / Hedwig preferred)
 * and tunes via Live TV.
 *
 * Device reality (Fire TV Cube Recast):
 * - Digit entry (`input text` / digit keyevents) does NOT reliably tune; it often pauses Live TV.
 * - `input keyevent 166` (CHANNEL_UP) and `167` (CHANNEL_DOWN) DO change Recast channels.
 * - Primary UX: bring Live TV forward, then send Ch+/Ch− after a short delay.
 * - Optional TvContract VIEW when a system channel id is visible (usually empty for 3P apps).
 * - Experimental digit-entry path exists but is disabled by default.
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

    enum class ChannelStep {
        UP,
        DOWN
    }

    @Volatile
    private var byNumber: Map<String, Long> = emptyMap()

    @Volatile
    private var byName: Map<String, Long> = emptyMap()

    @Volatile
    private var lastError: String? = null

    private val loaded = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shellExecutor = Executors.newSingleThreadExecutor()

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
     * Program select / legacy Tune: prefer TvContract VIEW when a system id matches;
     * otherwise bring Live TV forward and best-effort CHANNEL_UP once.
     *
     * Direct numeric tune is not reliable on Recast (digit entry pauses Live TV).
     *
     * @return true if a TvContract channel VIEW was started; false if Ch+ path ran
     */
    fun tune(channel: Channel): Boolean {
        val systemId = resolveSystemChannelId(channel)
        if (systemId != null) {
            return try {
                startLiveTv(systemId)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Live TV for channelId=$systemId; falling back to Ch+", t)
                stepChannel(ChannelStep.UP)
                false
            }
        }

        val reason = when {
            lastError != null -> lastError
            !loaded.get() -> "Channel map still loading"
            byNumber.isEmpty() -> "No system TV channels found (Amazon locks Recast rows from 3P apps)"
            else -> "No Recast/Live TV match for ${channel.number} ${channel.callSign}"
        }
        Log.w(TAG, "Tune miss (using Ch+): $reason")
        stepChannel(ChannelStep.UP)

        if (ENABLE_EXPERIMENTAL_DIGIT_ENTRY) {
            Log.i(TAG, "Experimental digit entry also scheduled for ${channel.number}")
            tuneViaDigitEntry(channel)
        }
        return false
    }

    /** Bring Live TV forward, wait briefly, then send CHANNEL_UP (166). */
    fun channelUp() = stepChannel(ChannelStep.UP)

    /** Bring Live TV forward, wait briefly, then send CHANNEL_DOWN (167). */
    fun channelDown() = stepChannel(ChannelStep.DOWN)

    private fun stepChannel(step: ChannelStep) {
        try {
            launchLiveTvPlayer()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch Live TV before channel step", t)
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Couldn't open Live TV",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        val keyCode = when (step) {
            ChannelStep.UP -> KEYCODE_CHANNEL_UP
            ChannelStep.DOWN -> KEYCODE_CHANNEL_DOWN
        }
        val label = when (step) {
            ChannelStep.UP -> "CHANNEL_UP"
            ChannelStep.DOWN -> "CHANNEL_DOWN"
        }

        Log.i(TAG, "$label scheduled after ${CHANNEL_STEP_DELAY_MS}ms")
        mainHandler.postDelayed({
            shellExecutor.execute {
                val ok = injectKeyEvent(keyCode)
                if (!ok) {
                    mainHandler.post {
                        Toast.makeText(
                            context,
                            "Couldn't send $label — is ADB debugging still on?",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }, CHANNEL_STEP_DELAY_MS)
    }

    /**
     * Preferred Live TV entry form: dotted major.minor (e.g. `4.1`, `15-1` → `15.1`).
     * Kept for experimental digit-entry path only.
     */
    fun channelEntryNumber(channel: Channel): String? {
        val raw = channel.number.trim()
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('–', '-')
            .replace('—', '-')
        if (raw.isEmpty()) return null
        val preferred = if (raw.contains('-')) raw.replace('-', '.') else raw
        return sanitizeChannelDigits(preferred)
    }

    /** Only digits, `.`, and `-` — never pass unsanitized strings to the shell. */
    fun sanitizeChannelDigits(raw: String): String? {
        if (raw.isEmpty()) return null
        if (!raw.all { it.isDigit() || it == '.' || it == '-' }) return null
        if (!raw.any { it.isDigit() }) return null
        return raw
    }

    /**
     * Experimental / disabled by default. Digit entry pauses Live TV on Recast Cube;
     * do not use as primary tune path.
     */
    private fun tuneViaDigitEntry(channel: Channel) {
        if (!ENABLE_EXPERIMENTAL_DIGIT_ENTRY) return

        val entry = channelEntryNumber(channel)
        if (entry == null) {
            Log.w(TAG, "Digit entry aborted: unusable channel number '${channel.number}'")
            return
        }

        try {
            launchLiveTvPlayer()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch Live TV player before digit entry", t)
            return
        }

        Log.i(TAG, "Experimental digit entry scheduled for channel=$entry after ${DIGIT_ENTRY_DELAY_MS}ms")
        mainHandler.postDelayed({
            shellExecutor.execute {
                injectChannelDigits(entry)
            }
        }, DIGIT_ENTRY_DELAY_MS)
    }

    private fun injectChannelDigits(number: String): Boolean {
        val safe = sanitizeChannelDigits(number)
        if (safe == null) {
            Log.e(TAG, "Refusing to inject unsanitized channel digits: '$number'")
            return false
        }

        val textOk = runShellCommand(arrayOf("input", "text", safe))
        if (!textOk) {
            val textOkSh = runShellCommand(arrayOf("sh", "-c", "input text $safe"))
            if (!textOkSh) return false
        }

        try {
            Thread.sleep(350L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val keyOk = runShellCommand(arrayOf("input", "keyevent", "23"))
        if (!keyOk) {
            return runShellCommand(arrayOf("sh", "-c", "input keyevent 23"))
        }
        return true
    }

    private fun injectKeyEvent(keyCode: Int): Boolean {
        val ok = runShellCommand(arrayOf("input", "keyevent", keyCode.toString()))
        if (ok) return true
        return runShellCommand(arrayOf("sh", "-c", "input keyevent $keyCode"))
    }

    private fun runShellCommand(argv: Array<String>): Boolean {
        return try {
            val pb = ProcessBuilder(*argv)
            pb.redirectErrorStream(false)
            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            Log.i(
                TAG,
                "shell argv=${argv.joinToString(" ")} exit=$code stdout=${stdout.trim()} stderr=${stderr.trim()}"
            )
            if (stderr.isNotBlank()) {
                Log.w(TAG, "shell stderr: ${stderr.trim()}")
            }
            code == 0
        } catch (t: Throwable) {
            Log.e(TAG, "shell exec failed argv=${argv.joinToString(" ")}", t)
            false
        }
    }

    fun resolveSystemChannelId(channel: Channel): Long? {
        val numKey = normalizeNumber(channel.number)
        byNumber[numKey]?.let { return it }

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

    /** Open Live TV player with ACTION_VIEW (no channel URI). */
    private fun launchLiveTvPlayer() {
        try {
            if (isLiveTvAliasResolvable()) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(LIVE_TV_PACKAGE, LIVE_TV_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Launched Live TV player")
                return
            }
            val launch = context.packageManager.getLeanbackLaunchIntentForPackage(LIVE_TV_PACKAGE)
                ?: context.packageManager.getLaunchIntentForPackage(LIVE_TV_PACKAGE)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                Log.i(TAG, "Launched Live TV package leanback/launch intent")
            } else {
                Log.w(TAG, "No resolvable Live TV launch intent")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Live TV player launch failed", t)
            throw t
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

        /** Android KeyEvent.KEYCODE_CHANNEL_UP */
        private const val KEYCODE_CHANNEL_UP = 166
        /** Android KeyEvent.KEYCODE_CHANNEL_DOWN */
        private const val KEYCODE_CHANNEL_DOWN = 167

        /** Wait for Live TV to come forward before Ch+/Ch− (~500–800ms). */
        private const val CHANNEL_STEP_DELAY_MS = 650L

        /** Experimental digit path only (disabled). */
        private const val DIGIT_ENTRY_DELAY_MS = 2000L

        /**
         * Digit entry (`input text`) pauses Live TV on Recast Cube and does not tune.
         * Leave off unless experimenting with an alternate device.
         */
        const val ENABLE_EXPERIMENTAL_DIGIT_ENTRY = false

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

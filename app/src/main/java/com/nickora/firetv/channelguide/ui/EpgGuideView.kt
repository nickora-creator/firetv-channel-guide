package com.nickora.firetv.channelguide.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nickora.firetv.channelguide.R
import com.nickora.firetv.channelguide.data.Channel
import com.nickora.firetv.channelguide.data.EpgGuide
import com.nickora.firetv.channelguide.data.Program
import com.nickora.firetv.channelguide.data.SampleEpgData
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Classic Recast-style channel × time EPG grid.
 *
 * - Sticky channel column (number + call sign)
 * - Sticky time header (~30 min slots)
 * - Program cells sized by duration
 * - Red "now" line
 * - D-pad focus navigation
 */
class EpgGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onProgramFocused(channel: Channel, program: Program?)
        fun onProgramSelected(channel: Channel, program: Program?)
    }

    var listener: Listener? = null

    private var guide: EpgGuide? = null
    private var filteredChannels: List<Channel> = emptyList()
    private var categoryFilter: String = "All"

    private val halfHourMs = TimeUnit.MINUTES.toMillis(30)
    private val slotWidthPx = dp(180f)
    private val channelColWidth = dp(140f)
    private val rowHeight = dp(56f)
    private val timeHeaderHeight = dp(36f)
    private val gap = dp(2f)
    private val corner = dp(8f)

    private var scrollXpx = 0f
    private var scrollYpx = 0f

    private var focusRow = 0
    private var focusProgramId: String? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_background)
    }
    private val channelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_channel_bg)
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_tile)
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_tile_focused)
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_now_line)
        strokeWidth = dp(2f)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_divider)
        strokeWidth = 1f
    }
    private val textPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_text_primary)
        textSize = sp(14f)
    }
    private val textSecondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_text_secondary)
        textSize = sp(12f)
    }
    private val textOnFocus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_text_on_focus)
        textSize = sp(14f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textChannelNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_text_primary)
        textSize = sp(13f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textChannelName = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_text_secondary)
        textSize = sp(11f)
    }
    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_surface)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.epg_now_line)
    }

    private val tmpRect = RectF()
    private var lastNowMs = System.currentTimeMillis()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    fun setGuide(guide: EpgGuide) {
        this.guide = guide
        applyFilter(categoryFilter)
        // Focus first channel's program at/near now
        lastNowMs = System.currentTimeMillis()
        focusRow = 0
        focusNearestToNow()
        // Scroll so "now" is visible with a little past context
        scrollToNow()
        invalidate()
        notifyFocus()
    }

    fun setCategoryFilter(filter: String) {
        categoryFilter = filter
        applyFilter(filter)
        focusRow = focusRow.coerceIn(0, max(0, filteredChannels.lastIndex))
        focusNearestToNow()
        invalidate()
        notifyFocus()
    }

    fun getFocusedProgram(): Pair<Channel, Program?>? {
        val ch = filteredChannels.getOrNull(focusRow) ?: return null
        return ch to findFocusedProgram(ch)
    }

    private fun applyFilter(filter: String) {
        val g = guide ?: run {
            filteredChannels = emptyList()
            return
        }
        filteredChannels = when (filter) {
            "All" -> g.channels
            "Favorites" -> g.channels.filter { it.favorite }
            else -> g.channels.filter { filter in it.categories }
        }
        if (filteredChannels.isEmpty()) {
            filteredChannels = g.channels
        }
    }

    private fun focusNearestToNow() {
        val ch = filteredChannels.getOrNull(focusRow) ?: return
        val g = guide ?: return
        val now = System.currentTimeMillis()
        val programs = g.programsFor(ch.id)
        val current = programs.firstOrNull { it.startEpochMs <= now && now < it.endEpochMs }
            ?: programs.minByOrNull { kotlin.math.abs(it.startEpochMs - now) }
        focusProgramId = current?.id
    }

    private fun findFocusedProgram(channel: Channel): Program? {
        val g = guide ?: return null
        val programs = g.programsFor(channel.id)
        return programs.firstOrNull { it.id == focusProgramId }
            ?: programs.firstOrNull {
                val now = lastNowMs
                it.startEpochMs <= now && now < it.endEpochMs
            }
    }

    private fun notifyFocus() {
        val ch = filteredChannels.getOrNull(focusRow) ?: return
        listener?.onProgramFocused(ch, findFocusedProgram(ch))
    }

    private fun scrollToNow() {
        val now = System.currentTimeMillis()
        val x = timeToX(now) - channelColWidth - slotWidthPx
        scrollXpx = x.coerceIn(0f, maxScrollX())
        ensureFocusVisible()
    }

    private fun maxScrollX(): Float {
        val g = guide ?: return 0f
        val contentW = minutesToWidth(SampleEpgData.minutesBetween(g.windowStartMs, g.windowEndMs))
        return max(0f, contentW - (width - channelColWidth))
    }

    private fun maxScrollY(): Float {
        val contentH = filteredChannels.size * rowHeight
        return max(0f, contentH - (height - timeHeaderHeight))
    }

    private fun minutesToWidth(minutes: Float): Float =
        (minutes / 30f) * slotWidthPx

    private fun timeToX(epochMs: Long): Float {
        val g = guide ?: return channelColWidth
        val minutes = SampleEpgData.minutesBetween(g.windowStartMs, epochMs)
        return channelColWidth + minutesToWidth(minutes) - scrollXpx
    }

    private fun programLeft(program: Program): Float {
        val g = guide ?: return channelColWidth
        val minutes = SampleEpgData.minutesBetween(g.windowStartMs, program.startEpochMs)
        return channelColWidth + minutesToWidth(minutes) - scrollXpx
    }

    private fun programWidth(program: Program): Float {
        return minutesToWidth(SampleEpgData.minutesBetween(program.startEpochMs, program.endEpochMs))
            .coerceAtLeast(dp(40f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = guide ?: return
        lastNowMs = System.currentTimeMillis()

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Time header background
        canvas.drawRect(0f, 0f, width.toFloat(), timeHeaderHeight, headerBgPaint)

        // Date/now label in channel-header corner
        val nowLabel = SampleEpgData.formatDayTime(lastNowMs)
        canvas.drawText(nowLabel, dp(8f), timeHeaderHeight / 2f + sp(4f), textSecondary)

        // Time slot labels
        val slots = SampleEpgData.timeSlots(g.windowStartMs, g.windowEndMs)
        textSecondary.textAlign = Paint.Align.LEFT
        for (slot in slots) {
            val x = timeToX(slot)
            if (x + slotWidthPx < channelColWidth || x > width) continue
            // subtle vertical tick
            canvas.drawLine(x, timeHeaderHeight - dp(8f), x, timeHeaderHeight, dividerPaint)
            val label = SampleEpgData.formatTime(slot)
            canvas.drawText(label, x + dp(6f), timeHeaderHeight / 2f + sp(4f), textSecondary)
        }

        // Clip program rows below header
        canvas.save()
        canvas.clipRect(0f, timeHeaderHeight, width.toFloat(), height.toFloat())

        val firstRow = max(0, (scrollYpx / rowHeight).toInt())
        val visibleRows = (height / rowHeight).toInt() + 2
        val lastRow = min(filteredChannels.lastIndex, firstRow + visibleRows)

        for (row in firstRow..lastRow) {
            val channel = filteredChannels[row]
            val top = timeHeaderHeight + row * rowHeight - scrollYpx
            drawChannelCell(canvas, channel, top, row == focusRow)
            drawProgramRow(canvas, g, channel, top, row == focusRow)
        }

        // Now line
        val nowX = timeToX(lastNowMs)
        if (nowX >= channelColWidth && nowX <= width) {
            canvas.drawLine(nowX, 0f, nowX, height.toFloat(), nowPaint)
        }

        canvas.restore()

        // Re-draw sticky channel column over scrolled content? Channel cells already drawn at fixed x.
        // Re-draw time header corner to cover scrolled ticks
        canvas.drawRect(0f, 0f, channelColWidth, timeHeaderHeight, headerBgPaint)
        canvas.drawText(nowLabel, dp(8f), timeHeaderHeight / 2f + sp(4f), textSecondary)
        canvas.drawLine(channelColWidth, 0f, channelColWidth, height.toFloat(), dividerPaint)
        canvas.drawLine(0f, timeHeaderHeight, width.toFloat(), timeHeaderHeight, dividerPaint)
    }

    private fun drawChannelCell(canvas: Canvas, channel: Channel, top: Float, focusedRow: Boolean) {
        tmpRect.set(0f, top + gap, channelColWidth - gap, top + rowHeight - gap)
        canvas.drawRoundRect(tmpRect, corner, corner, channelBgPaint)
        if (focusedRow) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                color = ContextCompat.getColor(context, R.color.epg_tile_focused)
            }
            canvas.drawRoundRect(tmpRect, corner, corner, border)
        }
        val heart = if (channel.favorite) "♥ " else ""
        canvas.drawText(
            "$heart${channel.number}",
            dp(10f),
            top + rowHeight / 2f - dp(2f),
            textChannelNum
        )
        canvas.drawText(
            channel.callSign,
            dp(10f),
            top + rowHeight / 2f + sp(12f),
            textChannelName
        )
    }

    private fun drawProgramRow(
        canvas: Canvas,
        guide: EpgGuide,
        channel: Channel,
        top: Float,
        isFocusRow: Boolean
    ) {
        val programs = guide.programsFor(channel.id)
        for (program in programs) {
            val left = programLeft(program)
            val w = programWidth(program)
            val right = left + w - gap
            if (right < channelColWidth || left > width) continue

            val clippedLeft = max(left, channelColWidth)
            tmpRect.set(clippedLeft, top + gap, right, top + rowHeight - gap)
            if (tmpRect.width() < 2f) continue

            val focused = isFocusRow && program.id == focusProgramId
            canvas.drawRoundRect(tmpRect, corner, corner, if (focused) focusPaint else tilePaint)

            // Live progress bar on currently airing focused/unfocused cell
            val now = lastNowMs
            if (program.startEpochMs <= now && now < program.endEpochMs) {
                val progress = ((now - program.startEpochMs).toFloat() / program.durationMs).coerceIn(0f, 1f)
                val fullLeft = max(left, channelColWidth)
                val fullRight = right
                val progRight = fullLeft + (fullRight - fullLeft) * progress
                if (progRight > fullLeft) {
                    val barTop = top + rowHeight - gap - dp(3f)
                    canvas.drawRect(fullLeft, barTop, progRight, top + rowHeight - gap, progressPaint)
                }
            }

            val titlePaint = if (focused) textOnFocus else textPrimary
            val textX = clippedLeft + dp(8f)
            val textY = top + rowHeight / 2f + sp(4f)
            val maxTextW = tmpRect.width() - dp(16f)
            if (maxTextW > dp(20f)) {
                val title = ellipsize(program.title, titlePaint, maxTextW)
                canvas.drawText(title, textX, textY, titlePaint)
            }
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (filteredChannels.isEmpty() || guide == null) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveFocusVertical(-1); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveFocusVertical(1); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveFocusHorizontal(-1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveFocusHorizontal(1); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val ch = filteredChannels.getOrNull(focusRow)
                if (ch != null) {
                    listener?.onProgramSelected(ch, findFocusedProgram(ch))
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                val ch = filteredChannels.getOrNull(focusRow)
                if (ch != null) {
                    listener?.onProgramSelected(ch, findFocusedProgram(ch))
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun moveFocusVertical(delta: Int) {
        val newRow = (focusRow + delta).coerceIn(0, filteredChannels.lastIndex)
        if (newRow == focusRow) return
        val prevProgram = findFocusedProgram(filteredChannels[focusRow])
        focusRow = newRow
        // Prefer program overlapping previous start time
        val g = guide ?: return
        val targetTime = prevProgram?.startEpochMs ?: lastNowMs
        val programs = g.programsFor(filteredChannels[focusRow].id)
        val match = programs.firstOrNull { it.startEpochMs <= targetTime && targetTime < it.endEpochMs }
            ?: programs.minByOrNull { kotlin.math.abs(it.startEpochMs - targetTime) }
        focusProgramId = match?.id
        ensureFocusVisible()
        invalidate()
        notifyFocus()
    }

    private fun moveFocusHorizontal(delta: Int) {
        val ch = filteredChannels.getOrNull(focusRow) ?: return
        val g = guide ?: return
        val programs = g.programsFor(ch.id)
        if (programs.isEmpty()) return
        val idx = programs.indexOfFirst { it.id == focusProgramId }.let { if (it < 0) 0 else it }
        val newIdx = (idx + delta).coerceIn(0, programs.lastIndex)
        focusProgramId = programs[newIdx].id
        ensureFocusVisible()
        invalidate()
        notifyFocus()
    }

    private fun ensureFocusVisible() {
        val ch = filteredChannels.getOrNull(focusRow) ?: return
        val program = findFocusedProgram(ch) ?: return

        // Vertical
        val rowTop = focusRow * rowHeight
        val rowBottom = rowTop + rowHeight
        val visibleTop = scrollYpx
        val visibleBottom = scrollYpx + (height - timeHeaderHeight)
        if (rowTop < visibleTop) {
            scrollYpx = rowTop
        } else if (rowBottom > visibleBottom) {
            scrollYpx = rowBottom - (height - timeHeaderHeight)
        }
        scrollYpx = scrollYpx.coerceIn(0f, maxScrollY())

        // Horizontal — use absolute content coords
        val g = guide ?: return
        val leftAbs = minutesToWidth(SampleEpgData.minutesBetween(g.windowStartMs, program.startEpochMs))
        val rightAbs = leftAbs + programWidth(program)
        val visibleLeft = scrollXpx
        val visibleRight = scrollXpx + (width - channelColWidth)
        if (leftAbs < visibleLeft) {
            scrollXpx = leftAbs
        } else if (rightAbs > visibleRight) {
            scrollXpx = rightAbs - (width - channelColWidth)
        }
        scrollXpx = scrollXpx.coerceIn(0f, maxScrollX())
    }

    // Touch / mouse scroll support for emulator testing
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                requestFocus()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = lastTouchX - event.x
                val dy = lastTouchY - event.y
                scrollXpx = (scrollXpx + dx).coerceIn(0f, maxScrollX())
                scrollYpx = (scrollYpx + dy).coerceIn(0f, maxScrollY())
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Tap to focus program
                hitTest(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float) {
        if (y < timeHeaderHeight) return
        val g = guide ?: return
        val row = ((y - timeHeaderHeight + scrollYpx) / rowHeight).toInt()
        if (row !in filteredChannels.indices) return
        focusRow = row
        val channel = filteredChannels[row]
        if (x < channelColWidth) {
            focusNearestToNow()
        } else {
            val programs = g.programsFor(channel.id)
            val hit = programs.firstOrNull { program ->
                val left = programLeft(program)
                val right = left + programWidth(program)
                x in left..right
            }
            focusProgramId = hit?.id ?: focusProgramId
        }
        ensureFocusVisible()
        invalidate()
        notifyFocus()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollXpx = scrollXpx.coerceIn(0f, maxScrollX())
        scrollYpx = scrollYpx.coerceIn(0f, maxScrollY())
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}

package com.nickora.firetv.channelguide

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nickora.firetv.channelguide.data.Channel
import com.nickora.firetv.channelguide.data.Program
import com.nickora.firetv.channelguide.data.SampleEpgData
import com.nickora.firetv.channelguide.tv.RecastTuner
import com.nickora.firetv.channelguide.ui.EpgGuideView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Classic Fire TV Recast-style channel guide (sample EPG + Live TV tune).
 */
class MainActivity : AppCompatActivity(), EpgGuideView.Listener {

    private lateinit var epgGuide: EpgGuideView
    private lateinit var detailTitle: TextView
    private lateinit var detailTime: TextView
    private lateinit var detailRating: TextView
    private lateinit var detailHd: TextView
    private lateinit var detailDescription: TextView
    private lateinit var detailWatchOn: TextView
    private lateinit var btnTune: Button
    private lateinit var btnRecord: Button
    private lateinit var filterBar: LinearLayout

    private lateinit var recastTuner: RecastTuner
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var focusedChannel: Channel? = null
    private var focusedProgram: Program? = null
    private var selectedFilter = "All"

    private val filters = listOf(
        "All", "Favorites", "Sports", "News", "Movies", "Kids", "TV Shows"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recastTuner = RecastTuner(applicationContext)

        bindViews()
        buildFilterPills()
        wireActions()
        loadSystemChannels()

        val guide = SampleEpgData.build(days = 10)
        epgGuide.listener = this
        epgGuide.setGuide(guide)
        epgGuide.requestFocus()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun loadSystemChannels() {
        activityScope.launch {
            val result = recastTuner.loadChannelMap()
            Log.i(
                TAG,
                "System TV channel map: count=${result.count} error=${result.error} " +
                    "samples=${result.samples}"
            )
        }
    }

    private fun bindViews() {
        epgGuide = findViewById(R.id.epgGuide)
        detailTitle = findViewById(R.id.detailTitle)
        detailTime = findViewById(R.id.detailTime)
        detailRating = findViewById(R.id.detailRating)
        detailHd = findViewById(R.id.detailHd)
        detailDescription = findViewById(R.id.detailDescription)
        detailWatchOn = findViewById(R.id.detailWatchOn)
        btnTune = findViewById(R.id.btnTune)
        btnRecord = findViewById(R.id.btnRecord)
        filterBar = findViewById(R.id.filterBar)
    }

    private fun buildFilterPills() {
        filterBar.removeAllViews()
        val padH = dp(14)
        val padV = dp(8)
        val margin = dp(8)

        filters.forEach { label ->
            val pill = TextView(this).apply {
                text = label
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (label == selectedFilter) R.color.epg_text_on_focus else R.color.epg_text_primary
                    )
                )
                textSize = 14f
                typeface = if (label == selectedFilter) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(padH, padV, padH, padV)
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (label == selectedFilter) R.drawable.bg_filter_selected else R.drawable.bg_filter_outline
                )
                isFocusable = true
                isClickable = true
                gravity = Gravity.CENTER
                setOnClickListener { selectFilter(label) }
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && label != selectedFilter) {
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.epg_text_on_focus))
                        background = ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.bg_filter_selected
                        )
                    } else if (!hasFocus && label != selectedFilter) {
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.epg_text_primary))
                        background = ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.bg_filter_outline
                        )
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                        (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                    ) {
                        selectFilter(label)
                        true
                    } else false
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = margin }
            filterBar.addView(pill, lp)
        }
    }

    private fun selectFilter(label: String) {
        selectedFilter = label
        buildFilterPills()
        epgGuide.setCategoryFilter(label)
        epgGuide.requestFocus()
    }

    private fun wireActions() {
        btnTune.setOnClickListener { tuneToFocused() }
        btnRecord.setOnClickListener { stubRecord() }

        // After buttons, push focus into grid on down
        btnTune.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                epgGuide.requestFocus()
                true
            } else false
        }
        btnRecord.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                epgGuide.requestFocus()
                true
            } else false
        }
    }

    override fun onProgramFocused(channel: Channel, program: Program?) {
        focusedChannel = channel
        focusedProgram = program
        updateDetailPanel(channel, program)
    }

    override fun onProgramSelected(channel: Channel, program: Program?) {
        focusedChannel = channel
        focusedProgram = program
        updateDetailPanel(channel, program)
        tuneToFocused()
    }

    private fun updateDetailPanel(channel: Channel, program: Program?) {
        if (program == null) {
            detailTitle.text = getString(R.string.no_program)
            detailTime.text = ""
            detailRating.visibility = View.GONE
            detailHd.visibility = View.GONE
            detailDescription.text = ""
            detailWatchOn.text = getString(R.string.watch_on, channel.callSign)
            return
        }
        detailTitle.text = program.title
        detailTime.text = SampleEpgData.formatTimeRange(program.startEpochMs, program.endEpochMs)
        detailRating.visibility = View.VISIBLE
        detailRating.text = program.rating
        detailHd.visibility = if (program.hd) View.VISIBLE else View.GONE
        detailDescription.text = program.description
        detailWatchOn.text = getString(R.string.watch_on, channel.callSign)
    }

    private fun tuneToFocused() {
        val ch = focusedChannel
        if (ch == null) {
            Toast.makeText(this, R.string.tune_no_channel, Toast.LENGTH_SHORT).show()
            return
        }
        recastTuner.tune(ch)
    }

    private fun stubRecord() {
        val prog = focusedProgram
        val msg = if (prog != null) {
            getString(R.string.record_stub, prog.title)
        } else {
            getString(R.string.record)
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val TAG = "ChannelGuide"
    }
}

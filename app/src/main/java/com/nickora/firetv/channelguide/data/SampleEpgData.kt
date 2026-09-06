package com.nickora.firetv.channelguide.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

/**
 * Generates sample OTA-style EPG relative to "now".
 * Leads with confirmed Recast channels 2.1 / 2.2; other rows are generic OTA placeholders.
 * ~10 channels × ~10 days of half-hour / hour blocks.
 */
object SampleEpgData {

    private val HALF_HOUR = TimeUnit.MINUTES.toMillis(30)
    private val HOUR = TimeUnit.HOURS.toMillis(1)
    private val DAY = TimeUnit.DAYS.toMillis(1)

    fun build(nowMs: Long = System.currentTimeMillis(), days: Int = 10): EpgGuide {
        val windowStart = alignDown(nowMs - DAY, HALF_HOUR) // include yesterday
        val windowEnd = windowStart + days * DAY

        val channels = sampleChannels()
        val programs = mutableListOf<Program>()
        var progSeq = 0

        channels.forEachIndexed { index, channel ->
            val templates = templatesFor(channel)
            var cursor = windowStart
            // Offset each channel slightly so grid doesn't look identical
            val seed = Random(channel.id.hashCode() + 42)
            while (cursor < windowEnd) {
                val template = templates[seed.nextInt(templates.size)]
                val durationSlots = template.durationSlots
                val duration = durationSlots * HALF_HOUR
                val end = minOf(cursor + duration, windowEnd)
                if (end > cursor) {
                    progSeq++
                    val hourOfDay = Calendar.getInstance(TimeZone.getDefault()).apply {
                        timeInMillis = cursor
                    }.get(Calendar.HOUR_OF_DAY)
                    val title = pickTitle(template, hourOfDay, seed)
                    programs += Program(
                        id = "p$progSeq-${channel.id}",
                        channelId = channel.id,
                        title = title,
                        description = template.description.replace("{title}", title),
                        startEpochMs = cursor,
                        endEpochMs = end,
                        rating = template.rating,
                        hd = true,
                        category = template.category
                    )
                }
                cursor = end
            }
            if (index == 0) {
                // no-op; generation already covers now
            }
        }

        return EpgGuide(
            channels = channels,
            programs = programs,
            windowStartMs = windowStart,
            windowEndMs = windowEnd
        )
    }

    /**
     * Confirmed on-device Recast: 2.1 and 2.2 first.
     * Remaining rows are generic OTA placeholders (not tied to a specific DMA).
     */
    private fun sampleChannels(): List<Channel> = listOf(
        Channel(
            "ch-21", "2.1", "PBS", "Channel 2.1", "PBS",
            favorite = true, categories = setOf("Kids", "TV Shows", "Movies")
        ),
        Channel(
            "ch-22", "2.2", "CREATE", "Channel 2.2", "PBS",
            favorite = true, categories = setOf("TV Shows")
        ),
        Channel(
            "ch-41", "4.1", "CBS", "Channel 4.1", "CBS",
            favorite = true, categories = setOf("News", "Sports", "TV Shows")
        ),
        Channel(
            "ch-51", "5.1", "ABC", "Channel 5.1", "ABC",
            favorite = true, categories = setOf("News", "TV Shows")
        ),
        Channel(
            "ch-91", "9.1", "FOX", "Channel 9.1", "FOX",
            favorite = true, categories = setOf("News", "Sports", "TV Shows")
        ),
        Channel(
            "ch-111", "11.1", "NBC", "Channel 11.1", "NBC",
            favorite = true, categories = setOf("News", "Sports", "TV Shows")
        ),
        Channel(
            "ch-23", "2.3", "PBSK", "Channel 2.3", "PBS Kids",
            favorite = false, categories = setOf("Kids")
        ),
        Channel(
            "ch-42", "4.2", "COZI", "Channel 4.2", "Cozi",
            favorite = false, categories = setOf("TV Shows", "Movies")
        ),
        Channel(
            "ch-231", "23.1", "CW", "Channel 23.1", "CW",
            favorite = false, categories = setOf("TV Shows", "Sports")
        ),
        Channel(
            "ch-451", "45.1", "ION", "Channel 45.1", "ION",
            favorite = false, categories = setOf("TV Shows", "Movies")
        )
    )

    private data class Template(
        val titles: List<String>,
        val description: String,
        val durationSlots: Int,
        val rating: String,
        val category: String,
        val daypart: DayPart = DayPart.ANY
    )

    private enum class DayPart { MORNING, DAYTIME, PRIMETIME, LATE, ANY }

    private fun templatesFor(channel: Channel): List<Template> {
        val news = Template(
            titles = listOf("${channel.callSign} News at", "Morning News", "Evening News", "Nightly News"),
            description = "Local and national news from {title}.",
            durationSlots = 1,
            rating = "TV-PG",
            category = "News",
            daypart = DayPart.ANY
        )
        val sports = Template(
            titles = listOf("College Football", "MLB Baseball", "NBA Basketball", "PBR Team Series", "SportsCenter Local"),
            description = "Live sports coverage: {title}.",
            durationSlots = 4,
            rating = "TV-PG",
            category = "Sports"
        )
        val drama = Template(
            titles = listOf("NCIS", "Blue Bloods", "Chicago Fire", "Law & Order", "FBI"),
            description = "Drama series episode of {title}.",
            durationSlots = 2,
            rating = "TV-14",
            category = "TV Shows",
            daypart = DayPart.PRIMETIME
        )
        val comedy = Template(
            titles = listOf("The Neighborhood", "Young Sheldon", "Abbott Elementary", "Night Court"),
            description = "Comedy episode: {title}.",
            durationSlots = 1,
            rating = "TV-PG",
            category = "TV Shows"
        )
        val movie = Template(
            titles = listOf("Friday Night Movie", "Sunday Cinema", "Classic Film Hour", "Action Movie Matinee"),
            description = "Feature film block: {title}.",
            durationSlots = 4,
            rating = "TV-14",
            category = "Movies"
        )
        val kids = Template(
            titles = listOf("Sesame Street", "Daniel Tiger", "Wild Kratts", "Curious George", "Odd Squad"),
            description = "Kids programming: {title}.",
            durationSlots = 1,
            rating = "TV-Y",
            category = "Kids"
        )
        val paid = Template(
            titles = listOf("Paid Programming", "Infomercial"),
            description = "Paid programming.",
            durationSlots = 1,
            rating = "TV-G",
            category = "TV Shows",
            daypart = DayPart.LATE
        )
        val weather = Template(
            titles = listOf("Weather Now", "Forecast Loop", "Radar Live"),
            description = "Continuous weather updates.",
            durationSlots = 1,
            rating = "TV-G",
            category = "News"
        )
        val pbsDoc = Template(
            titles = listOf("NOVA", "Nature", "Frontline", "American Experience", "Hemingway"),
            description = "Documentary: {title}.",
            durationSlots = 2,
            rating = "TV-PG",
            category = "TV Shows"
        )
        val lifestyle = Template(
            titles = listOf("This Old House", "America's Test Kitchen", "Ask This Old House", "Craft in America"),
            description = "Lifestyle: {title}.",
            durationSlots = 1,
            rating = "TV-G",
            category = "TV Shows"
        )

        return when (channel.network) {
            "CBS", "ABC", "NBC", "FOX" -> listOf(news, news, drama, comedy, sports, paid, movie)
            "PBS" -> if (channel.id == "ch-22") listOf(lifestyle, lifestyle, paid)
            else listOf(pbsDoc, kids, news, lifestyle, movie)
            "PBS Kids" -> listOf(kids, kids, kids)
            "Weather" -> listOf(weather)
            "CW" -> listOf(drama, comedy, sports, paid)
            "ION", "Bounce", "Cozi" -> listOf(drama, movie, comedy, paid)
            else -> listOf(drama, comedy, paid, news)
        }
    }

    private fun pickTitle(template: Template, hourOfDay: Int, seed: Random): String {
        val base = template.titles[seed.nextInt(template.titles.size)]
        return if (base.endsWith(" at")) {
            val label = when (hourOfDay) {
                in 5..10 -> "Morning"
                in 11..15 -> "Midday"
                in 16..18 -> "$hourOfDay"
                in 19..22 -> "Evening"
                else -> "Late"
            }
            "$base $label"
        } else base
    }

    private fun alignDown(timeMs: Long, intervalMs: Long): Long {
        return timeMs - (timeMs % intervalMs)
    }

    fun formatTimeRange(startMs: Long, endMs: Long): String {
        return "${formatTime(startMs)}–${formatTime(endMs)}"
    }

    fun formatTime(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val hour = cal.get(Calendar.HOUR)
        val displayHour = if (hour == 0) 12 else hour
        val minute = cal.get(Calendar.MINUTE)
        val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        return String.format(Locale.US, "%d:%02d %s", displayHour, minute, ampm)
    }

    fun formatDayTime(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val day = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)?.uppercase(Locale.US) ?: ""
        val month = cal.get(Calendar.MONTH) + 1
        val date = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%s %02d/%02d %s", day, month, date, formatTime(epochMs))
    }

    /** Half-hour slot labels from window start. */
    fun timeSlots(windowStartMs: Long, windowEndMs: Long): List<Long> {
        val slots = mutableListOf<Long>()
        var t = alignDown(windowStartMs, HALF_HOUR)
        while (t < windowEndMs) {
            slots += t
            t += HALF_HOUR
        }
        return slots
    }

    fun minutesBetween(fromMs: Long, toMs: Long): Float {
        return max(0f, (toMs - fromMs).toFloat() / TimeUnit.MINUTES.toMillis(1))
    }
}

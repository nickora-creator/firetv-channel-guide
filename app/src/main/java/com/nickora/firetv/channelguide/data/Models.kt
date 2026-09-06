package com.nickora.firetv.channelguide.data

/**
 * Core EPG models for the classic channel × time grid.
 */
data class Channel(
    val id: String,
    val number: String,
    val callSign: String,
    val name: String,
    val network: String,
    val favorite: Boolean = false,
    val categories: Set<String> = emptySet()
)

data class Program(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val rating: String = "TV-PG",
    val hd: Boolean = true,
    val category: String = "TV Shows"
) {
    val durationMs: Long get() = (endEpochMs - startEpochMs).coerceAtLeast(0L)

    fun overlaps(fromMs: Long, toMs: Long): Boolean =
        startEpochMs < toMs && endEpochMs > fromMs
}

data class EpgGuide(
    val channels: List<Channel>,
    val programs: List<Program>,
    /** Inclusive guide window start (aligned to a half-hour). */
    val windowStartMs: Long,
    /** Exclusive guide window end. */
    val windowEndMs: Long
) {
    fun programsFor(channelId: String): List<Program> =
        programs.filter { it.channelId == channelId }.sortedBy { it.startEpochMs }
}

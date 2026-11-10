package app.polyphony.core.domain.connection

import app.polyphony.core.domain.transcode.NetworkKind
import kotlin.math.roundToLong

/** How each kind of network has done, from the stored samples (diagnostics). */
object ConnectionSummary {

    data class Row(
        val network: NetworkKind,
        val samples: Int,
        /** Time streamed on it. */
        val streamedMs: Long,
        /** Median of the samples that measured a throughput; null when none did. */
        val medianThroughputKbps: Int?,
        val stalls: Int,
        val stallsPerHour: Double,
        /** Time the latency cap was in force. */
        val cappedMs: Long,
    )

    /** One row per kind of network that has samples, in [NetworkKind] order. */
    fun of(samples: List<ConnectionSample>): List<Row> =
        samples.groupBy { it.network }.entries.sortedBy { it.key.ordinal }.map { (kind, group) ->
            val streamedMs = group.sumOf { it.durationMs }
            val stalls = group.sumOf { it.stalls }
            Row(
                network = kind,
                samples = group.size,
                streamedMs = streamedMs,
                medianThroughputKbps = ConnectionWindow.median(group.mapNotNull { it.throughputKbps?.toLong() })?.toInt(),
                stalls = stalls,
                stallsPerHour = if (streamedMs > 0) stalls * HOUR_MS.toDouble() / streamedMs else 0.0,
                cappedMs = group.sumOf { it.cappedMs },
            )
        }

    /** A line per row, for the diagnostics report and screen: "Wi-Fi: 120 samples (1 h 0 min), …". */
    fun describe(rows: List<Row>): List<String> = rows.map { row -> "${label(row.network)}: ${details(row)}" }

    /** "120 samples (1 h 0 min), median 2.5 Mbit/s, 0.3 stalls/h, capped 5 min". */
    fun details(row: Row): String = buildString {
        append(row.samples).append(if (row.samples == 1) " sample" else " samples")
        append(" (").append(duration(row.streamedMs)).append("), ")
        append("median ").append(row.medianThroughputKbps?.let(::rate) ?: "unmeasured").append(", ")
        append(formatOneDecimal(row.stallsPerHour)).append(" stalls/h")
        if (row.cappedMs > 0) append(", capped ").append(duration(row.cappedMs))
    }

    fun label(kind: NetworkKind): String = when (kind) {
        NetworkKind.WIFI -> "Wi-Fi"
        NetworkKind.ETHERNET -> "Ethernet"
        NetworkKind.CELLULAR -> "Mobile data"
        NetworkKind.OTHER -> "Other"
        NetworkKind.NONE -> "Offline"
    }

    fun rate(kbps: Int): String {
        if (kbps < 1_000) return "$kbps kbit/s"
        val tenths = (kbps + 50) / 100
        return "${tenths / 10}.${tenths % 10} Mbit/s"
    }

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
            minutes >= 1 -> "$minutes min"
            else -> "${ms / 1_000} s"
        }
    }

    private fun formatOneDecimal(value: Double): String {
        val tenths = (value * 10).roundToLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    private const val HOUR_MS = 3_600_000L
}

/**
 * How long samples are kept: [maxAgeMs], and at most [maxRows] of the newest. The database applies
 * the SQL twin of [retain] after every insert.
 */
data class ConnectionRetention(
    val maxAgeMs: Long = 14L * 24 * 3_600_000,
    val maxRows: Int = 20_000,
) {
    /** Samples that ended before this are dropped. */
    fun cutoffMs(nowMs: Long): Long = nowMs - maxAgeMs

    /** What survives pruning at [nowMs], newest first. */
    fun retain(samples: List<ConnectionSample>, nowMs: Long): List<ConnectionSample> {
        val cutoff = cutoffMs(nowMs)
        return samples.filter { it.endedAtMs >= cutoff }.sortedByDescending { it.endedAtMs }.take(maxRows)
    }
}

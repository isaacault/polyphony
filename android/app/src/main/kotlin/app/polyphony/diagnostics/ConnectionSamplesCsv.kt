package app.polyphony.diagnostics

import app.polyphony.core.domain.connection.ConnectionSample

/**
 * The connection samples as CSV for the diagnostics report, one row per sample, oldest first, with
 * the columns of the stored sample (and its derived duration and source). Empty cells are unknown.
 */
internal object ConnectionSamplesCsv {
    private val columns: List<Pair<String, (ConnectionSample) -> Any?>> = listOf(
        "startedAtMs" to { it.startedAtMs },
        "endedAtMs" to { it.endedAtMs },
        "durationMs" to { it.durationMs },
        "trigger" to { it.trigger },
        "network" to { it.network },
        "metered" to { it.metered },
        "vpn" to { it.vpn },
        "atHome" to { it.atHome },
        "responseMs" to { it.responseMs },
        "networkBytes" to { it.networkBytes },
        "networkMs" to { it.networkMs },
        "throughputKbps" to { it.throughputKbps },
        "cachedBytes" to { it.cachedBytes },
        "downloadedBytes" to { it.downloadedBytes },
        "source" to { it.source },
        "stalls" to { it.stalls },
        "stallMs" to { it.stallMs },
        "serverId" to { it.serverId },
        "profileId" to { it.profileId },
        "codec" to { it.codec },
        "maxBitrateKbps" to { it.maxBitrateKbps },
        "reason" to { it.reason },
        "capped" to { it.capped },
        "cappedMs" to { it.cappedMs },
    )

    fun write(samples: List<ConnectionSample>, out: Appendable) {
        out.append(columns.joinToString(",") { it.first }).append('\n')
        samples.forEach { sample ->
            out.append(columns.joinToString(",") { (_, value) -> cell(value(sample)) }).append('\n')
        }
    }

    private fun cell(value: Any?): String {
        val text = value?.toString() ?: return ""
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}

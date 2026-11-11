package app.polyphony.core.playback.stream

import app.polyphony.core.domain.transcode.AdaptiveStreaming
import app.polyphony.core.domain.transcode.NetworkState

/**
 * Which upcoming songs to have fully on the phone, so a network drop doesn't stop the music at
 * the next song. Measured in minutes of music rather than songs (a song is 2 to 10 minutes):
 *
 * - on Wi-Fi (unmetered): the next 20 minutes, at least 2 songs and at most 10, which rides out
 *   a typical dead zone (a tunnel, a lift, a patchy train line) with room to spare;
 * - on mobile data: the next 8 minutes, 1 to 3 songs; every song fetched ahead costs data even
 *   when it's skipped, so less, and none when the setting is off;
 * - offline: nothing to fetch;
 * - while the connection keeps stalling (a road with patchy signal, #185): the next 30 minutes,
 *   up to 12 songs, on Wi-Fi or mobile data alike (when fetching ahead on mobile data is on);
 *   adaptive songs are then fetched at the lowest rung, ~240 KB a minute, so whatever signal
 *   there is goes into the queue ahead.
 *
 * Songs already on the phone (downloaded) count towards the minutes but aren't fetched. The
 * fetching itself also stops at a quarter of the stream cache, so it never evicts what was just
 * played (see QueuePrefetcher).
 */
object PrefetchPlan {

    data class Upcoming(val trackId: String, val durationMs: Long, val onDevice: Boolean = false, val fetchable: Boolean = true)

    data class Budget(val aheadMs: Long, val minSongs: Int, val maxSongs: Int)

    val WIFI = Budget(aheadMs = 20 * 60_000L, minSongs = 2, maxSongs = 10)
    val MOBILE = Budget(aheadMs = 8 * 60_000L, minSongs = 1, maxSongs = 3)
    val FLAKY = Budget(
        aheadMs = AdaptiveStreaming.AHEAD_WHEN_FLAKY_MS, minSongs = 2, maxSongs = AdaptiveStreaming.AHEAD_WHEN_FLAKY_MAX_SONGS,
    )

    /** An unknown length counts as a typical song, so a queue of them still stops somewhere. */
    private const val UNKNOWN_DURATION_MS = 4 * 60_000L

    fun budget(network: NetworkState, enabled: Boolean, onMobileData: Boolean, flaky: Boolean = false): Budget? = when {
        !enabled || !network.isOnline -> null
        network.isMetered && !onMobileData -> null
        flaky -> FLAKY
        !network.isMetered -> WIFI
        else -> MOBILE
    }

    /**
     * The files of an HLS media playlist to fetch, in order: the init segment (EXT-X-MAP) and the
     * segments, as the playlist names them (relative to it).
     */
    fun hlsFiles(playlist: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") ->
                    Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let(out::add)
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> out += line
            }
        }
        return out
    }

    /** The songs to fetch, nearest first, out of [upcoming] (the queue after the current song, in play order). */
    fun pick(upcoming: List<Upcoming>, budget: Budget?): List<String> {
        if (budget == null) return emptyList()
        val out = mutableListOf<String>()
        var aheadMs = 0L
        for ((i, song) in upcoming.withIndex()) {
            if (i >= budget.maxSongs) break
            if (aheadMs >= budget.aheadMs && i >= budget.minSongs) break
            if (!song.onDevice && song.fetchable) out += song.trackId
            aheadMs += song.durationMs.takeIf { it > 0 } ?: UNKNOWN_DURATION_MS
        }
        return out
    }
}

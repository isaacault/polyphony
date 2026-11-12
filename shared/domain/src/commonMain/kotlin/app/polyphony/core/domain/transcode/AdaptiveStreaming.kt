package app.polyphony.core.domain.transcode

import app.polyphony.core.domain.model.Server

/**
 * Adaptive streaming from continuo (#185): a song streamed from a continuo server plays as HLS,
 * Opus at [LADDER] kbit/s in 6-second segments, so the player changes quality between segments
 * as the connection changes, on Wi-Fi and on mobile data alike. Gapless across songs (continuo
 * states each rendition's trims; docs/spikes/hls-gapless).
 *
 * What it decides, from the connection as it is now and never from its history:
 * - [route]: which songs stream that way; downloads, Play now songs and other servers don't.
 * - [ceilingKbps]: the streaming rules' profile still bounds the quality (data saver stays small).
 * - [capKbps]: while the connection keeps stalling (LatencyMonitor.degraded) the player stays on
 *   the lowest rung, so what does arrive goes as far as possible, and the queue is fetched ahead
 *   at that rung ([AHEAD_WHEN_FLAKY]).
 * - [selectorMaxBitrate]: the cap as Media3's maxAudioBitrate, which compares a rendition's peak
 *   BANDWIDTH (continuo announces kbit/s x 1.06 x 1.25).
 */
object AdaptiveStreaming {
    /** continuo's renditions, kbit/s (continuo/stream/playlists.py LADDER). */
    val LADDER = listOf(32, 64, 128, 256)

    /** Songs fetched ahead while the connection comes and goes: 32 kbit/s is ~240 KB a minute. */
    const val AHEAD_WHEN_FLAKY_MS = 30 * 60_000L
    const val AHEAD_WHEN_FLAKY_MAX_SONGS = 12

    enum class Route { HLS, PROGRESSIVE }

    fun isContinuo(server: Server?): Boolean = server?.isContinuo == true

    /**
     * HLS when the song comes from a continuo server's network stream: not when the setting is off,
     * not for a Play now song (continuo serves those while they download), not when a downloaded
     * copy plays instead.
     */
    fun route(server: Server?, enabled: Boolean, playNow: Boolean, downloadedPlays: Boolean): Route =
        if (enabled && isContinuo(server) && !playNow && !downloadedPlays) Route.HLS else Route.PROGRESSIVE

    /** The highest rung the streaming rules allow: a profile's bitrate bound, none for original/lossless. */
    fun ceilingKbps(profile: TranscodeProfile): Int =
        if (profile.maxBitrateKbps > 0) rungAtOrBelow(profile.maxBitrateKbps) else LADDER.last()

    /**
     * The highest rung to play now: the rules' ceiling, or the lowest rung while the connection
     * keeps failing. Not while there's no network at all: nothing is fetched then, and what plays
     * is whatever level the stream cache holds (a song fetched ahead before the connection turned).
     */
    fun capKbps(profile: TranscodeProfile, degraded: Boolean, online: Boolean = true): Int =
        if (degraded && online) LADDER.first() else ceilingKbps(profile)

    fun rungAtOrBelow(kbps: Int): Int = LADDER.lastOrNull { it <= kbps } ?: LADDER.first()

    /** maxAudioBitrate (bit/s) letting through exactly the rungs up to [capKbps]: between it and the next. */
    fun selectorMaxBitrate(capKbps: Int): Int =
        if (capKbps >= LADDER.last()) Int.MAX_VALUE else rungAtOrBelow(capKbps) * 1_400

    /** The rung an adaptive stream is playing, from its rendition's announced peak bandwidth (bit/s). */
    fun rungForBandwidth(bitsPerSecond: Int): Int? =
        if (bitsPerSecond <= 0) null else LADDER.lastOrNull { it * 1_325 <= bitsPerSecond }

    /** The id of [profile], so labels can say "Adaptive". */
    const val PROFILE_ID = "adaptive.continuo"

    /**
     * What the player shows for an adaptive song: Opus at the rung playing ([playingKbps], once the
     * player has a rendition), else at the cap it may rise to.
     */
    fun profile(capKbps: Int, playingKbps: Int? = null): TranscodeProfile =
        TranscodeProfile(PROFILE_ID, "Adaptive", AudioCodec.OPUS, maxBitrateKbps = playingKbps ?: capKbps)

    /** The level songs ahead are encoded at: what's playing now, within the cap. */
    fun upcomingKbps(playingKbps: Int?, capKbps: Int): Int =
        rungAtOrBelow(minOf(playingKbps ?: capKbps, capKbps))
}

package app.polyphony.core.dlna

import app.polyphony.core.dlna.player.DlnaPlayer
import app.polyphony.core.domain.playback.DlnaRenderer

/**
 * Registered by the playback service. Mirrors Media3 Cast's `SessionAvailabilityListener`:
 * on available, move the queue/position onto [DlnaPlayer] and `mediaSession.setPlayer(player)`;
 * on unavailable, read the player's state back and return to the local ExoPlayer.
 * Both callbacks run on the main thread, and "unavailable" fires before the player is released.
 */
interface DlnaSessionListener {
    fun onDlnaSessionAvailable(player: DlnaPlayer, renderer: DlnaRenderer)
    fun onDlnaSessionUnavailable()
}

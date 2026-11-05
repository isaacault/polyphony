package app.polyphony.core.playback.session

import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture

/**
 * Lets the user scrub a song the local player cannot seek in yet. A transcode that Navidrome is
 * still producing arrives without a length, so Media3 treats it as a live stream: it withdraws
 * the seek commands, and the session silently drops every scrub (the slider snaps back).
 *
 * While a local song is in that state this player still offers seeking, and hands a seek inside
 * the song to [seekByReload], which reopens it at the target once the server knows its length
 * (the reopened song is seekable). The future it returns keeps the target position showing
 * until then. Everything else is forwarded unchanged; remote players are never touched.
 */
@UnstableApi
class SeekableStreamsPlayer(
    player: Player,
    private val seekByReload: (mediaItemIndex: Int, positionMs: Long) -> ListenableFuture<*>,
) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        if (!needsHelp()) return state
        val commands = state.availableCommands.buildUpon()
            .addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (needsHelp()) {
            val inner = player
            val target = when (seekCommand) {
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> positionMs
                Player.COMMAND_SEEK_BACK -> inner.currentPosition - inner.seekBackIncrement
                Player.COMMAND_SEEK_FORWARD -> inner.currentPosition + inner.seekForwardIncrement
                else -> null
            }
            if (target != null) return seekByReload(inner.currentMediaItemIndex, target.coerceAtLeast(0))
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    /** A local song is loaded but the player cannot seek in it (a live transcode). */
    private fun needsHelp(): Boolean {
        val inner = player
        return inner.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL &&
            inner.mediaItemCount > 0 &&
            inner.playbackState != Player.STATE_IDLE &&
            !inner.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }
}

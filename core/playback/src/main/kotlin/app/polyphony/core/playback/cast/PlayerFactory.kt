package app.polyphony.core.playback.cast

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Wraps the local [ExoPlayer] in Media3's composite [CastPlayer], which forwards to a
 * [RemoteCastPlayer] whenever a Cast session is active and transfers queue/position/state
 * both ways automatically. Falls back to the bare ExoPlayer when Cast is unavailable.
 */
object PlayerFactory {
    data class Players(val session: Player, val local: ExoPlayer, val remote: RemoteCastPlayer?)

    fun create(context: Context, local: ExoPlayer, castSupported: Boolean, converter: PolyphonyMediaItemConverter): Players {
        if (!castSupported) return Players(local, local, null)
        return runCatching {
            val remote = RemoteCastPlayer.Builder(context)
                .setMediaItemConverter(converter)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build()
            val composite = CastPlayer.Builder(context)
                .setLocalPlayer(local)
                .setRemotePlayer(remote)
                .setTransferCallback(CastPlayer.TransferCallback.DEFAULT)
                .build()
            Players(composite, local, remote)
        }.getOrElse { Players(local, local, null) }
    }

    fun Player.isRemotePlayback(): Boolean = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
}

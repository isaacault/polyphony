package app.polyphony.core.dlna.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.polyphony.core.dlna.engine.DlnaPlaybackEngine
import app.polyphony.core.dlna.engine.EnginePlaybackState
import app.polyphony.core.dlna.engine.EngineRepeatMode
import app.polyphony.core.dlna.engine.EngineState
import app.polyphony.core.domain.playback.DlnaRenderer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.guava.future

/**
 * Media3 [Player] for a UPnP renderer, backed by [DlnaPlaybackEngine]. Create and use it on the
 * main looper, exactly like `CastPlayer`; the playback service swaps it into the `MediaSession`.
 */
class DlnaPlayer internal constructor(
    private val engine: DlnaPlaybackEngine<MediaItem>,
    val renderer: DlnaRenderer,
    looper: Looper = Looper.getMainLooper(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        engine.state
            .drop(1)
            .onEach { invalidateState() }
            .launchIn(scope)
    }

    /** Snapshot of the engine, for the switcher to read position/index when handing back to local. */
    val engineState: EngineState<MediaItem> get() = engine.state.value

    override fun getState(): State {
        val s = engine.state.value
        val now = clock()
        val playlist = s.items.mapIndexed { index, entry ->
            val metaDuration = entry.item.mediaMetadata.durationMs
            val durationMs = when {
                index == s.currentIndex && s.durationMs > 0 -> s.durationMs
                metaDuration != null && metaDuration > 0 -> metaDuration
                else -> null
            }
            MediaItemData.Builder(entry.uid)
                .setMediaItem(entry.item)
                .setMediaMetadata(entry.item.mediaMetadata)
                .setDurationUs(durationMs?.let { it * 1_000 } ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .setIsDynamic(false)
                .build()
        }
        val playbackState = when {
            s.error != null -> Player.STATE_IDLE
            playlist.isEmpty() -> if (s.playbackState == EnginePlaybackState.ENDED) Player.STATE_ENDED else Player.STATE_IDLE
            else -> when (s.playbackState) {
                EnginePlaybackState.IDLE -> Player.STATE_IDLE
                EnginePlaybackState.BUFFERING -> Player.STATE_BUFFERING
                EnginePlaybackState.READY -> if (s.isLoading) Player.STATE_BUFFERING else Player.STATE_READY
                EnginePlaybackState.ENDED -> Player.STATE_ENDED
            }
        }
        val position = s.positionAt(now)
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(s.error?.let { PlaybackException(it.message, it.cause, PlaybackException.ERROR_CODE_REMOTE_ERROR) })
            .setRepeatMode(
                when (s.repeatMode) {
                    EngineRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    EngineRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    EngineRepeatMode.ALL -> Player.REPEAT_MODE_ALL
                },
            )
            .setShuffleModeEnabled(s.shuffleEnabled)
            .setIsLoading(s.isLoading && playbackState != Player.STATE_IDLE)
            .setVolume(1f)
            .setDeviceInfo(DEVICE_INFO)
            .setDeviceVolume(s.volumePercent)
            .setIsDeviceMuted(s.muted)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setMaxSeekToPreviousPositionMs(3_000)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else s.currentIndex.coerceIn(0, playlist.lastIndex))
            .setContentPositionMs(
                if (s.isPlayingNow) PositionSupplier.getExtrapolating(position, 1f) else PositionSupplier.getConstant(position),
            )
            .build()
    }

    // ---- command handlers ------------------------------------------------------------------------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        run(engine.setPlayWhenReady(playWhenReady))

    override fun handlePrepare(): ListenableFuture<*> = run(engine.prepare())

    override fun handleStop(): ListenableFuture<*> = run(engine.stop())

    override fun handleRelease(): ListenableFuture<*> {
        val job = engine.release()
        return scope.future {
            job.join()
            scope.cancel()
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = run(
        engine.setRepeatMode(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> EngineRepeatMode.ONE
                Player.REPEAT_MODE_ALL -> EngineRepeatMode.ALL
                else -> EngineRepeatMode.OFF
            },
        ),
    )

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        run(engine.setShuffleModeEnabled(shuffleModeEnabled))

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> =
        run(
            engine.setMediaItems(
                mediaItems.toList(),
                if (startIndex == C.INDEX_UNSET) 0 else startIndex,
                if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs,
            ),
        )

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        run(engine.addMediaItems(index, mediaItems.toList()))

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        run(engine.moveMediaItems(fromIndex, toIndex, newIndex))

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        run(engine.replaceMediaItems(fromIndex, toIndex, mediaItems.toList()))

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> =
        run(engine.removeMediaItems(fromIndex, toIndex))

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val s = engine.state.value
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs
        val job = when {
            mediaItemIndex == s.currentIndex || mediaItemIndex == C.INDEX_UNSET -> engine.seekTo(s.currentIndex, target)
            seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.next()
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.previous()
            else -> engine.seekTo(mediaItemIndex, target)
        }
        return run(job)
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> =
        // Media3's per-player volume is meaningless on a remote renderer; map it onto device volume.
        run(engine.setVolume((volume.coerceIn(0f, 1f) * 100).toInt()))

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
        run(engine.setVolume(deviceVolume))

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        run(engine.setVolume(engine.state.value.volumePercent + 5))

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        run(engine.setVolume(engine.state.value.volumePercent - 5))

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> =
        run(engine.setMuted(muted))

    private fun run(job: Job): ListenableFuture<Unit> = scope.future { job.join() }

    private companion object {
        val DEVICE_INFO: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_PLAYLIST_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}

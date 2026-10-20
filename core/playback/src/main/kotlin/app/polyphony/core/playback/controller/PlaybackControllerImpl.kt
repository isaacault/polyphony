package app.polyphony.core.playback.controller

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.DlnaController
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.playback.PlaybackPosition
import app.polyphony.core.domain.playback.PlaybackState
import app.polyphony.core.domain.playback.RepeatMode
import app.polyphony.core.domain.playback.ResumePoint
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.playback.PlaybackService
import app.polyphony.core.playback.cast.CastManager
import app.polyphony.core.playback.di.PlaybackScope
import app.polyphony.core.playback.engine.LatencyMonitor
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.stream.StreamResolver
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueMath
import app.polyphony.core.playback.queue.QueueStore
import app.polyphony.core.playback.session.CustomCommands
import app.polyphony.core.playback.session.PlayContextCodec
import app.polyphony.core.playback.session.SessionExtras
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-facing playback API backed by a [MediaController] connected to [PlaybackService]. Commands
 * issued before the controller is connected are queued and replayed.
 */
@Singleton
class PlaybackControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @PlaybackScope private val scope: CoroutineScope,
    private val queueStore: QueueStore,
    private val serverLookup: ServerLookup,
    private val engine: TranscodeDecisionEngine,
    private val latencyMonitor: LatencyMonitor,
    private val castManager: CastManager,
    private val dlnaController: DlnaController,
    private val streamResolver: StreamResolver,
    private val libraryRepository: LibraryRepository,
    private val listeningHistory: ListeningHistoryRepository,
) : PlaybackController {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val positionTick = MutableStateFlow(0)
    override val position: Flow<PlaybackPosition> =
        combine(_state.map { it.isPlaying }.distinctUntilChanged(), positionTick) { playing, _ -> playing }
            .flatMapLatest { playing ->
                flow {
                    while (true) {
                        emit(readPosition())
                        delay(if (playing) 250 else 1_000)
                    }
                }
            }
            .distinctUntilChanged()

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    init {
        castManager.initialize()
        connect()
        scope.launch {
            combine(engine.decision, latencyMonitor.degraded, castManager.state, dlnaController.state, streamResolver.applied) { decision, degraded, cast, dlna, _ ->
                val dlnaTarget = dlna.target
                val castTarget = cast.target
                val target: OutputTarget = when {
                    dlna.isConnected && dlnaTarget != null -> dlnaTarget
                    cast.isConnected && castTarget != null -> castTarget
                    else -> OutputTarget.Local
                }
                Triple(decision, degraded, target)
            }.collect { (decision, degraded, target) ->
                _state.update {
                    it.copy(transcode = effectiveDecision(it.currentTrack?.id, decision), latencyDegraded = degraded, outputTarget = target)
                }
            }
        }
        scope.launch { queueStore.changes.collect { controller?.let(::syncQueue) } }
    }

    // ---- connection ----

    private fun connect() {
        if (controller != null || connecting) return
        connecting = true
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val result = runCatching {
                MediaController.Builder(context, token).setListener(controllerListener).buildAsync().await()
            }
            connecting = false
            val c = result.getOrNull()
            if (c == null) {
                delay(2_000)
                connect()
                return@launch
            }
            controller = c
            c.addListener(playerListener)
            syncAll(c)
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) block(c) else {
            pending.addLast(block)
            connect()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            this@PlaybackControllerImpl.controller = null
            scope.launch {
                delay(500)
                connect()
            }
        }

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) = applyExtras(extras)
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                syncQueue(player)
            }
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_PLAYER_ERROR, Player.EVENT_MEDIA_ITEM_TRANSITION,
                )
            ) {
                syncPlayerFlags(player)
            }
            if (events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                positionTick.update { it + 1 }
            }
        }
    }

    // ---- state sync ----

    private fun syncAll(c: MediaController) {
        syncQueue(c)
        syncPlayerFlags(c)
        applyExtras(c.sessionExtras)
    }

    /**
     * The decision shown in the UI: what the stream resolver actually applied to the current
     * item (e.g. a downloaded file) when it matches, otherwise the engine's live decision.
     */
    private fun effectiveDecision(currentTrackId: String?, live: TranscodeDecision): TranscodeDecision {
        if (currentTrackId == null) return live
        return streamResolver.applied.value[currentTrackId] ?: live
    }

    private fun syncQueue(player: Player) {
        val count = player.mediaItemCount
        val tracks = ArrayList<Track>(count)
        for (i in 0 until count) {
            tracks += trackFor(player.getMediaItemAt(i))
        }
        _state.update {
            val index = player.currentMediaItemIndex
            it.copy(
                queue = tracks.toImmutableList(),
                currentIndex = index,
                transcode = effectiveDecision(tracks.getOrNull(index)?.id, engine.decision.value),
            )
        }
    }

    private fun syncPlayerFlags(player: Player) {
        _state.update {
            val index = player.currentMediaItemIndex
            it.copy(
                currentIndex = index,
                transcode = effectiveDecision(it.queue.getOrNull(index)?.id, engine.decision.value),
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                playbackSpeed = player.playbackParameters.speed,
                error = player.playerError?.let { e -> e.message ?: e.errorCodeName },
            )
        }
    }

    private fun applyExtras(extras: Bundle) {
        _state.update {
            it.copy(
                queueTitle = extras.getString(SessionExtras.QUEUE_TITLE),
                playContext = PlayContextCodec.decode(extras.getString(SessionExtras.PLAY_CONTEXT)),
                sleepTimerEndsAtEpochMs = extras.getLong(SessionExtras.SLEEP_TIMER_ENDS_AT, -1L).takeIf { v -> v > 0 },
                audioSessionId = extras.getInt(SessionExtras.AUDIO_SESSION_ID, 0),
            )
        }
    }

    private fun trackFor(item: MediaItem): Track =
        queueStore.get(item.mediaId)
            ?: MediaItemFactory.trackFrom(item)?.also(queueStore::put)
            ?: Track(
                id = item.mediaId,
                serverId = MediaItemFactory.serverIdFrom(item) ?: "",
                title = item.mediaMetadata.title?.toString() ?: "Unknown",
                artistName = item.mediaMetadata.artist?.toString(),
                albumName = item.mediaMetadata.albumTitle?.toString(),
                durationMs = item.mediaMetadata.durationMs ?: 0L,
                artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            )

    private fun readPosition(): PlaybackPosition {
        val c = controller ?: return PlaybackPosition()
        val duration = c.duration.takeIf { it > 0 } ?: c.currentMediaItem?.mediaMetadata?.durationMs ?: 0L
        return PlaybackPosition(
            positionMs = c.currentPosition.coerceAtLeast(0L),
            bufferedMs = c.bufferedPosition.coerceAtLeast(0L),
            durationMs = duration,
        )
    }

    // ---- commands ----

    override fun play() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    override fun pause() = withController { it.pause() }

    override fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    override fun stop() = withController { it.stop() }

    override fun seekTo(positionMs: Long) = withController {
        it.seekTo(positionMs.coerceAtLeast(0L))
        positionTick.update { v -> v + 1 }
    }

    override fun next() = withController { it.seekToNext() }
    override fun previous() = withController { it.seekToPrevious() }

    override fun skipToQueueItem(index: Int) = withController { c ->
        if (index !in 0 until c.mediaItemCount) return@withController
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    override fun playTracks(tracks: List<Track>, startIndex: Int, shuffle: Boolean, queueTitle: String?, context: PlayContext?) {
        if (tracks.isEmpty()) return
        val start = if (shuffle) QueueMath.shuffledStartIndex(tracks.size) else startIndex.coerceIn(0, tracks.lastIndex)
        startQueue(tracks, start, 0L, shuffle, queueTitle, context)
    }

    override fun resumeContext(context: PlayContext, fromStart: Boolean) {
        val current = _state.value
        if (!fromStart && current.playContext?.key == context.key && current.queue.isNotEmpty()) {
            play()
            return
        }
        scope.launch {
            val entry = if (fromStart) null else runCatching { listeningHistory.get(context) }.getOrNull()
            val tracks = tracksFor(context)
            if (tracks.isEmpty()) {
                runCatching { listeningHistory.remove(context) }
                return@launch
            }
            val point = ResumePoint.of(tracks, entry)
            startQueue(tracks, point.index, point.positionMs, shuffle = entry?.shuffle == true, queueTitle = context.title, context = context)
        }
    }

    /** The context's tracks from the cache, refreshing from the server once when it is empty. */
    private suspend fun tracksFor(context: PlayContext): List<Track> {
        suspend fun read(): List<Track> = runCatching {
            when (context.kind) {
                PlayContext.Kind.ALBUM -> libraryRepository.tracksForAlbum(context.id).first()
                PlayContext.Kind.ARTIST -> libraryRepository.tracksForArtist(context.id).first()
                PlayContext.Kind.PLAYLIST -> libraryRepository.tracksForPlaylist(context.id).first()
                PlayContext.Kind.GENRE -> libraryRepository.tracksByGenre(context.id).first()
                PlayContext.Kind.FAVORITES -> libraryRepository.starredTracks().first()
            }
        }.getOrDefault(emptyList())
        read().takeIf { it.isNotEmpty() }?.let { return it }
        runCatching {
            when (context.kind) {
                PlayContext.Kind.ALBUM -> libraryRepository.refreshAlbum(context.id)
                PlayContext.Kind.ARTIST -> libraryRepository.refreshArtist(context.id)
                PlayContext.Kind.PLAYLIST -> libraryRepository.refreshPlaylist(context.id)
                PlayContext.Kind.FAVORITES -> libraryRepository.refreshStarred()
                PlayContext.Kind.GENRE -> Unit
            }
        }
        return read()
    }

    /**
     * Replaces the queue. The context goes first so the service can flush progress for the
     * outgoing collection before the new items (and their first transition) arrive.
     */
    private fun startQueue(tracks: List<Track>, start: Int, positionMs: Long, shuffle: Boolean, queueTitle: String?, context: PlayContext?) {
        queueStore.putAll(tracks)
        val items = tracks.map(::itemFor)
        withController { c ->
            c.sendCustomCommand(
                CustomCommands.of(CustomCommands.SET_PLAY_CONTEXT),
                Bundle().apply { putString(CustomCommands.ARG_CONTEXT, PlayContextCodec.encode(context)) },
            )
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, start.coerceIn(0, items.lastIndex), positionMs.coerceAtLeast(0L))
            c.prepare()
            c.play()
            c.sendCustomCommand(
                CustomCommands.of(CustomCommands.SET_QUEUE_TITLE),
                Bundle().apply { putString(CustomCommands.ARG_TITLE, queueTitle) },
            )
        }
    }

    override fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queueStore.putAll(tracks)
        val items = tracks.map(::itemFor)
        withController { c ->
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(QueueMath.insertAfterCurrentIndex(c.mediaItemCount, c.currentMediaItemIndex), items)
            if (wasEmpty) {
                c.prepare()
                c.play()
            }
        }
    }

    override fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queueStore.putAll(tracks)
        val items = tracks.map(::itemFor)
        withController { c ->
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(c.mediaItemCount, items)
            if (wasEmpty) {
                c.prepare()
                c.play()
            }
        }
    }

    override fun removeFromQueue(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    override fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount && from != to) c.moveMediaItem(from, to)
    }

    override fun clearQueue() = withController { it.clearMediaItems() }

    override fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    override fun setRepeatMode(mode: RepeatMode) = withController {
        it.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun setPlaybackSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed.coerceIn(0.25f, 3f)) }

    override fun setSleepTimer(endsAtEpochMs: Long?) = withController { c ->
        c.sendCustomCommand(
            CustomCommands.of(CustomCommands.SET_SLEEP_TIMER),
            Bundle().apply { putLong(CustomCommands.ARG_ENDS_AT, endsAtEpochMs ?: -1L) },
        )
    }

    override fun restoreQuality() {
        latencyMonitor.reset()
        withController { it.sendCustomCommand(CustomCommands.of(CustomCommands.RESTORE_QUALITY), Bundle.EMPTY) }
    }

    override fun refreshTranscodeDecision() {
        engine.refresh()
        withController { it.sendCustomCommand(CustomCommands.of(CustomCommands.REFRESH_DECISION), Bundle.EMPTY) }
    }

    private fun itemFor(track: Track): MediaItem {
        val artwork = serverLookup.providerFor(track.serverId)?.artworkUrl(track.artworkId, 1200) ?: track.artworkUrl
        return MediaItemFactory.fromTrack(track, artwork)
    }
}

package app.polyphony.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.polyphony.core.dlna.DlnaManager
import app.polyphony.core.dlna.DlnaSessionListener
import app.polyphony.core.dlna.player.DlnaPlayer
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.DlnaRenderer
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.PlaybackSettings
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.playback.audio.HeadsetMonitor
import app.polyphony.core.playback.audio.ReplayGain
import app.polyphony.core.playback.cast.CastManager
import app.polyphony.core.playback.cast.PlayerFactory
import app.polyphony.core.playback.cast.PlayerFactory.isRemotePlayback
import app.polyphony.core.playback.cast.PolyphonyMediaItemConverter
import app.polyphony.core.playback.dlna.PolyphonyDlnaResolver
import app.polyphony.core.playback.engine.LatencyMonitor
import app.polyphony.core.playback.engine.LatencyPlayerListener
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.PersistedQueue
import app.polyphony.core.playback.queue.QueuePersistence
import app.polyphony.core.playback.queue.QueueStore
import app.polyphony.core.playback.reporting.PlaybackReporter
import app.polyphony.core.playback.session.CustomCommands
import app.polyphony.core.playback.session.PolyphonySessionCallback
import app.polyphony.core.playback.session.SessionActions
import app.polyphony.core.playback.session.SessionExtras
import app.polyphony.core.playback.stream.StreamCache
import app.polyphony.core.playback.stream.StreamResolver
import app.polyphony.core.playback.timer.SleepTimer
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * The playback engine. Hosts ExoPlayer (wrapped in Media3's composite CastPlayer when Google Cast
 * is available), the MediaLibrarySession, notification, queue persistence, scrobbling, sleep timer,
 * ReplayGain and the transcoding-decision reload logic.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var queueStore: QueueStore
    @Inject lateinit var queuePersistence: QueuePersistence
    @Inject lateinit var streamCache: StreamCache
    @Inject lateinit var streamResolver: StreamResolver
    @Inject lateinit var engine: TranscodeDecisionEngine
    @Inject lateinit var latencyMonitor: LatencyMonitor
    @Inject lateinit var castManager: CastManager
    @Inject lateinit var converter: PolyphonyMediaItemConverter
    @Inject lateinit var serverLookup: ServerLookup
    @Inject lateinit var dlnaManager: DlnaManager
    @Inject lateinit var dlnaResolver: PolyphonyDlnaResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var localPlayer: ExoPlayer
    /** ExoPlayer, or the composite Cast player wrapping it. Playback returns here after DLNA. */
    private lateinit var basePlayer: Player
    /** The player the session currently drives: [basePlayer] or an active [DlnaPlayer]. */
    private lateinit var player: Player
    private var dlnaPlayer: DlnaPlayer? = null
    private var session: MediaLibrarySession? = null
    private lateinit var sleepTimer: SleepTimer
    private lateinit var reporter: PlaybackReporter
    private lateinit var headset: HeadsetMonitor
    private lateinit var latencyListener: LatencyPlayerListener

    private var settings: PlaybackSettings = PlaybackSettings()
    private var queueTitle: String? = null
    private var lastAppliedProfileId: String? = null
    private var lastAppliedTargetKey: String? = null
    private var saveJob: Job? = null
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    override fun onCreate() {
        super.onCreate()
        castManager.initialize()
        val initial = runCatching {
            runBlocking { withTimeoutOrNull(1_500) { settingsRepository.current() } }
        }.getOrNull() ?: AppSettings()
        settings = initial.playback

        localPlayer = buildLocalPlayer(initial.playback)
        val players = PlayerFactory.create(this, localPlayer, castManager.isSupported, converter)
        basePlayer = players.session
        player = basePlayer

        sleepTimer = SleepTimer(scope) { player.pause() }
        reporter = PlaybackReporter(scope, serverLookup, libraryRepository).also {
            it.enabled = settings.scrobble
            it.threshold = settings.scrobbleThreshold
        }
        headset = HeadsetMonitor(this) {
            if (settings.resumeOnConnect && !player.isPlaying && player.mediaItemCount > 0) {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
        headset.setEnabled(settings.resumeOnConnect)
        latencyListener = LatencyPlayerListener(latencyMonitor)

        session = buildSession()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_polyphony) },
        )

        player.addListener(latencyListener)
        player.addListener(playerListener)
        dlnaManager.setMediaResolver(dlnaResolver)
        dlnaManager.setSessionListener(dlnaSessionListener)
        startCollectors()
        openAudioEffectSession()
        restoreQueueOnStart(initial.playback.rememberQueue)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        runCatching { runBlocking { withTimeoutOrNull(1_000) { saveQueue() } } }
        dlnaManager.setSessionListener(null)
        dlnaManager.setMediaResolver(null)
        dlnaPlayer?.let { dlna ->
            // Hand the session back to the base player; DlnaManager releases the renderer player.
            dlna.removeListener(playerListener)
            dlna.removeListener(latencyListener)
            session?.player = basePlayer
            player = basePlayer
            dlnaPlayer = null
            dlnaManager.disconnect()
        }
        closeAudioEffectSession()
        headset.setEnabled(false)
        sleepTimer.cancel()
        session?.release()
        session = null
        player.removeListener(playerListener)
        player.removeListener(latencyListener)
        player.release()
        if (player !== localPlayer) localPlayer.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- construction ----

    private fun buildLocalPlayer(playback: PlaybackSettings): ExoPlayer {
        val cache = streamCache.get(playback.streamCacheSizeMb)
        val upstream = OkHttpDataSource.Factory(okHttpClient)
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // DefaultDataSource routes http(s) through the cache + OkHttp and file:// (downloads) to
        // FileDataSource, so downloaded tracks bypass the stream cache entirely.
        val local = DefaultDataSource.Factory(this, cached)
        val resolving = ResolvingDataSource.Factory(local, streamResolver)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSkipSilenceEnabled(playback.skipSilence)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setMaxSeekToPreviousPositionMs(playback.previousRestartsAfterMs)
            .setName("Polyphony")
            .build()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioSessionId = audioManager.generateAudioSessionId()
        exo.setAudioSessionId(audioSessionId)
        applyPreload(exo, playback.preloadNextTrack)
        exo.setPlaybackSpeed(playback.playbackSpeed)
        return exo
    }

    private fun applyPreload(exo: ExoPlayer, enabled: Boolean) {
        exo.setPreloadConfiguration(
            if (enabled) ExoPlayer.PreloadConfiguration(PRELOAD_DURATION_US) else ExoPlayer.PreloadConfiguration.DEFAULT,
        )
    }

    private fun buildSession(): MediaLibrarySession {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                OkHttpDataSource.Factory(okHttpClient),
                /* options= */ null,
                /* maximumOutputDimension= */ 1024,
            ),
        )
        val callback = PolyphonySessionCallback(scope, queueStore, libraryRepository, sessionActions)
        return MediaLibrarySession.Builder(this, player, callback)
            .setId(SESSION_ID)
            .setBitmapLoader(bitmapLoader)
            .setMediaButtonPreferences(sessionActions.buttons())
            .apply { sessionActivity?.let(::setSessionActivity) }
            .build()
    }

    // ---- collectors ----

    private fun startCollectors() {
        scope.launch {
            settingsRepository.settings.map { it.playback }.distinctUntilChanged().collect(::onSettingsChanged)
        }
        scope.launch { engine.decision.collect(::onDecisionChanged) }
        scope.launch { latencyMonitor.degraded.collect { publishExtras() } }
        scope.launch { sleepTimer.endsAtEpochMs.collect { publishExtras() } }
        scope.launch {
            networkMonitor.state.map { it.kind to it.isMetered }.distinctUntilChanged().drop(1).collect { latencyMonitor.reset() }
        }
        scope.launch {
            networkMonitor.state.map { it.kind }.distinctUntilChanged().drop(1).collect { dlnaManager.onNetworkChanged() }
        }
        scope.launch {
            // A Cast session and a DLNA session cannot both own the queue: Cast wins.
            castManager.state.map { it.isConnected }.distinctUntilChanged().collect { connected ->
                if (connected && dlnaPlayer != null) dlnaManager.disconnect()
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                latencyMonitor.tick()
            }
        }
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                if (player.isPlaying) {
                    reporter.onPlayingTick(1_000)
                    if (++ticks % 10 == 0) saveQueue()
                }
            }
        }
    }

    private fun onSettingsChanged(new: PlaybackSettings) {
        val old = settings
        settings = new
        if (old.skipSilence != new.skipSilence) localPlayer.skipSilenceEnabled = new.skipSilence
        if (old.playbackSpeed != new.playbackSpeed) player.setPlaybackSpeed(new.playbackSpeed)
        if (old.preloadNextTrack != new.preloadNextTrack) applyPreload(localPlayer, new.preloadNextTrack)
        reporter.enabled = new.scrobble
        reporter.threshold = new.scrobbleThreshold
        headset.setEnabled(new.resumeOnConnect)
        applyReplayGain()
    }

    /**
     * Reacts to a changed transcoding decision. A change of output target is already handled by
     * the composite player's transfer (items are re-converted for the new target), so only a
     * profile change for the *same* target triggers a reload.
     */
    private fun onDecisionChanged(decision: TranscodeDecision) {
        publishExtras()
        val targetKey = engine.currentTarget.key
        val profileId = decision.profile.id
        if (lastAppliedProfileId == null || lastAppliedTargetKey != targetKey) {
            lastAppliedProfileId = profileId
            lastAppliedTargetKey = targetKey
            return
        }
        if (profileId == lastAppliedProfileId) return
        lastAppliedProfileId = profileId
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        when {
            engine.policy.reloadCurrentTrackOnChange -> reloadCurrentItem()
            player.isRemotePlayback() -> refreshUpcomingItems()
            // Local playback resolves URLs lazily, so the next track picks the new profile by itself.
        }
    }

    private fun reloadCurrentItem() {
        val items = currentItems()
        if (items.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(0, items.lastIndex)
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        player.setMediaItems(items, index, position)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    private fun refreshUpcomingItems() {
        val count = player.mediaItemCount
        val index = player.currentMediaItemIndex
        if (index < 0 || index + 1 >= count) return
        val tail = (index + 1 until count).map(player::getMediaItemAt)
        player.replaceMediaItems(index + 1, count, tail)
    }

    // ---- DLNA sessions ----

    private val dlnaSessionListener = object : DlnaSessionListener {
        override fun onDlnaSessionAvailable(player: DlnaPlayer, renderer: DlnaRenderer) {
            if (castManager.state.value.isActiveSession) castManager.disconnect()
            switchPlayer(player)
        }

        override fun onDlnaSessionUnavailable() {
            if (dlnaPlayer == null) return
            switchPlayer(basePlayer)
        }
    }

    /**
     * Moves the queue, position and modes from the active player to [to], re-points the session and
     * listeners, and leaves the old player stopped. Mirrors what Media3's composite CastPlayer does
     * for Cast, but for players it does not know about (DLNA).
     */
    private fun switchPlayer(to: Player) {
        val from = player
        if (from === to) return
        val items = currentItems()
        val index = from.currentMediaItemIndex.coerceAtLeast(0)
        val position = from.currentPosition.coerceAtLeast(0L)
        val playWhenReady = from.playWhenReady
        val shuffle = from.shuffleModeEnabled
        val repeat = from.repeatMode

        from.removeListener(playerListener)
        from.removeListener(latencyListener)
        runCatching { from.stop() }
        runCatching { from.clearMediaItems() }

        player = to
        dlnaPlayer = to as? DlnaPlayer
        to.addListener(latencyListener)
        to.addListener(playerListener)
        runCatching { session?.player = to }.onFailure { Log.w(TAG, "setPlayer failed: ${it.message}") }

        to.shuffleModeEnabled = shuffle
        to.repeatMode = repeat
        if (items.isNotEmpty()) {
            to.setMediaItems(items, index.coerceAtMost(items.lastIndex), position)
            to.prepare()
            to.playWhenReady = playWhenReady
        }
        lastAppliedProfileId = null
        lastAppliedTargetKey = null
        applyReplayGain()
        publishExtras()
        updateButtons()
        scheduleSave(immediate = true)
    }

    // ---- player events ----

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val track = mediaItem?.let { trackFor(it) }
            reporter.onTrackStarted(track)
            sleepTimer.onTrackChanged()
            applyReplayGain()
            lastAppliedProfileId = engine.decision.value.profile.id
            lastAppliedTargetKey = engine.currentTarget.key
        }

        override fun onEvents(p: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                scheduleSave()
                updateButtons()
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                queueStore.retain(currentItems().map { it.mediaId })
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !p.playWhenReady) {
                scheduleSave(immediate = true)
            }
            if (events.containsAny(Player.EVENT_AUDIO_SESSION_ID, Player.EVENT_DEVICE_INFO_CHANGED)) {
                publishExtras()
            }
        }
    }

    private fun currentItems(): List<MediaItem> = (0 until player.mediaItemCount).map(player::getMediaItemAt)

    private fun trackFor(item: MediaItem): Track? =
        queueStore.get(item.mediaId) ?: MediaItemFactory.trackFrom(item)?.also(queueStore::put)

    private fun currentTrack(): Track? = player.currentMediaItem?.let(::trackFor)

    private fun applyReplayGain() {
        val track = currentTrack()
        val neighbour = when {
            player.nextMediaItemIndex != C.INDEX_UNSET -> trackFor(player.getMediaItemAt(player.nextMediaItemIndex))
            player.previousMediaItemIndex != C.INDEX_UNSET -> trackFor(player.getMediaItemAt(player.previousMediaItemIndex))
            else -> null
        }
        localPlayer.volume = ReplayGain.volumeFor(
            track = track,
            mode = settings.replayGainMode,
            preampDb = settings.replayGainPreampDb,
            preventClipping = settings.replayGainPreventClipping,
            sameAlbumAsNeighbour = ReplayGain.sameAlbum(track, neighbour),
        )
    }

    // ---- persistence ----

    private fun scheduleSave(immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = scope.launch {
            if (!immediate) delay(750)
            saveQueue()
        }
    }

    private suspend fun saveQueue() {
        if (!settings.rememberQueue) return
        val items = currentItems()
        val tracks = items.mapNotNull(::trackFor)
        if (tracks.isEmpty()) {
            queuePersistence.clear()
            return
        }
        queuePersistence.save(
            PersistedQueue(
                tracks = tracks,
                index = player.currentMediaItemIndex.coerceIn(0, tracks.lastIndex),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                queueTitle = queueTitle,
                savedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun loadPersistedQueue(): MediaSession.MediaItemsWithStartPosition? {
        val saved = queuePersistence.load() ?: return null
        if (saved.tracks.isEmpty()) return null
        queueStore.putAll(saved.tracks)
        queueTitle = saved.queueTitle
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        val items = saved.tracks.map { MediaItemFactory.fromTrack(it) }
        return MediaSession.MediaItemsWithStartPosition(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
    }

    /** Puts the last queue back (paused, not prepared) so the mini player shows it on launch. */
    private fun restoreQueueOnStart(enabled: Boolean) {
        if (!enabled) return
        scope.launch {
            val restored = loadPersistedQueue() ?: return@launch
            if (player.mediaItemCount == 0) {
                player.setMediaItems(restored.mediaItems, restored.startIndex, restored.startPositionMs)
                publishExtras()
            }
        }
    }

    // ---- session plumbing ----

    private fun publishExtras() {
        val decision = engine.decision.value
        val extras = Bundle().apply {
            putString(SessionExtras.QUEUE_TITLE, queueTitle)
            putLong(SessionExtras.SLEEP_TIMER_ENDS_AT, sleepTimer.endsAtEpochMs.value ?: -1L)
            putInt(SessionExtras.AUDIO_SESSION_ID, audioSessionId)
            putBoolean(SessionExtras.LATENCY_DEGRADED, latencyMonitor.degraded.value)
            putString(SessionExtras.DECISION_PROFILE_ID, decision.profile.id)
            putString(SessionExtras.DECISION_PROFILE_NAME, decision.profile.name)
            putString(SessionExtras.DECISION_REASON, decision.reason.name)
            putBoolean(SessionExtras.DECISION_CAPPED, decision.cappedByLatency)
        }
        session?.setSessionExtras(extras)
    }

    private fun updateButtons() {
        session?.setMediaButtonPreferences(sessionActions.buttons())
    }

    private val sessionActions = object : SessionActions {
        override fun toggleShuffle() { player.shuffleModeEnabled = !player.shuffleModeEnabled }

        override fun cycleRepeat() {
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }

        override fun toggleFavorite() {
            val track = currentTrack() ?: return
            val starred = !track.starred
            queueStore.update(track.id) { it.copy(starred = starred) }
            updateButtons()
            scope.launch(Dispatchers.IO) {
                runCatching { libraryRepository.setStarred(track.id, ItemKind.TRACK, starred) }
            }
        }

        override fun restoreQuality() = latencyMonitor.reset()
        override fun refreshDecision() = engine.refresh()

        override fun setSleepTimer(endsAtEpochMs: Long?, finishTrack: Boolean) {
            sleepTimer.set(endsAtEpochMs, finishTrack)
            publishExtras()
        }

        override fun setQueueTitle(title: String?) {
            queueTitle = title
            publishExtras()
            scheduleSave()
        }

        override fun audioSessionId(): Int = audioSessionId

        override suspend fun restoreQueue(): MediaSession.MediaItemsWithStartPosition? = loadPersistedQueue()

        override fun buttons(): List<CommandButton> {
            val starred = currentTrack()?.starred == true
            val favorite = CommandButton.Builder(if (starred) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (starred) R.string.action_unfavorite else R.string.action_favorite))
                .setSessionCommand(CustomCommands.of(CustomCommands.TOGGLE_FAVORITE))
                .build()
            val shuffle = CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(R.string.action_shuffle))
                .setSessionCommand(CustomCommands.of(CustomCommands.TOGGLE_SHUFFLE))
                .build()
            val repeatIcon = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            }
            val repeat = CommandButton.Builder(repeatIcon)
                .setDisplayName(getString(R.string.action_repeat))
                .setSessionCommand(CustomCommands.of(CustomCommands.CYCLE_REPEAT))
                .build()
            return listOf(favorite, shuffle, repeat)
        }
    }

    // ---- audio effects ----

    private fun openAudioEffectSession() {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    private fun closeAudioEffectSession() {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName),
        )
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val SESSION_ID = "polyphony"
        const val NOTIFICATION_CHANNEL_ID = "polyphony_playback"
        private const val PRELOAD_DURATION_US = 10_000_000L
    }
}

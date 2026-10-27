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
import androidx.media3.common.PlaybackException
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
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.DlnaRenderer
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.PlaybackSettings
import app.polyphony.core.domain.repository.RequestRepository
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
import app.polyphony.core.playback.session.PlayContextCodec
import app.polyphony.core.playback.session.PolyphonySessionCallback
import app.polyphony.core.playback.stream.PrefetchPlan
import app.polyphony.core.playback.stream.QueuePrefetcher
import app.polyphony.core.playback.transcode.AppliedQuality
import app.polyphony.core.playback.transcode.QualityChangeRule
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    @Inject lateinit var feedbackRepository: FeedbackRepository
    @Inject lateinit var listeningHistory: ListeningHistoryRepository
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var queueStore: QueueStore
    @Inject lateinit var queuePersistence: QueuePersistence
    @Inject lateinit var streamCache: StreamCache
    @Inject lateinit var streamResolver: StreamResolver
    @Inject lateinit var requestRepository: RequestRepository
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
    /** The streaming HTTP source, shared by the player and the queue's fetching ahead. */
    private lateinit var streamingUpstream: OkHttpDataSource.Factory
    private lateinit var prefetcher: QueuePrefetcher

    private var settings: PlaybackSettings = PlaybackSettings()
    private var queueTitle: String? = null
    /** The collection the queue was started from; progress in it feeds Home's "Continue listening". */
    private var playContext: PlayContext? = null
    /** History writes go through one consumer so a flush of the old context never lands after the new one. */
    private val historyWrites = Channel<RecentContext>(Channel.UNLIMITED)
    private var historyJob: Job? = null
    /** What the current item was loaded with; see [QualityChangeRule]. */
    private var lastApplied: AppliedQuality? = null
    private var retryTrackId: String? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    /** Set when loading gave up for lack of network; cleared when the network returns and we resume. */
    private var awaitingNetwork = false
    private var slowRetryJob: Job? = null
    private var saveJob: Job? = null
    private var playNowOrderJob: Job? = null
    private var keepGoingJob: Job? = null
    /** Play now streams' playability, briefly remembered: streamId to (playable, checked at). */
    private val playNowChecks = HashMap<String, Pair<Boolean, Long>>()
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    override fun onCreate() {
        super.onCreate()
        castManager.initialize()
        val initial = runCatching {
            runBlocking { withTimeoutOrNull(1_500) { settingsRepository.current() } }
        }.getOrNull() ?: AppSettings()
        settings = initial.playback

        localPlayer = buildLocalPlayer(initial.playback)
        prefetcher = QueuePrefetcher(scope, streamResolver, streamCache, streamingUpstream)
        val players = PlayerFactory.create(this, localPlayer, castManager.isSupported, converter)
        basePlayer = players.session
        player = basePlayer

        sleepTimer = SleepTimer(scope, pause = { player.pause() }, setVolume = { player.volume = it })
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
        runCatching {
            runBlocking {
                withTimeoutOrNull(1_000) { saveQueue() }
                contextProgress()?.let(historyWrites::trySend)
                historyWrites.close()
                withTimeoutOrNull(1_000) { historyJob?.join() }
            }
        }
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
        prefetcher.stop()
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
        // Streaming gets tighter timeouts than the shared client: a transcode that never starts
        // should fail fast enough for the fallback to kick in, not hang for a minute.
        val streamingClient = okHttpClient.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val upstream = OkHttpDataSource.Factory(streamingClient)
        streamingUpstream = upstream
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
        val callback = PolyphonySessionCallback(scope, queueStore, libraryRepository, sessionActions) { feedbackRepository.feedback.value.usesRatings }
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
        scope.launch {
            networkMonitor.state.map { it.isOnline }.distinctUntilChanged().collect { online ->
                if (online && awaitingNetwork) {
                    awaitingNetwork = false
                    retryCount = 0
                    delay(1_200)
                    if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                        Log.i(TAG, "Network is back; resuming playback")
                        reloadCurrentItem()
                    }
                }
            }
        }
        scope.launch {
            networkMonitor.state.map { it.isOnline to it.isMetered }.distinctUntilChanged().collect { refreshPrefetch() }
        }
        scope.launch { latencyMonitor.degraded.collect { publishExtras() } }
        scope.launch { sleepTimer.endsAtEpochMs.collect { publishExtras() } }
        scope.launch { feedbackRepository.feedback.collect { updateButtons() } }
        historyJob = scope.launch(Dispatchers.IO) {
            for (entry in historyWrites) runCatching { listeningHistory.record(entry) }.onFailure { Log.w(TAG, "History write failed: ${it.message}") }
        }
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
                    // Every 15 s while playing (and at once after a start): the next songs on the phone.
                    if (ticks % 15 == 0) refreshPrefetch()
                    keepMusicGoing()
                    if (++ticks % 10 == 0) {
                        saveQueue()
                        recordContextProgress()
                    }
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
        if (old.prefetchQueue != new.prefetchQueue || old.prefetchOnMobileData != new.prefetchOnMobileData) refreshPrefetch()
    }

    /**
     * Fetch the next songs of the queue fully into the stream cache (see [PrefetchPlan] for how
     * many), so a network drop doesn't stop the music at the next song. Only for this phone's own
     * player: a Cast or DLNA receiver streams for itself.
     */
    private fun refreshPrefetch() {
        val budget = PrefetchPlan.budget(networkMonitor.state.value, settings.prefetchQueue, settings.prefetchOnMobileData)
        if (budget == null || player !== basePlayer || player.isRemotePlayback() || localPlayer.mediaItemCount == 0) {
            prefetcher.stop()
            return
        }
        val upcoming = upcomingSongs(budget.maxSongs)
        val picked = PrefetchPlan.pick(upcoming.map { it.first }, budget).toSet()
        prefetcher.update(upcoming.filter { it.first.trackId in picked }.map { it.second })
    }

    /**
     * Tell continuo the order the queue will play its Play now songs in (polyphony#164), so it
     * fetches them soonest needed first; sent shortly after the queue or the current song changes.
     */
    private fun syncPlayNowOrder() {
        val items = listOfNotNull(localPlayer.currentMediaItem) + upcomingItems(PLAY_NOW_LOOKAHEAD)
        val streams = items.mapNotNull { item -> PlayNowIds.streamId(item.mediaId)?.let { it to MediaItemFactory.serverIdFrom(item) } }
        val serverId = streams.firstNotNullOfOrNull { it.second } ?: return
        playNowOrderJob?.cancel()
        playNowOrderJob = scope.launch {
            delay(500)
            val server = serverLookup.providerFor(serverId)?.server ?: return@launch
            withContext(Dispatchers.IO) { runCatching { requestRepository.prioritisePlayNow(server, streams.map { it.first }) } }
        }
    }

    /**
     * The queue as a suggestion (polyphony#164): near the end of a song, when the next one is a
     * Play now song still downloading, the nearest song that's ready moves in front of it, so the
     * music keeps going; the other plays once it's ready. Not with shuffle or repeat-one, where
     * "next" isn't the queue's.
     */
    private fun keepMusicGoing() {
        if (keepGoingJob?.isActive == true) return
        if (player !== basePlayer || player.isRemotePlayback() || player.shuffleModeEnabled || player.repeatMode == Player.REPEAT_MODE_ONE) return
        val duration = player.duration
        if (duration <= 0 || duration - player.currentPosition > KEEP_GOING_AHEAD_MS) return
        val current = player.currentMediaItemIndex
        val nextIndex = current + 1
        if (nextIndex >= player.mediaItemCount) return
        val next = player.getMediaItemAt(nextIndex)
        val nextStream = PlayNowIds.streamId(next.mediaId) ?: return
        val serverId = MediaItemFactory.serverIdFrom(next) ?: return
        val later = (nextIndex + 1 until minOf(player.mediaItemCount, nextIndex + 1 + KEEP_GOING_LOOKAHEAD)).map(player::getMediaItemAt)
        keepGoingJob = scope.launch {
            val server = serverLookup.providerFor(serverId)?.server ?: return@launch
            if (playNowPlayable(server, nextStream)) return@launch
            val online = networkMonitor.state.value.isOnline
            val ready = later.firstOrNull { item ->
                val stream = PlayNowIds.streamId(item.mediaId)
                if (stream == null) online || withContext(Dispatchers.IO) { streamResolver.isOnPhone(item.mediaId) } else playNowPlayable(server, stream)
            } ?: return@launch
            // The queue may have changed while continuo was asked.
            if (player.currentMediaItemIndex != current || nextIndex >= player.mediaItemCount ||
                player.getMediaItemAt(nextIndex).mediaId != next.mediaId) return@launch
            val at = (nextIndex + 1 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == ready.mediaId } ?: return@launch
            Log.i(TAG, "\"${next.mediaMetadata.title}\" is still downloading; \"${ready.mediaMetadata.title}\" plays first")
            player.moveMediaItem(at, nextIndex)
        }
    }

    private suspend fun playNowPlayable(server: Server, streamId: String): Boolean {
        val now = System.currentTimeMillis()
        playNowChecks[streamId]?.let { (playable, at) -> if (playable || now - at < 5_000) return playable }
        val playable = withContext(Dispatchers.IO) {
            runCatching { requestRepository.playNowStatus(server, streamId).playable }.getOrDefault(false)
        }
        playNowChecks[streamId] = playable to now
        if (playNowChecks.size > 64) playNowChecks.keys.first().let(playNowChecks::remove)
        return playable
    }

    /** The queue's items after the current song, in play order (shuffle and repeat-all included). */
    private fun upcomingItems(max: Int): List<MediaItem> {
        val timeline = localPlayer.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val repeat = if (localPlayer.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else localPlayer.repeatMode
        var index = localPlayer.currentMediaItemIndex
        val seen = hashSetOf(index)
        val out = mutableListOf<MediaItem>()
        while (out.size < max) {
            index = timeline.getNextWindowIndex(index, repeat, localPlayer.shuffleModeEnabled)
            if (index == C.INDEX_UNSET || !seen.add(index)) break
            out += localPlayer.getMediaItemAt(index)
        }
        return out
    }

    /** The queue after the current song, in play order, as [PrefetchPlan] and the fetching see it. */
    private fun upcomingSongs(max: Int): List<Pair<PrefetchPlan.Upcoming, QueuePrefetcher.Song>> =
        upcomingItems(max).mapNotNull { item ->
            val serverId = MediaItemFactory.serverIdFrom(item) ?: return@mapNotNull null
            val trackId = item.mediaId
            PrefetchPlan.Upcoming(trackId, queueStore.get(trackId)?.durationMs ?: 0L, fetchable = PlayNowIds.streamId(trackId) == null) to
                QueuePrefetcher.Song(serverId, trackId)
        }

    /**
     * Reacts to a changed transcoding decision. A change of output target is already handled by
     * the composite player's transfer (items are re-converted for the new target), so only a
     * profile change for the *same* target triggers a reload.
     */
    private fun onDecisionChanged(decision: TranscodeDecision) {
        publishExtras()
        val next = appliedNow(decision)
        Log.i(TAG, "Transcode decision for ${next.targetKey}: ${decision.profile.name} [${decision.profile.summary}] reason=${decision.reason} cappedByLatency=${decision.cappedByLatency}")
        val action = QualityChangeRule.decide(lastApplied, next, engine.policy.reloadCurrentTrackOnChange)
        lastApplied = next
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        when (action) {
            QualityChangeRule.Action.RELOAD_NOW -> {
                Log.i(TAG, "Quality changed for the current output; reloading the playing track at its position")
                reloadCurrentItem()
            }
            // Local playback resolves URLs lazily, so the next track picks the new profile by itself;
            // remote outputs were handed concrete URLs and need the upcoming ones refreshed.
            QualityChangeRule.Action.NEXT_TRACK -> if (player.isRemotePlayback()) refreshUpcomingItems()
            QualityChangeRule.Action.NONE -> Unit
        }
    }

    private fun appliedNow(decision: TranscodeDecision): AppliedQuality {
        val network = networkMonitor.state.value
        return AppliedQuality(
            targetKey = engine.currentTarget.key,
            profile = decision.profile,
            networkKind = network.kind,
            metered = network.isMetered,
            cappedByLatency = decision.cappedByLatency,
        )
    }

    /** After fast retries are exhausted: try again every 15 s for a few minutes (server or network outage). */
    private fun startSlowRetry() {
        if (slowRetryJob?.isActive == true) return
        slowRetryJob = scope.launch {
            repeat(SLOW_RETRY_ATTEMPTS) { attempt ->
                delay(SLOW_RETRY_INTERVAL_MS)
                if (player.playbackState != Player.STATE_IDLE || player.mediaItemCount == 0) return@launch
                Log.i(TAG, "Slow retry ${attempt + 1}/$SLOW_RETRY_ATTEMPTS")
                retryCount = MAX_LOAD_RETRIES // a failure here comes straight back to the slow loop
                reloadCurrentItem()
            }
            Log.w(TAG, "Giving up after $SLOW_RETRY_ATTEMPTS slow retries")
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
        lastApplied = null // the new output re-resolves every item; its first decision is a baseline, not a change
        applyReplayGain()
        publishExtras()
        updateButtons()
        scheduleSave(immediate = true)
    }

    // ---- player events ----

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error ${error.errorCodeName} on ${engine.currentTarget.key}: ${error.message}", error.cause)
            val trackId = player.currentMediaItem?.mediaId ?: return
            val loadFailure = error.errorCode in 2000..3999 || error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED
            if (loadFailure && !player.isRemotePlayback()) {
                // A stream that dies within the last few seconds is a premature end of stream
                // (e.g. a transcoded body shorter than its declared length), not a broken codec.
                val duration = player.duration
                val remaining = if (duration > 0) duration - player.currentPosition else Long.MAX_VALUE
                if (remaining <= 4_000 && player.hasNextMediaItem()) {
                    Log.w(TAG, "Stream ended ${remaining} ms early; continuing with the next track")
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                    return
                }
            }
            if (!loadFailure || player.isRemotePlayback()) return
            // A Play now song (continuo#12) that caught up with its download: the next bytes are
            // still coming from Soulseek. Wait for them and carry on where it was, instead of
            // treating it as a broken stream.
            if (PlayNowIds.streamId(trackId) != null) {
                if (retryTrackId != trackId) { retryTrackId = trackId; retryCount = 0 }
                if (retryCount < PLAY_NOW_WAITS) {
                    retryCount++
                    Log.i(TAG, "Play now song is ahead of its download; waiting (${retryCount}/$PLAY_NOW_WAITS)")
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(PLAY_NOW_WAIT_MS)
                        if (player.currentMediaItem?.mediaId == trackId && player.playbackState == Player.STATE_IDLE) reloadCurrentItem()
                    }
                    return
                }
                Log.w(TAG, "Play now song's download stopped; giving up on it")
                if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
                return
            }
            // Transient failures (network hand-over, server hiccup): retry the same stream with a
            // short back-off before concluding anything about the server.
            if (retryTrackId != trackId) { retryTrackId = trackId; retryCount = 0 }
            if (retryCount < MAX_LOAD_RETRIES) {
                retryCount++
                val delayMs = 1_500L * retryCount
                Log.w(TAG, "Load failed (${error.errorCodeName}); retry $retryCount/$MAX_LOAD_RETRIES in $delayMs ms")
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(delayMs)
                    if (player.currentMediaItem?.mediaId == trackId && player.playbackState == Player.STATE_IDLE) reloadCurrentItem()
                }
                return
            }
            if (streamResolver.markTranscodeFailure(trackId)) {
                Log.w(TAG, "Retrying ${player.currentMediaItem?.mediaMetadata?.title} at original quality")
                retryCount = 0
                reloadCurrentItem()
                return
            }
            if (!networkMonitor.state.value.isOnline || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            ) {
                awaitingNetwork = true
                Log.w(TAG, "Server or network unavailable; will keep retrying \"${player.currentMediaItem?.mediaMetadata?.title}\"")
                startSlowRetry()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) { slowRetryJob?.cancel(); awaitingNetwork = false }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem?.mediaId != retryTrackId) { retryTrackId = null; retryCount = 0 }
            val track = mediaItem?.let { trackFor(it) }
            reporter.onTrackStarted(track)
            recordContextProgress()
            sleepTimer.onTrackChanged()
            applyReplayGain()
            lastApplied = appliedNow(engine.decision.value)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) recordContextProgress(finished = true)
        }

        override fun onEvents(p: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                scheduleSave()
                updateButtons()
                syncPlayNowOrder()
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                queueStore.retain(currentItems().map { it.mediaId })
                if (p.mediaItemCount == 0 && playContext != null) {
                    playContext = null
                    publishExtras()
                }
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !p.playWhenReady) {
                scheduleSave(immediate = true)
                recordContextProgress()
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

    // ---- listening history ----

    /** The current place in [playContext], or `null` when there is nothing to record. */
    private fun contextProgress(finished: Boolean = false): RecentContext? {
        val context = playContext ?: return null
        val track = if (finished) null else currentTrack() ?: return null
        return RecentContext(
            context = context,
            trackId = track?.id,
            trackTitle = track?.title,
            trackIndex = if (finished) 0 else player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = if (finished) 0L else player.currentPosition.coerceAtLeast(0L),
            shuffle = player.shuffleModeEnabled,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun recordContextProgress(finished: Boolean = false) {
        contextProgress(finished)?.let(historyWrites::trySend)
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
                context = playContext,
            ),
        )
    }

    private suspend fun loadPersistedQueue(): MediaSession.MediaItemsWithStartPosition? {
        val saved = queuePersistence.load() ?: return null
        if (saved.tracks.isEmpty()) return null
        queueStore.putAll(saved.tracks)
        queueTitle = saved.queueTitle
        playContext = saved.context
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
            putString(SessionExtras.PLAY_CONTEXT, PlayContextCodec.encode(playContext))
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

        override fun setPlayContext(context: PlayContext?) {
            // Sent before the new queue arrives, so this still sees the outgoing one: flush its progress.
            if (playContext?.key != context?.key) recordContextProgress()
            playContext = context
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
            // A notification cannot host the 1–5 picker, so Ratings mode leaves the heart out.
            return if (feedbackRepository.feedback.value.usesRatings) listOf(shuffle, repeat) else listOf(favorite, shuffle, repeat)
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
        private const val MAX_LOAD_RETRIES = 2
        /** A Play now song ahead of its download waits up to 3 minutes for it, every 3 s. */
        private const val PLAY_NOW_WAITS = 60
        private const val PLAY_NOW_WAIT_MS = 3_000L
        /** How far ahead of the queue continuo hears the Play now order. */
        private const val PLAY_NOW_LOOKAHEAD = 50
        /** The ready song moves up this close to the end of the current one, from the next 10. */
        private const val KEEP_GOING_AHEAD_MS = 15_000L
        private const val KEEP_GOING_LOOKAHEAD = 10
        private const val SLOW_RETRY_ATTEMPTS = 12
        private const val SLOW_RETRY_INTERVAL_MS = 15_000L
        const val SESSION_ID = "polyphony"
        const val NOTIFICATION_CHANNEL_ID = "polyphony_playback"
        private const val PRELOAD_DURATION_US = 10_000_000L
    }
}

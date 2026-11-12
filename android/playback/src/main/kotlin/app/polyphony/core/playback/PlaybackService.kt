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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
import app.polyphony.core.domain.playback.EqualizerMode
import app.polyphony.core.domain.playback.EqualizerSettings
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.PlaybackSettings
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.Scrobbler
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.transcode.AdaptiveStreaming
import app.polyphony.core.domain.transcode.NetworkState
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.playback.stream.AdaptiveMediaSourceFactory
import app.polyphony.core.playback.stream.ContinuoStreams
import app.polyphony.core.playback.stream.HlsDataSources
import app.polyphony.core.playback.stream.forAdaptiveStreams
import java.util.concurrent.ConcurrentHashMap
import app.polyphony.core.playback.audio.HeadsetMonitor
import app.polyphony.core.playback.audio.ReplayGain
import app.polyphony.core.playback.cast.CastManager
import app.polyphony.core.playback.cast.PlayerFactory
import app.polyphony.core.playback.cast.PlayerFactory.isRemotePlayback
import app.polyphony.core.playback.cast.PolyphonyMediaItemConverter
import app.polyphony.core.playback.connection.ConnectionSampler
import app.polyphony.core.playback.connection.MeteredDataSource
import app.polyphony.core.playback.dlna.PolyphonyDlnaResolver
import app.polyphony.core.playback.engine.LatencyMonitor
import app.polyphony.core.playback.engine.LatencyPlayerListener
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueShuffleOrder
import app.polyphony.core.playback.queue.PendingShuffleOrder
import app.polyphony.core.playback.queue.LongTrackPositions
import app.polyphony.core.playback.queue.QueuePersistence
import app.polyphony.core.playback.queue.QueueRestore
import app.polyphony.core.playback.queue.QueueSnapshot
import app.polyphony.core.playback.queue.QueueStore
import app.polyphony.core.playback.remote.PlayNowReceiverLinks
import app.polyphony.core.playback.reporting.PlaybackReporter
import app.polyphony.core.playback.session.CustomCommands
import app.polyphony.core.playback.session.PlayContextCodec
import app.polyphony.core.playback.session.PolyphonySessionCallback
import app.polyphony.core.playback.stream.PrefetchPlan
import app.polyphony.core.playback.stream.QueuePrefetcher
import app.polyphony.core.playback.transcode.AppliedQuality
import app.polyphony.core.playback.transcode.QualityChangeRule
import app.polyphony.core.playback.session.SessionActions
import app.polyphony.core.playback.session.SeekableStreamsPlayer
import app.polyphony.core.playback.session.SessionExtras
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import app.polyphony.core.playback.stream.LoadFailurePolicy
import app.polyphony.core.playback.stream.LoadFailures
import app.polyphony.core.playback.stream.StreamCache
import app.polyphony.core.playback.stream.StreamResolver
import app.polyphony.core.playback.stream.UnplayableSkips
import app.polyphony.core.playback.timer.SleepTimer
import app.polyphony.core.playback.audio.OutputVolume
import app.polyphony.core.playback.audio.PausingAudioFocus
import app.polyphony.core.playback.audio.eq.BuiltInEqualizer
import app.polyphony.core.playback.crossfade.CrossfadePlan
import app.polyphony.core.playback.crossfade.Crossfader
import androidx.media3.exoplayer.source.MediaSource
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
import kotlinx.coroutines.flow.first
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
    @Inject lateinit var connectionSampler: ConnectionSampler
    @Inject lateinit var castManager: CastManager
    @Inject lateinit var converter: PolyphonyMediaItemConverter
    @Inject lateinit var serverLookup: ServerLookup
    @Inject lateinit var dlnaManager: DlnaManager
    @Inject lateinit var dlnaResolver: PolyphonyDlnaResolver
    @Inject lateinit var playNowLinks: PlayNowReceiverLinks
    @Inject lateinit var scrobbler: Scrobbler
    @Inject lateinit var downloadRepository: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var localPlayer: ExoPlayer
    /** ExoPlayer, or the composite Cast player wrapping it. Playback returns here after DLNA. */
    private lateinit var basePlayer: Player
    /** The player the session currently drives: [basePlayer] or an active [DlnaPlayer]. */
    private lateinit var player: Player
    private var dlnaPlayer: DlnaPlayer? = null
    private var session: MediaLibrarySession? = null
    private lateinit var sleepTimer: SleepTimer
    /** The local player's volume: ReplayGain, the sleep timer and crossfade each set their share. */
    private lateinit var localVolume: OutputVolume
    private lateinit var crossfader: Crossfader
    /** The built-in equaliser, in the local player and the crossfade's second player alike. */
    private val equalizer = BuiltInEqualizer()
    private lateinit var pausingFocus: PausingAudioFocus
    /** How the local player opens songs; the crossfade's second player opens them the same way. */
    private lateinit var mediaSourceFactory: MediaSource.Factory
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    private lateinit var reporter: PlaybackReporter
    private lateinit var headset: HeadsetMonitor
    private lateinit var latencyListener: LatencyPlayerListener
    /** The streaming HTTP source, shared by the player and the queue's fetching ahead. */
    private lateinit var streamingUpstream: DataSource.Factory
    private lateinit var prefetcher: QueuePrefetcher
    /** continuo's adaptive streams (#185): their addresses and this install's tokens. */
    private lateinit var continuoStreams: ContinuoStreams
    /** continuo's HLS straight from the network (token, metering), for fetching ahead. */
    private lateinit var hlsNetwork: DataSource.Factory
    /** What continuo was last told is coming up (server, songs, kbit/s, lower too), to send only changes. */
    private var lastUpcoming: List<Any>? = null
    /** The level songs ahead are fetched at, kept for the song playing and its cap ([upcomingKbps]). */
    private var aheadKbps: Pair<String, Int>? = null
    /** The local player's track selector, which carries the audio bitrate cap ([applyAudioCap]). */
    private lateinit var trackSelector: DefaultTrackSelector
    /** Songs whose adaptive stream failed this session: they play their ordinary stream instead. */
    private val hlsFallback: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Songs whose media source is HLS, as created ([AdaptiveMediaSourceFactory]). */
    private val hlsSourced: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Downloaded songs, kept current, so choosing a song's stream ([routeFor]) never waits on the database. */
    @Volatile private var downloadedIds: Set<String> = emptySet()

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
    /** A restored queue's shuffle order, until the player holds that queue (see [applyRestoredShuffle]). */
    private var restoredShuffle: PendingShuffleOrder? = null
    /** The restore at start, which Media3's playback resumption waits for. */
    private var startRestore: Job? = null
    private val unplayableSkips = UnplayableSkips()
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
        equalizer.apply(initial.playback.equalizer)

        localPlayer = buildLocalPlayer(initial.playback)
        localVolume = OutputVolume { localPlayer.volume = it }
        crossfader = Crossfader(scope, localPlayer, localVolume, ::buildCrossfadePlayer, ::crossfadePlan)
        pausingFocus = PausingAudioFocus(this, localPlayer).also { it.enabled = !initial.playback.duckOnTransientFocusLoss }
        prefetcher = QueuePrefetcher(scope, streamResolver, streamCache, streamingUpstream, hlsNetwork)
        val players = PlayerFactory.create(this, localPlayer, castManager.isSupported, converter)
        basePlayer = SeekableStreamsPlayer(players.session, ::seekByReload)
        player = basePlayer

        // The fade is the local player's: a speaker's own volume isn't touched (it used to be left at 100%).
        sleepTimer = SleepTimer(
            scope,
            pause = { player.pause() },
            setVolume = { level ->
                localVolume.sleep = level
                crossfader.setSleep(level)
            },
            remainingInTrackMs = {
                val duration = player.duration
                if (duration == C.TIME_UNSET || duration <= 0 || !player.isPlaying) null else duration - player.currentPosition
            },
        )
        reporter = PlaybackReporter(scope, serverLookup, libraryRepository, scrobbler) { serverId, streamId, listenedAtMs ->
            val server = serverLookup.providerFor(serverId)?.server ?: return@PlaybackReporter
            if (requestRepository.supports(server)) requestRepository.scrobblePlayNow(server, streamId, listenedAtMs)
        }.also {
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
        latencyListener = LatencyPlayerListener(latencyMonitor, connectionSampler::onStall)

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
        if (settings.equalizer.mode == EqualizerMode.SYSTEM) openAudioEffectSession()
        restoreQueueOnStart(initial.playback.rememberQueue)
        scope.launch { loadLongPositions() }
        crossfader.start()
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
        connectionSampler.tick(streaming = false, playing = null)
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
        crossfader.release()
        pausingFocus.release()
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
        // What comes off the network, what the cache serves and what downloads serve are each
        // counted for the connection samples (#185).
        val upstream = MeteredDataSource.Factory(
            OkHttpDataSource.Factory(streamingClient), connectionSampler, MeteredDataSource.Kind.NETWORK,
        )
        streamingUpstream = upstream
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(MeteredDataSource.CacheListener(connectionSampler))
        // DefaultDataSource routes http(s) through the cache + OkHttp and file:// (downloads) to
        // FileDataSource, so downloaded tracks bypass the stream cache entirely.
        val local = MeteredDataSource.Factory(DefaultDataSource.Factory(this, cached), connectionSampler, MeteredDataSource.Kind.FILES)
        val resolving = ResolvingDataSource.Factory(local, streamResolver)
        // continuo's adaptive streams (#185): HLS through the same cache and metering, with this
        // install's token on every request (a fresh one when continuo refuses an expired one).
        continuoStreams = ContinuoStreams(requestRepository)
        val hlsSources = HlsDataSources(cached, upstream, ::serverForHls, continuoStreams) { trackId, _ ->
            streamResolver.recordAdaptive(trackId, currentCapKbps())
        }
        hlsNetwork = hlsSources.network
        val hls = hlsSources.mediaSourceFactory(online = { networkMonitor.state.value.isOnline })
        mediaSourceFactory = AdaptiveMediaSourceFactory(DefaultMediaSourceFactory(resolving), hls) { item ->
            // What each song's source actually is: a song downloaded since keeps the HLS source it got.
            routeFor(item).also { route ->
                if (route == AdaptiveStreaming.Route.HLS) hlsSourced += item.mediaId else hlsSourced -= item.mediaId
            }
        }
        trackSelector = newTrackSelector()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(equalizer.renderersFactory(this))
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            // Media3 ducks for a notification; with ducking off, PausingAudioFocus pauses instead.
            .setAudioAttributes(audioAttributes, playback.duckOnTransientFocusLoss)
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
        // Shuffle mixes the list you pressed play on, never the songs you queued (UserQueue).
        exo.setShuffleOrder(QueueShuffleOrder())
        exo.addAnalyticsListener(adaptiveRungs)
        return exo
    }

    /** The rung the adaptive song playing is at (song id, kbit/s), from [adaptiveRungs]. */
    @Volatile private var playingRung: Pair<String, Int>? = null

    /**
     * Follows the rung an adaptive song is playing at (#185), shown in Now Playing and used for the
     * songs ahead. The downstream format is the playing chunk's rendition, as the master playlist
     * announces it; the renderer's input format won't do, as it keeps the first-listed rendition's
     * bitrate whatever plays (HlsPlaybackTest).
     */
    private val adaptiveRungs = object : AnalyticsListener {
        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
            val format = mediaLoadData.trackFormat ?: return
            if (eventTime.timeline.isEmpty) return
            val item = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            if (!isAdaptive(item)) return
            val rung = AdaptiveStreaming.rungForBandwidth(format.bitrate) ?: return
            playingRung = item.mediaId to rung
            streamResolver.recordAdaptive(item.mediaId, currentCapKbps(), rung)
        }
    }

    /**
     * The crossfade's second player: opens songs as the local player does, in the same audio session
     * (so an equaliser applies to both), and never takes the audio focus, which stays the local player's.
     */
    private fun buildCrossfadePlayer(): ExoPlayer =
        ExoPlayer.Builder(this)
            .setRenderersFactory(equalizer.renderersFactory(this))
            .setMediaSourceFactory(mediaSourceFactory)
            // The cap as it is when the fade starts; a fade is seconds long.
            .setTrackSelector(newTrackSelector())
            .setAudioAttributes(audioAttributes, false)
            .setSkipSilenceEnabled(settings.skipSilence)
            .setName("Polyphony crossfade")
            .build()
            .also { if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) it.setAudioSessionId(audioSessionId) }

    /**
     * Crossfade into the next song, or not ([CrossfadePlan]): only on this phone's own player, not
     * with repeat-one (the "next" song is this one) or while the sleep timer waits for the song to end.
     */
    private fun crossfadePlan(): Crossfader.Plan? {
        if (settings.crossfadeMs <= 0 || player !== basePlayer || player.isRemotePlayback()) return null
        if (sleepTimer.waitingForTrackEnd || localPlayer.repeatMode == Player.REPEAT_MODE_ONE) return null
        val timeline = localPlayer.currentTimeline
        if (timeline.isEmpty) return null
        val next = timeline.getNextWindowIndex(localPlayer.currentMediaItemIndex, localPlayer.repeatMode, localPlayer.shuffleModeEnabled)
        if (next == C.INDEX_UNSET) return null
        val current = localPlayer.currentMediaItem?.let(::trackFor)
        val nextTrack = trackFor(localPlayer.getMediaItemAt(next))
        val fade = CrossfadePlan.fadeMs(settings.crossfadeMs, settings.gapless, current, nextTrack, localPlayer.duration)
        return if (fade > 0) Crossfader.Plan(next, fade) else null
    }

    /**
     * HLS from continuo or the ordinary stream, for one song ([AdaptiveStreaming.route]). Decided
     * from what's in memory (servers, the downloaded set), as Media3 asks when songs are queued.
     */
    private fun routeFor(item: MediaItem): AdaptiveStreaming.Route {
        val (serverId, trackId) = item.localConfiguration?.uri?.let(MediaItemFactory::parseUri)
            ?: return AdaptiveStreaming.Route.PROGRESSIVE
        if (trackId in hlsFallback) return AdaptiveStreaming.Route.PROGRESSIVE
        return AdaptiveStreaming.route(
            server = serverLookup.providerFor(serverId)?.server,
            enabled = settings.adaptiveStreaming,
            playNow = PlayNowIds.streamId(trackId) != null,
            downloadedPlays = trackId in downloadedIds,
        )
    }

    /** A song's server for the HLS loaders (blocking; the hot map may still be empty at start). */
    private fun serverForHls(serverId: String): Server? =
        serverLookup.providerFor(serverId)?.server ?: runBlocking { serverLookup.awaitProvider(serverId) }?.server

    /** The highest rung adaptive streams may play now: the rules' ceiling, the lowest while the connection keeps failing. */
    private fun currentCapKbps(): Int {
        val decision = engine.localDecision()
        return AdaptiveStreaming.capKbps(decision.profile, decision.cappedByLatency || latencyMonitor.degraded.value, networkMonitor.state.value.isOnline)
    }

    private fun newTrackSelector(): DefaultTrackSelector = DefaultTrackSelector(this).apply {
        setParameters(buildUponParameters().forAdaptiveStreams(currentCapKbps()))
    }

    /**
     * Apply the cap to the local player: an adaptive stream moves to it from its next segment, in
     * the middle of a song, with no reload. Other streams have one rendition and are unaffected
     * (the selector still plays a track over the cap when it's the only one).
     */
    private fun applyAudioCap() {
        val max = AdaptiveStreaming.selectorMaxBitrate(currentCapKbps())
        if (trackSelector.parameters.maxAudioBitrate == max) return
        Log.i(TAG, "Adaptive streams capped at ${currentCapKbps()} kbit/s")
        trackSelector.setParameters(trackSelector.buildUponParameters().setMaxAudioBitrate(max))
    }

    private fun isAdaptive(item: MediaItem?): Boolean = item != null && routeFor(item) == AdaptiveStreaming.Route.HLS

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
                applyAudioCap()   // offline, no cap: whatever level the cache holds plays
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
        scope.launch {
            latencyMonitor.degraded.collect {
                publishExtras()
                applyAudioCap()
                refreshPrefetch()
            }
        }
        scope.launch { downloadRepository.downloadedTrackIds().collect { downloadedIds = it } }
        scope.launch {
            sleepTimer.endsAtEpochMs.collect {
                publishExtras()
                updateButtons()
            }
        }
        scope.launch { feedbackRepository.feedback.collect { updateButtons() } }
        historyJob = scope.launch(Dispatchers.IO) {
            for (entry in historyWrites) runCatching { listeningHistory.record(entry) }.onFailure { Log.w(TAG, "History write failed: ${it.message}") }
        }
        // A new connection is judged afresh; the same one coming and going (a tunnel, a rural road)
        // says it keeps failing, so it stays degraded rather than resetting each time (#185).
        scope.launch {
            var lastOnline: NetworkState? = null
            var wasOnline = true
            networkMonitor.state.collect { state ->
                val streaming = player.playWhenReady && player.currentMediaItem != null && !player.isRemotePlayback()
                when (LatencyMonitor.networkChange(lastOnline, wasOnline, state, streaming)) {
                    LatencyMonitor.NetworkChange.NEW_CONNECTION -> latencyMonitor.reset()
                    LatencyMonitor.NetworkChange.LOST -> {
                        Log.i(TAG, "The network went away mid-stream; the connection counts as failing")
                        latencyMonitor.onConnectionLost()
                    }
                    LatencyMonitor.NetworkChange.BACK -> latencyMonitor.onConnectionBack()
                    LatencyMonitor.NetworkChange.NOTHING -> Unit
                }
                if (state.isOnline) lastOnline = state
                wasOnline = state.isOnline
            }
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
                connectionSampler.tick(isStreamingHere(), playingIds())
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

    /** This phone's own player is playing or rebuffering (not paused, not on a Cast or DLNA receiver). */
    private fun isStreamingHere(): Boolean =
        player === basePlayer && !player.isRemotePlayback() && player.playWhenReady && player.mediaItemCount > 0 &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private fun playingIds(): ConnectionSampler.Playing? =
        localPlayer.currentMediaItem?.localConfiguration?.uri?.let(MediaItemFactory::parseUri)
            ?.let { (serverId, trackId) -> ConnectionSampler.Playing(serverId, trackId) }

    private fun onSettingsChanged(new: PlaybackSettings) {
        val old = settings
        settings = new
        if (old.skipSilence != new.skipSilence) localPlayer.skipSilenceEnabled = new.skipSilence
        if (old.duckOnTransientFocusLoss != new.duckOnTransientFocusLoss) {
            localPlayer.setAudioAttributes(audioAttributes, new.duckOnTransientFocusLoss)
            pausingFocus.enabled = !new.duckOnTransientFocusLoss
        }
        if (old.playbackSpeed != new.playbackSpeed) player.setPlaybackSpeed(new.playbackSpeed)
        if (old.preloadNextTrack != new.preloadNextTrack) applyPreload(localPlayer, new.preloadNextTrack)
        if (old.equalizer != new.equalizer) applyEqualizer(old.equalizer.mode, new.equalizer)
        reporter.enabled = new.scrobble
        reporter.threshold = new.scrobbleThreshold
        headset.setEnabled(new.resumeOnConnect)
        // Off, the saved queue goes (it used to stay, and a media button after a restart brought it back).
        if (old.rememberQueue != new.rememberQueue) {
            if (new.rememberQueue) scheduleSave(immediate = true) else scope.launch { queuePersistence.clear() }
        }
        applyReplayGain()
        if (old.prefetchQueue != new.prefetchQueue || old.prefetchOnMobileData != new.prefetchOnMobileData) refreshPrefetch()
        // Songs are opened as HLS or not when they're queued: re-queue them, at the same place.
        if (old.adaptiveStreaming != new.adaptiveStreaming && player === basePlayer && !player.isRemotePlayback() &&
            localPlayer.mediaItemCount > 0
        ) {
            reloadCurrentItem()
            refreshPrefetch()
        }
    }

    /**
     * Fetch the next songs of the queue fully into the stream cache (see [PrefetchPlan] for how
     * many), so a network drop doesn't stop the music at the next song. Only for this phone's own
     * player: a Cast or DLNA receiver streams for itself.
     */
    private fun refreshPrefetch() {
        val flaky = latencyMonitor.degraded.value
        if (player === basePlayer && !player.isRemotePlayback()) syncUpcoming(flaky)
        val budget = PrefetchPlan.budget(networkMonitor.state.value, settings.prefetchQueue, settings.prefetchOnMobileData, flaky)
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
     * On a speaker (Cast, DLNA), fetch the queue's Play now links ahead: Cast's converter can't
     * wait for one, so a song whose link is in hand starts at once.
     */
    private fun warmPlayNowLinks() {
        if (dlnaPlayer == null && castManager.currentTargetSync() == null) return
        val streams = currentItems().mapNotNull { item ->
            PlayNowIds.streamId(item.mediaId)?.let { stream -> MediaItemFactory.serverIdFrom(item)?.let { it to stream } }
        }
        streams.groupBy({ it.first }, { it.second }).forEach { (serverId, ids) -> playNowLinks.warm(serverId, ids) }
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
    private fun upcomingSongs(max: Int): List<Pair<PrefetchPlan.Upcoming, QueuePrefetcher.Song>> {
        val kbps = upcomingKbps()
        return upcomingItems(max).mapNotNull { item ->
            val serverId = MediaItemFactory.serverIdFrom(item) ?: return@mapNotNull null
            val trackId = item.mediaId
            PrefetchPlan.Upcoming(trackId, queueStore.get(trackId)?.durationMs ?: 0L, fetchable = PlayNowIds.streamId(trackId) == null) to
                QueuePrefetcher.Song(serverId, trackId, hlsFor(item, serverId, trackId, kbps))
        }
    }

    /** An adaptive song's stream to fetch ahead: at the level playing now, within the cap (the lowest while stalling). */
    private fun hlsFor(item: MediaItem, serverId: String, trackId: String, kbps: Int): QueuePrefetcher.Hls? {
        if (routeFor(item) != AdaptiveStreaming.Route.HLS) return null
        val server = serverLookup.providerFor(serverId)?.server ?: return null
        return QueuePrefetcher.Hls(continuoStreams.masterUrl(server, trackId).removeSuffix("master.m3u8"), kbps)
    }

    /**
     * The level songs ahead are fetched and encoded at ([AdaptiveStreaming.upcomingKbps]): the
     * level of the song playing, taken once per song (and cap), so the player moving between levels
     * inside a song doesn't restart the fetching ahead at the other level each time.
     */
    private fun upcomingKbps(): Int {
        val cap = currentCapKbps()
        val current = localPlayer.currentMediaItem
        val key = "${current?.mediaId}|$cap"
        aheadKbps?.let { (k, kbps) -> if (k == key) return kbps }
        val adaptive = isAdaptive(current)
        val playing = if (adaptive) playingRung?.takeIf { it.first == current?.mediaId }?.second else null
        val kbps = AdaptiveStreaming.upcomingKbps(playing, cap)
        // Until an adaptive song's level is known, the cap stands in, and isn't kept.
        if (playing != null || !adaptive) aheadKbps = key to kbps
        return kbps
    }

    /**
     * Tell continuo the adaptive songs coming up (the next four, of the current song's server) and
     * the level playing, so it encodes them ahead; one level lower too for the next song while the
     * connection keeps stalling. Sent only when that changes; when none are coming up any more
     * (another server's songs, the end of the queue, the setting off), continuo is told so once,
     * and stops encoding the songs it was told before.
     */
    private fun syncUpcoming(flaky: Boolean) {
        val next = upcomingItems(UPCOMING_AHEAD * 2).filter(::isAdaptive)
        val serverId = next.firstNotNullOfOrNull(MediaItemFactory::serverIdFrom)
        val previous = lastUpcoming
        if (previous != null && previous[0] != serverId && (previous[1] as List<*>).isNotEmpty()) {
            postUpcoming(listOf(previous[0], emptyList<String>(), previous[2], false))
        }
        if (serverId == null) {
            lastUpcoming = null
            return
        }
        val songs = next.filter { MediaItemFactory.serverIdFrom(it) == serverId }.map { it.mediaId }.take(UPCOMING_AHEAD)
        val state = listOf(serverId, songs, upcomingKbps(), flaky)
        if (state == lastUpcoming) return
        lastUpcoming = state
        postUpcoming(state)
    }

    /** POST /v1/stream/upcoming with [state] (server id, songs, kbit/s, stalling). */
    private fun postUpcoming(state: List<Any>) {
        val serverId = state[0] as String
        @Suppress("UNCHECKED_CAST") val songs = state[1] as List<String>
        val kbps = state[2] as Int
        val flaky = state[3] as Boolean
        scope.launch(Dispatchers.IO) {
            val server = serverLookup.providerFor(serverId)?.server ?: return@launch
            runCatching { requestRepository.streamUpcoming(server, songs, kbps, lowerToo = flaky) }
                .onFailure { e ->
                    Log.i(TAG, "Couldn't tell continuo the songs coming up: ${e.message}")
                    withContext(Dispatchers.Main) { if (lastUpcoming == state) lastUpcoming = null }
                }
        }
    }

    /**
     * Reacts to a changed transcoding decision. A change of output target is already handled by
     * the composite player's transfer (items are re-converted for the new target), so only a
     * profile change for the *same* target triggers a reload.
     */
    private fun onDecisionChanged(decision: TranscodeDecision) {
        publishExtras()
        applyAudioCap()
        val next = appliedNow(decision)
        Log.i(TAG, "Transcode decision for ${next.targetKey}: ${decision.profile.name} [${decision.profile.summary}] reason=${decision.reason} cappedByLatency=${decision.cappedByLatency}")
        val action = QualityChangeRule.decide(lastApplied, next, engine.policy.reloadCurrentTrackOnChange)
        lastApplied = next
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        when (action) {
            QualityChangeRule.Action.RELOAD_NOW -> if (isAdaptive(player.currentMediaItem) && !player.isRemotePlayback()) {
                // An adaptive stream follows the new cap from its next segment: no reload, no restart.
                Log.i(TAG, "Quality changed; the adaptive stream follows from its next segment")
            } else {
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
                retryCount = LoadFailurePolicy.MAX_RETRIES // a failure here comes straight back to the slow loop
                reloadCurrentItem()
            }
            Log.w(TAG, "Giving up after $SLOW_RETRY_ATTEMPTS slow retries")
        }
    }

    /**
     * A scrub in a song the local player cannot seek in (a live transcode, see
     * [SeekableStreamsPlayer]): wait off the main thread until the server knows the song's length,
     * then reopen it at [positionMs], now seekable. If the length doesn't come in time the song
     * plays on where it was.
     */
    private fun seekByReload(index: Int, positionMs: Long): ListenableFuture<*> {
        val done = SettableFuture.create<Unit>()
        val item = player.takeIf { index in 0 until it.mediaItemCount }?.getMediaItemAt(index)
        val uri = item?.localConfiguration?.uri
        if (item == null || uri == null) return done.apply { set(Unit) }
        scope.launch {
            try {
                val ready = withContext(Dispatchers.IO) { streamResolver.awaitKnownLength(uri, SEEK_LENGTH_WAIT_MS) }
                if (player !== basePlayer || player.currentMediaItem?.mediaId != item.mediaId) return@launch
                if (ready) {
                    Log.i(TAG, "Seeking in a live transcode: reopening it at $positionMs ms with a known length")
                    reloadCurrentItem(positionMs)
                } else {
                    Log.w(TAG, "Can't seek yet: the server is still transcoding \"${item.mediaMetadata.title}\"")
                }
            } finally {
                done.set(Unit)
            }
        }
        return done
    }

    private fun reloadCurrentItem(atPositionMs: Long? = null) {
        val items = currentItems()
        if (items.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(0, items.lastIndex)
        // A new player period with a fresh extractor: the stream may resolve afresh (new quality,
        // the original after a failed transcode, a known length).
        items[index].mediaId.takeIf { it.isNotEmpty() }?.let(streamResolver::unpin)
        val position = atPositionMs ?: player.currentPosition
        val playWhenReady = player.playWhenReady
        player.setMediaItems(items, index, position)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    /**
     * Moves on from a song that can't play, keeping play or pause as it was; with nothing after it
     * that could (the end of the queue, or a whole queue of them) the error stays showing.
     */
    private fun skipUnplayable(why: String) {
        if (!player.hasNextMediaItem() || !unplayableSkips.allow(player.mediaItemCount)) {
            Log.w(TAG, "$why; nothing after it to play")
            return
        }
        Log.w(TAG, "$why; skipping to the next song")
        player.seekToNextMediaItem()
        player.prepare()
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
        streamResolver.retainPins(emptySet()) // every item gets a new period on the new output

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

    // ---- long songs' places (#37) ----

    private val longPositions = LongTrackPositions()
    /** The long song just resumed and where (song id, ms), for Now Playing's "Start over". */
    private var resumed: Pair<String, Long>? = null

    private fun durationOf(item: MediaItem): Long = queueStore.get(item.mediaId)?.durationMs ?: 0L

    private fun saveLongPositions() {
        val text = longPositions.serialize()
        scope.launch { queuePersistence.saveLongPositions(text) }
    }

    /**
     * The server's bookmark follows a long song's place, so the listener's other devices (and
     * this one after a reinstall) pick up there too. Best effort: the place on the phone stands.
     */
    private fun mirrorBookmark(item: MediaItem, change: LongTrackPositions.Change, positionMs: Long) {
        if (change == LongTrackPositions.Change.NONE || PlayNowIds.streamId(item.mediaId) != null) return
        val serverId = MediaItemFactory.serverIdFrom(item) ?: return
        scope.launch(Dispatchers.IO) {
            val provider = serverLookup.providerFor(serverId) ?: return@launch
            runCatching {
                if (change == LongTrackPositions.Change.KEPT) provider.saveBookmark(item.mediaId, positionMs)
                else provider.deleteBookmark(item.mediaId)
            }
        }
    }

    /** This phone's places, then the servers' bookmarks for songs it has none for. */
    private suspend fun loadLongPositions() {
        queuePersistence.loadLongPositions()?.let(longPositions::load)
        for (serverId in serverLookup.knownServerIds().orEmpty()) {
            val provider = serverLookup.awaitProvider(serverId) ?: continue
            val marks = withContext(Dispatchers.IO) { runCatching { provider.bookmarks() }.getOrDefault(emptyMap()) }
            longPositions.merge(marks)
        }
    }

    /** The song playing before the last change of song, to tell a reload of it from a new choice. */
    private var lastSongId: String? = null

    /**
     * A long song chosen (not reached by playing on) resumes where it was left. Not a crossfade
     * moving on to it (a seek, but the crossfader's), and not the same song reloaded (a retry, a
     * changed quality), which would undo a "Start over".
     */
    private fun resumeLongTrack(item: MediaItem?, reason: Int) {
        val before = resumed
        val reloaded = item != null && item.mediaId == lastSongId
        lastSongId = item?.mediaId
        if (reloaded) return
        resumed = null
        val chosen = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> true
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> item != null && !crossfader.movedOnTo(item.mediaId)
            else -> false
        }
        if (item != null && chosen && player.currentPosition < RESUME_ONLY_FROM_MS) {
            longPositions.resumeAt(item.mediaId, durationOf(item))?.let { at ->
                Log.i(TAG, "Resuming \"${item.mediaMetadata.title}\" at ${at / 1000} s")
                player.seekTo(at)
                resumed = item.mediaId to at
            }
        }
        if (resumed != before) publishExtras()
    }

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
            // Offline mode and no download, a removed server: no retry helps (a restored queue meets these).
            if (LoadFailures.isUnplayable(error)) {
                skipUnplayable("\"${player.currentMediaItem?.mediaMetadata?.title}\" can't play (${error.cause?.message})")
                return
            }
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
            if (retryTrackId != trackId) { retryTrackId = trackId; retryCount = 0 }
            val failure = LoadFailurePolicy.Failure(
                errorCode = error.errorCode,
                httpStatus = LoadFailures.httpStatus(error),
                online = networkMonitor.state.value.isOnline,
                // The source that failed, not how the song would be routed now (a download may have finished).
                adaptive = trackId in hlsSourced,
                ordinaryTried = trackId in hlsFallback,
                downloaded = trackId in downloadedIds,
                retries = retryCount,
                originalQualityLeft = streamResolver.canFallBackToOriginal(trackId),
            )
            val title = player.currentMediaItem?.mediaMetadata?.title
            when (val action = LoadFailurePolicy.decide(failure)) {
                is LoadFailurePolicy.Action.Retry -> {
                    retryCount = action.attempt
                    Log.w(TAG, "Load failed (${error.errorCodeName}); retry ${action.attempt}/${LoadFailurePolicy.MAX_RETRIES} in ${action.afterMs} ms")
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(action.afterMs)
                        if (player.currentMediaItem?.mediaId == trackId && player.playbackState == Player.STATE_IDLE) reloadCurrentItem()
                    }
                }
                LoadFailurePolicy.Action.PlayOrdinaryStream -> {
                    hlsFallback.add(trackId)
                    Log.w(TAG, "Adaptive stream failed for \"$title\"; playing its ordinary stream")
                    retryCount = 0
                    reloadCurrentItem()
                }
                LoadFailurePolicy.Action.PlayOriginalQuality -> {
                    streamResolver.markTranscodeFailure(trackId)
                    Log.w(TAG, "Retrying \"$title\" at original quality")
                    retryCount = 0
                    reloadCurrentItem()
                }
                LoadFailurePolicy.Action.SkipGone -> skipUnplayable("\"$title\" is gone from the server")
                LoadFailurePolicy.Action.WaitForNetwork -> {
                    awaitingNetwork = true
                    Log.w(TAG, "Server or network unavailable; will keep retrying \"$title\"")
                    startSlowRetry()
                }
                LoadFailurePolicy.Action.Stop -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) { slowRetryJob?.cancel(); awaitingNetwork = false; restoredShuffle = null; unplayableSkips.onPlaying() }
            // Paused: a long song keeps its place (#37).
            val item = player.currentMediaItem
            if (!isPlaying && item != null && player.playbackState == Player.STATE_READY) {
                val at = player.currentPosition
                mirrorBookmark(item, longPositions.left(item.mediaId, at, durationOf(item)), at)
                saveLongPositions()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Leaving a song: a long one keeps its place, or forgets it when it played to its end.
            val old = oldPosition.mediaItem ?: return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                if (longPositions.finished(old.mediaId)) mirrorBookmark(old, LongTrackPositions.Change.FORGOTTEN, 0L)
            } else if (old.mediaId != newPosition.mediaItem?.mediaId) {
                mirrorBookmark(old, longPositions.left(old.mediaId, oldPosition.positionMs, durationOf(old)), oldPosition.positionMs)
            } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // A seek within it is where it is now ("Start over" forgets the place).
                mirrorBookmark(old, longPositions.left(old.mediaId, newPosition.positionMs, durationOf(old)), newPosition.positionMs)
            } else {
                return
            }
            saveLongPositions()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resumeLongTrack(mediaItem, reason)
            if (mediaItem?.mediaId != retryTrackId) { retryTrackId = null; retryCount = 0 }
            // Only the current song's period and the next one's (preloading) can still re-open a stream.
            val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it).mediaId }
            streamResolver.retainPins(setOfNotNull(mediaItem?.mediaId, next))
            val track = mediaItem?.let { trackFor(it) }
            reporter.onTrackStarted(track)
            recordContextProgress()
            sleepTimer.onTrackChanged()
            applyReplayGain()
            lastApplied = appliedNow(engine.decision.value)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) recordContextProgress(finished = true)
            // The queue's last song played to its end (no change of song says so).
            val item = player.currentMediaItem
            if (playbackState == Player.STATE_ENDED && item != null && longPositions.finished(item.mediaId)) {
                mirrorBookmark(item, LongTrackPositions.Change.FORGOTTEN, 0L)
                saveLongPositions()
            }
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
                warmPlayNowLinks()
                if (player === basePlayer && !player.isRemotePlayback()) syncUpcoming(latencyMonitor.degraded.value)
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                applyRestoredShuffle()
                queueStore.retain(currentItems().map { it.mediaId })
                if (p.mediaItemCount == 0 && playContext != null) {
                    playContext = null
                    publishExtras()
                }
            }
            // A seek, notably while paused, where neither the playing timer nor a pause saves the place.
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) scheduleSave()
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
        localVolume.gain = ReplayGain.volumeFor(
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
        val snapshot = QueueSnapshot.of(
            items = items.map { QueueSnapshot.Item(trackFor(it), MediaItemFactory.isQueued(it)) },
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffleOrder = localShuffleOrder(items.size),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queueTitle = queueTitle,
            context = playContext,
            savedAt = System.currentTimeMillis(),
        )
        if (snapshot == null) queuePersistence.clear() else queuePersistence.save(snapshot)
    }

    /** The local player's shuffle order while it plays the [size]-item queue; null on a speaker. */
    private fun localShuffleOrder(size: Int): List<Int>? =
        if (player === basePlayer && !player.isRemotePlayback() && localPlayer.mediaItemCount == size) {
            QueueShuffleOrder.playOrder(localPlayer.shuffleOrder)
        } else {
            null
        }

    /** Puts the saved shuffle order back once the restored list is in the local player ([PendingShuffleOrder]). */
    private fun applyRestoredShuffle() {
        val pending = restoredShuffle ?: return
        if (player !== basePlayer || player.isRemotePlayback()) return
        val ids = (0 until localPlayer.mediaItemCount).map { localPlayer.getMediaItemAt(it).mediaId }
        when (pending.step(ids, QueueShuffleOrder.playOrder(localPlayer.shuffleOrder))) {
            PendingShuffleOrder.Step.APPLY -> localPlayer.setShuffleOrder(QueueShuffleOrder.restored(pending.order))
            PendingShuffleOrder.Step.FORGET -> restoredShuffle = null
            PendingShuffleOrder.Step.NOTHING -> Unit
        }
    }

    /** The saved queue, as it would come back; reads the file and changes nothing. */
    private suspend fun loadRestorePlan(): QueueRestore.Plan? {
        if (!settings.rememberQueue) return null
        val saved = queuePersistence.load() ?: return null
        return QueueRestore.plan(saved, serverLookup.knownServerIds())
    }

    private fun restoredItems(plan: QueueRestore.Plan): MediaSession.MediaItemsWithStartPosition =
        MediaSession.MediaItemsWithStartPosition(
            plan.tracks.mapIndexed { i, track -> MediaItemFactory.fromTrack(track, queued = plan.queued[i]) },
            plan.index,
            plan.positionMs,
        )

    /**
     * Takes on the restored queue's title, collection and modes, and returns its items, which the
     * caller puts in the player at once. Only then: a restore that lost the race to a queue started
     * meanwhile used to overwrite that queue's collection, shuffle and repeat with the old ones.
     */
    private fun adoptRestored(plan: QueueRestore.Plan): MediaSession.MediaItemsWithStartPosition {
        val restored = restoredItems(plan)
        queueStore.putAll(plan.tracks)
        queueTitle = plan.queueTitle
        playContext = plan.context
        player.shuffleModeEnabled = plan.shuffle
        player.repeatMode = plan.repeatMode
        restoredShuffle = plan.shuffleOrder?.let { PendingShuffleOrder(restored.mediaItems.map(MediaItem::mediaId), it) }
        publishExtras()
        return restored
    }

    /** Puts the last queue back (paused, not prepared) so the mini player shows it on launch. */
    private fun restoreQueueOnStart(enabled: Boolean) {
        if (!enabled) return
        startRestore = scope.launch {
            val plan = loadRestorePlan() ?: return@launch
            awaitRoutingState()
            // Something was put in the player while the file was read (a controller's play): it wins.
            if (player.mediaItemCount > 0) return@launch
            val restored = adoptRestored(plan)
            player.setMediaItems(restored.mediaItems, restored.startIndex, restored.startPositionMs)
        }
    }

    /**
     * Media3's playback resumption: a media button or Bluetooth play with nothing loaded (often
     * right after the process died, racing [restoreQueueOnStart]), or System UI's resume card.
     * When the restore at start has filled the player meanwhile, that queue is the answer (Media3
     * sets it again and plays). System UI asking at boot ([isForPlayback] false) only shows the
     * song, so nothing is changed for it.
     */
    private suspend fun resumptionQueue(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? {
        startRestore?.join()
        if (player.mediaItemCount > 0) {
            return MediaSession.MediaItemsWithStartPosition(
                currentItems(),
                player.currentMediaItemIndex.coerceAtLeast(0),
                player.currentPosition.coerceAtLeast(0L),
            )
        }
        val plan = loadRestorePlan() ?: return null
        if (isForPlayback) awaitRoutingState()
        return if (isForPlayback) adoptRestored(plan) else restoredItems(plan)
    }

    /**
     * Each song's stream is chosen when it's queued ([routeFor]), from the servers and the
     * downloaded set in memory, which fill asynchronously after a start. A restored queue waits
     * for them (briefly), or a downloaded song from continuo would stream, or a continuo song play
     * as an ordinary stream.
     */
    private suspend fun awaitRoutingState() {
        withTimeoutOrNull(ROUTING_STATE_WAIT_MS) {
            downloadedIds = downloadRepository.downloadedTrackIds().first()
            serverLookup.servers.first { it.isNotEmpty() }
        }
    }

    // ---- session plumbing ----

    private fun publishExtras() {
        val decision = engine.decision.value
        val extras = Bundle().apply {
            putString(SessionExtras.QUEUE_TITLE, queueTitle)
            putString(SessionExtras.PLAY_CONTEXT, PlayContextCodec.encode(playContext))
            putLong(SessionExtras.SLEEP_TIMER_ENDS_AT, sleepTimer.endsAtEpochMs.value ?: -1L)
            putString(SessionExtras.RESUMED_TRACK_ID, resumed?.first)
            putLong(SessionExtras.RESUMED_AT_MS, resumed?.second ?: -1L)
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

        override fun extendSleepTimer() {
            sleepTimer.extend(SLEEP_EXTEND_MS)
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

        override suspend fun restoreQueue(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? =
            resumptionQueue(isForPlayback)

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
            // While the sleep timer runs, ten more minutes in one tap, from the notification or the lock screen.
            val extend = sleepTimer.endsAtEpochMs.value?.let {
                CommandButton.Builder(CommandButton.ICON_PLUS_CIRCLE_UNFILLED)
                    .setDisplayName(getString(R.string.action_sleep_extend))
                    .setSessionCommand(CustomCommands.of(CustomCommands.EXTEND_SLEEP_TIMER))
                    .build()
            }
            // A notification cannot host the 1–5 picker, so Ratings mode leaves the heart out.
            val base = if (feedbackRepository.feedback.value.usesRatings) listOf(shuffle, repeat) else listOf(favorite, shuffle, repeat)
            return listOfNotNull(extend) + base
        }
    }

    // ---- audio effects ----

    /**
     * The built-in equaliser plays the new curve, and the phone's own effects hear of the session only
     * while they are the choice: switching to the built-in one tells them to let go, so the two never
     * both shape the sound.
     */
    private fun applyEqualizer(oldMode: EqualizerMode, new: EqualizerSettings) {
        equalizer.apply(new)
        val wasSystem = oldMode == EqualizerMode.SYSTEM
        val isSystem = new.mode == EqualizerMode.SYSTEM
        if (isSystem && !wasSystem) openAudioEffectSession()
        if (wasSystem && !isSystem) closeAudioEffectSession()
    }

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
        /** A Play now song ahead of its download waits up to 3 minutes for it, every 3 s. */
        private const val PLAY_NOW_WAITS = 60
        private const val PLAY_NOW_WAIT_MS = 3_000L
        /** How long a restored queue waits for the servers and the downloaded set ([awaitRoutingState]). */
        private const val ROUTING_STATE_WAIT_MS = 2_000L
        /** A long song resumes only when chosen from its start, not when a place was asked for. */
        private const val RESUME_ONLY_FROM_MS = 5_000L
        /** How far ahead of the queue continuo hears the Play now order. */
        private const val PLAY_NOW_LOOKAHEAD = 50
        /** Adaptive songs continuo encodes ahead of the player (continuo's AHEAD). */
        private const val UPCOMING_AHEAD = 4
        /** The ready song moves up this close to the end of the current one, from the next 10. */
        private const val KEEP_GOING_AHEAD_MS = 15_000L
        private const val KEEP_GOING_LOOKAHEAD = 10
        private const val SLOW_RETRY_ATTEMPTS = 12
        private const val SLOW_RETRY_INTERVAL_MS = 15_000L
        const val SESSION_ID = "polyphony"
        const val NOTIFICATION_CHANNEL_ID = "polyphony_playback"
        private const val PRELOAD_DURATION_US = 10_000_000L
        private const val SLEEP_EXTEND_MS = 10 * 60_000L
        /** How long a scrub in a live transcode waits for the server to finish it. */
        private const val SEEK_LENGTH_WAIT_MS = 8_000L
    }
}

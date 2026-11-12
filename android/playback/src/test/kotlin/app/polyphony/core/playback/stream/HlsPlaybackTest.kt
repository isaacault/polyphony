package app.polyphony.core.playback.stream

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.transcode.AdaptiveStreaming
import app.polyphony.core.playback.queue.MediaItemFactory
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * continuo's adaptive streams through Media3's real HLS player (#185), as PlaybackService wires
 * them ([HlsDataSources], the track selector's cap, fetching ahead), against a server answering
 * as continuo does: its playlists (continuo/stream/playlists.py) and a real song's segments
 * (continuo's tests/fixtures/hls: 441037 samples at 44.1 kHz, 6 s + 4.007354 s, the init segment
 * with the trims Media3 1.11.1 reads right). What a phone can't show without logs: which rendition
 * each request asked for, and with which token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Conscrypt has no linux-aarch64 build (the CI host is one), and plain HTTP needs no TLS provider.
@ConscryptMode(ConscryptMode.Mode.OFF)
class HlsPlaybackTest {
    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val meter = SettableBandwidthMeter(10_000_000)

    /** The token continuo takes; requests with another get a 401, as an expired one does. */
    @Volatile private var validToken = "t1"
    /** Called when a segment is asked for (path), before it's answered. */
    @Volatile private var onSegment: (String) -> Unit = {}
    private val tokenFetches = AtomicInteger()
    private val tokenRenewals = AtomicInteger()

    private lateinit var continuo: Server
    private lateinit var cache: SimpleCache

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
        continuo = Server(
            id = "srv", type = ServerType.SUBSONIC, name = "continuo", baseUrl = server.url("/").toString(),
            username = "isaac", credential = "", deviceId = "test",
        )
        @Suppress("DEPRECATION") // the index-file constructor: no SQLite (Robolectric has none for aarch64)
        cache = SimpleCache(folder.newFolder("cache"), NoOpCacheEvictor())
    }

    @After fun tearDown() {
        cache.release()
        server.close()
    }

    @Test fun `plays the top rung within the cap, with the token, and gapless trims`() {
        val played = play(capKbps = 256)

        val paths = requests.map { it.url.encodedPath }
        assertEquals(
            listOf(
                // Media3 reads the first-listed rendition's playlist to prepare (continuo serves it
                // from the song's plan, without encoding that rendition), then plays the one it picks.
                "/v1/stream/tr1/master.m3u8", "/v1/stream/tr1/32/index.m3u8", "/v1/stream/tr1/256/index.m3u8",
                "/v1/stream/tr1/256/init.mp4", "/v1/stream/tr1/256/0.m4s", "/v1/stream/tr1/256/1.m4s",
            ),
            paths,
        )
        assertTrue(requests.all { it.headers["Authorization"] == "Bearer t1" })
        // continuo's trims reach the audio renderer: Opus's 312-sample delay and the last packet's padding.
        val audio = played.audio.last()
        assertEquals("audio/opus", audio.sampleMimeType)
        assertEquals(312, audio.encoderDelay)
        assertEquals(607, audio.encoderPadding)
        // The rung playing reads back from the downstream format (PlaybackService.adaptiveRungs).
        assertEquals(listOf(256), played.rungs)
    }

    @Test fun `an album plays on, the next song fetched before the first ends, trims on both`() {
        val played = play(capKbps = 256, songs = listOf("tr1", "tr2"))

        val transition = checkNotNull(played.requestsAtTransition) { "never moved on to the second song" }
        val firstOfSecond = requests.indexOfFirst { it.url.encodedPath.startsWith("/v1/stream/tr2/") }
        assertTrue("the second song was asked for", firstOfSecond >= 0)
        // Gapless needs the next song ready at the join: it's loaded while the first still plays.
        assertTrue("tr2's first request ($firstOfSecond) came before the move ($transition)", firstOfSecond < transition)
        assertTrue(requests.any { it.url.encodedPath == "/v1/stream/tr2/256/1.m4s" })
        // Every song reached the renderer with continuo's trims.
        assertTrue(played.audio.isNotEmpty())
        assertTrue(played.audio.all { it.encoderDelay == 312 && it.encoderPadding == 607 })
        println("states: ${played.states}")
    }

    @Test fun `the rules' cap keeps every segment at or below its rung`() {
        play(capKbps = 64)

        val segments = segmentRungs()
        assertEquals(listOf(64, 64), segments)
    }

    @Test fun `the stalling cap plays the lowest rung`() {
        play(capKbps = AdaptiveStreaming.LADDER.first())

        assertEquals(listOf(32, 32), segmentRungs())
    }

    @Test fun `quality drops mid-song when the measured throughput does`() {
        onSegment = { path -> if (path.endsWith("/0.m4s")) meter.estimate = 30_000 }

        val played = play(capKbps = 256)

        val segments = segmentRungs()
        assertEquals(256, segments.first())
        assertEquals(32, segments.last())
        assertEquals(listOf(256, 32), played.rungs)
    }

    @Test fun `an expired token is renewed once and the song plays on`() {
        validToken = "t2"   // continuo no longer takes t1

        play(capKbps = 256)

        assertEquals(1, tokenRenewals.get())
        val accepted = requests.filter { it.headers["Authorization"] == "Bearer t2" }.map { it.url.encodedPath }
        assertTrue(accepted.last().endsWith("/1.m4s"))
        assertEquals(1, requests.count { it.headers["Authorization"] == "Bearer t1" })
    }

    @Test fun `a song fetched ahead at the low rung plays with continuo gone`() {
        val sources = sources()
        val fetchFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(sources.network)
        val base = sources.streams.masterUrl(continuo, "tr1").removeSuffix("master.m3u8")
        val written = runBlocking { QueuePrefetcher.fetchHls(fetchFactory, QueuePrefetcher.Hls(base, 32)) {} }
        assertEquals(917L + 65686 + 47179, written)
        server.close()   // the road trip: no signal at all
        requests.clear()

        play(capKbps = 32, sources = sources)

        assertTrue(requests.isEmpty())
    }

    @Test fun `offline, a song fetched ahead at another level plays at that level`() {
        // Fetched at 64 while the connection was fine; then no network, and the player, going by
        // the last throughput it saw, would pick 256.
        val sources = sources()
        val fetchFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(sources.network)
        val base = sources.streams.masterUrl(continuo, "tr1").removeSuffix("master.m3u8")
        runBlocking { QueuePrefetcher.fetchHls(fetchFactory, QueuePrefetcher.Hls(base, 64)) {} }
        server.close()

        val played = play(capKbps = 256, sources = sources, online = false, loadErrorsExpected = true)

        assertEquals("played what the cache holds", listOf(64), played.rungs)
        assertTrue(played.audio.all { it.encoderDelay == 312 && it.encoderPadding == 607 })
    }

    // ---- helpers ----

    private fun sources() = HlsDataSources(
        cached = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        network = OkHttpDataSource.Factory(OkHttpClient()),
        serverFor = { id -> continuo.takeIf { it.id == id } },
        streams = ContinuoStreams { _, refresh ->
            tokenFetches.incrementAndGet()
            if (refresh) tokenRenewals.incrementAndGet()
            mapOf("Authorization" to "Bearer " + if (refresh) "t2" else "t1")
        },
    )

    /**
     * What a song's playing showed: the formats the audio renderer got (the trims), and the rungs
     * played in turn, read from the downstream formats as PlaybackService reads them.
     */
    private class Played(
        val audio: List<Format>,
        val rungs: List<Int?>,
        /** Every playback state the player went through, in order. */
        val states: List<Int>,
        /** How many requests had been made when the player moved on to the next song. */
        val requestsAtTransition: Int?,
    )

    /** Plays [songs] (tr1 alone by default) to the end as adaptive streams capped at [capKbps]. */
    private fun play(capKbps: Int, sources: HlsDataSources = sources(), songs: List<String> = listOf("tr1"), online: Boolean = true, loadErrorsExpected: Boolean = false): Played {
        val selector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().forAdaptiveStreams(capKbps))
        }
        val player: ExoPlayer = TestExoPlayerBuilder(context)
            .setMediaSourceFactory(sources.mediaSourceFactory(online = { online }))
            .setTrackSelector(selector)
            .setBandwidthMeter(meter)
            .build()
        val formats = CopyOnWriteArrayList<Format>()
        val downstream = CopyOnWriteArrayList<Format>()
        val states = CopyOnWriteArrayList<Int>()
        var requestsAtTransition: Int? = null
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                states += state
            }

            override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) requestsAtTransition = requests.size
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                formats += format
            }

            override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
                mediaLoadData.trackFormat?.let { downstream += it }
            }
        })
        try {
            player.setMediaItems(songs.map { MediaItem.Builder().setUri(MediaItemFactory.uriFor("srv", it)).setMediaId(it).build() })
            player.prepare()
            player.play()
            val run = TestPlayerRunHelper.advance(player)
            // Offline, the renditions that can't connect are set aside through non-fatal load errors.
            (if (loadErrorsExpected) run.ignoringNonFatalErrors() else run).untilState(Player.STATE_ENDED)
        } finally {
            player.release()
        }
        return Played(formats, downstream.map { AdaptiveStreaming.rungForBandwidth(it.bitrate) }.distinct(), states, requestsAtTransition)
    }

    /** The rung each media segment was fetched at, in order. */
    private fun segmentRungs(): List<Int> = requests.map { it.url.encodedPath }
        .filter { it.endsWith(".m4s") }
        .map { it.split('/').dropLast(1).last().toInt() }

    private fun answer(request: RecordedRequest): MockResponse {
        requests += request
        if (request.headers["Authorization"] != "Bearer $validToken") return MockResponse.Builder().code(401).build()
        // Any song answers with the same fixture song: /v1/stream/<song>/<path>.
        val path = request.url.encodedPath.removePrefix("/v1/stream/").substringAfter('/')
        val parts = path.split('/')
        return when {
            path == "master.m3u8" -> text(MASTER)
            parts.size == 2 && parts[0].toInt() in AdaptiveStreaming.LADDER -> when (val name = parts[1]) {
                "index.m3u8" -> text(MEDIA)
                "init.mp4" -> bytes("init.mp4")
                "0.m4s", "1.m4s" -> {
                    onSegment(request.url.encodedPath)
                    bytes(name)
                }
                else -> MockResponse.Builder().code(404).build()
            }
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun text(body: String) = MockResponse.Builder()
        .code(200).addHeader("Content-Type", "application/vnd.apple.mpegurl").body(body).build()

    private fun bytes(name: String): MockResponse {
        val file = if (name == "init.mp4") "media3_verified_init.mp4" else name
        val data = checkNotNull(javaClass.getResourceAsStream("/hls/$file")).use { it.readBytes() }
        return MockResponse.Builder().code(200).body(Buffer().write(data)).build()
    }

    /** A bandwidth estimate the test sets; the player's own measurements don't move it. */
    private class SettableBandwidthMeter(@Volatile var estimate: Long) : BandwidthMeter {
        override fun getBitrateEstimate(): Long = estimate
        override fun getTransferListener(): TransferListener? = null
        override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) {}
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {}
    }

    private companion object {
        // What continuo/stream/playlists.py writes for this song: master(LADDER) and media([6.0, 4.007354]).
        val MASTER = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            for (k in AdaptiveStreaming.LADDER) {
                append("#EXT-X-STREAM-INF:BANDWIDTH=${(k * 1000 * 1.06 * 1.25).toInt()},")
                append("AVERAGE-BANDWIDTH=${(k * 1000 * 1.06).toInt()},CODECS=\"opus\"\n$k/index.m3u8\n")
            }
        }
        const val MEDIA = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n" +
            "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
            "#EXTINF:6.000000,\n0.m4s\n#EXTINF:4.007354,\n1.m4s\n#EXT-X-ENDLIST\n"
    }
}

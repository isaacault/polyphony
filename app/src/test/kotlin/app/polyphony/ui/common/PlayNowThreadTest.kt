package app.polyphony.ui.common

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.ContinuoAccount
import app.polyphony.core.domain.model.PlayNowStream
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * alpha.32's Play now never played: PlayNow ran on the application scope (Dispatchers.Default)
 * and called the player from there, which Media3's MediaController refuses. The player calls
 * have to land on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class PlayNowThreadTest {

    private val main = newSingleThreadContext("fake-main")
    private val server = Server("s", ServerType.SUBSONIC, "Continuo", "https://music-api.example", "isaac", "", deviceId = "d",
                                options = mapOf(ServerOptions.CONTINUO to "true"))
    private val playerThreads = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    /** An interface whose listed calls answer as given; anything else isn't expected. */
    private inline fun <reified T> fake(crossinline answer: (String, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name, args)
        } as T

    private fun playNow(): PlayNow {
        val servers = fake<ServerRepository> { name, _ ->
            when (name) {
                "getActive" -> server
                "getActiveServer" -> flowOf(server)
                else -> error("unexpected $name")
            }
        }
        val requests = fake<RequestRepository> { name, _ ->
            when (name) {
                "supports" -> true
                "account" -> ContinuoAccount("isaac", "user", setOf("play_now"))
                "startPlayNow" -> PlayNowStream("st", "ready", true, 100.0, "Espresso", "Sabrina Carpenter", 175_453, "flac", null)
                else -> error("unexpected $name")
            }
        }
        val playback = fake<PlaybackController> { name, _ ->
            playerThreads += "$name@${Thread.currentThread().name.substringBefore(" @")}"   // without debug mode's " @coroutine#n"
            Unit
        }
        return PlayNow(servers, requests, playback, CoroutineScope(Dispatchers.Default))
    }

    private val espresso = CatalogTrack(
        key = "mb:rec:rec-esp", title = "Espresso", artist = "Sabrina Carpenter", durationMs = 175_453,
        albumTitle = "Short n’ Sweet", albumReleaseGroupId = "rg-sns", availability = Availability.Requestable, recordingId = "rec-esp",
    )

    @Test
    fun `a song played now reaches the player on the main thread`() = runBlocking {
        val p = playNow()
        val outcome = async { withTimeout(5_000) { p.events.first { it !is PlayNowEvent.Finding } } }
        yield()
        p.play(espresso)
        assertTrue("it played: ${outcome.await()}", outcome.await() is PlayNowEvent.Playing)
        assertEquals(listOf("playTracks@fake-main"), playerThreads)
    }

    @Test
    fun `a song queued reaches the player on the main thread`() = runBlocking {
        val p = playNow()
        val outcome = async { withTimeout(5_000) { p.events.first() } }
        yield()
        p.queue(espresso, next = true)
        assertTrue("it was queued: ${outcome.await()}", outcome.await() is PlayNowEvent.Queued)
        assertEquals(listOf("playNext@fake-main"), playerThreads)
    }
}

package app.polyphony.ui.common

import android.util.Log
import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Radio from any song or artist (polyphony#173): an endless queue of similar music. On a continuo
 * server the similar artists are ListenBrainz's (from continuo's catalogue): their songs in the
 * library first, then a few Play now songs a batch, fetched in turn as the queue reaches them.
 * Elsewhere, the server's own similar artists and songs. Another batch goes on the end whenever
 * fewer than [LOW_WATER] songs are left; playing anything else ends the radio.
 */
@Singleton
class Radio @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    private val playNow: PlayNow,
    @ApplicationScope private val scope: CoroutineScope,
) {
    sealed interface Event {
        data class Finding(val seed: String) : Event
        data class Started(val title: String) : Event
        data class NothingSimilar(val seed: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private class Station(
        val title: String,
        val server: Server,
        val canFetch: Boolean,
        val artists: MutableList<RadioMix.Artist>,
        /** Artists (by catalogue id) whose own similar artists have been added. */
        val explored: MutableSet<String>,
        val mbids: MutableMap<String, String>,
        val played: MutableSet<String>,
        var nextArtist: Int = 0,
        /** A song to find more like it (no catalogue): the last one queued. */
        var lastTrackId: String? = null,
    )

    private var station: Station? = null
    private var starting: Job? = null
    private var watching: Job? = null
    private var refilling = false

    fun fromTrack(track: Track) = start(seedName = track.artistName ?: track.displayArtist, seedArtistId = track.artistId, seedTrack = track)

    fun fromArtist(artist: Artist) = start(seedName = artist.name, seedArtistId = artist.id, seedTrack = null)

    private fun start(seedName: String, seedArtistId: String?, seedTrack: Track?) {
        starting?.cancel()
        starting = scope.launch {
            try {
                val server = servers.getActive() ?: return@launch
                _events.emit(Event.Finding(seedName))
                val built = build(server, seedName, seedArtistId, seedTrack)
                val first = nextBatch(built, size = FIRST_BATCH)
                val queue = listOfNotNull(seedTrack) + first.filter { it.id != seedTrack?.id }
                if (queue.size <= 1) {
                    _events.emit(Event.NothingSimilar(seedName))
                    if (queue.isEmpty()) return@launch
                }
                station = built
                withContext(Dispatchers.Main) { playback.playTracks(queue, 0, queueTitle = built.title) }
                _events.emit(Event.Started(built.title))
                watch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Radio from \"$seedName\" failed", e)
                _events.emit(Event.NothingSimilar(seedName))
            }
        }
    }

    /** The seed and its similar artists, with their songs. */
    private suspend fun build(server: Server, seedName: String, seedArtistId: String?, seedTrack: Track?): Station {
        val continuo = requests.supports(server)
        val canFetch = continuo && runCatching { requests.account(server).canPlayNow }.getOrDefault(false)
        val names = mutableListOf(seedName)
        val mbids = mutableMapOf<String, String>()
        val explored = mutableSetOf<String>()
        if (continuo) {
            runCatching {
                val found = requests.findArtist(server, seedName)
                found?.musicBrainzId?.let { mbid ->
                    mbids[seedName] = mbid
                    explored += mbid
                    requests.catalogArtist(server, mbid)?.similarArtists?.take(SIMILAR_ARTISTS)?.forEach { a ->
                        if (names.none { it.equals(a.name, ignoreCase = true) }) {
                            names += a.name
                            a.musicBrainzId?.let { mbids[a.name] = it }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "No similar artists from continuo for \"$seedName\": ${it.message}") }
        }
        if (names.size == 1 && seedArtistId != null) {
            runCatching { library.getSimilarArtists(seedArtistId) }.getOrDefault(emptyList()).take(SIMILAR_ARTISTS).forEach { a ->
                if (names.none { it.equals(a.name, ignoreCase = true) }) names += a.name
            }
        }
        val artists = load(server, names, mbids, canFetch, seedArtistId to seedName)
        val title = "$seedName radio"
        return Station(title, server, canFetch, artists.toMutableList(), explored, mbids,
            played = listOfNotNull(seedTrack?.id, seedTrack?.let { RadioMix.sameSong(it.title, seedName) }).toMutableSet(),
            lastTrackId = seedTrack?.id)
    }

    /** Each artist's songs: the library's (shuffled), and the catalogue's top songs not in it. */
    private suspend fun load(
        server: Server,
        names: List<String>,
        mbids: Map<String, String>,
        canFetch: Boolean,
        seedId: Pair<String?, String>,
    ): List<RadioMix.Artist> = coroutineScope {
        names.map { name ->
            async {
                val libraryId = if (seedId.first != null && name == seedId.second) seedId.first else libraryArtistId(name)
                val have = libraryId?.let { id -> runCatching { library.tracksForArtist(id).first() }.getOrDefault(emptyList()) }
                    .orEmpty().shuffled(random)
                val fetch = if (canFetch) {
                    mbids[name]?.let { mbid -> runCatching { requests.catalogArtist(server, mbid)?.topTracks }.getOrNull() }.orEmpty()
                        .filter { it.availability !is Availability.InLibrary && it.availability !is Availability.Partial }
                } else {
                    emptyList()
                }
                RadioMix.Artist(name, have, fetch)
            }
        }.awaitAll()
    }

    private suspend fun libraryArtistId(name: String): String? =
        runCatching { library.searchLocal(name, limit = 5).first().artists }.getOrDefault(emptyList())
            .firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

    /** The next songs for [station], as tracks the player can queue; Play now ones are started here. */
    private suspend fun nextBatch(station: Station, size: Int = BATCH): List<Track> {
        var batch = RadioMix.batch(station.artists, station.played, size, MAX_FETCH, station.nextArtist, station.canFetch)
        if (batch.picks.isEmpty()) {
            if (explore(station)) batch = RadioMix.batch(station.artists, station.played, size, MAX_FETCH, station.nextArtist, station.canFetch)
        }
        station.nextArtist = batch.nextArtist
        val tracks = batch.picks.mapNotNull { pick ->
            station.played += pick.key
            when (pick) {
                is RadioMix.Pick.Have -> {
                    station.played += RadioMix.sameSong(pick.track.title, pick.artist)
                    pick.track
                }
                is RadioMix.Pick.Fetch -> {
                    station.played += RadioMix.sameSong(pick.track.title, pick.artist)
                    playNow.queued(station.server, pick.track)
                }
            }
        }.toMutableList()
        // Nothing left among these artists, and no catalogue to find more: the server's songs like the last one.
        if (tracks.isEmpty()) {
            station.lastTrackId?.let { id ->
                runCatching { library.getSimilarTracks(id, size * 2) }.getOrDefault(emptyList())
                    .filter { it.id !in station.played }
                    .take(size)
                    .forEach {
                        station.played += it.id
                        tracks += it
                    }
            }
        }
        tracks.lastOrNull { PlayNowIds.streamId(it.id) == null }?.let { station.lastTrackId = it.id }
        return tracks
    }

    /** More artists: the similar artists of one not looked at yet. False when there are none left. */
    private suspend fun explore(station: Station): Boolean {
        if (station.artists.size >= MAX_ARTISTS) return false
        val next = station.artists.firstOrNull { a -> station.mbids[a.name]?.let { it !in station.explored } == true } ?: return false
        val mbid = station.mbids.getValue(next.name)
        station.explored += mbid
        val similar = runCatching { requests.catalogArtist(station.server, mbid)?.similarArtists }.getOrNull().orEmpty()
            .filter { s -> station.artists.none { it.name.equals(s.name, ignoreCase = true) } }
            .take(SIMILAR_ARTISTS)
        if (similar.isEmpty()) return explore(station)
        similar.forEach { s -> s.musicBrainzId?.let { station.mbids[s.name] = it } }
        station.artists += load(station.server, similar.map { it.name }, station.mbids, station.canFetch, null to "")
        return true
    }

    /** Keeps the radio going while its queue plays; anything else played ends it. */
    private fun watch() {
        watching?.cancel()
        watching = scope.launch {
            playback.state.collect { state ->
                val current = station ?: return@collect
                if (state.queueTitle != current.title || state.queue.isEmpty()) {
                    station = null
                    watching?.cancel()
                    return@collect
                }
                val left = state.queue.size - state.currentIndex - 1
                if (left >= LOW_WATER || refilling) return@collect
                refilling = true
                scope.launch {
                    try {
                        val more = nextBatch(current)
                        if (more.isNotEmpty() && station === current) {
                            withContext(Dispatchers.Main) { playback.insertIntoQueue(playback.state.value.queue.size, more) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Radio couldn't add more: ${e.message}")
                    } finally {
                        refilling = false
                    }
                }
            }
        }
    }

    private val random = Random(System.currentTimeMillis())

    private companion object {
        const val TAG = "PolyphonyRadio"
        const val FIRST_BATCH = 12
        const val BATCH = 10
        /** Songs Play now fetches per batch, at most. */
        const val MAX_FETCH = 3
        const val LOW_WATER = 3
        const val SIMILAR_ARTISTS = 15
        const val MAX_ARTISTS = 60
    }
}

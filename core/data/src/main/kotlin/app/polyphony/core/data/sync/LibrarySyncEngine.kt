package app.polyphony.core.data.sync

import app.polyphony.core.data.util.attempt
import app.polyphony.core.domain.model.AlbumListType
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.ProviderException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.NumberFormat

/**
 * Pulls a server's library into a [SyncSink], page by page, with progress. Pure Kotlin: no Android,
 * no Room, so it is covered by JVM tests with fakes.
 *
 * Full sync: ping → artists → albums (paged) → tracks (paged, or album walk when the provider cannot
 * list tracks) → playlists → starred → genres → delete stale rows.
 * Incremental sync: artists → recently added albums (new/changed ones get their tracks) →
 * playlists → starred → genres.
 */
class LibrarySyncEngine(
    private val pageSize: Int = 500,
    private val concurrency: Int = 4,
    private val recentAlbumWindow: Int = 200,
) {
    data class Summary(
        val full: Boolean,
        val artists: Int,
        val albums: Int,
        val tracks: Int,
        val playlists: Int,
        val usedAlbumWalk: Boolean,
    )

    fun interface ProgressListener {
        fun onProgress(phase: String, progress: Float?)
    }

    suspend fun run(
        provider: MediaProvider,
        sink: SyncSink,
        generation: Long,
        full: Boolean,
        listener: ProgressListener,
    ): Summary {
        val serverId = provider.server.id
        listener.onProgress("Connecting", null)
        provider.ping()
        return if (full) fullSync(provider, sink, serverId, generation, listener)
        else incrementalSync(provider, sink, serverId, generation, listener)
    }

    private suspend fun fullSync(
        provider: MediaProvider,
        sink: SyncSink,
        serverId: String,
        generation: Long,
        listener: ProgressListener,
    ): Summary {
        listener.onProgress("Artists", 0.01f)
        val artists = provider.getArtists()
        sink.writeArtists(serverId, artists, generation)
        val estimatedAlbums = artists.sumOf { it.albumCount }.takeIf { it > 0 }

        // ---- Albums (paged) ----
        val albumTrackCounts = LinkedHashMap<String, Int>()
        var offset = 0
        while (true) {
            val page = provider.getAlbums(AlbumListType.ALPHABETICAL_BY_NAME, size = pageSize, offset = offset)
            if (page.isEmpty()) break
            val fresh = page.filter { it.id !in albumTrackCounts }
            page.forEach { albumTrackCounts[it.id] = it.trackCount }
            if (fresh.isEmpty()) break // server ignored the offset; stop rather than loop forever
            sink.writeAlbums(serverId, fresh, generation)
            offset += page.size
            listener.onProgress(
                countPhase("Albums", albumTrackCounts.size, estimatedAlbums),
                lerp(ALBUMS_START, ALBUMS_END, fraction(albumTrackCounts.size, estimatedAlbums)),
            )
            if (page.size < pageSize) break
        }
        val estimatedTracks = albumTrackCounts.values.sum().takeIf { it > 0 }

        // ---- Tracks (paged, with album-walk fallback) ----
        var trackTotal = 0
        var usedWalk = false
        val seenTracks = HashSet<String>()
        try {
            offset = 0
            while (true) {
                val page = provider.getAllTracks(pageSize, offset)
                if (page.isEmpty()) break
                val fresh = page.filter { seenTracks.add(it.id) }
                if (fresh.isEmpty()) break
                sink.writeTracks(serverId, fresh, generation)
                trackTotal += fresh.size
                offset += page.size
                listener.onProgress(
                    countPhase("Tracks", trackTotal, estimatedTracks),
                    lerp(TRACKS_START, TRACKS_END, fraction(trackTotal, estimatedTracks)),
                )
                if (page.size < pageSize) break
            }
        } catch (e: ProviderException.Unsupported) {
            usedWalk = true
        }
        if (usedWalk) {
            trackTotal = walkAlbums(provider, sink, serverId, albumTrackCounts.keys.toList(), generation, estimatedTracks, listener)
        }

        val playlistCount = syncPlaylists(provider, sink, serverId, generation, listener)
        syncStarredAndGenres(provider, sink, serverId, generation, listener)

        listener.onProgress("Cleaning up", 0.98f)
        sink.deleteStale(serverId, generation)
        listener.onProgress("Done", 1f)
        return Summary(true, artists.size, albumTrackCounts.size, trackTotal, playlistCount, usedWalk)
    }

    private suspend fun incrementalSync(
        provider: MediaProvider,
        sink: SyncSink,
        serverId: String,
        generation: Long,
        listener: ProgressListener,
    ): Summary {
        listener.onProgress("Artists", 0.02f)
        val artists = provider.getArtists()
        sink.writeArtists(serverId, artists, generation)

        listener.onProgress("Recent albums", 0.08f)
        val recent = provider.getAlbums(AlbumListType.RECENTLY_ADDED, size = recentAlbumWindow, offset = 0)
        val known = sink.albumTrackCounts(serverId)
        // A retag (an artist renamed on the server) keeps the track count: compare names too, so
        // the album's tracks are re-read now rather than at the next daily full sync.
        val names = sink.albumNames(serverId)
        val changed = recent.filter { a ->
            known[a.id] != a.trackCount || names[a.id]?.let { (name, artist) -> name != a.name || artist != a.artistName } == true
        }
        sink.writeAlbums(serverId, recent, generation)
        val estimated = changed.sumOf { it.trackCount }.takeIf { it > 0 }
        val tracks = if (changed.isEmpty()) 0 else {
            walkAlbums(provider, sink, serverId, changed.map { it.id }, generation, estimated, listener, TRACKS_START_INCREMENTAL, TRACKS_END)
        }

        val playlistCount = syncPlaylists(provider, sink, serverId, generation, listener)
        syncStarredAndGenres(provider, sink, serverId, generation, listener)
        listener.onProgress("Done", 1f)
        return Summary(false, artists.size, recent.size, tracks, playlistCount, usedAlbumWalk = changed.isNotEmpty())
    }

    private suspend fun walkAlbums(
        provider: MediaProvider,
        sink: SyncSink,
        serverId: String,
        albumIds: List<String>,
        generation: Long,
        estimatedTracks: Int?,
        listener: ProgressListener,
        start: Float = TRACKS_START,
        end: Float = TRACKS_END,
    ): Int = coroutineScope {
        val semaphore = Semaphore(concurrency)
        var written = 0
        var processed = 0
        albumIds.chunked(concurrency * 4).forEach { chunk ->
            val tracks = chunk.map { id ->
                async { semaphore.withPermit { attempt { provider.getAlbum(id).tracks } ?: emptyList() } }
            }.awaitAll().flatten()
            if (tracks.isNotEmpty()) sink.writeTracks(serverId, tracks, generation)
            written += tracks.size
            processed += chunk.size
            listener.onProgress(
                countPhase("Tracks", written, estimatedTracks),
                lerp(start, end, fraction(processed, albumIds.size)),
            )
        }
        written
    }

    private suspend fun syncPlaylists(
        provider: MediaProvider,
        sink: SyncSink,
        serverId: String,
        generation: Long,
        listener: ProgressListener,
    ): Int = coroutineScope {
        listener.onProgress("Playlists", PLAYLISTS_START)
        val playlists = provider.getPlaylists()
        val semaphore = Semaphore(concurrency)
        var done = 0
        playlists.chunked(concurrency * 2).forEach { chunk ->
            val details = chunk.map { playlist ->
                async { semaphore.withPermit { attempt { provider.getPlaylist(playlist.id) } } }
            }.awaitAll()
            details.forEach { detail ->
                if (detail != null) sink.writePlaylist(serverId, detail.playlist, detail.tracks, generation)
            }
            done += chunk.size
            listener.onProgress(
                countPhase("Playlists", done, playlists.size),
                lerp(PLAYLISTS_START, PLAYLISTS_END, fraction(done, playlists.size)),
            )
        }
        sink.deletePlaylistsExcept(serverId, playlists.map { it.id })
        playlists.size
    }

    private suspend fun syncStarredAndGenres(
        provider: MediaProvider,
        sink: SyncSink,
        serverId: String,
        generation: Long,
        listener: ProgressListener,
    ) {
        listener.onProgress("Favourites", PLAYLISTS_END)
        attempt { provider.getStarred() }?.let { sink.applyStarred(serverId, it, generation) }
        listener.onProgress("Genres", 0.96f)
        attempt { provider.getGenres() }?.let { sink.writeGenres(serverId, it, generation) }
    }

    private fun countPhase(name: String, done: Int, total: Int?): String {
        val fmt = NumberFormat.getIntegerInstance()
        return if (total != null && total >= done) "$name ${fmt.format(done)} / ${fmt.format(total)}" else "$name ${fmt.format(done)}"
    }

    private fun fraction(done: Int, total: Int?): Float? =
        if (total == null || total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)

    private fun lerp(start: Float, end: Float, t: Float?): Float? = t?.let { start + (end - start) * it }

    private companion object {
        const val ALBUMS_START = 0.05f
        const val ALBUMS_END = 0.30f
        const val TRACKS_START = 0.30f
        const val TRACKS_START_INCREMENTAL = 0.10f
        const val TRACKS_END = 0.85f
        const val PLAYLISTS_START = 0.86f
        const val PLAYLISTS_END = 0.94f
    }
}

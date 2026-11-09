package app.polyphony.ui.common

import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.Track

/**
 * What a radio plays next (polyphony#173): the seed's and similar artists' songs, taking turns so
 * one artist doesn't play twice in a row, and the library's songs first: only when those run out does
 * it reach for songs Play now fetches, a few a batch, so a radio never sets off a download spree.
 * Pure, so it's tested; [Radio] feeds it and queues what it picks.
 */
object RadioMix {
    sealed interface Pick {
        val key: String
        val artist: String

        data class Have(val track: Track, override val artist: String) : Pick {
            override val key: String get() = track.id
        }

        data class Fetch(val track: CatalogTrack, override val artist: String) : Pick {
            override val key: String get() = "rec:" + (track.recordingId ?: track.key)
        }
    }

    /** One artist on the station: the songs of theirs the library has, and ones to fetch. */
    data class Artist(val name: String, val library: List<Track> = emptyList(), val catalog: List<CatalogTrack> = emptyList())

    data class Batch(val picks: List<Pick>, val nextArtist: Int)

    /**
     * Up to [size] songs, starting with the artist at [fromArtist] and going round; [played] are songs
     * already on the station (by [Pick.key], and the library's by title and artist too, so a song you
     * have isn't fetched as well).
     */
    fun batch(artists: List<Artist>, played: Set<String>, size: Int, maxFetch: Int, fromArtist: Int = 0, canFetch: Boolean = true): Batch {
        if (artists.isEmpty() || size <= 0) return Batch(emptyList(), fromArtist)
        val used = played.toMutableSet()
        val picks = mutableListOf<Pick>()
        var cursor = fromArtist.mod(artists.size)

        fun round(take: (Artist) -> Pick?): Boolean {
            var any = false
            for (step in artists.indices) {
                if (picks.size >= size) break
                val artist = artists[(cursor + step) % artists.size]
                val pick = take(artist) ?: continue
                picks += pick
                used += pick.key
                used += sameSong(pick)
                any = true
            }
            cursor = (cursor + 1) % artists.size
            return any
        }

        // The library's songs, a turn each, until the batch is full or they've all been played.
        while (picks.size < size) {
            val more = round { a ->
                a.library.firstOrNull { it.id !in used && sameSong(it.title, a.name) !in used }?.let { Pick.Have(it, a.name) }
            }
            if (!more) break
        }
        // Then songs to fetch, a few at most.
        var fetched = 0
        if (canFetch) {
            while (picks.size < size && fetched < maxFetch) {
                val more = round { a ->
                    if (fetched >= maxFetch) return@round null
                    a.catalog.firstOrNull { t ->
                        t.recordingId != null && "rec:${t.recordingId}" !in used && sameSong(t.title, a.name) !in used
                    }?.let { fetched++; Pick.Fetch(it, a.name) }
                }
                if (!more) break
            }
        }
        // Spaced out within each kind, so the library's songs still all come first.
        val library = spreadArtists(picks.filterIsInstance<Pick.Have>())
        return Batch(library + spreadArtists(picks.filterIsInstance<Pick.Fetch>(), after = library.lastOrNull()?.artist), cursor)
    }

    /** A song by its title and artist, so one you have and its catalogue copy count as one. */
    fun sameSong(title: String, artist: String): String = "song:" + title.lowercase().trim() + "|" + artist.lowercase().trim()

    private fun sameSong(pick: Pick): String = when (pick) {
        is Pick.Have -> sameSong(pick.track.title, pick.artist)
        is Pick.Fetch -> sameSong(pick.track.title, pick.artist)
    }

    /** No artist twice running where another can go between; [after] is who played just before. */
    fun spreadArtists(picks: List<Pick>, after: String? = null): List<Pick> {
        val left = picks.toMutableList()
        val out = mutableListOf<Pick>()
        while (left.isNotEmpty()) {
            val previous = out.lastOrNull()?.artist ?: after
            val i = left.indexOfFirst { it.artist != previous }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(i)
        }
        return out
    }
}

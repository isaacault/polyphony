package app.polyphony.core.data.offline

/** Minimal projection of a track used to derive which albums/artists/genres have downloads. */
data class OfflineTrackRef(
    val id: String,
    val albumId: String?,
    val artistId: String?,
    val genre: String?,
)

/**
 * Everything that is browsable while offline mode is on: the downloaded tracks and every
 * collection that contains at least one of them.
 */
data class OfflineIndex(
    val trackIds: Set<String>,
    val albumIds: Set<String>,
    val artistIds: Set<String>,
    val playlistIds: Set<String>,
    val genres: Set<String>,
) {
    companion object {
        val EMPTY = OfflineIndex(emptySet(), emptySet(), emptySet(), emptySet(), emptySet())

        fun build(trackIds: Set<String>, refs: Collection<OfflineTrackRef>, playlistIds: Collection<String>): OfflineIndex {
            if (trackIds.isEmpty()) return EMPTY
            val albums = HashSet<String>()
            val artists = HashSet<String>()
            val genres = HashSet<String>()
            for (ref in refs) {
                if (ref.id !in trackIds) continue
                ref.albumId?.let(albums::add)
                ref.artistId?.let(artists::add)
                ref.genre?.takeIf { it.isNotBlank() }?.let(genres::add)
            }
            return OfflineIndex(trackIds, albums, artists, playlistIds.toSet(), genres)
        }
    }
}

/** Pure list filtering for offline mode; `index == null` means offline mode is off. */
object OfflineFilter {
    /** SQLite's default variable limit is 999; stay comfortably below it. */
    const val IN_CHUNK = 900

    fun <T> apply(
        list: List<T>,
        index: OfflineIndex?,
        allowed: (OfflineIndex) -> Set<String>,
        key: (T) -> String,
    ): List<T> {
        if (index == null) return list
        val ids = allowed(index)
        if (ids.isEmpty()) return emptyList()
        return list.filter { key(it) in ids }
    }
}

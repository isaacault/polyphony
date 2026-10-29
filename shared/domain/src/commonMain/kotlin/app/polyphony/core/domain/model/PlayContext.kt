package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

/**
 * The collection a queue was started from: an album, an artist, a playlist, a genre or the
 * favourites list. Home's "Continue listening" shelf shows these (never loose tracks) and resumes
 * each one at the track and position the listener left off.
 */
@Serializable
data class PlayContext(
    val kind: Kind,
    /** Album / artist / playlist id, genre name, or empty for favourites. */
    val id: String,
    val serverId: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
) {
    enum class Kind { ALBUM, ARTIST, PLAYLIST, GENRE, FAVORITES }

    /** Stable identity across servers and kinds. */
    val key: String get() = "$serverId:${kind.name}:$id"

    companion object {
        fun album(album: Album) = PlayContext(
            kind = Kind.ALBUM, id = album.id, serverId = album.serverId,
            title = album.name, subtitle = album.artistName, artworkUrl = album.artworkUrl,
        )

        fun artist(artist: Artist) = PlayContext(
            kind = Kind.ARTIST, id = artist.id, serverId = artist.serverId,
            title = artist.name, artworkUrl = artist.artworkUrl,
        )

        fun playlist(playlist: Playlist) = PlayContext(
            kind = Kind.PLAYLIST, id = playlist.id, serverId = playlist.serverId,
            title = playlist.name, subtitle = playlist.owner, artworkUrl = playlist.artworkUrl,
        )

        fun genre(name: String, serverId: String, artworkUrl: String? = null) = PlayContext(
            kind = Kind.GENRE, id = name, serverId = serverId, title = name, artworkUrl = artworkUrl,
        )

        fun favorites(serverId: String, title: String = "Favourites", artworkUrl: String? = null) = PlayContext(
            kind = Kind.FAVORITES, id = "", serverId = serverId, title = title, artworkUrl = artworkUrl,
        )
    }
}

/** A [PlayContext] with the listener's last known place in it. */
data class RecentContext(
    val context: PlayContext,
    /** Last track that was playing; `null` once the context was played to the end. */
    val trackId: String? = null,
    val trackTitle: String? = null,
    val trackIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val updatedAt: Long = 0L,
)

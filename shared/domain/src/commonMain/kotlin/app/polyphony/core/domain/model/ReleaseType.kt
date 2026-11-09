package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

/**
 * What kind of release an album is, as MusicBrainz types it: an album, an EP or a single. Only
 * the primary type decides; secondary ones (compilation, live, remix, soundtrack…) don't make a
 * single an album or the other way round. In page order: artist pages group by it.
 */
@Serializable
enum class ReleaseType {
    ALBUM, EP, SINGLE, OTHER;

    companion object {
        /**
         * The type from a server's release types (OpenSubsonic `releaseTypes`, from the files'
         * MusicBrainz tags, e.g. `["Album", "Compilation"]`), or null when none of them is a
         * primary type (untagged, or only secondary ones): such an album shows as an album.
         * contract/cases/release-type.json holds the cases both apps test.
         */
        fun of(types: List<String>?): ReleaseType? = types.orEmpty().firstNotNullOfOrNull { primary(it) }

        private fun primary(type: String): ReleaseType? = when (type.trim().lowercase()) {
            "album" -> ALBUM
            "ep" -> EP
            "single" -> SINGLE
            "broadcast", "other" -> OTHER
            else -> null
        }
    }
}

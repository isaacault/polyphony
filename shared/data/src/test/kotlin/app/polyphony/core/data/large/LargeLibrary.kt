package app.polyphony.core.data.large

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.ReleaseType
import app.polyphony.core.domain.model.StarredContent
import app.polyphony.core.domain.model.Track
import kotlin.math.pow
import kotlin.random.Random

/**
 * A synthetic library the size of a big personal collection (issue #9): by default 2,000 artists,
 * 5,000 albums and 60,000 tracks, generated deterministically from [seed] in well under a second.
 *
 * The shape is what the hot queries care about, not the audio: a long tail of artists (a few with
 * dozens of albums, most with one or two), Zipf-distributed genres with a couple of case variants
 * ("rock" next to "Rock"), "The …" names that the A–Z sorts must file under their second word,
 * accented and non-Latin names, singles and EPs among the albums, compilations, "feat." credits,
 * play counts where most tracks were never played, a few per cent starred and rated, and dates
 * spread over eight years.
 */
class LargeLibrary(
    val serverId: String = "big",
    artistCount: Int = 2_000,
    albumCount: Int = 5_000,
    trackCount: Int = 60_000,
    playlistCount: Int = 40,
    seed: Long = 9L,
) {
    val artists: List<Artist>
    val albums: List<Album>
    val tracks: List<Track>
    val genres: List<Genre>
    val playlists: List<Pair<Playlist, List<Track>>>

    val starred: StarredContent
        get() = StarredContent(
            artists = artists.filter { it.starred },
            albums = albums.filter { it.starred },
            tracks = tracks.filter { it.starred },
        )

    init {
        require(albumCount >= artistCount && trackCount >= albumCount)
        val random = Random(seed)

        // ---- artists, with a long tail of album counts ----
        val artistNames = HashSet<String>()
        val artistList = ArrayList<Artist>(artistCount)
        for (i in 0 until artistCount) {
            var name = artistName(random)
            while (!artistNames.add(name)) name = "$name ${random.nextInt(2, 99)}"
            artistList += Artist(
                id = "ar$i",
                serverId = serverId,
                name = name,
                artworkId = "ar-$i",
                starred = random.nextInt(100) < 3,
                rating = if (random.nextInt(100) < 4) random.nextInt(1, 6) else 0,
            )
        }
        // Everyone has one album; the rest go to artists by a Zipf weight (rank^-0.9).
        val albumsPerArtist = IntArray(artistCount) { 1 }
        val weights = DoubleArray(artistCount) { 1.0 / (it + 1.0).pow(0.9) }
        val shuffledRanks = (0 until artistCount).shuffled(random)
        repeat(albumCount - artistCount) {
            albumsPerArtist[shuffledRanks[pick(weights, random)]]++
        }

        // ---- albums ----
        val albumList = ArrayList<Album>(albumCount)
        val albumTypes = ArrayList<ReleaseType?>(albumCount)
        val albumSizes = IntArray(albumCount)
        val albumArtist = IntArray(albumCount)
        val now = BASE_TIME
        var albumIndex = 0
        for (artistIndex in 0 until artistCount) {
            repeat(albumsPerArtist[artistIndex]) {
                val roll = random.nextInt(100)
                val type = when {
                    roll < 14 -> ReleaseType.SINGLE
                    roll < 24 -> ReleaseType.EP
                    roll < 90 -> ReleaseType.ALBUM
                    else -> null // untagged
                }
                val compilation = type != ReleaseType.SINGLE && random.nextInt(100) < 4
                val artist = artistList[artistIndex]
                val createdAt = now - (random.nextDouble().pow(1.6) * EIGHT_YEARS_MS).toLong()
                val played = random.nextInt(100) < 45
                albumTypes += type
                albumArtist[albumIndex] = artistIndex
                albumSizes[albumIndex] = when (type) {
                    ReleaseType.SINGLE -> random.nextInt(1, 4)
                    ReleaseType.EP -> random.nextInt(4, 7)
                    else -> random.nextInt(8, 19)
                }
                albumList += Album(
                    id = "al$albumIndex",
                    serverId = serverId,
                    name = albumName(random, type),
                    artistId = if (compilation) null else artist.id,
                    artistName = if (compilation) "Various Artists" else artist.name,
                    year = if (random.nextInt(100) < 3) null else 2025 - (random.nextDouble().pow(1.8) * 65).toInt(),
                    genre = if (random.nextInt(100) < 3) null else genreName(random),
                    artworkId = "al-$albumIndex",
                    starred = random.nextInt(100) < 3,
                    rating = if (random.nextInt(100) < 5) random.nextInt(1, 6) else 0,
                    playCount = if (played) zipfCount(random) else 0,
                    createdAt = createdAt,
                    lastPlayedAt = if (played) now - random.nextLong(EIGHT_YEARS_MS / 4) else null,
                    isCompilation = compilation,
                    releaseType = type,
                )
                albumIndex++
            }
        }
        // Scale the album sizes so the tracks add up to exactly trackCount.
        var total = albumSizes.sum()
        while (total != trackCount) {
            val i = random.nextInt(albumCount)
            if (albumTypes[i] == ReleaseType.SINGLE) continue
            if (total < trackCount) { albumSizes[i]++; total++ } else if (albumSizes[i] > 1) { albumSizes[i]--; total-- }
        }

        // ---- tracks ----
        val trackList = ArrayList<Track>(trackCount)
        var trackIndex = 0
        val withSizes = ArrayList<Album>(albumCount)
        albumList.forEachIndexed { i, album ->
            var duration = 0L
            for (n in 1..albumSizes[i]) {
                val performer = artistList[if (album.isCompilation) random.nextInt(artistCount) else albumArtist[i]]
                val featuring = random.nextInt(100) < 7
                val artistName = if (featuring) "${performer.name} feat. ${artistList[random.nextInt(artistCount)].name}" else performer.name
                val played = random.nextInt(100) < 35
                val ms = random.nextLong(90_000, 600_000)
                duration += ms
                val discs = if (albumSizes[i] > 14 && random.nextInt(100) < 20) 2 else 1
                val genre = album.genre?.let { if (random.nextInt(100) < 2) it.lowercase() else it }
                trackList += Track(
                    id = "t$trackIndex",
                    serverId = serverId,
                    title = trackTitle(random),
                    albumId = album.id,
                    albumName = album.name,
                    artistId = performer.id,
                    artistName = artistName,
                    albumArtistName = album.artistName,
                    trackNumber = if (discs == 2) (n - 1) % ((albumSizes[i] + 1) / 2) + 1 else n,
                    discNumber = if (discs == 2 && n > (albumSizes[i] + 1) / 2) 2 else 1,
                    year = album.year,
                    genre = genre,
                    durationMs = ms,
                    bitrateKbps = if (random.nextBoolean()) 320 else 1_000,
                    sampleRateHz = 44_100,
                    bitDepth = 16,
                    channels = 2,
                    container = if (random.nextInt(3) == 0) "mp3" else "flac",
                    mimeType = "audio/flac",
                    sizeBytes = ms * 120,
                    artworkId = album.artworkId,
                    starred = random.nextInt(100) < 2,
                    rating = if (random.nextInt(100) < 5) random.nextInt(1, 6) else 0,
                    playCount = if (played) zipfCount(random) else 0,
                    path = "${album.artistName}/${album.name}/$n.flac",
                    createdAt = album.createdAt,
                )
                trackIndex++
            }
            withSizes += album.copy(trackCount = albumSizes[i], durationMs = duration)
        }

        // ---- playlists: from a few songs to a few hundred ----
        val playlistList = (0 until playlistCount).map { p ->
            val size = (random.nextDouble().pow(2) * 480).toInt() + 5
            val members = List(size) { trackList[random.nextInt(trackList.size)] }
            Playlist(
                id = "pl$p",
                serverId = serverId,
                name = "${word(ADJECTIVES, random)} ${word(NOUNS, random)} mix",
                trackCount = members.size,
                durationMs = members.sumOf { it.durationMs },
                createdAt = now - random.nextLong(EIGHT_YEARS_MS),
            ) to members
        }

        artists = artistList.mapIndexed { i, a -> a.copy(albumCount = albumsPerArtist[i]) }
        albums = withSizes
        tracks = trackList
        genres = trackList.mapNotNull { it.genre }.groupingBy { it }.eachCount().map { (name, count) ->
            Genre(name = name, serverId = serverId, trackCount = count, albumCount = withSizes.count { it.genre == name })
        }
        playlists = playlistList
    }

    private fun pick(weights: DoubleArray, random: Random): Int {
        val sum = weights.sum()
        var r = random.nextDouble() * sum
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0) return i
        }
        return weights.lastIndex
    }

    private fun genreName(random: Random): String = GENRES[pick(GENRE_WEIGHTS, random)]

    /** Most played things were played a handful of times, a few hundreds. */
    private fun zipfCount(random: Random): Long = (1.0 / (1.0 - random.nextDouble() * 0.995)).toLong().coerceAtMost(400)

    private fun artistName(random: Random): String = when (random.nextInt(100)) {
        in 0..14 -> "The ${word(ADJECTIVES, random)} ${word(NOUNS, random)}s"
        in 15..49 -> "${word(FIRST_NAMES, random)} ${word(LAST_NAMES, random)}"
        in 50..74 -> "${word(ADJECTIVES, random)} ${word(NOUNS, random)}"
        in 75..84 -> word(NOUNS, random)
        in 85..89 -> "${word(NOUNS, random)} & the ${word(NOUNS, random)}s"
        in 90..92 -> "DJ ${word(NOUNS, random)}"
        in 93..96 -> "${word(ACCENTED, random)} ${word(LAST_NAMES, random)}"
        in 97..98 -> "${word(NON_LATIN, random)} ${random.nextInt(1, 9)}"
        else -> "${random.nextInt(2, 99)} ${word(NOUNS, random)}s"
    }

    private fun albumName(random: Random, type: ReleaseType?): String {
        val base = when (random.nextInt(100)) {
            in 0..29 -> "${word(ADJECTIVES, random)} ${word(NOUNS, random)}"
            in 30..49 -> "${word(NOUNS, random)} of ${word(NOUNS, random)}"
            in 50..64 -> "The ${word(NOUNS, random)}"
            in 65..79 -> word(NOUNS, random)
            in 80..89 -> "${word(VERBS, random)} the ${word(NOUNS, random)}"
            in 90..94 -> "A ${word(ADJECTIVES, random)} ${word(NOUNS, random)}"
            else -> "${word(ACCENTED, random)} ${word(NOUNS, random)}"
        }
        return when {
            type == ReleaseType.EP -> "$base EP"
            random.nextInt(100) < 4 -> "$base (Deluxe Edition)"
            random.nextInt(100) < 3 -> "$base, Vol. ${random.nextInt(2, 5)}"
            else -> base
        }
    }

    private fun trackTitle(random: Random): String {
        val base = when (random.nextInt(100)) {
            in 0..24 -> "${word(ADJECTIVES, random)} ${word(NOUNS, random)}"
            in 25..44 -> "${word(VERBS, random)} the ${word(NOUNS, random)}"
            in 45..59 -> word(NOUNS, random)
            in 60..69 -> "The ${word(NOUNS, random)}"
            in 70..79 -> "${word(NOUNS, random)} in the ${word(NOUNS, random)}"
            in 80..86 -> "${word(VERBS, random)} ${word(ADVERBS, random)}"
            in 87..90 -> "${word(ACCENTED, random)} ${word(NOUNS, random)}"
            in 91..92 -> word(NON_LATIN, random)
            else -> "${random.nextInt(1, 100)} ${word(NOUNS, random)}s"
        }
        return when (random.nextInt(100)) {
            in 0..3 -> "$base (Remix)"
            in 4..5 -> "$base - Live"
            6 -> "$base (Remastered)"
            else -> base
        }
    }

    private fun word(list: List<String>, random: Random) = list[random.nextInt(list.size)]

    companion object {
        /** Fixed "now" so generated dates (and therefore sort orders) never change between runs. */
        const val BASE_TIME = 1_790_000_000_000L
        const val EIGHT_YEARS_MS = 8L * 365 * 24 * 60 * 60 * 1000

        val GENRES = listOf(
            "Rock", "Pop", "Electronic", "Jazz", "Hip-Hop", "Classical", "Indie", "Metal", "Folk", "Soul",
            "R&B", "Ambient", "Punk", "Blues", "Country", "Reggae", "Soundtrack", "Funk", "Latin", "World",
            "House", "Techno", "Alternative", "Singer-Songwriter", "Post-Rock", "Shoegaze", "Drum & Bass", "Disco",
            "Gospel", "K-Pop", "J-Pop", "Bossa Nova", "Experimental", "Trip-Hop", "Dub", "Grunge", "Synthpop",
            "Lo-Fi", "New Wave", "Hardcore",
        )
        private val GENRE_WEIGHTS = DoubleArray(GENRES.size) { 1.0 / (it + 1.0) }

        private val ADJECTIVES = listOf(
            "Silent", "Golden", "Electric", "Broken", "Velvet", "Crimson", "Hollow", "Bright", "Lonely", "Wild",
            "Frozen", "Burning", "Distant", "Midnight", "Paper", "Glass", "Neon", "Quiet", "Restless", "Endless",
            "Sweet", "Bitter", "Lost", "Secret", "Silver", "Black", "White", "Blue", "Red", "Green", "Young",
            "Ancient", "Hidden", "Fading", "Rising", "Falling", "Wandering", "Open", "Northern", "Southern",
            "Tender", "Strange", "Sacred", "Savage", "Gentle", "Heavy", "Little", "Great", "Last", "First",
        )
        private val NOUNS = listOf(
            "Heart", "River", "Mountain", "Ocean", "Fire", "Rain", "Star", "Moon", "Sun", "Shadow", "Light",
            "Dream", "Road", "City", "Garden", "Window", "Mirror", "Echo", "Wave", "Storm", "Forest", "Desert",
            "Island", "Bridge", "Tower", "Machine", "Signal", "Ghost", "Angel", "Wolf", "Tiger", "Raven", "Sparrow",
            "Horizon", "Summer", "Winter", "Autumn", "Spring", "Morning", "Evening", "Night", "Thunder", "Lightning",
            "Harbor", "Lantern", "Compass", "Anchor", "Crown", "Diamond", "Feather", "Stone", "Highway", "Station",
            "Satellite", "Galaxy", "Planet", "Orbit", "Circuit", "Pulse", "Rhythm", "Melody", "Chorus", "Anthem",
            "Ballad", "Season", "Memory", "Promise", "Secret", "Silence", "Whisper", "Kingdom", "Empire", "Paradise",
        )
        private val VERBS = listOf(
            "Chasing", "Running", "Holding", "Breaking", "Finding", "Losing", "Leaving", "Burning", "Dancing",
            "Falling", "Crossing", "Building", "Calling", "Waiting", "Watching", "Hunting", "Saving", "Follow",
            "Remember", "Forget", "Bring", "Catch", "Light", "Feel", "Touch", "Hear", "Taste", "Keep",
        )
        private val ADVERBS = listOf("Slowly", "Forever", "Tonight", "Again", "Alone", "Away", "Home", "Now", "Softly", "Together")
        private val FIRST_NAMES = listOf(
            "Anna", "Ben", "Clara", "David", "Elena", "Felix", "Grace", "Hugo", "Iris", "Jack", "Kira", "Leo",
            "Maya", "Noah", "Olivia", "Paul", "Quinn", "Rosa", "Sam", "Tara", "Uma", "Victor", "Wendy", "Xavier",
            "Yara", "Zane", "Marcus", "Nina", "Oscar", "Lena", "Theo", "Ivy", "Jonas", "Mila", "Ruben", "Sofia",
        )
        private val LAST_NAMES = listOf(
            "Walker", "Stone", "Rivers", "Hart", "Black", "Fields", "Moreau", "Novak", "Larsen", "Costa",
            "Fischer", "Okafor", "Tanaka", "Silva", "Kowalski", "Murphy", "Bennett", "Reyes", "Lindqvist",
            "Haddad", "Ivanova", "Kim", "Nakamura", "Dubois", "Rossi", "Weber", "Clarke", "Patel", "Andersen",
        )
        private val ACCENTED = listOf("Zoë", "Björn", "Renée", "Ólafur", "Søren", "José", "Chloé", "Mañana", "Éclair", "Ångström", "Façade", "Über")
        private val NON_LATIN = listOf("夜明け", "東京", "Звезда", "Весна", "서울", "Αθήνα")
    }
}

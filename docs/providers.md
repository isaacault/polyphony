# Providers (music backends)

A **provider** is Polyphony's adapter for one kind of music server: Subsonic/Navidrome,
Jellyfin, and — in the future — Plex, Emby, Kodi, local files, and so on. Every provider speaks
the same backend-neutral contract, `MediaProvider`, so the rest of the app never knows which
server it is talking to. This document is the contributor guide for implementing a new one.

Read [ARCHITECTURE.md](../ARCHITECTURE.md) first for the overall module graph; this doc zooms in
on the provider layer.

## Where providers live

```
app → core:ui, core:playback, core:data, core:dlna, core:downloads, provider:* → core:network → core:domain
```

- Each backend is its own Gradle module under `provider:*` (e.g. `:provider:subsonic`,
  `:provider:jellyfin`). A provider module depends only on `:core:domain` (the contracts and
  models) and `:core:network` (the per-server OkHttp client factory). It must **not** depend on
  `:app` or on any other `:core` module.
- `:app` depends on each provider module purely so Hilt can discover its DI module at compile
  time. No app or `core` code references a concrete provider class — everything goes through the
  `MediaProvider` / `MediaProviderFactory` interfaces and the `ProviderRegistry`.
- The contracts and all domain models are pure Kotlin in `:core:domain`
  (`core/domain/src/main/kotlin/app/polyphony/core/domain/`): no Android dependencies, which keeps
  providers unit-testable on the JVM.

This is the whole extension point: add a module, implement two interfaces, bind a factory into a
Hilt set. Nothing in `:app`, `:core:data`, `:core:playback`, or the UI needs to change for the
provider to work end to end (one onboarding card is the only UI touch — see below).

## The `MediaProvider` contract

Source: `core/domain/src/main/kotlin/app/polyphony/core/domain/provider/MediaProvider.kt`.

### Threading and exception contract

From the interface KDoc:

> All suspend functions throw `ProviderException` on failure. Implementations must be safe to call
> from any dispatcher and should perform I/O on `Dispatchers.IO`. Every returned model carries
> `Server.id` in its `serverId` field.

Concretely:

- **Never leak transport exceptions.** Wrap every network/parse failure in the right
  `ProviderException` subtype (see below). Callers only catch `ProviderException` (plus
  `CancellationException`, which must always propagate).
- **Do your own I/O dispatch.** The caller may invoke you from any dispatcher; move blocking work
  onto `Dispatchers.IO` inside the provider. (Subsonic does this in its `subsonicCall { }` helper;
  Jellyfin in `jellyfinCall { }`.)
- **Stamp `serverId`.** Every `Artist`/`Album`/`Track`/`Playlist`/`Genre` you return must have its
  `serverId` set to `server.id`. The mappers take the server id in their constructor so this is
  automatic.
- **The URL methods are pure and synchronous** (`fun`, not `suspend`) — they must not perform I/O.

### Properties

| Member | Responsibility |
| --- | --- |
| `val server: Server` | The configured server this instance talks to (id, base URL, credentials, options). |
| `val capabilities: Set<Capability>` | Optional features this backend supports; the UI hides what is absent. May be refined at runtime (Subsonic upgrades it after probing OpenSubsonic extensions). |

### Methods

Each signature is copied from the interface.

| Method | Responsibility |
| --- | --- |
| `suspend fun ping(): ServerInfo` | Reach the server, verify the credentials, and report product/version (and, for Subsonic, OpenSubsonic extensions). Used at connect time and for health checks. |
| `suspend fun getArtists(): List<Artist>` | All artists for the artists browse screen / library sync. |
| `suspend fun getArtist(id: String): ArtistDetail` | One artist with its albums (and similar artists / bio where available). |
| `suspend fun getAlbums(type: AlbumListType, size: Int = 50, offset: Int = 0, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Album>` | A page of albums for a given list type (recently added, most played, by year, by genre, alphabetical, …). |
| `suspend fun getAlbum(id: String): AlbumDetail` | One album with its ordered track list. |
| `suspend fun getAllTracks(size: Int, offset: Int): List<Track>` | Page through every track for the full library sync. Throw `ProviderException.Unsupported` if the backend cannot list all tracks — the sync then walks albums instead. |
| `suspend fun getTrack(id: String): Track` | A single track by id. |
| `suspend fun getRandomTracks(size: Int = 50, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Track>` | Random tracks (optionally filtered) for shuffle/discovery. |
| `suspend fun getTracksByGenre(genre: String, size: Int = 100, offset: Int = 0): List<Track>` | A page of tracks in a genre. |
| `suspend fun getSimilarTracks(trackId: String, count: Int = 50): List<Track>` | "More like this" radio seed. |
| `suspend fun getTopTracks(artist: Artist, count: Int = 20): List<Track>` | An artist's most-played / top tracks. |
| `suspend fun getPlaylists(): List<Playlist>` | All playlists. |
| `suspend fun getPlaylist(id: String): PlaylistDetail` | One playlist with its tracks. |
| `suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Playlist` | Create a playlist and return it. |
| `suspend fun updatePlaylist(id: String, name: String? = null, description: String? = null, isPublic: Boolean? = null, addTrackIds: List<String> = emptyList(), removeIndices: List<Int> = emptyList())` | Rename / re-describe / set visibility, add tracks, and remove tracks by index. |
| `suspend fun deletePlaylist(id: String)` | Delete a playlist. |
| `suspend fun getGenres(): List<Genre>` | All genres with counts. |
| `suspend fun getStarred(): StarredContent` | Favourited artists, albums and tracks. |
| `suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean)` | Star / unstar an item. Throw `ProviderException.Unsupported` for kinds the backend cannot star. |
| `suspend fun setRating(id: String, kind: ItemKind, rating: Int)` | Set a 0–5 rating on an item. |
| `suspend fun search(query: String, artistLimit: Int = 20, albumLimit: Int = 20, trackLimit: Int = 50): SearchResults` | Search artists, albums and tracks for the search screen. |
| `suspend fun getLyrics(track: Track): Lyrics?` | Lyrics (synced where supported), or `null` if none. |
| `suspend fun reportNowPlaying(trackId: String)` | "Now playing" scrobble (no play count). |
| `suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long)` | Submitted scrobble when a track has been played (counts the play). |
| `fun streamRequest(track: Track, profile: TranscodeProfile): StreamRequest` | **Pure.** Build the audio stream URL honouring the transcode `profile`. Must not perform I/O. |
| `fun downloadRequest(track: Track): StreamRequest` | **Pure.** URL for the original, untranscoded file (offline downloads). |
| `fun artworkUrl(artworkId: String?, sizePx: Int = 600): String?` | **Pure.** Cover-art URL for a cover-art id at roughly `sizePx` square, or `null`. |

### `MediaProviderFactory`

One factory per `ServerType`, bound into a Hilt multibinding set:

```kotlin
interface MediaProviderFactory {
    val type: ServerType
    /** Validate credentials, exchange them for a token where needed, and return a persistable Server. */
    suspend fun authenticate(request: ConnectRequest): Server
    fun create(server: Server): MediaProvider
}
```

- `authenticate(request)` runs at onboarding: it normalises the base URL, talks to the server to
  validate credentials (exchanging username/password for an access token where the backend uses
  one), and returns a fully-populated `Server` to persist. Subsonic stores the password as the
  `credential`; Jellyfin stores the access token it got back and the `userId`.
- `create(server)` is cheap and synchronous — it wires up the HTTP client and returns a
  `MediaProvider`. The `ProviderRegistry` caches instances, so `create` must not do network I/O.

`ConnectRequest` is the onboarding input:

```kotlin
data class ConnectRequest(
    val type: ServerType,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val options: Map<String, String> = emptyMap(),
)
```

### `ProviderException`

Map every failure to one of these (defined in the same file):

| Subtype | When |
| --- | --- |
| `ProviderException.Unauthorized` | Bad credentials / expired token. |
| `ProviderException.NotFound` | Item or endpoint missing. |
| `ProviderException.Network` | Connectivity / DNS / TLS / timeout / malformed address. |
| `ProviderException.Server(code, …)` | Server returned an error status. |
| `ProviderException.Unsupported` | The backend cannot do this operation (e.g. `getAllTracks`, starring playlists). |
| `ProviderException.Unknown` | Anything else. |

### Capabilities

`Capability` (in `core/domain/.../model/Library.kt`) is the set of optional features. The UI hides
what a provider does not advertise. Current flags: `LYRICS`, `SYNCED_LYRICS`, `RATINGS`,
`FAVORITES`, `PLAYLIST_EDIT`, `SCROBBLE`, `SIMILAR_ARTISTS`, `TOP_TRACKS`, `GENRES`,
`RANDOM_TRACKS`, `TRANSCODE_FORMAT_SELECTION`, `DOWNLOAD_ORIGINAL`, `FULL_TRACK_LISTING`.

Jellyfin declares a fixed set; Subsonic starts from a base set and adds `SYNCED_LYRICS` once it has
probed for the `songLyrics` OpenSubsonic extension. Only advertise what you actually implement.

## How a provider is registered and resolved

There is no manual registry wiring. Each provider module declares a Hilt module that binds its
factory `@IntoSet`:

```kotlin
// provider/subsonic/.../SubsonicProviderModule.kt
@Module
@InstallIn(SingletonComponent::class)
abstract class SubsonicProviderModule {
    @Binds
    @IntoSet
    abstract fun bindSubsonicFactory(factory: SubsonicProviderFactory): MediaProviderFactory
}
```

(Jellyfin keeps the equivalent `@Module` at the bottom of `JellyfinProviderFactory.kt` — either
placement is fine.)

`:core:data` declares the set so it exists even when empty, and binds the registry:

```kotlin
// core/data/.../di/DataModule.kt
@Multibinds
abstract fun providerFactories(): Set<MediaProviderFactory>

@Binds abstract fun bindProviderRegistry(impl: ProviderRegistryImpl): ProviderRegistry
```

`ProviderRegistryImpl` (`core/data/.../repository/ProviderRegistryImpl.kt`) receives
`Set<MediaProviderFactory>`, indexes them by `type`, and resolves/caches `MediaProvider` instances
per server:

```kotlin
interface ProviderRegistry {
    val activeProvider: Flow<MediaProvider?>
    fun factoryFor(type: ServerType): MediaProviderFactory
    fun forServer(server: Server): MediaProvider
    suspend fun active(): MediaProvider?
    fun invalidate(serverId: String) // drop cached providers after credentials change
}
```

Onboarding calls `providerRegistry.factoryFor(type).authenticate(request)`; everything else talks
to `activeProvider` / `forServer(server)`.

## Step-by-step: add a new provider

The two existing modules are the reference implementations:
`provider/subsonic/` (hand-rolled REST + kotlinx.serialization) and `provider/jellyfin/` (official
Jellyfin Kotlin SDK). Pick whichever is the closer shape for your backend and mirror it.

### 1. Create the module

```
provider/<name>/
  build.gradle.kts
  src/main/AndroidManifest.xml          # <manifest .../> — empty, code-only library
  src/main/kotlin/app/polyphony/provider/<name>/
      <Name>ProviderFactory.kt          # MediaProviderFactory + Hilt @Module
      <Name>MediaProvider.kt            # MediaProvider implementation
      <Name>Mapper.kt                   # DTO → domain conversions (pure)
      ...                               # DTOs, URL/stream helpers, error mapping
  src/test/kotlin/...                   # JVM unit tests
```

Use a `build.gradle.kts` modelled on `provider/subsonic/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)   // if you parse JSON yourself
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.polyphony.provider.<name>"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { /* Java 17 */ }
    buildFeatures { buildConfig = false }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:network"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // implementation(libs.<your.sdk>)      // only if you use a vendor SDK

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

### 2. Register the module in `settings.gradle.kts`

```kotlin
include(":provider:<name>")
```

### 3. Add it to `:app`

In `app/build.gradle.kts`, next to the existing provider lines:

```kotlin
implementation(project(":provider:<name>"))
```

This is only so Hilt sees the provider's `@Module` at compile time — no app code references the
provider directly.

### 4. Add dependencies to the version catalog (if needed)

If your backend needs a client SDK, add the version and library to `gradle/libs.versions.toml`
(see how `jellyfinSdk` / `jellyfin-core` are declared) and reference it via `libs.*`. Reuse the
existing `okhttp`, `kotlinx-serialization-json`, `hilt-*`, and `junit` entries where possible.

### 5. Add a `ServerType`

In `core/domain/.../model/Server.kt`:

```kotlin
enum class ServerType(val displayName: String, val defaultPort: Int?) {
    SUBSONIC("Subsonic / Navidrome / OpenSubsonic", 4533),
    JELLYFIN("Jellyfin", 8096),
    PLEX("Plex", 32400),   // example
}
```

If you need backend-specific settings, add keys to the `ServerOptions` object in the same file and
read them from `Server.options` / `ConnectRequest.options` (that is how `LEGACY_AUTH`,
`CUSTOM_HEADERS`, `TRUST_SELF_SIGNED`, `REMOTE_BASE_URL`, etc. are threaded through).

### 6. Implement the factory, provider and mappers

- **Factory** — inject `ServerHttpClientFactory` from `:core:network` and use
  `clientFor(options)` (during `authenticate`) / `clientFor(server)` (in `create`) so custom
  headers and "trust self-signed" apply to every request. Normalise the base URL, validate
  credentials, and return a `Server`. Wrap failures in `ProviderException`.
- **Provider** — implement every `MediaProvider` method. Perform I/O on `Dispatchers.IO`, stamp
  `serverId`, and translate transport errors to `ProviderException`. The URL methods must be pure.
- **Mapper** — a pure class that takes `serverId` (and a URL builder) in its constructor and
  converts your DTOs to domain models. Keep it free of I/O so it is trivially unit-testable.

### 7. Bind the factory into Hilt

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class <Name>ProviderModule {
    @Binds
    @IntoSet
    abstract fun bind<Name>Factory(factory: <Name>ProviderFactory): MediaProviderFactory
}
```

That is all the data/registry wiring — `ProviderRegistryImpl` picks it up from the set.

### 8. Surface it in onboarding

Onboarding currently lists the two backends explicitly (it is not auto-generated from
`ServerType`). Add a card in `ServerTypeStep` in
`app/src/main/kotlin/app/polyphony/ui/onboarding/ConnectServerScreen.kt`:

```kotlin
ServerTypeCard(
    icon = Icons.Rounded.<Icon>,
    title = stringResource(R.string.server_type_<name>_title),
    description = stringResource(R.string.server_type_<name>_desc),
    onClick = { onSelect(ServerType.<NAME>) },
)
```

Add the two string resources, and handle any backend-specific form fields (e.g. the Subsonic
`legacyAuth` toggle is gated on `type == ServerType.SUBSONIC` in `ConnectForm` and in
`ConnectServerViewModel.connect()`). The ViewModel already calls
`providerRegistry.factoryFor(type).authenticate(request)` generically, so no other onboarding
logic changes.

## URL resolution, Cast and DLNA

`streamRequest`, `downloadRequest` and `artworkUrl` return plain URLs (wrapped in `StreamRequest`
for audio), and they must be **pure** — the player builds them lazily at load time with the
transcode decision in force at that moment.

```kotlin
data class StreamRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,     // best-effort MIME of the delivered stream
    val isTranscoded: Boolean = false,
)
```

- Honour the `TranscodeProfile`: `profile.isPassthrough` means serve the original bytes; otherwise
  map `profile.codec` / `profile.maxBitrateKbps` (and, where the backend supports it,
  `maxSampleRateHz` / `maxBitDepth`) to the server's transcode parameters. Set
  `isTranscoded` accordingly so the player knows whether the body length is exact.
- `downloadRequest` must always be the untranscoded original (`isTranscoded = false`) for offline
  caching.
- For Cast and DLNA the receiver fetches the URL itself, so the URL must embed whatever auth the
  receiver needs (query-string token, not an app-only header). If auth can only travel as a header,
  put it in `StreamRequest.headers`; `:core:playback` decides how receivers get it. Receiver URLs
  are further rewritten by `RemoteUrlResolver` / `ServerOptions.REMOTE_BASE_URL` (LAN address
  substitution) — see the transcoding section of [ARCHITECTURE.md](../ARCHITECTURE.md).

## Testing

Providers are built to be unit-tested on the JVM without a live server. Keep all pure logic
(mappers, URL builders, stream-param resolvers, auth/error mapping) in pure classes and test them
directly. Examples to copy:

- `provider/subsonic/src/test/kotlin/.../SubsonicMapperTest.kt` — album-list-type and codec/format
  mapping, ISO-date parsing.
- `provider/subsonic/src/test/kotlin/.../SubsonicUrlBuilderTest.kt` — URL construction, including
  auth parameters.
- `provider/subsonic/src/test/kotlin/.../SubsonicProviderTest.kt` — drives the provider against a
  fake `Call.Factory` / canned responses (see `TestSupport.kt`) to exercise paging, fallbacks and
  error mapping end to end without a network.
- `provider/jellyfin/src/test/kotlin/.../JellyfinStreamParamsTest.kt` — transcode-profile →
  stream-parameter decisions (direct play vs. transcode, bitrate/sample-rate caps, MIME type).
- `provider/jellyfin/src/test/kotlin/.../JellyfinUrlNormalizerTest.kt` — base-URL normalisation.

Guidelines:

- Put the pieces that decide URLs, formats and mappings behind pure functions/classes so a test
  needs no Android or network. The `TranscodeResolver` is tested the same way in `:core:domain`.
- Cover the awkward cases real servers produce: empty/legacy envelopes, missing fields, servers
  that don't support an endpoint (assert `ProviderException.Unsupported`), and date/number formats.
- Run `./gradlew test` (or `./gradlew :provider:<name>:test`) before pushing; CI must be green.

## Checklist

- [ ] `provider/<name>/` module with `build.gradle.kts`, empty `AndroidManifest.xml`.
- [ ] `include(":provider:<name>")` in `settings.gradle.kts`.
- [ ] `implementation(project(":provider:<name>"))` in `app/build.gradle.kts`.
- [ ] New SDK deps (if any) in `gradle/libs.versions.toml`.
- [ ] New `ServerType` (and any `ServerOptions` keys) in `core/domain/.../model/Server.kt`.
- [ ] `<Name>ProviderFactory` implementing `authenticate` + `create`, errors as `ProviderException`.
- [ ] `<Name>MediaProvider` implementing every `MediaProvider` method; I/O on `Dispatchers.IO`;
      `serverId` stamped; pure URL methods.
- [ ] `<Name>Mapper` (pure) for DTO → domain conversions.
- [ ] Hilt `@Module` binding the factory `@IntoSet` as `MediaProviderFactory`.
- [ ] Accurate `capabilities` set.
- [ ] Onboarding card + string resources in `ConnectServerScreen.kt`.
- [ ] JVM unit tests for mappers / URL builders / stream-param resolvers; `./gradlew test` green.

# Architecture

Polyphony is one repository for every client: Android today, then Mac, iPhone, Apple TV, Windows
and Linux, all on the same backend (continuo, a separate repo) and plain Subsonic servers. The
logic they share lives in `shared/`, written once; each platform adds its own UI and player.

```
shared/                       the logic every client needs; becoming Kotlin Multiplatform (Android,
                              desktop JVM, Apple frameworks), so nothing here may depend on Android
  domain                      Models, MediaProvider + repository contracts, TranscodeResolver (pure Kotlin)
  network                     OkHttp/Json plumbing, per-server client factory
  data                        Room cache, DataStore settings, repository impls, library sync, network monitor
  requests                    The continuo client: search, requests, Play now, discovery, lyrics
  scrobble                    Scrobbling from the phone to ListenBrainz and Last.fm: both clients, the
                              offline queue (own Room DB) and its WorkManager drain
  providers/subsonic          Subsonic / OpenSubsonic / Navidrome implementation of MediaProvider
  providers/jellyfin          Jellyfin implementation of MediaProvider (official Kotlin SDK)
android/                      the Android app, and the modules still tied to Android
  app                         Compose screens, navigation, Application, DI aggregation
  ui                          Theme (Material 3 Expressive), motion tokens, shared components, palette utils
  playback                    Media3 PlaybackService, local ⇄ Cast switching, stream URL resolution,
                              latency monitor, scrobbling, crossfade, the built-in equaliser (an
                              AudioProcessor in each local player), PlaybackController/CastController impls
  dlna                        Hand-rolled UPnP AV control point (SSDP, SOAP AVTransport/RenderingControl),
                              DlnaPlayer (Media3 SimpleBasePlayer), DlnaController impl
  downloads                   Offline downloads: own Room DB, WorkManager download queue, rules engine
                              (favourites / playlists / albums / artists / genres / recently added / most
                              played) with storage-cap eviction, DownloadRepository impl
apple/                        the Mac app in SwiftUI (iOS and tvOS to follow on the same code)
  PolyphonyKit                What isn't UI, in Swift until shared/ produces an Apple framework:
                              MusicLibrary (Subsonic, Jellyfin), continuo, the player controller, the
                              DLNA and Cast engines, streaming rules, downloads. Tested on Linux
  Polyphony                   Views, and what only a Mac has: AVFoundation, Network, Keychain
desktop/                      (to come) Windows + Linux, Compose Multiplatform
contract/                     continuo's OpenAPI schema and the test cases every client must pass
```

The dependency rule is strict:
`android:app → android:{ui, playback, dlna, downloads}, shared:data, shared:providers:*, shared:requests,
shared:scrobble → shared:network → shared:domain`. Nothing in `shared/` depends on `android/`; `shared:domain` is pure
Kotlin with no Android dependencies. Logic that a second platform will need moves from `android/`
into `shared/` (the playback queue rules, the download rules engine), leaving the platform glue behind.

Adding a music backend (Plex, Emby, local files, …) is a self-contained `shared:providers:*` module.
See [docs/providers.md](docs/providers.md) for the `MediaProvider` contract, registration, and a
step-by-step guide.

## Key contracts (shared:domain)

| Contract | Purpose |
| --- | --- |
| `MediaProvider` / `MediaProviderFactory` | Backend-neutral API. One factory per `ServerType`, registered into a Hilt `Set<MediaProviderFactory>`. |
| `LibraryRepository` | Room-backed Flows for every browse screen; mutations are optimistic. |
| `LibrarySyncManager` | Full/incremental sync into Room (WorkManager for periodic runs). |
| `ServerRepository` / `ProviderRegistry` | Configured servers, the active one, and cached provider instances. |
| `SettingsRepository` (`AppSettings`) | DataStore-backed settings incl. `TranscodePolicy`. |
| `TranscodeProfileRepository` | User profiles + remembered remote devices and their pinned profiles. |
| `NetworkMonitor` | `StateFlow<NetworkState>` (Wi-Fi / cellular / metered). |
| `TranscodeResolver` | Pure policy → profile decision (unit-tested). |
| `PlaybackController` / `CastController` / `DlnaController` | What the UI talks to. |
| `DownloadRepository` (`DownloadRule`, `DownloadSettings`) | Pinned and rule-managed downloads, storage usage, offline mode. |
| `ScrobbleRule` | When a play counts; the server's play count, history and the phone's own scrobbles all use it. |
| `Scrobbler` / `ScrobbleRepository` | The phone's ListenBrainz and Last.fm accounts and the queue of plays not yet sent. |

## Transcoding engine

```
            ┌────────────────┐   ┌──────────────┐   ┌──────────────────┐
settings ──▶│ TranscodePolicy│   │ NetworkState │   │ OutputTarget +   │
            └───────┬────────┘   └──────┬───────┘   │ device override  │
                    │                   │           └────────┬─────────┘
                    ▼                   ▼                    ▼
                 ┌──────────────────────────────────────────────┐
 latency ───────▶│              TranscodeResolver               │
 degraded?       └──────────────────────┬───────────────────────┘
                                        ▼  TranscodeDecision(profile, reason)
                     provider.streamRequest(track, profile) → URL
```

- **Local playback** uses a `ResolvingDataSource`: queue items carry an opaque
  `polyphony://track/{serverId}/{trackId}` URI and the real stream URL is computed at load time
  with the decision in force *at that moment*. Nothing needs rebuilding when rules change.
- **Downloads**: the resolver asks `DownloadRepository.localFile(trackId)` first (always in offline
  mode, otherwise when `preferDownloadedForPlayback` is on) and plays the `file://` URI.
- **Cast and DLNA** must send real URLs to the receiver, so the `MediaItemConverter` resolves them with the
  decision for the connected device. When the decision changes mid-session, the remaining queue
  is re-sent.
- **Receiver URLs** pass through `RemoteUrlResolver` (android:playback): an explicit per-server
  receiver address (`ServerOptions.REMOTE_BASE_URL`) replaces the base URL; otherwise plain-http
  hosts the phone resolves to a private IPv4 get that IP substituted. HTTPS hosts are never
  rewritten because the receiver would fail certificate validation.
- **Per-device profiles** are keyed by `OutputTarget.key` (`cast:<deviceId>`, `dlna:<udn>`) and stored in
  `TranscodeProfileRepository`. Devices are remembered as soon as they are discovered so the
  Settings screen can list them even when not connected.
- **Network rules** come from `NetworkMonitor` (Wi-Fi/Ethernet vs cellular/metered).
- **Adaptive latency**: the `LatencyMonitor` in `android:playback` counts rebuffer events (and long
  stalls) in a sliding window. Crossing the threshold sets `latencyDegraded = true`, which makes
  the resolver cap quality with the high-latency profile. It auto-recovers after a quiet period
  online or when the user taps "Restore quality". A new connection (another kind, another
  tariff) resets it; the same one coming and going doesn't, and losing the network mid-stream
  trips it (`LatencyMonitor.networkChange`), so a road trip's dead zones keep the low level and
  the fetching ahead instead of resetting them at every drop.
- **Connection samples** (#185): while the phone streams, `ConnectionSampler` (android:playback)
  turns stream reads (timed at the HTTP source under the stream cache; cache and download reads
  counted apart), stalls and the latency cap into a `ConnectionSample` (shared:domain) every 30 s
  and on a network change, kept in `connection_samples` for 14 days. About → Diagnostics sums them
  up per network and the report carries them as CSV. Nothing is sent to continuo yet.
- **Adaptive streaming** (#185, continuo#119): a continuo server's network stream (not Play now
  songs, downloads or other servers; `AdaptiveStreaming.route` in shared:domain) plays as HLS from
  `/v1/stream/{song}/master.m3u8`, Opus at 32/64/128/256 kbit/s in 6 s segments, so Media3 changes
  quality between segments from the throughput it measures, mid-song, on Wi-Fi and mobile data
  alike. `AdaptiveMediaSourceFactory` picks HLS or progressive per item; items keep their opaque
  URIs, `ContinuoHlsResolver` maps them to continuo's playlist and reports real URLs (so relative
  playlist URIs resolve), every request carries the bearer token, and a 401 renews it once
  (`RefreshingAuthDataSource`). The rules' profile (rounded down to a rung) or, while
  `LatencyMonitor` is degraded, the lowest rung is the track selector's `maxAudioBitrate`: a
  change applies from the next segment without a reload. While degraded, the queue's next 30
  minutes are fetched ahead at that rung (HLS playlists and segments into the stream cache under
  the URLs the player asks for), and continuo is told the next four songs and the level
  (`POST /v1/stream/upcoming`) so it encodes them first. Gapless: continuo states each rendition's
  trims in its init segment (docs/spikes/hls-gapless on branch spike/hls-gapless). A song plays
  its ordinary stream for the session only when continuo answered and couldn't serve it (an
  error status, playlists or segments that don't parse), or it was downloaded after it was
  queued; a lost connection keeps it adaptive and waits for the network (the ordinary stream
  couldn't get through either, and would be heavier when it did). Now Playing shows the rung
  playing ("Adaptive Opus 64k"). The wiring is `HlsDataSources` (stream/AdaptiveHls.kt), which
  `HlsPlaybackTest` plays through Media3's real HLS player under Robolectric against continuo's
  playlists and a real song's segments. Cast and DLNA keep the progressive URLs. Off in Playback
  settings → Adaptive streaming.
- If `reloadCurrentTrackOnChange` is on, a changed decision re-prepares the current item at the
  same position (not an adaptive stream, which follows from its next segment); otherwise it
  applies from the next track.

## Data flow

- Browse screens read Room through `LibraryRepository`; nothing waits on the network to render.
- `LibrarySyncManager` pulls artists → albums → tracks → playlists → starred into Room, in pages,
  on first connect and periodically (WorkManager, Wi-Fi-only by default). Detail screens call
  `refreshX` to pick up changes immediately.
- Smart playlists (#33) are rules kept with the settings (`SmartPlaylist` in shared:domain,
  `LibrarySettings.smartPlaylists`), not on any server: `SmartPlaylistQueries` (shared:data) turns
  them into one query over `tracks` on the active server, with every value bound and only
  whitelisted columns and operators. `LibraryRepository.smartPlaylistTracks` observes it, so the
  list follows syncs, plays and ratings. A download rule can keep one on the phone
  (`DownloadRuleTarget.Smart`).
- Credentials live in the app-private Room database. Subsonic uses salted token auth; Jellyfin
  stores the access token obtained at login.
- Scrobbling from the phone (opt-in, the only traffic that leaves the user's own server): the
  `PlaybackReporter` hands a started track and a counted play (`ScrobbleRule`) to the `Scrobbler`,
  which queues one row per connected account in `polyphony-scrobble.db` (its own database: the
  ListenBrainz token and Last.fm session key sit there, left out of the cloud backup like the
  server passwords and carried in the backup file like them). A unique WorkManager job drains the
  queue when there is a network: oldest first, 100 listens per ListenBrainz request and 50 per
  Last.fm one, exponential backoff for an unreachable or busy service, a refused batch retried one by
  one so only the bad listen is dropped, and a refused token leaves the plays waiting for a
  reconnect. Last.fm needs the app's API account at build time (`lastfm.apiKey` and
  `lastfm.apiSecret` in `local.properties`, or `POLYPHONY_LASTFM_API_KEY` /
  `POLYPHONY_LASTFM_API_SECRET`; `scripts/release.sh` reads `lastfm.properties` from the signing
  folder); without them Last.fm isn't offered.

## Conventions

- Kotlin 2.4, Compose BOM 2026.09, Material 3 1.4 (Expressive APIs opted-in per module).
- Hilt everywhere; KSP for Hilt and Room. No kapt.
- Coroutines + Flow; `Dispatchers.IO` for I/O inside repositories/providers; ViewModels expose
  `StateFlow<UiState>` via `stateIn(WhileSubscribed(5_000))`.
- Type-safe Navigation Compose routes in `app/navigation/Routes.kt`; screens obtain
  `LocalNavigator` and never touch `NavController` directly.
- Build: `source scripts/env.sh && ./gradlew assembleDebug`. Tests: `./gradlew test`.
- Docker: `docker build -t polyphony-build . && docker run --rm -v "$PWD":/src polyphony-build`
  produces `android/app/build/outputs/apk/debug/app-debug.apk` on any x86_64 host.

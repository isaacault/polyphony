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
  providers/subsonic          Subsonic / OpenSubsonic / Navidrome implementation of MediaProvider
  providers/jellyfin          Jellyfin implementation of MediaProvider (official Kotlin SDK)
android/                      the Android app, and the modules still tied to Android
  app                         Compose screens, navigation, Application, DI aggregation
  ui                          Theme (Material 3 Expressive), motion tokens, shared components, palette utils
  playback                    Media3 PlaybackService, local ⇄ Cast switching, stream URL resolution,
                              latency monitor, scrobbling, PlaybackController/CastController impls
  dlna                        Hand-rolled UPnP AV control point (SSDP, SOAP AVTransport/RenderingControl),
                              DlnaPlayer (Media3 SimpleBasePlayer), DlnaController impl
  downloads                   Offline downloads: own Room DB, WorkManager download queue, rules engine
                              (favourites / playlists / albums / artists / genres / recently added / most
                              played) with storage-cap eviction, DownloadRepository impl
apple/                        (to come) one Xcode project: macOS, iOS, tvOS in SwiftUI over shared/
desktop/                      (to come) Windows + Linux, Compose Multiplatform
contract/                     continuo's OpenAPI schema and the test cases every client must pass
```

The dependency rule is strict:
`android:app → android:{ui, playback, dlna, downloads}, shared:data, shared:providers:*, shared:requests
→ shared:network → shared:domain`. Nothing in `shared/` depends on `android/`; `shared:domain` is pure
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
  or when the user taps "Restore quality". Network changes reset it.
- If `reloadCurrentTrackOnChange` is on, a changed decision re-prepares the current item at the
  same position; otherwise it applies from the next track.

## Data flow

- Browse screens read Room through `LibraryRepository`; nothing waits on the network to render.
- `LibrarySyncManager` pulls artists → albums → tracks → playlists → starred into Room, in pages,
  on first connect and periodically (WorkManager, Wi-Fi-only by default). Detail screens call
  `refreshX` to pick up changes immediately.
- Credentials live in the app-private Room database. Subsonic uses salted token auth; Jellyfin
  stores the access token obtained at login.

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

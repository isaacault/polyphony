# core:downloads

Offline downloads with standing rules. Self-contained: its own Room database
(`polyphony-downloads.db`), WorkManager workers, and storage under the app's private files.

## Integration contract

**Playback (`core:playback` stream resolver)**
- Before building a network `DataSpec`, call `DownloadRepository.localFile(trackId)` when
  `AppSettings.downloads.preferDownloadedForPlayback` or `offlineMode` is on. A non-null result
  means a verified file: resolve to `Uri.fromFile(File(filePath))` (`file://`), MIME from
  `mimeType`, and skip the stream cache (`DataSpec.FLAG_...` / bypass `CacheDataSource`), or keep
  the cache key `"$trackId|${profileId}"` consistent if you do route through it.
- The call also bumps `lastAccessAt`, which is what the LRU eviction orders by.
- In `offlineMode`, tracks without a local file should be skipped (or the queue filtered) instead of
  attempting the network.

**Library (`core:data` / UI)**
- `downloadedTrackIds()` is a cheap `Flow<Set<String>>` for the active server. Offline mode =
  filter lists/search by it (do it in the repository or the ViewModels; the set is small compared
  to the library).

**UI**
- Per item: `downloadState(trackId)`; per album/playlist/artist: `collectionState(trackIds)`
  (`isFullyDownloaded`, `isPartiallyDownloaded`, `isDownloading`, `progress`).
- Actions: `download(tracks, profileId?, pinned = true)`, `remove(ids)`, `pauseAll/resumeAll/retryFailed`.
- Storage: `storageUsage` (used / limit / device free / file count).
- Rules: `rules`, `upsertRule`, `deleteRule(id, removeFiles)`, `evaluateRulesNow()`,
  `ruleEvaluationState`. Rule targets: Favourites, Playlist, Album, Artist, Genre,
  Recently added (N albums), Most played (N), Recently played (N); optional per-rule profile and
  `maxTracks`.
- Settings live in `AppSettings.downloads` (`DownloadSettings`): Wi-Fi only, charging only, storage
  cap, default profile, remove-when-unmatched, LRU eviction, location, offline mode, prefer
  downloaded, evaluation interval, concurrency.

## Behaviour

- `download()` skips tracks already present at the same or better quality (by
  `TranscodeProfile.qualityRank`); a higher-quality request replaces the file.
- Pinned (user-requested) downloads are never removed by rules or eviction.
- Rules are re-evaluated: after a library sync completes, when rules change, when download
  settings change, when the active server changes (all debounced 30 s; a rule edit evaluates
  immediately), and periodically (`ruleEvaluationHours`).
- Original-quality downloads resume with HTTP `Range`; transcoded ones restart.
- The drainer runs as a foreground worker with a progress notification (channel "Downloads").

## Startup

The repository starts its observers when first injected. Inject `DownloadRepository` early
(e.g. in `Application` or the playback service) so scheduling and triggers are live.

## TODO

- Migrate files when `DownloadSettings.location` changes.
- Per-server rule evaluation for non-active servers.
- Expose per-download speed/ETA.

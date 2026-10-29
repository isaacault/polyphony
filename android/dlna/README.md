# core:dlna — UPnP / DLNA renderers

A small, dependency-free UPnP AV control point: SSDP discovery over UDP, device descriptions over
HTTP, SOAP actions over OkHttp, and a Media3 `Player` (`DlnaPlayer`, built on `SimpleBasePlayer`)
that the playback service can swap into its `MediaSession` exactly like `CastPlayer`.

```
SsdpDiscovery ──▶ DeviceDescriptionClient ──▶ UpnpDevice (AVTransport / RenderingControl / ConnectionManager)
                                                   │
DlnaManager (DlnaController) ── connect(udn) ──▶ RendererTransport ──▶ DlnaPlaybackEngine<MediaItem> ──▶ DlnaPlayer
```

## Integration (playback module)

1. Inject `DlnaManager` and, when the `PlaybackService` starts:
   ```kotlin
   dlnaManager.setMediaResolver { item, renderer -> /* see below */ }
   dlnaManager.setSessionListener(object : DlnaSessionListener {
       override fun onDlnaSessionAvailable(player: DlnaPlayer, renderer: DlnaRenderer) {
           // copy queue, index, position, playWhenReady, shuffle/repeat from the current player
           // mediaSession.setPlayer(player); localPlayer.stop()
       }
       override fun onDlnaSessionUnavailable() {
           // read player.engineState / player.currentPosition and move back to ExoPlayer
       }
   })
   ```
   `onDlnaSessionUnavailable` is delivered before the `DlnaPlayer` is released, on the main thread.
2. **Resolver**: return a `ResolvedStream` for the item using the transcoding decision for
   `OutputTarget.Dlna(renderer.udn, renderer.friendlyName, renderer.modelName)` (its `key` is
   `dlna:<udn>`; per-device profiles are stored under that key by `TranscodeProfileRepository`,
   which `DlnaManager` already populates via `rememberRemoteDevice`). Check the chosen codec against
   `RendererFormatSupport.from(renderer.supportedMimeTypes)`: if `!support.supports(mime)`, fall
   back to the first of `support.preferredCodecs` (MP3 is the universal safe choice). Include the
   artwork URL and duration — many renderers show them.
3. The player exposes `renderer`, `engineState`, and standard `Player` APIs; device volume maps to
   RenderingControl (`setDeviceVolume(0..100)`), shuffle/repeat are handled by the engine, and
   `seekToNext/Previous` follow the engine's shuffle order.
4. UI: observe `DlnaController.renderers` while a picker is open (`startDiscovery()` /
   `stopDiscovery()` are reference counted), call `connect(udn)` / `disconnect()`, and show
   `DlnaController.state` next to Cast state. Call `DlnaManager.onNetworkChanged()` when Wi-Fi
   changes.

## Renderer quirks handled

- Devices that do not implement `SetNextAVTransportURI` (or reject it): first failure disables it
  and end-of-track is detected from `STOPPED` near the known duration (or after 5 s of play when
  the duration is unknown).
- Devices that cannot `Pause` (error 701): we `Stop` and re-cue at the saved position on resume.
- `Seek` right after `Play` is retried once after 600 ms (some renderers refuse until PLAYING).
- Position/transport mismatches within 1.8 s of our own command are ignored to avoid flapping;
  pause/play/stop performed on the device itself are mirrored into the state after that window.
- `GetPositionInfo` values like `NOT_IMPLEMENTED`, `00:03:25.5`, `0:00:10/1` and missing
  `TrackDuration` are tolerated; durations fall back to what the resolver reported.
- Relative `controlURL`s with or without `URLBase`, nested (embedded) renderer devices, BOMs and
  junk descriptions, PNG-vs-JPEG icon choice, multicast join failures (search-only fallback),
  renderers that disappear (`byebye` / expiry) while connected are kept until disconnect.
- Five consecutive polling failures surface a `PlaybackException` instead of a silent hang.

## Not implemented

- GENA event subscriptions (we poll once per second instead; cheaper to reason about and works on
  every renderer we know of).
- Multi-instance renderers (InstanceID is always 0) and playlists pushed as a whole (`SetAVTransportURI`
  per track plus `SetNext` is the broadly compatible approach).

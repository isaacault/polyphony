# Polyphony for Apple platforms

The Mac app, in SwiftUI (macOS 14+), with iPhone and Apple TV to follow on the same code.

## Run it

On a Mac with Xcode 16 or later:

```
brew install xcodegen
cd apple
xcodegen                      # writes Polyphony.xcodeproj from project.yml
open Polyphony.xcodeproj      # then Run (⌘R)
```

Sign in with your continuo address (`https://continuo.ault.me`), a Jellyfin server, or any Subsonic
server such as a plain Navidrome; the app works out which it is (Settings > Servers adds more and switches between
them). The build is signed to run locally; macOS may ask once to let it use the Keychain item it saves
your sign-ins in, and once for Local Network access when it looks for DLNA speakers.

To play to a speaker (the Yamaha) or a Chromecast, pick it from the speaker menu in the player bar.
They fetch the music themselves and many can't do HTTPS: continuo hands out its plain-HTTP LAN address
(`RECEIVER_BASE_URL`), or set one in Settings > Servers > Speakers.

Something wrong? Help > Save Diagnostics writes what's set up and the recent log (no passwords or
tokens) to a file to send.

## Layout

- `PolyphonyKit/`: the Swift package with everything that isn't UI: `MusicLibrary` with its Subsonic
  and Jellyfin clients, the continuo client, sign-in, token refresh, backup and the server list, the
  library and catalogue models, the play queue and the player controller (which drives an `AudioEngine`
  protocol, scrobbles, levels volume with ReplayGain and keeps listening history), DLNA (SSDP, SOAP, and
  an engine that follows gapless hand-offs on a speaker), Cast (the CASTV2 protocol and an engine),
  streaming rules (profiles, networks, home, adaptive quality, fetching ahead), download rules and the
  storage limit, and the diagnostics log. No AppKit/UIKit, so it builds and tests on
  Linux: `PolyphonyKit/scripts/ci.sh` (Swift 6.2 in Docker). Rules both apps must agree on (artist
  credits, quality chips, resuming, quality rules) are tested from the same JSON in `contract/cases`.
- `Polyphony/`: the Mac app.
  - `App/`: the app, its menus (Controls: play/pause, next, previous, shuffle, repeat, volume), and
    `AppModel` (who's signed in, the clients, the player).
  - `Platform/`: the Apple pieces PolyphonyKit can't hold: the AVFoundation engine (gapless through an
    AVQueuePlayer), Now Playing and the media keys, the Keychain store, downloads, the stream cache,
    speaker discovery (a UDP socket), Cast discovery and its TLS socket, the network and power watchers,
    keeping the Mac awake, arrival notifications, the AirPlay button.
  - `Views/`: sidebar (Home, Search, Artists, Albums, Songs, Playlists, Genres, Favourites, Downloads,
    Inbox, Requests, Recently Removed), album, artist, playlist, genre and catalogue pages, the player
    bar, the queue and lyrics panes, Now Playing, Settings.
- `project.yml`: the Xcode project, for XcodeGen. The generated project isn't committed.

## Status

Mostly written on Linux, without a Mac to build on: PolyphonyKit is tested there
(`PolyphonyKit/scripts/ci.sh`); the app's model, downloads and stream cache type-check against it
(`scripts/typecheck-app.sh`, with stand-ins for the Apple-only classes in `scripts/linux-stand-ins`);
the views and the Apple glue are syntax-checked (`swiftc -parse`) only. The app was last built and run
in Xcode on 2026-10-30, per the fixes reported from those builds. What came after (speakers' gapless hand-offs,
streaming rules, Jellyfin, Cast, the settings) hasn't been: expect the next build to need small fixes,
and Jellyfin and Cast have only met scripted servers, not real ones.

Not on the Mac yet, which Android has: custom HTTP headers, trusting self-signed certificates and legacy
authentication for a server; skip silence (AVPlayer can't); hiding compilations under artists; a chosen
download location; the built-in equaliser (Android's other choice, the phone's own effects panel, a Mac
doesn't have). Library sync isn't needed, as the Mac reads the server live; swipe gestures, haptics and
Android Auto are the phone's.

Once `shared/` (Kotlin Multiplatform) produces its Apple framework, the Subsonic and continuo code in
PolyphonyKit gives way to it; the Apple glue stays.

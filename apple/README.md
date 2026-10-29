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

Sign in with your continuo address (`https://continuo.ault.me`), or with any Subsonic server such as
a plain Navidrome; the app works out which it is (Settings > Servers adds more and switches between
them). The build is signed to run locally; macOS may ask once to let it use the Keychain item it saves
your sign-ins in, and once for Local Network access when it looks for DLNA speakers.

To play to a speaker (the Yamaha), pick it from the speaker menu in the player bar. Speakers fetch the
music themselves and many can't do HTTPS: continuo hands out its plain-HTTP LAN address
(`RECEIVER_BASE_URL`), or set one in Settings > Speakers.

Something wrong? Help > Save Diagnostics writes what's set up and the recent log (no passwords or
tokens) to a file to send.

## Layout

- `PolyphonyKit/`: the Swift package with everything that isn't UI: the Subsonic and continuo clients,
  sign-in, token refresh and the server list, the library and catalogue models, the play queue and the
  player controller (which drives an `AudioEngine` protocol, scrobbles, levels volume with ReplayGain
  and keeps listening history), DLNA (SSDP, SOAP, and an engine that plays on a speaker), download
  rules and the storage limit, and the diagnostics log. No AppKit/UIKit, so it builds and tests on
  Linux: `PolyphonyKit/scripts/ci.sh` (Swift 6.2 in Docker). Rules both apps must agree on (artist
  credits, quality chips, resuming) are tested from the same JSON in `contract/cases`.
- `Polyphony/`: the Mac app.
  - `App/`: the app, its menus (Controls: play/pause, next, previous, shuffle, repeat, volume), and
    `AppModel` (who's signed in, the clients, the player).
  - `Platform/`: the Apple pieces PolyphonyKit can't hold: the AVFoundation engine (gapless through an
    AVQueuePlayer), Now Playing and the media keys, the Keychain store, downloads, speaker discovery
    (a UDP socket), arrival notifications, the AirPlay button.
  - `Views/`: sidebar (Home, Search, Artists, Albums, Songs, Playlists, Genres, Favourites, Downloads,
    Inbox, Requests, Recently Removed), album, artist, playlist, genre and catalogue pages, the player
    bar, the queue and lyrics panes, Now Playing, Settings.
- `project.yml`: the Xcode project, for XcodeGen. The generated project isn't committed.

## Status

Written without a Mac to build on: PolyphonyKit is tested (Linux), the app code is syntax-checked
(`swiftc -parse`) but has not been compiled or run. Expect a first build to need small fixes.

Once `shared/` (Kotlin Multiplatform) produces its Apple framework, the Subsonic and continuo code in
PolyphonyKit gives way to it; the Apple glue stays.

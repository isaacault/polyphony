# Polyphony for Apple platforms

To come: one Xcode project with macOS, iOS and tvOS targets in SwiftUI. It needs Xcode on a Mac.

`PolyphonyKit/` is a Swift package written before this repo became the home of every client
(it began as the separate `polyphony-apple` repo). It has a Subsonic client, continuo sign-in with
single-flight token refresh, the library models and the quality badges, with 22 tests that run on Linux
(`apple/PolyphonyKit/scripts/ci.sh`, Swift 6.2 in Docker).

It's transitional. Once `shared/` is Kotlin Multiplatform, the Apple apps use the shared logic as a
framework, and the Subsonic and continuo code here goes. What stays is the Apple glue the shared code
can't provide: the URLSession transport, a Keychain session store, and the AVFoundation player with
Now Playing and media keys.

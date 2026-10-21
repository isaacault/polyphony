# Local end-to-end testing

A full loop that needs no real server or phone: a generated music library, a local Navidrome,
and a headless emulator. This is what the screenshots in the README came from.

## 1. Generate a tagged test library

Requires `ffmpeg` (`brew install ffmpeg`). Each call makes one album of sine-tone tracks with
proper tags and a solid-colour `cover.jpg`:

```sh
T=$HOME/polyphony-testbed; mkdir -p "$T/music" && cd "$T/music"
gen() { artist="$1" album="$2" year="$3" genre="$4" color="$5" fmt="$6"; shift 6
  dir="$artist/$album"; mkdir -p "$dir"
  ffmpeg -loglevel error -y -f lavfi -i "color=c=$color:s=600x600:d=1" -frames:v 1 "$dir/cover.jpg"
  n=1; for title in "$@"; do
    ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=$((220+n*55)):duration=$((18+n*3))" -ac 2 -ar 44100 \
      $([ "$fmt" = flac ] && echo "-c:a flac" || echo "-c:a libmp3lame -b:a 192k") \
      -metadata title="$title" -metadata artist="$artist" -metadata album_artist="$artist" \
      -metadata album="$album" -metadata track="$n" -metadata genre="$genre" -metadata date="$year" \
      "$dir/$(printf %02d $n) - $title.$fmt"; n=$((n+1)); done; }
gen "Aurora Lane" "Night Signals" 2021 Electronic 0x2b3a67 flac Overture "Neon Tide" Afterglow
gen "Mira Solano" "Cantos" 2020 Jazz 0x9b2226 flac "Prelude in Blue" "Cantos I" Coda
```

## 2. Run Navidrome

```sh
brew install navidrome
ND_MUSICFOLDER=$T/music ND_DATAFOLDER=$T/navidrome ND_ADDRESS=127.0.0.1 ND_PORT=4533 \
  ND_SCANSCHEDULE=0 ND_ENABLEINSIGHTSCOLLECTOR=false navidrome &
curl -s -X POST http://127.0.0.1:4533/auth/createAdmin -H 'Content-Type: application/json' \
  -d '{"username":"polyphony","password":"polyphony"}'
```

## 3. Emulator

```sh
source scripts/env.sh
sdkmanager --sdk_root="$ANDROID_HOME" emulator "system-images;android-36;google_apis;arm64-v8a" "cmdline-tools;latest"
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n polyphony -d pixel_8 \
  -k "system-images;android-36;google_apis;arm64-v8a"
# avdmanager may put the AVD under ~/.config/.android/avd; point the emulator at it:
ANDROID_AVD_HOME=$HOME/.config/.android/avd "$ANDROID_HOME/emulator/emulator" -avd polyphony \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.polyphony.debug/app.polyphony.MainActivity
```

In the app, connect to `http://10.0.2.2:4533` with `polyphony` / `polyphony` (10.0.2.2 is the host
loopback from inside the emulator).

Handy checks:

```sh
adb exec-out screencap -p > shot.png                       # screenshot
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml   # semantics tree
adb shell dumpsys media_session | grep -A2 polyphony       # playback state
adb logcat -d | grep -c "FATAL EXCEPTION"                  # crashes
adb shell 'run-as app.polyphony.debug ls -laR files/downloads'   # downloaded files
```

Cast and DLNA cannot be exercised on the emulator; use a physical device on the same Wi-Fi as
the receiver for those.

## 4. Large-library bed (50k tracks)

Performance work needs more than the hand-made albums above. `scripts/testbed/gen_large_library.py`
writes a tagged MP3 library of any size in seconds (one ffmpeg render, tags written directly), and a
second Navidrome instance serves it on another port so the small bed stays intact:

```sh
python3 scripts/testbed/gen_large_library.py "$T/music-large" 1000 5 10   # 1,000 artists × 5 albums × 10 tracks
mkdir -p "$T/navidrome-large"
ND_MUSICFOLDER="$T/music-large" ND_DATAFOLDER="$T/navidrome-large" ND_ADDRESS=127.0.0.1 ND_PORT=4534 \
  ND_SCANSCHEDULE=0 ND_ENABLEINSIGHTSCOLLECTOR=false navidrome &
curl -s -X POST http://127.0.0.1:4534/auth/createAdmin -H 'Content-Type: application/json' \
  -d '{"username":"polyphony","password":"polyphony"}'
```

Navidrome scans the 50k files in about a minute. Add the server in the app as `http://10.0.2.2:4534`;
a full sync takes about 20 s on the emulator. Reference numbers from this bed are kept on issue #9.

Measuring: `uiautomator dump` costs ~0.4 s per call, so do not time screens with dump loops. Add
temporary `Log.i` lines (or `SideEffect { Log.i(...) }` in a composable) and read logcat
timestamps; `adb shell dumpsys gfxinfo <package> reset` before a scroll and `dumpsys gfxinfo
<package>` after gives frame percentiles. The debug build installs as `app.polyphony.debug`; its
Room database is `/data/data/app.polyphony.debug/databases/polyphony-library.db` (read it as root
with `sqlite3`, and spell the name exactly, because `sqlite3` creates any path you mistype).

## 5. Database migrations

Room schemas are exported to `core/data/schemas/<db>/<version>.json`; commit the new file with every
version bump together with a `Migration` in `Migrations.ALL`. `MigrationsChainTest` (plain unit
test, runs in CI) fails if the chain has a gap. `MigrationsTest` migrates a real database from the
oldest schema and lets Room validate the result; it needs the emulator:

```sh
./gradlew :core:data:connectedDebugAndroidTest
```

Instrumented tests in any module run on demand from the Actions tab (`Connected tests` workflow),
or locally against an emulator with the command above. Anything that depends on Android's own platform classes (XML factories, DataStore,
Room) needs a test there, because the JVM stand-ins behave differently.

## 6. Merging

CI runs locally. `scripts/ci.sh` runs what the old `Build` workflow ran (`./gradlew test lintDebug
:app:assembleDebug`) inside the `polyphony-build` Docker image and exits 0 when green; the log is
`build/local-ci/<sha>.log`. It works from any checkout or git worktree, on x86_64 and on arm64 (the
image runs Google's x86_64-only aapt2 under qemu there; see the Dockerfile). Runs are serialised
machine-wide with an exclusive `flock` on `/ssd/Personal/repositories/.locks/docker-ci.lock`
(`CI_LOCK`), shared with any other Docker CI on the host, and wait for 1.5 GB of free memory
(`CI_MIN_MEM_MB`).

`scripts/ci.sh --status` does the same on a clean, pushed HEAD and posts the result as the
`local-ci` commit status, which is what a PR shows as its check. A PR must be green on its current
head before it merges: `scripts/merge_when_green.sh <pr>` squash-merges only when `local-ci` is
`success` on that exact commit (the merge is pinned to it), runs `ci.sh --status` itself in a
throwaway worktree when the head has no status yet, and refuses on a failed or missing one.

GitHub Actions now runs `Build` only for pull requests from forks and on manual dispatch, so outside
contributors still get CI without spending minutes on everyday work.

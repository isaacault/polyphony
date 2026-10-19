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

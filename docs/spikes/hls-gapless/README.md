# Spike: gapless Opus over HLS (2026-11-11)

Question: if continuo streams each song as HLS (Opus in fMP4 segments, several bitrates, so the
player can change quality mid-song), do albums stay gapless in Polyphony (Media3 1.11.1)?

Answer: **yes, if continuo rewrites each rendition's init segment** (two small edits, below).
Proved on the JVM with Media3's own `FragmentedMp4Extractor` (what `HlsMediaSource` uses for fMP4)
in `android/playback/src/test/.../hls/HlsGaplessSpikeTest.kt`. Not yet heard on a device.

## Fixtures
`make.sh` (ffmpeg 9.0.2 in `linuxserver/ffmpeg`): one continuous 440/660 Hz tone at 44.1 kHz cut
sample-exactly into three tracks, each encoded as Ogg Opus (what Navidrome streams today) and as HLS
fMP4, Opus and AAC, 64 and 128 kbps, 6 s segments. `inspect.py out` prints what ffmpeg wrote;
`inject.py out` writes the `*_gapless_init.mp4` continuo would serve.

## What ffmpeg's HLS output carries
| | start | end | edit list |
|---|---|---|---|
| Opus fMP4 | `dOps` PreSkip 312 | last sample's duration shortened to the exact end | empty (0, 0) |
| AAC fMP4 | 1024 priming samples, **not marked** | exact | empty (0, 0) |

AAC is out (every song would start with ~23 ms of priming). Opus keeps everything, but:

1. **A decoder outputs whole 20 ms packets**, and Media3 trims the end only from
   `Format.encoderPadding`. ffmpeg states no padding.
2. **Media3 1.11.1 misreads `dOps`.** `BoxParser` builds a little-endian OpusHead by copying the
   big-endian `dOps` body, so PreSkip 312 (`0x0138`) is read as 14337 (`0x3801`): ~0.3 s cut from
   the start of every song. Known upstream as androidx/media#3431, fixed on main by c2061b8
   (2026-10-01, extractor and muxer) but not in 1.11.1; a zeroed PreSkip reads the same before and
   after the fix, so the workaround below stays correct when Polyphony upgrades.

## What continuo does to each init segment (`inject.py`)
- Zero `dOps` PreSkip (reads the same in either byte order), so the decoder skips nothing.
- Add `moov/udta/meta/ilst/----` `com.apple.iTunes` `iTunSMPB` =
  ` 00000000 <delay> <padding> <samples> …` with delay = ffmpeg's PreSkip (312) and
  padding = packets × 960 − the segments' declared sample total. `FragmentedMp4Extractor` reads it
  through `GaplessInfoHolder` (HLS passes no sideloaded track, so `moov` is parsed), and
  `DefaultAudioSink`'s `TrimmingAudioProcessor` trims both ends.
- Merge into ffmpeg's existing `udta/meta/ilst` (it already writes `©too`); a second `meta` box may
  be ignored.

## Results (HlsGaplessSpikeTest)
- t1/t2/t3 play 480041 / 480299 / 479662 samples vs 480040.27 / 480298.23 / 479661.50 expected
  (resampling 44.1 → 48 kHz per track; within one sample).
- Joined: 1440002 samples vs 1440000 for the uncut tone (0.04 ms over two joins).
- ffmpeg's own init: Media3 reads PreSkip 14337, every song 279.5 ms short.
- 64 and 128 kbps renditions have identical packets and trims, so a mid-song switch keeps the join.

## Side finding
Media3's Ogg reader does not trim the end from the final granule position either, so today's Ogg
Opus transcodes (Navidrome) likely end each song with up to 20 ms of padding. The HLS path above is
exact.

## Still to confirm on a device
A listen across a gapless album over HLS (the decoder and sink behave as the source code says), and
a quality switch mid-song with no click.

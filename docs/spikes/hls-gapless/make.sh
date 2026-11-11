#!/usr/bin/env bash
# Gapless spike fixtures: one continuous tone cut sample-exactly into three tracks, then encoded
# the way Navidrome streams today (Ogg Opus) and the way continuo would (HLS, fMP4 segments).
set -euo pipefail
cd "$(dirname "$0")"  # writes ./out
rm -rf out && mkdir -p out
ff() { docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/w" -w /w --entrypoint ffmpeg linuxserver/ffmpeg -hide_banner -loglevel error -y "$@"; }

# 30 s of 44.1 kHz stereo: 440 Hz left, 660 Hz right. Cuts at odd sample positions.
ff -f lavfi -i "sine=f=440:r=44100:d=30" -f lavfi -i "sine=f=660:r=44100:d=30" \
   -filter_complex "[0][1]amerge=inputs=2" -c:a flac out/whole.flac
cuts=(0 441037 882311 1323000)
for i in 1 2 3; do
  s=${cuts[$((i-1))]}; e=${cuts[$i]}
  ff -i out/whole.flac -af "atrim=start_sample=$s:end_sample=$e,asetpts=N/SR/TB" -c:a flac out/t$i.flac
  # Today: progressive Ogg Opus, as Navidrome transcodes.
  ff -i out/t$i.flac -c:a libopus -b:a 128k -f ogg out/t$i.opus
  # continuo HLS candidates: Opus and AAC in fMP4 segments of 6 s, at two levels each.
  for br in 64 128; do
    ff -i out/t$i.flac -c:a libopus -b:a ${br}k -f hls -hls_time 6 -hls_playlist_type vod \
       -hls_segment_type fmp4 -hls_fmp4_init_filename t${i}_opus${br}_init.mp4 \
       -hls_segment_filename "out/t${i}_opus${br}_%d.m4s" out/t${i}_opus${br}.m3u8
    ff -i out/t$i.flac -c:a aac -b:a ${br}k -f hls -hls_time 6 -hls_playlist_type vod \
       -hls_segment_type fmp4 -hls_fmp4_init_filename t${i}_aac${br}_init.mp4 \
       -hls_segment_filename "out/t${i}_aac${br}_%d.m4s" out/t${i}_aac${br}.m3u8
  done
done
ls out | wc -l

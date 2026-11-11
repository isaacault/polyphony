"""Write iTunSMPB gapless info into each Opus HLS init segment, as continuo would after ffmpeg.

ffmpeg's fMP4 HLS output keeps Opus pre-skip (dOps) and trims the last sample's duration, but a
player decodes whole 20 ms packets, so the tail padding must be stated: padding = packets x 960 -
the declared total. Delay is 0: the Opus decoder applies pre-skip itself.

Usage: python3 -I inject.py <out dir>   (writes <name>_gapless_init.mp4 next to each Opus init)
"""
import glob
import json
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from inspect_lib import init_facts, segment_samples  # noqa: E402

OPUS_FRAME = 960  # libopus default 20 ms frames at 48 kHz


def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def full_box(kind, payload, version=0, flags=0):
    return box(kind, struct.pack(">I", (version << 24) | flags) + payload)


def gapless_udta(delay, padding, samples):
    text = " 00000000 %08X %08X %016X" % (delay, padding, samples)
    text += " 00000000" * 8
    freeform = box(b"----",
                   full_box(b"mean", b"com.apple.iTunes")
                   + full_box(b"name", b"iTunSMPB")
                   + box(b"data", struct.pack(">II", 1, 0) + text.encode()))
    hdlr = full_box(b"hdlr", struct.pack(">I4s", 0, b"mdir") + b"appl" + b"\0" * 8 + b"\0")
    meta = full_box(b"meta", hdlr + box(b"ilst", freeform))
    return meta


def children(data, start, end):
    i = start
    while i + 8 <= end:
        size, kind = struct.unpack(">I4s", data[i:i + 8])
        yield kind, i, size
        i += size


def inject(init_path, out_path, delay, padding, samples):
    """Add the iTunSMPB freeform entry to moov/udta/meta/ilst, creating what is missing."""
    data = bytearray(open(init_path, "rb").read())
    entry_meta = gapless_udta(delay, padding, samples)          # a whole meta box
    ilst_payload = entry_meta[12 + struct.unpack(">I", entry_meta[12:16])[0] + 8:]  # the ---- box
    moov = next((o, s) for k, o, s in children(data, 0, len(data)) if k == b"moov")
    ancestors = [moov[0]]
    udta = next(((o, s) for k, o, s in children(data, moov[0] + 8, moov[0] + moov[1]) if k == b"udta"), None)
    if udta is None:
        insert_at, payload = moov[0] + moov[1], box(b"udta", entry_meta)
    else:
        ancestors.append(udta[0])
        meta = next(((o, s) for k, o, s in children(data, udta[0] + 8, udta[0] + udta[1]) if k == b"meta"), None)
        if meta is None:
            insert_at, payload = udta[0] + udta[1], entry_meta
        else:
            ancestors.append(meta[0])
            ilst = next(((o, s) for k, o, s in children(data, meta[0] + 12, meta[0] + meta[1]) if k == b"ilst"), None)
            if ilst is None:
                insert_at, payload = meta[0] + meta[1], box(b"ilst", ilst_payload)
            else:
                ancestors.append(ilst[0])
                insert_at, payload = ilst[0] + ilst[1], ilst_payload
    data[insert_at:insert_at] = payload
    # Media3 1.11.1 copies dOps (big-endian, ISO BMFF Opus) into a little-endian OpusHead, so a
    # PreSkip of 312 (0x0138) is read as 14337. Zero reads the same both ways: the delay goes in
    # iTunSMPB instead and DefaultAudioSink trims it.
    dops = data.find(b"dOps", 0, insert_at)
    assert dops > 0
    data[dops + 4 + 2:dops + 4 + 4] = b"\0\0"
    for off in ancestors:
        size = struct.unpack(">I", data[off:off + 4])[0]
        data[off:off + 4] = struct.pack(">I", size + len(payload))
    open(out_path, "wb").write(bytes(data))


def main(out):
    report = {}
    for init in sorted([p for p in glob.glob(os.path.join(out, "t?_opus*_init.mp4")) if "gapless" not in p]):
        name = os.path.basename(init)[:-len("_init.mp4")]
        facts = init_facts(init)
        segs = sorted(glob.glob(os.path.join(out, name + "_*.m4s")),
                      key=lambda s: int(re.findall(r"_(\d+)\.m4s$", s)[0]))
        packets, declared = 0, 0
        for s in segs:
            _, n, d = segment_samples(s, facts.get("trex_default_duration", 0))
            packets += n
            declared += d
        preskip = facts["opus_preskip"]
        padding = packets * OPUS_FRAME - declared
        content = declared - preskip
        inject(init, os.path.join(out, name + "_gapless_init.mp4"), preskip, padding, content)
        report[name] = {"packets": packets, "declared": declared, "preskip": preskip,
                        "padding": padding, "content": content}
        print(name, report[name])
    json.dump(report, open(os.path.join(out, "gapless.json"), "w"), indent=1)


main(sys.argv[1])

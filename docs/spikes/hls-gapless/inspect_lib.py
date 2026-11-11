"""Gapless facts in the spike's HLS fMP4 output: edit lists, Opus pre-skip, sample totals.

Usage: python3 -I inspect.py <out dir>
"""
import glob
import os
import re
import struct
import sys

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"moof", b"traf", b"mvex", b"udta"}
CUTS = [0, 441037, 882311, 1323000]


def boxes(data, start=0, end=None):
    end = len(data) if end is None else end
    i = start
    while i + 8 <= end:
        size, kind = struct.unpack(">I4s", data[i:i + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[i + 8:i + 16])[0]
            header = 16
        elif size == 0:
            size = end - i
        yield kind, i + header, i + size
        i += size


def walk(data, start=0, end=None, path=()):
    for kind, body, stop in boxes(data, start, end):
        yield path + (kind,), body, stop
        if kind in CONTAINERS:
            yield from walk(data, body, stop, path + (kind,))
        elif kind == b"stsd":
            # full box + entry count, then sample entries
            yield from walk_entries(data, body + 8, stop, path + (kind,))


def walk_entries(data, start, end, path):
    for kind, body, stop in boxes(data, start, end):
        yield path + (kind,), body, stop
        # audio sample entry: 28 bytes after the box header before child boxes
        yield from walk(data, body + 28, stop, path + (kind,))


def init_facts(path):
    data = open(path, "rb").read()
    facts = {"elst": None}
    for p, body, stop in walk(data):
        k = p[-1]
        if k == b"mdhd":
            v = data[body]
            facts["timescale"] = struct.unpack(">I", data[body + (20 if v == 1 else 12):][:4])[0]
        elif k == b"elst":
            v = data[body]
            n = struct.unpack(">I", data[body + 4:body + 8])[0]
            entries = []
            off = body + 8
            for _ in range(n):
                if v == 1:
                    dur, mt = struct.unpack(">Qq", data[off:off + 16]); off += 16
                else:
                    dur, mt = struct.unpack(">Ii", data[off:off + 8]); off += 8
                rate = struct.unpack(">hh", data[off:off + 4]); off += 4
                entries.append((dur, mt, rate))
            facts["elst"] = entries
        elif k == b"mvhd":
            v = data[body]
            facts["movie_timescale"] = struct.unpack(">I", data[body + (20 if v == 1 else 12):][:4])[0]
        elif k == b"dOps":
            ver, ch, preskip, rate, gain = struct.unpack(">BBHIh", data[body:body + 10])
            facts["opus_preskip"] = preskip
            facts["opus_input_rate"] = rate
        elif k == b"trex":
            facts["trex_default_duration"] = struct.unpack(">I", data[body + 12:body + 16])[0]
        elif k == b"sgpd" or k == b"sbgp":
            facts.setdefault("roll_groups", []).append(k.decode())
    return facts


def segment_samples(path, default_duration):
    data = open(path, "rb").read()
    total, count, tfdt = 0, 0, None
    for p, body, stop in walk(data):
        k = p[-1]
        if k == b"tfdt":
            v = data[body]
            tfdt = struct.unpack(">Q" if v == 1 else ">I", data[body + 4:body + (12 if v == 1 else 8)])[0]
        elif k == b"tfhd":
            flags = int.from_bytes(data[body + 1:body + 4], "big")
            off = body + 8
            if flags & 0x1: off += 8
            if flags & 0x2: off += 4
            if flags & 0x8:
                default_duration = struct.unpack(">I", data[off:off + 4])[0]
        elif k == b"trun":
            flags = int.from_bytes(data[body + 1:body + 4], "big")
            n = struct.unpack(">I", data[body + 4:body + 8])[0]
            off = body + 8
            if flags & 0x1: off += 4
            if flags & 0x4: off += 4
            for _ in range(n):
                d = default_duration
                if flags & 0x100:
                    d = struct.unpack(">I", data[off:off + 4])[0]; off += 4
                if flags & 0x200: off += 4
                if flags & 0x400: off += 4
                if flags & 0x800: off += 4
                total += d
                count += 1
    return tfdt, count, total


def main(out):
    for m3u8 in sorted(glob.glob(os.path.join(out, "t?_*.m3u8"))):
        name = os.path.basename(m3u8)[:-5]
        track = int(name[1])
        source = CUTS[track] - CUTS[track - 1]
        f = init_facts(os.path.join(out, name + "_init.mp4"))
        ts = f.get("timescale")
        segs = sorted(glob.glob(os.path.join(out, name + "_*.m4s")), key=lambda s: int(re.findall(r"_(\d+)\.m4s$", s)[0]))
        total = 0
        first_tfdt = None
        for s in segs:
            tfdt, n, dur = segment_samples(s, f.get("trex_default_duration", 0))
            first_tfdt = tfdt if first_tfdt is None else first_tfdt
            total += dur
        expected = round(source * ts / 44100)
        print(f"{name}: timescale {ts}, source {source} smp@44.1k = {expected} @{ts}; "
              f"coded {total} (+{total - expected}); first tfdt {first_tfdt}; elst {f['elst']}; "
              f"preskip {f.get('opus_preskip')}; groups {f.get('roll_groups')}")



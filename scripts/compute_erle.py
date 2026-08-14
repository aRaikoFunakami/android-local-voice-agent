#!/usr/bin/env python3
"""raw_capture.pcm / aec_output.pcm から ERLE を算出する（Issue #16）。

ERLE = 10*log10( E[raw^2] / E[aec^2] ) を far-end アクティブ窓（raw RMS が
閾値超）に限って集計する。48kHz mono int16 生 PCM を前提とする。

使い方:
  adb pull .../raw_capture.pcm .../aec_output.pcm .
  python3 scripts/compute_erle.py raw_capture.pcm aec_output.pcm [--settle 5.0]
"""
import argparse
import math
import struct
import sys

def read_pcm(path):
    with open(path, "rb") as f:
        d = f.read()
    return struct.unpack("<%dh" % (len(d) // 2), d)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("aec")
    ap.add_argument("--rate", type=int, default=48000)
    ap.add_argument("--settle", type=float, default=5.0,
                    help="AEC 収束待ち（先頭を集計から除外）秒")
    ap.add_argument("--active-rms", type=float, default=300.0,
                    help="far-end アクティブ判定の raw RMS 閾値")
    args = ap.parse_args()

    raw = read_pcm(args.raw)
    aec = read_pcm(args.aec)
    n = min(len(raw), len(aec))
    win = args.rate // 10  # 100ms
    start = int(args.settle * args.rate)

    in_e = out_e = 0.0
    active = total = 0
    for i in range(start, n - win, win):
        total += 1
        rw = raw[i:i + win]
        rms = math.sqrt(sum(x * x for x in rw) / win)
        if rms < args.active_rms:
            continue
        active += 1
        in_e += sum(float(x) * x for x in rw)
        aw = aec[i:i + win]
        out_e += sum(float(x) * x for x in aw)

    if active == 0 or out_e == 0:
        print(f"no active windows (total={total}); check inputs")
        sys.exit(1)
    erle = 10.0 * math.log10(in_e / out_e)
    print(f"windows: {active}/{total} active, settle={args.settle}s")
    print(f"ERLE = {erle:.1f} dB")

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Synthesizes the meditation bell, so the app ships no third-party audio."""
import math, struct, sys, wave

RATE, SECONDS = 22050, 7
# Frequency ratio, loudness and decay time of each partial of a singing bowl.
PARTIALS = [(1.0, 1.0, 2.6), (2.76, 0.42, 1.6), (5.4, 0.16, 0.8), (1.004, 0.5, 2.3)]
BASE_HZ = 196.0

with wave.open(sys.argv[1], "wb") as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(RATE)
    total = RATE * SECONDS
    for n in range(total):
        t = n / RATE
        s = sum(a * math.exp(-t / d) * math.sin(2 * math.pi * BASE_HZ * r * t) for r, a, d in PARTIALS)
        fade = min(1.0, t / 0.008) * min(1.0, (total - n) / (RATE * 0.5))
        out.writeframes(struct.pack("<h", int(max(-1, min(1, s * 0.42 * fade)) * 32767)))

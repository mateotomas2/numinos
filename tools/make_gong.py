#!/usr/bin/env python3
"""Synthesizes the altar's gong, so the app ships no third-party audio."""
import math, struct, sys, wave

RATE, SECONDS = 22050, 9
# Frequency ratio, loudness and decay time of each partial. The uneven ratios give a gong its shimmer.
PARTIALS = [(1.0, 1.0, 3.6), (1.012, 0.6, 3.1), (1.48, 0.5, 2.6), (2.13, 0.4, 2.0), (2.91, 0.26, 1.4), (4.17, 0.14, 0.8)]
BASE_HZ = 98.0

with wave.open(sys.argv[1], "wb") as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(RATE)
    total = RATE * SECONDS
    for n in range(total):
        t = n / RATE
        s = sum(a * math.exp(-t / d) * math.sin(2 * math.pi * BASE_HZ * r * t) for r, a, d in PARTIALS)
        fade = min(1.0, t / 0.02) * min(1.0, (total - n) / (RATE * 0.5))
        out.writeframes(struct.pack("<h", int(max(-1, min(1, s * 0.34 * fade)) * 32767)))

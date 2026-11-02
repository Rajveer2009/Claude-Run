#!/usr/bin/env python3
"""Synthesizes Claude Run's sound effects into app/src/main/res/raw/.

Everything is generated from sine/triangle tones and filtered noise, so there are no
third-party audio assets. Re-run after tweaking:  python3 tools/gen_sounds.py
"""
import math
import os
import random
import struct
import wave

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def buf(seconds):
    return [0.0] * int(SR * seconds)


def tone(out, start, dur, f0, f1=None, decay=8.0, attack=0.004, harmonics=((1, 1.0),), vibrato=0.0):
    """Adds a gliding tone (f0 -> f1, exponential) with a fast attack and exponential decay."""
    f1 = f1 or f0
    p = 0.0
    n0 = int(start * SR)
    for i in range(int(dur * SR)):
        t = i / SR
        f = f0 * (f1 / f0) ** (t / dur)
        if vibrato:
            f *= 1 + vibrato * math.sin(2 * math.pi * 6 * t)
        p += 2 * math.pi * f / SR
        env = min(1.0, t / attack) * math.exp(-decay * t)
        # short release at the very end avoids clicks
        env *= min(1.0, (dur - t) / 0.01)
        s = sum(a * math.sin(k * p) for k, a in harmonics)
        if 0 <= n0 + i < len(out):
            out[n0 + i] += s * env


def noise(out, start, dur, cutoff0, cutoff1, gain=1.0, decay=10.0):
    """Adds a one-pole low-passed noise sweep (a soft whoosh / thud)."""
    rnd = random.Random(7)
    y = 0.0
    n0 = int(start * SR)
    for i in range(int(dur * SR)):
        t = i / SR
        fc = cutoff0 * (cutoff1 / cutoff0) ** (t / dur)
        a = 1 - math.exp(-2 * math.pi * fc / SR)
        y += a * (rnd.uniform(-1, 1) - y)
        env = min(1.0, t / 0.01) * math.exp(-decay * t) * min(1.0, (dur - t) / 0.01)
        out[n0 + i] += y * env * gain


def write(name, samples, peak):
    m = max(abs(s) for s in samples) or 1.0
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(s / m * peak * 32767)) for s in samples))
    print(f"{path}: {len(samples) / SR:.2f}s")


SOFT = ((1, 1.0), (2, 0.18))
BELL = ((1, 1.0), (2, 0.25), (3, 0.08))
TRI = ((1, 1.0), (3, 1 / 9), (5, 1 / 25))

# jump: quick upward "boing" an octave up
s = buf(0.17)
tone(s, 0, 0.17, 380, 760, decay=14, harmonics=SOFT)
write("sfx_jump", s, 0.55)

# token: two-note chime (B5 -> E6)
s = buf(0.38)
tone(s, 0.0, 0.08, 987.77, decay=20, harmonics=BELL)
tone(s, 0.06, 0.32, 1318.51, decay=11, harmonics=BELL)
write("sfx_token", s, 0.5)

# milestone: rising C-major arpeggio
s = buf(0.75)
for k, f in enumerate((523.25, 659.25, 783.99, 1046.50)):
    tone(s, k * 0.075, 0.75 - k * 0.075, f, decay=6, harmonics=SOFT)
write("sfx_level", s, 0.5)

# crash: soft thud, then a sad descending "womp womp"
s = buf(0.75)
tone(s, 0.0, 0.12, 140, 55, decay=22, harmonics=((1, 1.0),))
noise(s, 0.0, 0.08, 1800, 300, gain=0.6, decay=40)
tone(s, 0.10, 0.20, 392.00, 370, decay=5, harmonics=TRI)
tone(s, 0.30, 0.45, 311.13, 233.08, decay=4, harmonics=TRI, vibrato=0.012)
write("sfx_crash", s, 0.6)

# start / retry: two bright quick notes
s = buf(0.22)
tone(s, 0.0, 0.07, 783.99, decay=18, harmonics=SOFT)
tone(s, 0.06, 0.16, 1174.66, decay=16, harmonics=SOFT)
write("sfx_start", s, 0.45)

# duck / fast fall: short downward whoosh
s = buf(0.16)
noise(s, 0.0, 0.16, 3000, 400, decay=14)
tone(s, 0.0, 0.12, 500, 260, decay=20, harmonics=((1, 0.3),))
write("sfx_duck", s, 0.4)

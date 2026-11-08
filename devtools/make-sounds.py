#!/usr/bin/env python3
"""Makes Joey64's own unlock sounds (Settings > RetroAchievements > Unlock sound), synthesised from
nothing but sine and triangle waves, so they are ours to ship (GPL-3.0-or-later with the app).

    python devtools/make-sounds.py

Writes app/src/main/res/raw/unlock_chime.wav, unlock_ping.wav and unlock_coin.wav: 44.1 kHz, 16-bit
mono, peaks at -3 dBFS (the app's Volume scales them down from there).
"""
import math
import os
import struct
import wave

RATE = 44100
PEAK = 10 ** (-3 / 20)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")


def hz(note):
    """MIDI note number to frequency."""
    return 440.0 * 2 ** ((note - 69) / 12)


def bell(freq, t, decay):
    """A struck bell: the note plus a few inharmonic partials that die away faster."""
    partials = ((1.0, 1.0, 1.0), (2.0, 0.42, 1.8), (3.01, 0.20, 2.6), (4.17, 0.11, 3.4), (5.43, 0.05, 4.4))
    return sum(a * math.exp(-t * decay * d) * math.sin(2 * math.pi * freq * m * t) for m, a, d in partials)


def triangle(freq, t):
    x = (freq * t) % 1.0
    return 4 * abs(x - 0.5) - 1


def attack(t, ms):
    """A short fade in, so nothing clicks."""
    return min(1.0, t / (ms / 1000.0))


def render(length, notes):
    """notes: (start seconds, function of time since start) summed over [length] seconds."""
    n = int(RATE * length)
    out = [0.0] * n
    for start, f in notes:
        s0 = int(start * RATE)
        for i in range(s0, n):
            out[i] += f((i - s0) / RATE)
    # The last 30 ms fade to silence; then the whole sound is scaled to the peak.
    tail = int(0.03 * RATE)
    for i in range(tail):
        out[n - 1 - i] *= i / tail
    top = max(abs(v) for v in out) or 1.0
    return [v / top * PEAK for v in out]


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in samples))
    print(f"{name}: {len(samples) / RATE:.2f} s")


# Joey64 chime: three bell notes rising (G5, D6, G6), the last one left to ring.
write("unlock_chime.wav", render(1.25, [
    (0.00, lambda t: 0.55 * attack(t, 4) * bell(hz(79), t, 5.0)),
    (0.09, lambda t: 0.65 * attack(t, 4) * bell(hz(86), t, 4.5)),
    (0.18, lambda t: 0.85 * attack(t, 4) * bell(hz(91), t, 2.6)),
]))

# Soft ping: one gentle high note (A6) with a quiet octave above, slowly fading.
write("unlock_ping.wav", render(0.9, [
    (0.00, lambda t: attack(t, 8) * math.exp(-t * 4.2) * (math.sin(2 * math.pi * hz(93) * t)
                                                           + 0.18 * math.sin(2 * math.pi * hz(105) * t))),
]))

# Coin: a quick triangle-wave blip up a fourth (C6 to F6), held on the top note.
write("unlock_coin.wav", render(0.55, [
    (0.00, lambda t: (0.8 if t < 0.065 else 0.0) * attack(t, 2) * triangle(hz(84), t)),
    (0.065, lambda t: attack(t, 2) * math.exp(-t * 5.5) * triangle(hz(89), t)),
]))

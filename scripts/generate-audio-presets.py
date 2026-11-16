"""Reproducible, original PCM loops; no downloads or licensed recordings required.

Run with Python 3 to regenerate the bundled Android alarm and sleep sound resources.
"""
import math
from pathlib import Path
import random
import struct
import wave

OUT = Path(__file__).resolve().parents[1] / 'modules/kt-alarm/android/src/main/res/raw'
RATE = 16000
SECONDS = 12


def write(name, samples):
    peak = max(abs(x) for x in samples) or 1
    gain = 0.65 / peak
    with wave.open(str(OUT / (name + '.wav')), 'wb') as sound:
        sound.setnchannels(1)
        sound.setsampwidth(2)
        sound.setframerate(RATE)
        sound.writeframes(b''.join(struct.pack('<h', int(x * gain * 32767)) for x in samples))


def noise(kind):
    rng = random.Random(454)
    rows = [rng.uniform(-1, 1) for _ in range(12)]
    brown = 0
    low = 0
    result = []
    length = RATE * SECONDS
    cross = RATE // 2
    for i in range(length + cross):
        white = rng.uniform(-1, 1)
        j = min(11, ((i + 1) & -(i + 1)).bit_length() - 1)
        rows[j] = rng.uniform(-1, 1)
        pink = (sum(rows) + white) / 4
        brown = 0.995 * brown + white * 0.045
        low = 0.97 * low + white * 0.03
        t = i / RATE
        value = {
            'white': white * 0.4,
            'pink': pink * 0.6,
            'brown': brown * 2,
            'rain': (white * 0.22 + pink * 0.3) * (0.85 + 0.15 * math.sin(2 * math.pi * t / 6)),
            'ocean': (pink * 0.4 + brown * 0.5) * (0.55 + 0.45 * math.sin(math.pi * t / 6) ** 2),
            'fan': low * 1.8 + math.sin(2 * math.pi * 90 * t) * 0.025,
        }[kind]
        result.append(value)
    # Blend the seam without a silent gap; the player can loop indefinitely.
    for i in range(cross):
        mix = i / cross
        result[i] = result[i] * mix + result[length + i] * (1 - mix)
    return result[:length]


def tone(kind):
    result = []
    notes = {'chime': [523.25, 659.25, 783.99, 1046.5],
             'sunrise': [392, 493.88, 587.33, 783.99],
             'bell': [880, 880, 660, 660]}[kind]
    for i in range(RATE * 8):
        t = i / RATE
        local = t % 1
        f = notes[int(t) % len(notes)]
        attack = min(1, local / 0.015)
        envelope = attack * math.exp(-local * (4 if kind != 'sunrise' else 2.8))
        value = sum(math.sin(2 * math.pi * f * harmonic * local) / harmonic ** 2
                    for harmonic in range(1, 5)) * envelope
        # A short quiet tail keeps repeated alarm phrases free of clicks.
        value *= min(1, (8 - t) / 0.08)
        result.append(value)
    return result


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    for kind in ['white', 'pink', 'brown', 'rain', 'ocean', 'fan']:
        write('kt_sleep_' + kind, noise(kind))
    for kind in ['chime', 'sunrise', 'bell']:
        write('kt_tone_' + kind, tone(kind))

package com.claudegame.run;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A tiny offline synthesizer. All of the game's audio (sound effects and the music loop)
 * is rendered from sine-based tones and filtered noise at startup, so the app ships with
 * no audio files at all.
 */
final class Synth {
    static final int SR = 22050;

    private static final int TABLE = 4096;
    private static final float[] SINE = new float[TABLE + 1];

    static {
        for (int i = 0; i <= TABLE; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / TABLE);
    }

    // harmonic recipes: {harmonic, amplitude, harmonic, amplitude, ...}
    static final float[] PURE = {1, 1f};
    static final float[] SOFT = {1, 1f, 2, 0.18f};
    static final float[] BELL = {1, 1f, 2, 0.25f, 3, 0.08f};
    static final float[] TRI = {1, 1f, 3, 1 / 9f, 5, 1 / 25f};
    static final float[] PLUCK = {1, 1f, 2, 0.2f, 3, 0.06f};
    static final float[] BASS = {1, 1f, 2, 0.35f, 3, 0.1f};
    static final float[] PAD = {1, 1f, 2, 0.12f};

    private Synth() {
    }

    /** sine of a phase measured in cycles, via linear-interpolated wavetable */
    static float sin(double cycles) {
        double p = cycles - Math.floor(cycles);
        float x = (float) (p * TABLE);
        int i = (int) x;
        if (i >= TABLE) { // p just below 1.0 can round up to exactly TABLE as a float
            i = 0;
            x = 0f;
        }
        return SINE[i] + (SINE[i + 1] - SINE[i]) * (x - i);
    }

    static double midi(int note) {
        return 440.0 * Math.pow(2, (note - 69) / 12.0);
    }

    /**
     * Mixes a tone into {@code out}: an exponential glide from f0 to f1, linear attack,
     * exponential decay and a linear release at the end. With {@code wrap} the tail
     * wraps around to the start of the buffer, which makes seamless loops.
     */
    static void tone(float[] out, double start, double dur, double f0, double f1,
                     double attack, double decay, double release, float[] harm,
                     double vibrato, float gain, boolean wrap) {
        int n0 = (int) Math.round(start * SR);
        int n = (int) (dur * SR);
        int attackN = Math.max(1, (int) (attack * SR));
        int releaseN = Math.max(1, (int) (release * SR));
        double f = f0, fMul = Math.pow(f1 / f0, 1.0 / n);
        double env = gain, envMul = Math.exp(-decay / SR);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            int idx = n0 + i;
            if (wrap) idx %= out.length;
            else if (idx >= out.length) break;
            double fi = vibrato == 0 ? f : f * (1 + vibrato * sin(6.0 * i / SR));
            phase += fi / SR;
            double a = env;
            if (i < attackN) a *= (double) i / attackN;
            if (n - i < releaseN) a *= (double) (n - i) / releaseN;
            float s = 0;
            for (int h = 0; h < harm.length; h += 2) s += harm[h + 1] * sin(phase * harm[h]);
            out[idx] += (float) (s * a);
            f *= fMul;
            env *= envMul;
        }
    }

    static void tone(float[] out, double start, double dur, double f0, double f1,
                     double decay, float[] harm) {
        tone(out, start, dur, f0, f1, 0.004, decay, 0.01, harm, 0, 1f, false);
    }

    /**
     * Mixes filtered noise into {@code out}. The one-pole low-pass cutoff glides from c0 to
     * c1; with {@code highpass} the low-passed part is removed instead (a crisp tick).
     */
    static void noise(float[] out, double start, double dur, double c0, double c1, float gain,
                      double decay, long seed, boolean highpass, boolean wrap) {
        java.util.Random rnd = new java.util.Random(seed);
        int n0 = (int) Math.round(start * SR);
        int n = (int) (dur * SR);
        int edge = Math.max(1, (int) (0.01 * SR));
        double y = 0, env = gain, envMul = Math.exp(-decay / SR);
        for (int i = 0; i < n; i++) {
            int idx = n0 + i;
            if (wrap) idx %= out.length;
            else if (idx >= out.length) break;
            double fc = c0 * Math.pow(c1 / c0, (double) i / n);
            double a = 1 - Math.exp(-2 * Math.PI * fc / SR);
            double x = rnd.nextDouble() * 2 - 1;
            y += a * (x - y);
            double s = highpass ? x - y : y;
            double e = env * Math.min(1.0, (double) i / edge) * Math.min(1.0, (double) (n - i) / edge);
            out[idx] += (float) (s * e);
            env *= envMul;
        }
    }

    /** Normalizes to {@code peak} (0..1) and converts to 16-bit PCM. */
    static short[] toPcm(float[] x, float peak) {
        float m = 1e-9f;
        for (float v : x) m = Math.max(m, Math.abs(v));
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) out[i] = (short) Math.round(x[i] / m * peak * 32767);
        return out;
    }

    static void writeWav(File file, short[] pcm) throws IOException {
        int dataLen = pcm.length * 2;
        byte[] b = new byte[44 + dataLen];
        putStr(b, 0, "RIFF");
        putInt(b, 4, 36 + dataLen);
        putStr(b, 8, "WAVE");
        putStr(b, 12, "fmt ");
        putInt(b, 16, 16);
        putShort(b, 20, 1);          // PCM
        putShort(b, 22, 1);          // mono
        putInt(b, 24, SR);
        putInt(b, 28, SR * 2);       // byte rate
        putShort(b, 32, 2);          // block align
        putShort(b, 34, 16);         // bits per sample
        putStr(b, 36, "data");
        putInt(b, 40, dataLen);
        for (int i = 0; i < pcm.length; i++) putShort(b, 44 + i * 2, pcm[i]);
        try (FileOutputStream fo = new FileOutputStream(file)) {
            fo.write(b);
        }
    }

    private static void putStr(byte[] b, int at, String s) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    // ------------------------------------------------------------------ effects

    static float[] jump() {
        float[] s = new float[(int) (0.17 * SR)];
        tone(s, 0, 0.17, 380, 760, 14, SOFT);
        return s;
    }

    static float[] token() {
        float[] s = new float[(int) (0.38 * SR)];
        tone(s, 0.0, 0.08, 987.77, 987.77, 20, BELL);
        tone(s, 0.06, 0.32, 1318.51, 1318.51, 11, BELL);
        return s;
    }

    static float[] level() {
        float[] s = new float[(int) (0.75 * SR)];
        double[] notes = {523.25, 659.25, 783.99, 1046.50};
        for (int k = 0; k < notes.length; k++) {
            tone(s, k * 0.075, 0.75 - k * 0.075, notes[k], notes[k], 6, SOFT);
        }
        return s;
    }

    static float[] crash() {
        float[] s = new float[(int) (0.75 * SR)];
        tone(s, 0.0, 0.12, 140, 55, 22, PURE);
        noise(s, 0.0, 0.08, 1800, 300, 0.6f, 40, 7, false, false);
        tone(s, 0.10, 0.20, 392.00, 370, 5, TRI);
        tone(s, 0.30, 0.45, 311.13, 233.08, 0.004, 4, 0.01, TRI, 0.012, 1f, false);
        return s;
    }

    static float[] start() {
        float[] s = new float[(int) (0.22 * SR)];
        tone(s, 0.0, 0.07, 783.99, 783.99, 18, SOFT);
        tone(s, 0.06, 0.16, 1174.66, 1174.66, 16, SOFT);
        return s;
    }

    static float[] duck() {
        float[] s = new float[(int) (0.16 * SR)];
        noise(s, 0.0, 0.16, 3000, 400, 1f, 14, 7, false, false);
        tone(s, 0.0, 0.12, 500, 260, 20, new float[]{1, 0.3f});
        return s;
    }

    // ------------------------------------------------------------------ music

    static final double BPM = 96;
    static final int BARS = 8;

    /**
     * A calm 8-bar loop (96 BPM, ~20 s): warm pad chords, a soft bass line, a plucked
     * arpeggio and light drums. Progression: Fmaj7 G6 Em7 Am7 | Fmaj7 G Cmaj7 Cadd9.
     * Everything is rendered with wrap-around so the loop is seamless.
     */
    static float[] musicLoop() {
        double beat = 60.0 / BPM, bar = beat * 4;
        float[] s = new float[(int) Math.round(bar * BARS * SR)];
        int[][] chords = {
                {53, 57, 60, 64}, {55, 59, 62, 64}, {52, 55, 59, 62}, {57, 60, 64, 67},
                {53, 57, 60, 64}, {55, 59, 62, 67}, {60, 64, 67, 71}, {60, 64, 67, 74},
        };
        int[] bass = {41, 43, 40, 45, 41, 43, 48, 48};
        int[][] arp = {
                {0, 2, 1, 3, 2, 1, 3, 2},
                {3, 2, 1, 2, 0, 2, 1, 3},
        };
        for (int b = 0; b < BARS; b++) {
            double t0 = b * bar;
            // pad: two slightly detuned voices per chord tone, crossfading into the next bar
            for (int note : chords[b]) {
                double f = midi(note);
                for (double detune : new double[]{0.9985, 1.0015}) {
                    tone(s, t0, bar + 0.9, f * detune, f * detune, 0.5, 0.25, 0.9, PAD, 0, 0.05f, true);
                }
            }
            // bass on beats 1 and 3, with a short pickup an octave up on the "and" of 4
            double fb = midi(bass[b]);
            tone(s, t0, beat * 1.6, fb, fb, 0.01, 2.2, 0.08, BASS, 0, 0.32f, true);
            tone(s, t0 + 2 * beat, beat * 1.3, fb, fb, 0.01, 2.2, 0.08, BASS, 0, 0.30f, true);
            tone(s, t0 + 3.5 * beat, beat * 0.45, fb * 2, fb * 2, 0.01, 4, 0.05, BASS, 0, 0.16f, true);
            // plucked arpeggio in eighths, one octave above the chord; breathe at phrase ends
            int[] pattern = arp[b < 4 ? 0 : 1];
            int steps = (b == 3 || b == 7) ? 6 : 8;
            for (int k = 0; k < steps; k++) {
                double f = midi(chords[b][pattern[k]] + 12);
                float g = k % 2 == 0 ? 0.11f : 0.08f; // gentle swing in dynamics
                tone(s, t0 + k * beat / 2, 0.55, f, f, 0.005, 7, 0.05, PLUCK, 0, g, true);
            }
            // drums: soft kick on 1 and 3, hi-hat ticks on the off-beats
            for (int k = 0; k < 4; k += 2) {
                tone(s, t0 + k * beat, 0.25, 110, 45, 0.002, 14, 0.02, PURE, 0, 0.45f, true);
            }
            for (int k = 0; k < 4; k++) {
                noise(s, t0 + (k + 0.5) * beat, 0.06, 5000, 5000, 0.12f, 60, b * 4L + k, true, true);
            }
        }
        return s;
    }
}

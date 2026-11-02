package com.claudegame.run;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.IOException;

/**
 * Short sound effects played through a low-latency SoundPool. The effects are synthesized
 * at startup (see {@link Synth}) and written to the app's cache, since SoundPool loads
 * from files; nothing is bundled in the APK.
 */
final class Sfx {
    static final int JUMP = 0, TOKEN = 1, LEVEL = 2, CRASH = 3, START = 4, DUCK = 5;

    private static final String[] NAMES = {"jump", "token", "level", "crash", "start", "duck"};
    private static final float[] PEAK = {0.55f, 0.5f, 0.5f, 0.6f, 0.45f, 0.4f};
    private static final float[] VOLUME = {0.55f, 0.7f, 0.8f, 0.9f, 0.6f, 0.45f};

    final SoundPool pool; // package-private for tests
    private final int[] ids = new int[NAMES.length];
    private final String[] paths = new String[NAMES.length];
    private boolean muted;

    Sfx(Context context, boolean muted) {
        this.muted = muted;
        pool = new SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        File dir = new File(context.getCacheDir(), "sfx");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        for (int i = 0; i < NAMES.length; i++) {
            File f = new File(dir, NAMES[i] + ".wav");
            try {
                Synth.writeWav(f, Synth.toPcm(render(i), PEAK[i]));
                paths[i] = f.getAbsolutePath();
                ids[i] = pool.load(paths[i], 1);
            } catch (IOException e) {
                ids[i] = 0; // cache not writable: that effect stays silent
            }
        }
    }

    private static float[] render(int sound) {
        switch (sound) {
            case JUMP: return Synth.jump();
            case TOKEN: return Synth.token();
            case LEVEL: return Synth.level();
            case CRASH: return Synth.crash();
            case START: return Synth.start();
            default: return Synth.duck();
        }
    }

    void play(int sound) {
        play(sound, 1f);
    }

    /** @param rate playback speed / pitch, 0.5..2.0 */
    void play(int sound, float rate) {
        if (muted || ids[sound] == 0) return;
        float v = VOLUME[sound];
        pool.play(ids[sound], v, v, 1, 0, Math.max(0.5f, Math.min(2f, rate)));
    }

    /** cached file for an effect (tests check what was played) */
    String path(int sound) {
        return paths[sound];
    }

    boolean isMuted() {
        return muted;
    }

    void setMuted(boolean m) {
        muted = m;
        if (m) pool.autoPause();
    }

    void release() {
        pool.release();
    }
}

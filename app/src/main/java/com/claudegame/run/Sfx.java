package com.claudegame.run;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** Short sound effects played through a low-latency SoundPool. Sounds live in res/raw. */
final class Sfx {
    static final int JUMP = 0, TOKEN = 1, LEVEL = 2, CRASH = 3, START = 4, DUCK = 5;

    private static final int[] RES = {
            R.raw.sfx_jump, R.raw.sfx_token, R.raw.sfx_level,
            R.raw.sfx_crash, R.raw.sfx_start, R.raw.sfx_duck,
    };
    private static final float[] VOLUME = {0.55f, 0.7f, 0.8f, 0.9f, 0.6f, 0.45f};

    final SoundPool pool; // package-private for tests
    private final int[] ids = new int[RES.length];
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
        for (int i = 0; i < RES.length; i++) ids[i] = pool.load(context, RES[i], 1);
    }

    void play(int sound) {
        play(sound, 1f);
    }

    /** @param rate playback speed / pitch, 0.5..2.0 */
    void play(int sound, float rate) {
        if (muted) return;
        float v = VOLUME[sound];
        pool.play(ids[sound], v, v, 1, 0, Math.max(0.5f, Math.min(2f, rate)));
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

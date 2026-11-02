package com.claudegame.run;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

/**
 * Background music: a synthesized loop streamed through an AudioTrack on its own thread.
 * Volume changes (mute, duck on game over, app going to background) fade smoothly instead
 * of cutting off, and the loop is gapless.
 */
final class Music implements Runnable {
    private static final float VOLUME = 0.3f;     // relative to the sound effects
    private static final float DUCKED = 0.45f;    // fraction of VOLUME on the game over screen
    private static final int CHUNK = 1024;        // frames per write

    private final Object lock = new Object();
    private volatile boolean enabled, foreground, ducked, released;
    private Thread thread;

    /** for tests */
    volatile boolean playing;
    volatile long framesWritten;

    Music(boolean enabled) {
        this.enabled = enabled;
    }

    /** App came to the foreground. The loop is synthesized on first start. */
    void start() {
        synchronized (lock) {
            foreground = true;
            if (thread == null && !released) {
                thread = new Thread(this, "claude-run-music");
                thread.setDaemon(true);
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** App went to the background. */
    void stop() {
        synchronized (lock) {
            foreground = false;
            lock.notifyAll();
        }
    }

    void setEnabled(boolean e) {
        synchronized (lock) {
            enabled = e;
            lock.notifyAll();
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    void setDucked(boolean d) {
        ducked = d;
    }

    void release() {
        synchronized (lock) {
            released = true;
            lock.notifyAll();
        }
    }

    private float targetGain() {
        if (released || !enabled || !foreground) return 0f;
        return ducked ? VOLUME * DUCKED : VOLUME;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] loop = Synth.toPcm(Synth.musicLoop(), 0.9f);
        AudioTrack track;
        int bufferBytes;
        try {
            int min = AudioTrack.getMinBufferSize(Synth.SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            bufferBytes = Math.max(min, CHUNK * 2) * 2;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(Synth.SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        } catch (RuntimeException e) {
            return; // no audio output available; play silently
        }

        byte[] chunk = new byte[CHUNK * 2];
        float gain = 0f, step = 1f / (0.35f * Synth.SR); // ~0.1 s fades at this volume
        int pos = 0;
        long written = 0, startNs = System.nanoTime();
        // After fading out, keep feeding silence until the hardware buffer holds nothing
        // but silence, so pausing/flushing can't cut off the fade (no clicks).
        final int drainFrames = bufferBytes / 2 + CHUNK;
        int silentFrames = drainFrames;
        try {
            while (!released) {
                float target = targetGain();
                if (target == 0f && gain == 0f && silentFrames >= drainFrames) {
                    // fully faded out: stop the hardware and sleep until needed again
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause();
                        track.flush();
                    }
                    playing = false;
                    synchronized (lock) {
                        while (!released && targetGain() == 0f) lock.wait();
                    }
                    written = 0;
                    startNs = System.nanoTime();
                    continue;
                }
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                playing = true;

                for (int i = 0; i < CHUNK; i++) {
                    if (gain < target) gain = Math.min(target, gain + step);
                    else if (gain > target) gain = Math.max(target, gain - step);
                    int v = Math.round(loop[pos] * gain);
                    chunk[i * 2] = (byte) v;
                    chunk[i * 2 + 1] = (byte) (v >> 8);
                    if (++pos == loop.length) pos = 0;
                }
                silentFrames = gain == 0f ? silentFrames + CHUNK : 0;
                track.write(chunk, 0, chunk.length); // blocks while the buffer is full
                written += CHUNK;
                framesWritten += CHUNK;

                // If the sink doesn't block (some devices, tests), don't race ahead of real time.
                long aheadMs = written * 1000 / Synth.SR - (System.nanoTime() - startNs) / 1_000_000;
                if (aheadMs > 300) Thread.sleep(aheadMs - 300);
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (RuntimeException ignored) {
            // audio device went away; stop the music quietly
        } finally {
            playing = false;
            track.release();
        }
    }
}

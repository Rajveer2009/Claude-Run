package com.claudegame.run;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/** Plays the game headlessly and renders a few frames to PNG for a visual smoke test. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class GameViewRenderTest {

    private static final int W = 1600, H = 720;

    @Test
    public void playsAndRenders() throws Exception {
        GameView v = new GameView(RuntimeEnvironment.getApplication());
        v.measure(W, H);
        v.layout(0, 0, W, H);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        String out = System.getProperty("renderDir", "build/renders");
        new File(out).mkdirs();

        step(v, c, 30);
        save(bmp, out + "/1_title.png");

        tap(v, W * 0.8f, MotionEvent.ACTION_DOWN);
        tap(v, W * 0.8f, MotionEvent.ACTION_UP);
        step(v, c, 90);
        save(bmp, out + "/2_running.png");
        tap(v, W * 0.8f, MotionEvent.ACTION_DOWN); // jump
        step(v, c, 14);
        save(bmp, out + "/3_jump.png");
        tap(v, W * 0.8f, MotionEvent.ACTION_UP);

        // run until we crash (we never dodge) and capture the game over screen
        int frames = 0;
        while (frames++ < 5000) {
            step(v, c, 1);
        }
        step(v, c, 60);
        save(bmp, out + "/4_game_over.png");
        assertTrue(new File(out + "/4_game_over.png").length() > 0);
    }

    private static void step(GameView v, Canvas c, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            Thread.sleep(16);
            v.draw(c);
        }
    }

    private static void tap(GameView v, float x, int action) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, H / 2f, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void save(Bitmap b, String path) throws Exception {
        try (FileOutputStream fo = new FileOutputStream(path)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }
}

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

    /** With the phone in dark mode the game should start (and stay) dark. */
    @Test
    @Config(qualifiers = "night")
    public void followsSystemDarkMode() throws Exception {
        GameView v = new GameView(RuntimeEnvironment.getApplication());
        v.measure(W, H);
        v.layout(0, 0, W, H);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        String out = System.getProperty("renderDir", "build/renders");
        new File(out).mkdirs();

        step(v, c, 30);
        save(bmp, out + "/5_dark_title.png");
        assertTrue("background should be dark", luminance(bmp.getPixel(5, H / 2)) < 0.2f);

        tap(v, W * 0.8f, MotionEvent.ACTION_DOWN);
        tap(v, W * 0.8f, MotionEvent.ACTION_UP);
        step(v, c, 90);
        save(bmp, out + "/6_dark_running.png");
        assertTrue("stays dark while playing", luminance(bmp.getPixel(5, H / 2)) < 0.2f);

        // Switching the phone back to light mode mid-game fades the palette back to light.
        android.content.res.Configuration light =
                new android.content.res.Configuration(v.getResources().getConfiguration());
        light.uiMode = (light.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_NO;
        v.dispatchConfigurationChanged(light);
        step(v, c, 120);
        assertTrue("turns light again", luminance(bmp.getPixel(5, H / 2)) > 0.8f);
    }

    private static float luminance(int argb) {
        return (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF)
                + 0.114f * (argb & 0xFF)) / 255f;
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

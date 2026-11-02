package com.claudegame.run;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.WindowInsets;
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
import java.util.Collections;

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

    @Test
    public void playsSoundEffects() throws Exception {
        GameView v = newView();
        Canvas c = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        org.robolectric.shadows.ShadowSoundPool pool = shadowOf(v.sfx().pool);

        step(v, c, 5);
        tap(v, W * 0.8f, MotionEvent.ACTION_DOWN); // start
        tap(v, W * 0.8f, MotionEvent.ACTION_UP);
        assertTrue("start sound", pool.wasResourcePlayed(R.raw.sfx_start));
        step(v, c, 3);
        tap(v, W * 0.8f, MotionEvent.ACTION_DOWN); // jump
        tap(v, W * 0.8f, MotionEvent.ACTION_UP);
        assertTrue("jump sound", pool.wasResourcePlayed(R.raw.sfx_jump));
        step(v, c, 60);
        tap(v, W * 0.1f, MotionEvent.ACTION_DOWN); // duck
        tap(v, W * 0.1f, MotionEvent.ACTION_UP);
        assertTrue("duck sound", pool.wasResourcePlayed(R.raw.sfx_duck));
        for (int i = 0; i < 5000 && !pool.wasResourcePlayed(R.raw.sfx_crash); i++) step(v, c, 1);
        assertTrue("crash sound", pool.wasResourcePlayed(R.raw.sfx_crash));

        // 'M' mutes (also available as an on-screen button on menus); nothing plays after that
        v.onKeyDown(KeyEvent.KEYCODE_M, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M));
        assertTrue(v.sfx().isMuted());
        pool.clearPlayed();
        step(v, c, 60);
        tap(v, W * 0.8f, MotionEvent.ACTION_DOWN); // restart
        tap(v, W * 0.8f, MotionEvent.ACTION_UP);
        assertFalse("muted", pool.wasResourcePlayed(R.raw.sfx_start));
    }

    /** A phone with a punch-hole camera on the left edge (landscape): HUD must avoid it. */
    @Test
    public void hudAvoidsCameraCutout() throws Exception {
        GameView v = newView();
        int notch = 140;
        Rect hole = new Rect(0, 20, notch - 40, 140);
        WindowInsets insets = new WindowInsets.Builder()
                .setDisplayCutout(new DisplayCutout(Insets.of(notch, 0, 0, 0), null, null, null, null))
                .build();
        v.dispatchApplyWindowInsets(insets);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        step(v, c, 5);
        tap(v, W * 0.8f, MotionEvent.ACTION_DOWN);
        tap(v, W * 0.8f, MotionEvent.ACTION_UP);
        step(v, c, 60);

        // nothing but background inside the cutout's safe-inset strip near the top
        int bg = bmp.getPixel(notch + 300, H / 2);
        for (int x = 0; x < notch; x += 4) {
            for (int y = 0; y < 120; y += 4) {
                assertTrue("HUD drawn under notch at " + x + "," + y,
                        Math.abs(luminance(bmp.getPixel(x, y)) - luminance(bg)) < 0.08f);
            }
        }
        // draw a fake camera hole so the render shows what a phone would look like
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        c.drawCircle(hole.centerX(), hole.centerY(), 30, p);
        String out = System.getProperty("renderDir", "build/renders");
        new File(out).mkdirs();
        save(bmp, out + "/7_cutout.png");
    }

    private static GameView newView() {
        GameView v = new GameView(RuntimeEnvironment.getApplication());
        v.measure(W, H);
        v.layout(0, 0, W, H);
        return v;
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

package com.claudegame.run;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * Claude Run — an endless runner starring Claude's orange spark.
 *
 * All sizes are expressed in "units" (u), where 100u == screen height, so the game
 * plays the same on every device. Everything is drawn with Canvas primitives.
 */
public class GameView extends View {

    // ---------------------------------------------------------------- tuning
    private static final float GRAVITY = 420f;          // u/s^2
    private static final float JUMP_VELOCITY = 122f;    // u/s
    private static final float HOLD_GRAVITY_SCALE = 0.58f;
    private static final float FAST_FALL_SCALE = 3.2f;
    private static final float START_SPEED = 62f;       // u/s
    private static final float MAX_SPEED = 150f;
    private static final float ACCEL = 0.012f;          // speed gained per u travelled
    private static final float BODY_R = 8f;             // spark radius
    private static final float LEG_LEN = 3.5f;
    private static final int NIGHT_EVERY = 700;         // score between day/night swaps

    private static final String[] OOPS = {
            "I made an error.",
            "Let me reconsider that.",
            "That was a bug, not a feature.",
            "Context window exceeded.",
            "I apologize for the confusion.",
            "Hmm, let me think again.",
    };

    private enum State { READY, RUNNING, PAUSED, OVER }

    private static final int OB_BUG = 0, OB_BLOCK = 1, OB_CLOUD = 2;

    private static class Obstacle {
        int type, count;
        float x, w, h, lift;   // lift = height of the bottom edge above ground
        float phase;
        String label;
    }

    private static class Token {
        float x, lift, phase;
        boolean taken;
    }

    private static class Particle {
        float x, y, vx, vy, life, max, size;
        int color;
    }

    private static class Deco {
        float x, y, size, depth;
        int kind;
        String glyph;
    }

    // ---------------------------------------------------------------- state
    private final Random rnd = new Random();
    private final ArrayList<Obstacle> obstacles = new ArrayList<>();
    private final ArrayList<Token> tokens = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Deco> decos = new ArrayList<>();

    private State state = State.READY;
    private float W, H, u, groundY, playerX;
    private float lift, vy;                 // player height above ground / vertical speed (up +)
    private boolean jumpHeld, duckHeld, wasOnGround = true;
    private int jumpPointer = -1, duckPointer = -1;
    private float downY;
    private float speed = START_SPEED, distance, groundScroll;
    private int bonus, tokenCount, score, hiScore;
    private boolean newBest;
    private float spin, runPhase, blinkTimer = 2f, idleTime, overTime, nightT, flashTime;
    private String oopsLine = OOPS[0];
    private String banner;
    private float bannerTime;
    private int lastMilestone;
    private long lastFrameNs;
    private boolean hostActive = true;

    private final SharedPreferences prefs;

    // ---------------------------------------------------------------- paints
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    // palette (day, night)
    private static final int ORANGE = 0xFFD97757;
    private static final int ORANGE_DARK = 0xFFBD5D3A;
    private static final int INK = 0xFF1F1E1D;
    private static final int[] BG = {0xFFF5F0E8, 0xFF1F1E1D};
    private static final int[] FG = {0xFF3D3929, 0xFFE8E2D6};
    private static final int[] FAINT = {0xFFE6DDCF, 0xFF2D2B28};
    private static final int[] CLOUD = {0xFFEAE3D7, 0xFF33312D};
    private static final int[] BUG = {0xFF9C3B2A, 0xFFE0705A};
    private static final int[] TERM = {0xFF2A2825, 0xFF3A3834};
    private static final int[] LIMIT = {0xFF8E8A80, 0xFF6B675F};

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        prefs = context.getSharedPreferences("claude_run", Context.MODE_PRIVATE);
        hiScore = prefs.getInt("hi", 0);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setTextAlign(Paint.Align.CENTER);
    }

    // ================================================================ lifecycle

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        W = w;
        H = h;
        u = H / 100f;
        groundY = H * 0.80f;
        playerX = Math.max(W * 0.14f, 16 * u);
        decos.clear();
        for (int i = 0; i < 9; i++) decos.add(makeDeco(rnd.nextFloat() * W));
    }

    void onHostPause() {
        hostActive = false;
        if (state == State.RUNNING) {
            state = State.PAUSED;
            jumpHeld = duckHeld = false;
        }
    }

    void onHostResume() {
        hostActive = true;
        lastFrameNs = 0;
        invalidate();
    }

    private void resetGame() {
        obstacles.clear();
        tokens.clear();
        particles.clear();
        lift = vy = 0;
        speed = START_SPEED;
        distance = 0;
        bonus = tokenCount = score = 0;
        lastMilestone = 0;
        newBest = false;
        spin = 0;
        banner = null;
        jumpHeld = duckHeld = false;
        jumpPointer = duckPointer = -1;
        state = State.RUNNING;
    }

    // ================================================================ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx);
                if (state == State.READY || state == State.PAUSED) {
                    if (state == State.READY) resetGame(); else state = State.RUNNING;
                    lastFrameNs = 0;
                    invalidate();
                    return true;
                }
                if (state == State.OVER) {
                    if (overTime > 0.6f) { resetGame(); invalidate(); }
                    return true;
                }
                // Left side of the screen ducks, everything else jumps.
                if (x < W * 0.4f) {
                    duckPointer = id;
                    duckHeld = true;
                } else {
                    jumpPointer = id;
                    downY = e.getY(idx);
                    pressJump();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (jumpPointer >= 0) {
                    int pi = e.findPointerIndex(jumpPointer);
                    // Swipe down on the jump side = fast fall + duck.
                    if (pi >= 0 && e.getY(pi) - downY > 8 * u) {
                        jumpHeld = false;
                        duckHeld = true;
                        duckPointer = jumpPointer;
                        jumpPointer = -1;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL || id == jumpPointer) {
                    jumpHeld = false;
                    jumpPointer = -1;
                }
                if (action == MotionEvent.ACTION_CANCEL || id == duckPointer) {
                    duckHeld = false;
                    duckPointer = -1;
                }
                if (action == MotionEvent.ACTION_UP) {
                    jumpHeld = duckHeld = false;
                    jumpPointer = duckPointer = -1;
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (state == State.READY) resetGame();
                else if (state == State.PAUSED) { state = State.RUNNING; lastFrameNs = 0; }
                else if (state == State.OVER) { if (overTime > 0.6f) resetGame(); return true; }
                pressJump();
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                duckHeld = true;
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                jumpHeld = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                duckHeld = false;
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void pressJump() {
        jumpHeld = true;
        if (state == State.RUNNING && lift <= 0.01f) {
            vy = JUMP_VELOCITY;
            lift = 0.01f;
            puff(playerX, groundY, 5, FG[0]);
        }
    }

    // ================================================================ update

    private void update(float dt) {
        idleTime += dt;
        blinkTimer -= dt;
        if (blinkTimer < -0.14f) blinkTimer = 2f + rnd.nextFloat() * 3f;
        if (bannerTime > 0) bannerTime -= dt;
        if (flashTime > 0) flashTime -= dt;

        float night = (state == State.RUNNING || state == State.OVER)
                && (score / NIGHT_EVERY) % 2 == 1 ? 1f : 0f;
        nightT += (night - nightT) * Math.min(1f, dt * 1.5f);

        updateParticles(dt);

        if (state == State.OVER) {
            overTime += dt;
            return;
        }
        if (state == State.PAUSED) return;

        float scroll = state == State.RUNNING ? speed : 18f;
        scrollDecos(scroll * dt);
        if (state != State.RUNNING) {
            runPhase += dt * 6f;
            groundScroll += scroll * dt * u;
            return;
        }

        // --- speed & score
        speed = Math.min(MAX_SPEED, START_SPEED + distance * ACCEL);
        distance += speed * dt;
        groundScroll += speed * dt * u;
        score = (int) (distance / 5f) + bonus;
        int milestone = score / 500;
        if (milestone > lastMilestone) {
            lastMilestone = milestone;
            showBanner(milestone % 2 == 0 ? "Thinking faster..." : "Extended thinking!");
            flashTime = 0.6f;
        }

        // --- player physics
        boolean onGround = lift <= 0f;
        if (!onGround) {
            float g = GRAVITY;
            if (duckHeld) g *= FAST_FALL_SCALE;
            else if (jumpHeld && vy > 0) g *= HOLD_GRAVITY_SCALE;
            vy -= g * dt;
            lift += vy * dt;
            spin += dt * 360f;
            if (lift <= 0f) {
                lift = 0f;
                vy = 0f;
            }
        } else {
            // settle spin onto the nearest ray position (rays repeat every 30 degrees)
            float target = Math.round(spin / 30f) * 30f;
            spin += (target - spin) * Math.min(1f, dt * 12f);
            runPhase += dt * speed * 0.22f;
        }
        boolean nowOnGround = lift <= 0f;
        if (nowOnGround && !wasOnGround) puff(playerX, groundY, 6, FG[0]);
        wasOnGround = nowOnGround;

        // --- world
        float dx = speed * dt * u;
        for (Iterator<Obstacle> it = obstacles.iterator(); it.hasNext(); ) {
            Obstacle o = it.next();
            o.x -= o.type == OB_CLOUD ? dx * 1.12f : dx;
            o.phase += dt;
            if (o.x + o.w < -10 * u) it.remove();
        }
        for (Iterator<Token> it = tokens.iterator(); it.hasNext(); ) {
            Token t = it.next();
            t.x -= dx;
            t.phase += dt;
            if (t.x < -10 * u || t.taken) it.remove();
        }
        spawnIfNeeded();

        // --- collisions
        RectF p = playerHitbox();
        for (Token t : tokens) {
            float ty = groundY - t.lift * u;
            float r = 3.4f * u;
            if (!t.taken && t.x + r > p.left && t.x - r < p.right && ty + r > p.top && ty - r < p.bottom) {
                t.taken = true;
                tokenCount++;
                bonus += 10;
                puff(t.x, ty, 8, ORANGE);
            }
        }
        RectF ob = new RectF();
        for (Obstacle o : obstacles) {
            obstacleHitbox(o, ob);
            if (RectF.intersects(p, ob)) {
                gameOver();
                break;
            }
        }
    }

    private void gameOver() {
        state = State.OVER;
        overTime = 0;
        jumpHeld = duckHeld = false;
        oopsLine = OOPS[rnd.nextInt(OOPS.length)];
        float[] c = playerCenter();
        for (int i = 0; i < 26; i++) {
            Particle q = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            float s = (20 + rnd.nextFloat() * 50) * u;
            q.x = c[0];
            q.y = c[1];
            q.vx = (float) Math.cos(a) * s;
            q.vy = (float) Math.sin(a) * s - 20 * u;
            q.life = q.max = 0.6f + rnd.nextFloat() * 0.6f;
            q.size = (0.6f + rnd.nextFloat()) * u;
            q.color = rnd.nextBoolean() ? ORANGE : ORANGE_DARK;
            particles.add(q);
        }
        if (score > hiScore) {
            hiScore = score;
            newBest = true;
            prefs.edit().putInt("hi", hiScore).apply();
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void showBanner(String s) {
        banner = s;
        bannerTime = 1.8f;
    }

    private void spawnIfNeeded() {
        float rightmost = -1e9f;
        for (Obstacle o : obstacles) rightmost = Math.max(rightmost, o.x + o.w);
        // Minimum gap scales with speed so a full jump always lands before the next obstacle.
        float minGap = (speed * 0.95f + 16f) * u;
        if (rightmost > W - minGap) return;
        if (!obstacles.isEmpty() && rnd.nextFloat() < 0.35f && rightmost > W - minGap * 1.6f) return;

        Obstacle o = new Obstacle();
        o.x = W + 4 * u;
        o.phase = rnd.nextFloat() * 10f;
        float roll = rnd.nextFloat();
        boolean cloudsAllowed = score > 250;
        if (cloudsAllowed && roll < 0.22f) {
            o.type = OB_CLOUD;
            o.w = 15 * u;
            o.h = 8 * u;
            // low cloud: duck under it (or big jump); high cloud: just keep running
            o.lift = rnd.nextFloat() < 0.65f ? 12f : 21f;
            o.label = "429";
        } else if (roll < 0.6f) {
            o.type = OB_BUG;
            o.count = 1 + rnd.nextInt(score > 400 ? 3 : 2);
            o.w = o.count * 8.5f * u;
            o.h = 6.5f * u;
        } else {
            o.type = OB_BLOCK;
            o.count = score > 300 && rnd.nextFloat() < 0.35f ? 2 : 1;
            o.w = (o.count * 9.5f - 0.5f) * u;
            o.h = (10f + rnd.nextFloat() * (score > 200 ? 7f : 3f)) * u;
            String[] labels = {"ERR", "{ }", "!", "NaN", "404"};
            o.label = labels[rnd.nextInt(labels.length)];
        }
        obstacles.add(o);

        // sprinkle tokens before the obstacle
        if (rnd.nextFloat() < 0.45f) {
            int n = 1 + rnd.nextInt(3);
            float baseX = o.x - minGap * 0.45f;
            float baseLift = 6f + rnd.nextFloat() * 18f;
            for (int i = 0; i < n; i++) {
                Token t = new Token();
                t.x = baseX + i * 7f * u;
                t.lift = baseLift + (n == 3 && i == 1 ? 4f : 0f);
                t.phase = i * 0.4f;
                if (t.x > W * 0.6f) tokens.add(t);
            }
        }
    }

    private void scrollDecos(float d) {
        for (int i = 0; i < decos.size(); i++) {
            Deco dc = decos.get(i);
            dc.x -= d * dc.depth * u;
            if (dc.x < -30 * u) decos.set(i, makeDeco(W + rnd.nextFloat() * 40 * u));
        }
    }

    private Deco makeDeco(float x) {
        Deco d = new Deco();
        d.x = x;
        d.kind = rnd.nextInt(3); // 0,1 cloud, 2 glyph
        if (d.kind == 2) {
            String[] g = {"{ }", "</>", "( )", "=>", "#", "*", "[ ]", "fn"};
            d.glyph = g[rnd.nextInt(g.length)];
            d.y = (12 + rnd.nextFloat() * 50) * u;
            d.size = (4 + rnd.nextFloat() * 3) * u;
            d.depth = 0.15f + rnd.nextFloat() * 0.15f;
        } else {
            d.y = (10 + rnd.nextFloat() * 40) * u;
            d.size = (5 + rnd.nextFloat() * 5) * u;
            d.depth = 0.08f + rnd.nextFloat() * 0.12f;
        }
        return d;
    }

    private void puff(float x, float y, int n, int color) {
        for (int i = 0; i < n; i++) {
            Particle q = new Particle();
            q.x = x + (rnd.nextFloat() - 0.5f) * 4 * u;
            q.y = y - rnd.nextFloat() * u;
            q.vx = (rnd.nextFloat() - 0.7f) * 25 * u;
            q.vy = -rnd.nextFloat() * 18 * u;
            q.life = q.max = 0.25f + rnd.nextFloat() * 0.3f;
            q.size = (0.4f + rnd.nextFloat() * 0.6f) * u;
            q.color = color;
            particles.add(q);
        }
    }

    private void updateParticles(float dt) {
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle q = it.next();
            q.life -= dt;
            if (q.life <= 0) { it.remove(); continue; }
            q.vy += 120 * u * dt;
            q.x += q.vx * dt;
            q.y += q.vy * dt;
        }
    }

    // ================================================================ geometry

    private boolean ducking() {
        return duckHeld && lift <= 0f && state == State.RUNNING;
    }

    /** returns {cx, cy, scaleX, scaleY} for the spark's body */
    private float[] playerCenter() {
        if (ducking()) {
            return new float[]{playerX, groundY - (LEG_LEN * 0.4f + BODY_R * 0.6f) * u, 1.25f, 0.6f};
        }
        float bob = 0;
        if (lift <= 0 && state == State.RUNNING) bob = (float) Math.abs(Math.sin(runPhase)) * 0.8f;
        if (state == State.READY) bob = (float) Math.sin(idleTime * 3f) * 0.8f;
        return new float[]{playerX, groundY - (lift + LEG_LEN + BODY_R + bob) * u, 1f, 1f};
    }

    private RectF playerHitbox() {
        float[] c = playerCenter();
        float rx = BODY_R * 0.68f * c[2] * u;
        float ry = BODY_R * 0.68f * c[3] * u;
        return new RectF(c[0] - rx, c[1] - ry, c[0] + rx, groundY - lift * u - 0.5f * u);
    }

    private void obstacleHitbox(Obstacle o, RectF out) {
        float bottom = groundY - o.lift * u;
        float top = bottom - o.h;
        float insetX = o.w * 0.1f, insetY = o.h * 0.12f;
        out.set(o.x + insetX, top + insetY, o.x + o.w - insetX, bottom - (o.lift > 0 ? insetY : 0));
    }

    // ================================================================ draw

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0f : (now - lastFrameNs) / 1e9f;
        lastFrameNs = now;
        if (W > 0) update(Math.min(dt, 1f / 30f));

        int bg = mix(BG), fg = mix(FG);
        c.drawColor(bg);
        if (flashTime > 0) {
            fill.setColor(ORANGE);
            fill.setAlpha((int) (40 * flashTime / 0.6f));
            c.drawRect(0, 0, W, H, fill);
            fill.setAlpha(255);
        }

        drawBackground(c);
        drawGround(c, fg);
        for (Token t : tokens) drawToken(c, t);
        for (Obstacle o : obstacles) drawObstacle(c, o);
        drawPlayer(c);
        for (Particle q : particles) {
            fill.setColor(q.color);
            fill.setAlpha((int) (255 * Math.max(0, q.life / q.max)));
            c.drawCircle(q.x, q.y, q.size, fill);
        }
        fill.setAlpha(255);
        drawHud(c, fg, bg);

        if (hostActive) postInvalidateOnAnimation();
    }

    private void drawBackground(Canvas c) {
        int faint = mix(FAINT), cloud = mix(CLOUD);
        // sun / moon
        float sx = W * 0.82f, sy = 20 * u;
        fill.setColor(lerpColor(0xFFF0D9C4, 0xFFE8E2D6, nightT));
        c.drawCircle(sx, sy, 7 * u, fill);
        if (nightT > 0.01f) {
            fill.setColor(mix(BG));
            fill.setAlpha((int) (255 * nightT));
            c.drawCircle(sx + 3 * u, sy - 2 * u, 6 * u, fill);
            fill.setAlpha(255);
            // stars
            fill.setColor(0xFFE8E2D6);
            fill.setAlpha((int) (160 * nightT));
            for (int i = 0; i < 18; i++) {
                float x = ((i * 977) % 1000) / 1000f * W;
                float y = ((i * 571) % 1000) / 1000f * groundY * 0.7f;
                c.drawCircle(x, y, 0.35f * u, fill);
            }
            fill.setAlpha(255);
        }
        for (Deco d : decos) {
            if (d.kind == 2) {
                text.setTextSize(d.size);
                text.setColor(faint);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(d.glyph, d.x, d.y, text);
            } else {
                fill.setColor(cloud);
                float s = d.size;
                rect.set(d.x - s, d.y - s * 0.35f, d.x + s * 1.3f, d.y + s * 0.35f);
                c.drawRoundRect(rect, s * 0.35f, s * 0.35f, fill);
                c.drawCircle(d.x - s * 0.1f, d.y - s * 0.35f, s * 0.45f, fill);
                c.drawCircle(d.x + s * 0.55f, d.y - s * 0.3f, s * 0.35f, fill);
            }
        }
    }

    private void drawGround(Canvas c, int fg) {
        stroke.setColor(fg);
        stroke.setStrokeWidth(0.5f * u);
        c.drawLine(0, groundY, W, groundY, stroke);
        // scrolling pebbles / dashes generated from a hash of their tile index
        float step = 6 * u;
        long first = (long) Math.floor(groundScroll / step);
        stroke.setStrokeWidth(0.35f * u);
        for (long k = first; k < first + (long) (W / step) + 2; k++) {
            int hsh = (int) ((k * 2654435761L) >>> 8) & 0xFFFF;
            float x = k * step - groundScroll + (hsh % 100) / 100f * step;
            float y = groundY + (2 + (hsh >> 8) % 6) * u;
            float len = (0.5f + (hsh % 7) * 0.4f) * u;
            if (hsh % 3 != 0) c.drawLine(x, y, x + len, y, stroke);
        }
    }

    private void drawToken(Canvas c, Token t) {
        float y = groundY - t.lift * u + (float) Math.sin(t.phase * 4) * 0.8f * u;
        float r = 2.6f * u;
        float squash = (float) Math.abs(Math.cos(t.phase * 2.5f));
        fill.setColor(ORANGE);
        rect.set(t.x - r * Math.max(0.2f, squash), y - r, t.x + r * Math.max(0.2f, squash), y + r);
        c.drawOval(rect, fill);
        if (squash > 0.4f) {
            fill.setColor(0xFFFFF4EC);
            float s = r * 0.55f * squash;
            path.reset();
            path.moveTo(t.x, y - s);
            path.quadTo(t.x, y, t.x + s, y);
            path.quadTo(t.x, y, t.x, y + s);
            path.quadTo(t.x, y, t.x - s, y);
            path.quadTo(t.x, y, t.x, y - s);
            c.drawPath(path, fill);
        }
    }

    private void drawObstacle(Canvas c, Obstacle o) {
        float bottom = groundY - o.lift * u;
        switch (o.type) {
            case OB_BUG:
                for (int i = 0; i < o.count; i++) {
                    drawBug(c, o.x + (i * 8.5f + 4.25f) * u, bottom, o.phase + i * 0.7f);
                }
                break;
            case OB_BLOCK:
                for (int i = 0; i < o.count; i++) {
                    float h = o.h * (i == 0 ? 1f : 0.72f);
                    drawTerminal(c, o.x + i * 9.5f * u, bottom - h, 9f * u, h, o.label);
                }
                break;
            case OB_CLOUD:
                drawLimitCloud(c, o, bottom + (float) Math.sin(o.phase * 3) * 0.8f * u);
                break;
        }
    }

    private void drawBug(Canvas c, float cx, float bottom, float phase) {
        int col = mix(BUG);
        float bw = 3.6f * u, bh = 2.6f * u;
        float cy = bottom - 3.3f * u;
        // legs
        stroke.setColor(col);
        stroke.setStrokeWidth(0.45f * u);
        for (int i = -1; i <= 1; i++) {
            float wig = (float) Math.sin(phase * 14 + i * 2) * 0.6f * u;
            float lx = cx + i * 1.6f * u;
            c.drawLine(lx, cy, lx - 1.2f * u + wig, bottom, stroke);
            c.drawLine(lx, cy, lx + 1.2f * u - wig, bottom, stroke);
        }
        // body
        fill.setColor(col);
        rect.set(cx - bw, cy - bh, cx + bw, cy + bh);
        c.drawOval(rect, fill);
        // head (faces the player, i.e. left)
        c.drawCircle(cx - bw - 0.6f * u, cy - 0.3f * u, 1.5f * u, fill);
        // antennae
        c.drawLine(cx - bw - 1.2f * u, cy - 1.4f * u, cx - bw - 2.6f * u, cy - 3.4f * u, stroke);
        c.drawLine(cx - bw - 0.4f * u, cy - 1.5f * u, cx - bw - 0.9f * u, cy - 3.8f * u, stroke);
        // shell split + spots
        stroke.setColor(mix(BG));
        stroke.setStrokeWidth(0.3f * u);
        c.drawLine(cx - bw * 0.2f, cy - bh, cx - bw * 0.2f, cy + bh * 0.9f, stroke);
        fill.setColor(mix(BG));
        c.drawCircle(cx + 1.4f * u, cy - 0.8f * u, 0.5f * u, fill);
        c.drawCircle(cx - 1.6f * u, cy + 0.6f * u, 0.4f * u, fill);
        c.drawCircle(cx - bw - 1.0f * u, cy - 0.6f * u, 0.35f * u, fill); // eye
    }

    private void drawTerminal(Canvas c, float x, float top, float w, float h, String label) {
        float r = 1.2f * u;
        fill.setColor(mix(TERM));
        rect.set(x, top, x + w, top + h);
        c.drawRoundRect(rect, r, r, fill);
        // title bar dots
        fill.setColor(ORANGE);
        c.drawCircle(x + 1.6f * u, top + 1.6f * u, 0.55f * u, fill);
        fill.setColor(0xFFE0C08A);
        c.drawCircle(x + 3.2f * u, top + 1.6f * u, 0.55f * u, fill);
        fill.setColor(0xFF9DB38A);
        c.drawCircle(x + 4.8f * u, top + 1.6f * u, 0.55f * u, fill);
        // label
        text.setColor(ORANGE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(label.length() > 2 ? 2.6f * u : 3.6f * u);
        c.drawText(label, x + w / 2, top + Math.min(h * 0.5f, 7.5f * u), text);
        // code lines
        stroke.setColor(0xFF5A5650);
        stroke.setStrokeWidth(0.5f * u);
        for (float ly = top + 9.5f * u; ly < top + h - 1.2f * u; ly += 2f * u) {
            float len = (2f + ((int) (ly / u) * 37 % 5)) * u;
            c.drawLine(x + 1.5f * u, ly, x + 1.5f * u + Math.min(len, w - 3 * u), ly, stroke);
        }
    }

    private void drawLimitCloud(Canvas c, Obstacle o, float bottom) {
        float x = o.x, w = o.w, h = o.h;
        fill.setColor(mix(LIMIT));
        rect.set(x, bottom - h * 0.65f, x + w, bottom);
        c.drawRoundRect(rect, h * 0.32f, h * 0.32f, fill);
        c.drawCircle(x + w * 0.35f, bottom - h * 0.62f, h * 0.38f, fill);
        c.drawCircle(x + w * 0.65f, bottom - h * 0.55f, h * 0.3f, fill);
        // grumpy face + label
        text.setColor(mix(BG));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(3f * u);
        c.drawText(o.label, x + w * 0.6f, bottom - h * 0.18f, text);
        fill.setColor(mix(BG));
        c.drawCircle(x + w * 0.16f, bottom - h * 0.42f, 0.5f * u, fill);
        c.drawCircle(x + w * 0.28f, bottom - h * 0.42f, 0.5f * u, fill);
        stroke.setColor(mix(BG));
        stroke.setStrokeWidth(0.35f * u);
        c.drawLine(x + w * 0.12f, bottom - h * 0.56f, x + w * 0.2f, bottom - h * 0.5f, stroke);
        c.drawLine(x + w * 0.32f, bottom - h * 0.56f, x + w * 0.24f, bottom - h * 0.5f, stroke);
        // rain drops
        stroke.setColor(mix(LIMIT));
        for (int i = 0; i < 3; i++) {
            float dy = ((o.phase * 2 + i * 0.33f) % 1f) * 3 * u;
            float dx = x + w * (0.3f + i * 0.2f);
            c.drawLine(dx, bottom + 0.6f * u + dy, dx, bottom + 1.4f * u + dy, stroke);
        }
    }

    private void drawPlayer(Canvas c) {
        float[] pc = playerCenter();
        float cx = pc[0], cy = pc[1], sxs = pc[2], sys = pc[3];
        boolean dead = state == State.OVER;
        boolean grounded = lift <= 0f;

        // shadow
        fill.setColor(mix(FAINT));
        float sh = Math.max(0.3f, 1f - lift / 40f);
        rect.set(cx - 6 * u * sh, groundY - 0.6f * u, cx + 6 * u * sh, groundY + 0.8f * u);
        c.drawOval(rect, fill);

        // legs
        stroke.setColor(mix(FG));
        stroke.setStrokeWidth(0.9f * u);
        float hipY = cy + BODY_R * 0.35f * sys * u;
        if (dead) {
            c.drawLine(cx - 2 * u, hipY, cx - 3.5f * u, groundY - lift * u, stroke);
            c.drawLine(cx + 2 * u, hipY, cx + 3.5f * u, groundY - lift * u, stroke);
        } else if (grounded && state == State.RUNNING) {
            float a = (float) Math.sin(runPhase) * 2f * u;
            float footY = groundY - 0.2f * u;
            c.drawLine(cx - 1.8f * u, hipY, cx - 1.8f * u + a, footY - Math.max(0, a) * 0.4f, stroke);
            c.drawLine(cx + 1.8f * u, hipY, cx + 1.8f * u - a, footY - Math.max(0, -a) * 0.4f, stroke);
        } else if (!grounded) {
            c.drawLine(cx - 1.8f * u, hipY, cx - 2.6f * u, hipY + 2.2f * u, stroke);
            c.drawLine(cx + 1.8f * u, hipY, cx + 1.2f * u, hipY + 2.4f * u, stroke);
        } else {
            c.drawLine(cx - 1.8f * u, hipY, cx - 1.8f * u, groundY - 0.2f * u, stroke);
            c.drawLine(cx + 1.8f * u, hipY, cx + 1.8f * u, groundY - 0.2f * u, stroke);
        }

        c.save();
        c.translate(cx, cy);
        c.scale(sxs, sys);

        // the spark: 12 rounded rays with alternating lengths
        stroke.setColor(ORANGE);
        stroke.setStrokeWidth(2.5f * u);
        float wobble = state == State.READY ? (float) Math.sin(idleTime * 2) * 6f : 0f;
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 + spin + wobble);
            float len = (i % 2 == 0 ? BODY_R - 1.25f : BODY_R * 0.74f - 1.25f) * u;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cos * 2 * u, sin * 2 * u, cos * len, sin * len, stroke);
        }
        fill.setColor(ORANGE);
        c.drawCircle(0, 0, 4.4f * u, fill);

        // face (does not spin)
        float ex = 1.7f * u, ey = -0.6f * u;
        if (dead) {
            stroke.setColor(INK);
            stroke.setStrokeWidth(0.45f * u);
            float s = 0.8f * u;
            for (int k = -1; k <= 1; k += 2) {
                float x0 = k * ex;
                c.drawLine(x0 - s, ey - s, x0 + s, ey + s, stroke);
                c.drawLine(x0 - s, ey + s, x0 + s, ey - s, stroke);
            }
            fill.setColor(INK);
            c.drawCircle(0, 1.9f * u, 0.6f * u, fill);
        } else if (blinkTimer < 0 || ducking()) {
            stroke.setColor(INK);
            stroke.setStrokeWidth(0.45f * u);
            c.drawLine(-ex - 0.7f * u, ey, -ex + 0.7f * u, ey, stroke);
            c.drawLine(ex - 0.7f * u, ey, ex + 0.7f * u, ey, stroke);
        } else {
            fill.setColor(INK);
            rect.set(-ex - 0.65f * u, ey - 1.0f * u, -ex + 0.65f * u, ey + 1.0f * u);
            c.drawOval(rect, fill);
            rect.set(ex - 0.65f * u, ey - 1.0f * u, ex + 0.65f * u, ey + 1.0f * u);
            c.drawOval(rect, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(-ex + 0.25f * u, ey - 0.4f * u, 0.25f * u, fill);
            c.drawCircle(ex + 0.25f * u, ey - 0.4f * u, 0.25f * u, fill);
            // smile
            stroke.setColor(INK);
            stroke.setStrokeWidth(0.35f * u);
            rect.set(-1.1f * u, 0.4f * u, 1.1f * u, 2.0f * u);
            c.drawArc(rect, 20, 140, false, stroke);
        }
        // blush
        fill.setColor(0x55FFFFFF);
        c.drawCircle(-3.0f * u, 1.0f * u, 0.7f * u, fill);
        c.drawCircle(3.0f * u, 1.0f * u, 0.7f * u, fill);
        c.restore();
    }

    private void drawHud(Canvas c, int fg, int bg) {
        float pad = 4 * u;
        text.setTextSize(4.2f * u);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(fg);
        String s = pad5(score);
        c.drawText(s, W - pad, pad + 4 * u, text);
        float sw = text.measureText(s);
        text.setColor(lerpColor(0xFF9B9382, 0xFF8A8478, nightT));
        c.drawText("HI " + pad5(hiScore) + "  ", W - pad - sw, pad + 4 * u, text);

        if (state != State.READY) {
            // token counter
            fill.setColor(ORANGE);
            c.drawCircle(pad + 2 * u, pad + 2.6f * u, 2 * u, fill);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(fg);
            c.drawText("x " + tokenCount, pad + 5.5f * u, pad + 4 * u, text);
        }

        if (banner != null && bannerTime > 0 && state == State.RUNNING) {
            title.setColor(ORANGE);
            title.setAlpha((int) (255 * Math.min(1f, bannerTime / 0.4f)));
            title.setTextSize(6 * u);
            c.drawText(banner, W / 2, 24 * u, title);
            title.setAlpha(255);
        }

        switch (state) {
            case READY:
                title.setColor(fg);
                title.setTextSize(14 * u);
                c.drawText("Claude Run", W / 2, 30 * u, title);
                title.setTextSize(4.2f * u);
                title.setColor(ORANGE);
                c.drawText("Tap to start", W / 2, 42 * u + (float) Math.sin(idleTime * 4) * 0.8f * u, title);
                drawHint(c, fg);
                break;
            case PAUSED:
                overlay(c, bg);
                title.setColor(fg);
                title.setTextSize(10 * u);
                c.drawText("Paused", W / 2, 38 * u, title);
                title.setTextSize(4.2f * u);
                title.setColor(ORANGE);
                c.drawText("Tap to continue", W / 2, 48 * u, title);
                break;
            case OVER:
                overlay(c, bg);
                title.setColor(fg);
                title.setTextSize(9 * u);
                c.drawText(oopsLine, W / 2, 34 * u, title);
                title.setTextSize(4.5f * u);
                title.setColor(ORANGE);
                String sub = newBest ? "New best: " + score + "!" : "Score: " + score;
                c.drawText(sub, W / 2, 44 * u, title);
                if (overTime > 0.6f) {
                    title.setColor(fg);
                    title.setTextSize(4f * u);
                    c.drawText("Tap to try again", W / 2, 53 * u, title);
                }
                break;
            default:
                break;
        }
    }

    private void drawHint(Canvas c, int fg) {
        text.setTextSize(2.8f * u);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(lerpColor(fg, mix(BG), 0.35f));
        c.drawText("Tap right side: jump (hold = higher)   ·   Hold left side or swipe down: duck",
                W / 2, groundY + 12 * u, text);
        text.setColor(lerpColor(fg, mix(BG), 0.55f));
        c.drawText("Dodge bugs, errors and rate limits. Collect tokens.", W / 2, groundY + 16.5f * u, text);
    }

    private void overlay(Canvas c, int bg) {
        fill.setColor(bg);
        fill.setAlpha(110);
        c.drawRect(0, 0, W, H, fill);
        fill.setAlpha(255);
    }

    // ================================================================ helpers

    private static String pad5(int n) {
        String s = Integer.toString(Math.max(0, n));
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 5; i++) sb.append('0');
        return sb.append(s).toString();
    }

    private int mix(int[] pair) {
        return lerpColor(pair[0], pair[1], nightT);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a), aa = Color.alpha(a);
        return Color.argb(
                (int) (aa + (Color.alpha(b) - aa) * t),
                (int) (ar + (Color.red(b) - ar) * t),
                (int) (ag + (Color.green(b) - ag) * t),
                (int) (ab + (Color.blue(b) - ab) * t));
    }
}

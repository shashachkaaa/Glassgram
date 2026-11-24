package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Random;

/**
 * glassGram: snow for the winter look (GlassgramWinter). Flakes fall over the screen, swaying, and
 * those that come down on the glass (the tab bar, the header's buttons: Surfaces) settle on it as
 * a little drift along its top, thicker in the middle, thin where the glass curves away at its
 * ends. When the glass moves, its snow comes off and falls. The drifts melt slowly, so they stay
 * small.
 * <p>
 * Light: one view, a few dozen dots and a path per drift, at most 60 frames a second, and nothing
 * while it doesn't run (setRunning) or isn't shown. It takes no touches: the screen passes them on
 * (touchAt), and the glass a finger holds sheds its snow.
 */
public class GlassgramSnowView extends View {

    /** The glass the snow settles on, in this view's coordinates: fills the rects and their corner radii, returns how many. */
    public interface Surfaces {
        int collect(RectF[] out, float[] radii);
    }

    private static final int MAX_SURFACES = 12;
    private static final int MAX_CHUNKS = 140;
    private static final long FRAME_MS = 16;

    private final Random random = new Random();
    private final ArrayList<Flake> flakes = new ArrayList<>();
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private final Drift[] drifts = new Drift[MAX_SURFACES];
    private final RectF[] collected = new RectF[MAX_SURFACES];
    private final float[] collectedRadii = new float[MAX_SURFACES];
    private final Paint flakePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint driftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint driftEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Surfaces surfaces;
    private boolean running;
    private boolean slow;
    private float density = 1f;
    private long lastFrame;
    private int laidOutWidth, laidOutHeight;

    private static final class Flake {
        float x, y, r, vy, sway, swayFreq, phase, alpha;
        // how much it goes with the content moving under it: the near ones as much, the far ones less
        float depth;
    }

    private static final class Chunk {
        float x, y, vx, vy, r, alpha;
    }

    private static final class Drift {
        final RectF rect = new RectF();
        float radius;
        float column;
        float maxHeight;
        float[] heights;
        boolean valid;
        boolean dirty = true;
        final Path path = new Path();
        // the snow's top alone, outlined so a drift shows on light glass too
        final Path edge = new Path();
    }

    public GlassgramSnowView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        for (int i = 0; i < MAX_SURFACES; i++) {
            drifts[i] = new Drift();
            collected[i] = new RectF();
        }
        driftEdgePaint.setStyle(Paint.Style.STROKE);
        driftEdgePaint.setStrokeWidth(AndroidUtilities.dpf2(.8f));
        driftEdgePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Where the snow settles; null for nowhere (it only falls). */
    public void setSurfaces(Surfaces surfaces) {
        this.surfaces = surfaces;
    }

    /** Slower and fewer flakes, as behind the messages of a chat. */
    public void setSlow(boolean slow) {
        this.slow = slow;
        density = slow ? .55f : 1f;
        flakes.clear();
        if (getWidth() > 0) {
            fill(true);
        }
    }

    /** Falls while true; still and drawing nothing new otherwise. */
    public void setRunning(boolean running) {
        if (this.running == running) {
            return;
        }
        this.running = running;
        lastFrame = 0;
        if (running) {
            invalidate();
        } else {
            pushX = pushY = carryVx = carryVy = 0;
            dragging = false;
        }
    }

    // --- The content moving under it ----------------------------------------------------------
    // The snow goes with what is scrolled or swiped under it (a list, a chat, the folders, the tabs),
    // the far flakes less than the near ones, and carries on a little after it, slowing down: it
    // is in the air over the screen, not falling on its own apart from it

    private static final float MAX_CARRY = 6000f;
    private float pushX, pushY;
    private float carryVx, carryVy;
    private float carryDecay = 3f;

    /** The content under the snow moved by (dx, dy) px on the screen (a list scrolled, a page swiped). */
    public void push(float dx, float dy) {
        pushX += dx;
        pushY += dy;
        // a scroll of its own (a fling) carries on with its pushes: what is left after it, slower
        carryDecay = 4f;
        if (running && lastFrame != 0) {
            return;
        }
        invalidate();
    }

    /** The content goes on at (vx, vy) px/s as the finger leaves it (a fling): the snow carries on with it, slowing down. */
    public void fling(float vx, float vy) {
        carryVx = Math.max(-MAX_CARRY, Math.min(MAX_CARRY, vx));
        carryVy = Math.max(-MAX_CARRY, Math.min(MAX_CARRY, vy));
        // as a list flung on its own slows down
        carryDecay = 2.2f;
    }

    // A finger dragging over the screen moves the content under it, along the way it scrolls: the
    // snow goes with the finger, the same way, from the moment it is a drag (past the touch slop)
    private boolean dragPushes = true;
    private boolean dragging;
    private int dragAxis; // 0 none yet, 1 across, 2 down
    private float downX, downY, lastX, lastY, dragVx, dragVy;
    private long lastMoveAt;

    /** Whether the finger's drags move the snow (off where the content tells its scroll itself, a chat). */
    public void setDragPushes(boolean pushes) {
        dragPushes = pushes;
    }

    private void dragAt(float x, float y, int action) {
        if (!dragPushes) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            dragging = false;
            dragAxis = 0;
            downX = lastX = x;
            downY = lastY = y;
            dragVx = dragVy = 0;
            lastMoveAt = now;
            // a finger on the content stops what was carried on
            carryVx = carryVy = 0;
        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
            if (!dragging) {
                final float slop = AndroidUtilities.dpf2(10);
                final float ddx = x - downX, ddy = y - downY;
                if (ddx * ddx + ddy * ddy < slop * slop) {
                    return;
                }
                dragging = true;
                dragAxis = Math.abs(ddx) > Math.abs(ddy) ? 1 : 2;
                lastX = x;
                lastY = y;
                lastMoveAt = now;
                return;
            }
            final float dx = dragAxis == 1 ? x - lastX : 0f, dy = dragAxis == 2 ? y - lastY : 0f;
            final long elapsed = Math.max(1, now - lastMoveAt);
            dragVx = dragVx * .5f + dx * 1000f / elapsed * .5f;
            dragVy = dragVy * .5f + dy * 1000f / elapsed * .5f;
            lastX = x;
            lastY = y;
            lastMoveAt = now;
            push(dx, dy);
        } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            if (dragging && action == android.view.MotionEvent.ACTION_UP && now - lastMoveAt < 80) {
                fling(dragVx, dragVy);
            }
            dragging = false;
        }
    }

    /** Shakes the snow off the glass, as when the screen changes. */
    public void shake() {
        for (Drift drift : drifts) {
            if (drift.valid) {
                shed(drift, Float.NaN, Float.NaN);
            }
        }
    }

    // The glass a finger holds (pressed, pulled, dragged about): its snow comes off as the finger
    // comes down, and none settles on it, where it was or where it is pulled, until it is let go
    // and has sprung back
    private static final long HOLD_AFTER_MS = 500;
    private final RectF held = new RectF();
    private boolean holding;
    private long heldUntil;
    private int collectedCount;

    /**
     * Every touch of the screen, in this view's coordinates: the glass under the finger shakes its
     * snow off, the snow flying away from the finger, and holds none while it is held.
     */
    public void touchAt(float x, float y, int action) {
        dragAt(x, y, action);
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            holding = false;
            held.setEmpty();
            final float reach = AndroidUtilities.dpf2(10);
            for (int i = 0; i < collectedCount; i++) {
                final RectF r = collected[i];
                if (x >= r.left - reach && x <= r.right + reach && y >= r.top - reach * 2 && y <= r.bottom + reach) {
                    if (held.isEmpty()) {
                        held.set(r);
                    } else {
                        held.union(r);
                    }
                    holding = true;
                }
            }
            boolean shed = false;
            for (Drift drift : drifts) {
                if (drift.valid && (holding && RectF.intersects(held, drift.rect)
                        || x >= drift.rect.left - reach && x <= drift.rect.right + reach && y >= drift.rect.top - reach * 2 && y <= drift.rect.bottom + reach)) {
                    shed(drift, x, y);
                    shed = true;
                }
            }
            if (shed && !running) {
                invalidate();
            }
        } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            if (holding) {
                holding = false;
                heldUntil = SystemClock.uptimeMillis() + HOLD_AFTER_MS;
            }
        }
    }

    /** Whether the glass at r is held by a finger, or springing back from it: no snow lies on it. */
    private boolean isHeld(RectF r, long now) {
        return (holding || now < heldUntil) && !held.isEmpty() && RectF.intersects(held, r);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != laidOutWidth || h != laidOutHeight) {
            laidOutWidth = w;
            laidOutHeight = h;
            flakes.clear();
            fill(true);
        }
    }

    private int flakesWanted() {
        final float wDp = getWidth() / AndroidUtilities.density, hDp = getHeight() / AndroidUtilities.density;
        return (int) Utilities.clamp(wDp * hDp / 6500f * density, 72, 12);
    }

    private void fill(boolean anywhere) {
        final int wanted = flakesWanted();
        while (flakes.size() < wanted) {
            final Flake flake = new Flake();
            spawn(flake, anywhere);
            flakes.add(flake);
        }
    }

    private void spawn(Flake flake, boolean anywhere) {
        final float near = random.nextFloat();
        // most are small and far; a few near, bigger and faster
        flake.r = AndroidUtilities.dpf2(1.1f + 1.9f * near * near);
        flake.vy = AndroidUtilities.dpf2((slow ? 14f : 26f) + (slow ? 20f : 36f) * near);
        flake.sway = AndroidUtilities.dpf2(5f + 12f * random.nextFloat());
        flake.swayFreq = .5f + .9f * random.nextFloat();
        flake.phase = random.nextFloat() * 6.283f;
        flake.alpha = .45f + .5f * near;
        flake.depth = .3f + .6f * near;
        flake.x = random.nextFloat() * Math.max(1, getWidth());
        flake.y = anywhere ? random.nextFloat() * Math.max(1, getHeight()) : -flake.r - random.nextFloat() * AndroidUtilities.dp(40);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final float dt = lastFrame == 0 ? 0f : Math.min(.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (running && dt > 0) {
            step(dt, now / 1000f);
        }
        final boolean dark = Theme.isCurrentThemeDark();
        final int flakeColor = dark ? 0xFFFFFFFF : 0xFF9DB2CB;
        flakePaint.setColor(flakeColor);
        for (int i = 0; i < flakes.size(); i++) {
            final Flake flake = flakes.get(i);
            flakePaint.setAlpha((int) (0xFF * flake.alpha * (dark ? .9f : .75f)));
            canvas.drawCircle(flake.x, flake.y, flake.r, flakePaint);
        }
        if (driftLayer != null) {
            // the snow lying on the glass is drawn over it, by the layer above
            driftLayer.invalidate();
        } else {
            drawDrifts(canvas);
        }
        if (running) {
            postInvalidateDelayed(Math.max(0, FRAME_MS - (SystemClock.uptimeMillis() - now)));
        }
    }

    private void drawDrifts(Canvas canvas) {
        final boolean dark = Theme.isCurrentThemeDark();
        driftPaint.setColor(dark ? 0xF2FFFFFF : 0xFFFFFFFF);
        driftEdgePaint.setColor(dark ? 0x40FFFFFF : 0x26314558);
        for (Drift drift : drifts) {
            if (!drift.valid) {
                continue;
            }
            if (drift.dirty) {
                buildPath(drift);
            }
            if (!drift.path.isEmpty()) {
                canvas.drawPath(drift.path, driftPaint);
                canvas.drawPath(drift.edge, driftEdgePaint);
            }
        }
        for (int i = 0; i < chunks.size(); i++) {
            final Chunk chunk = chunks.get(i);
            flakePaint.setColor(dark ? 0xFFFFFFFF : 0xFFE9EFF6);
            flakePaint.setAlpha((int) (0xFF * chunk.alpha));
            canvas.drawCircle(chunk.x, chunk.y, chunk.r, flakePaint);
        }
    }

    private DriftLayer driftLayer;

    /**
     * The snow lying on the glass, over the glass while the falling view stays behind it (the
     * messages of a chat). It draws in the falling view's coordinates, wherever the two are laid
     * out, and takes no touches: the screen passes them to touchAt.
     */
    public final class DriftLayer extends View {
        private DriftLayer(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(GlassgramSnowView.this.getX() - getX(), GlassgramSnowView.this.getY() - getY());
            drawDrifts(canvas);
            canvas.restore();
        }
    }

    public View createDriftLayer(Context context) {
        driftLayer = new DriftLayer(context);
        return driftLayer;
    }

    private void step(float dt, float time) {
        // the glass, where it is now
        int count = 0;
        if (surfaces != null) {
            count = Math.min(MAX_SURFACES, surfaces.collect(collected, collectedRadii));
        }
        collectedCount = count;
        final long uptime = SystemClock.uptimeMillis();
        for (int i = 0; i < MAX_SURFACES; i++) {
            final Drift drift = drifts[i];
            if (i < count && collected[i].width() > AndroidUtilities.dp(8) && collected[i].height() > AndroidUtilities.dp(8)
                    && !isHeld(collected[i], uptime)) {
                final RectF r = collected[i];
                if (drift.valid && (Math.abs(drift.rect.left - r.left) > AndroidUtilities.dp(1) || Math.abs(drift.rect.top - r.top) > AndroidUtilities.dp(1)
                        || Math.abs(drift.rect.width() - r.width()) > AndroidUtilities.dp(2))) {
                    shed(drift, Float.NaN, Float.NaN);
                }
                if (!drift.valid) {
                    setUp(drift, r, collectedRadii[i]);
                }
            } else if (drift.valid) {
                shed(drift, Float.NaN, Float.NaN);
            }
        }

        // what the content under the snow moved by since the last frame, or what it carries on with
        float moveX = 0f, moveY = 0f;
        if (pushX != 0f || pushY != 0f) {
            moveX = pushX;
            moveY = pushY;
            // its speed, to carry on at once the pushes stop
            carryVx = Math.max(-MAX_CARRY, Math.min(MAX_CARRY, carryVx * .6f + pushX / dt * .4f));
            carryVy = Math.max(-MAX_CARRY, Math.min(MAX_CARRY, carryVy * .6f + pushY / dt * .4f));
            pushX = pushY = 0f;
        } else if (!dragging && (carryVx != 0f || carryVy != 0f)) {
            moveX = carryVx * dt;
            moveY = carryVy * dt;
            final float decay = (float) Math.exp(-dt * carryDecay);
            carryVx *= decay;
            carryVy *= decay;
            if (Math.abs(carryVx) < AndroidUtilities.dpf2(8) && Math.abs(carryVy) < AndroidUtilities.dpf2(8)) {
                carryVx = carryVy = 0f;
            }
        }

        // the flakes go with it, fall, sway, and settle on the glass they come down on
        final int width = getWidth(), height = getHeight();
        final float margin = AndroidUtilities.dpf2(40);
        for (int i = 0; i < flakes.size(); i++) {
            final Flake flake = flakes.get(i);
            if (moveX != 0f || moveY != 0f) {
                flake.x += moveX * flake.depth;
                flake.y += moveY * flake.depth;
                // carried up past the top: it comes back in from under the bottom
                if (flake.y < -flake.r - margin) {
                    flake.y += height + margin + flake.r * 2;
                }
            }
            final float prevY = flake.y;
            flake.y += flake.vy * dt;
            flake.x += (float) Math.sin(flake.phase + time * flake.swayFreq * 6.283f) * flake.sway * dt;
            boolean landed = false;
            for (int s = 0; s < count && !landed; s++) {
                final Drift drift = drifts[s];
                if (!drift.valid || flake.x < drift.rect.left || flake.x > drift.rect.right) {
                    continue;
                }
                final float surface = contour(drift, flake.x) - heightAt(drift, flake.x);
                if (prevY + flake.r <= surface && flake.y + flake.r >= surface) {
                    settle(drift, flake.x, flake.r);
                    landed = true;
                }
            }
            if (landed || flake.y - flake.r > height) {
                spawn(flake, false);
            } else if (flake.x < -flake.r * 4) {
                flake.x += width + flake.r * 8;
            } else if (flake.x > width + flake.r * 4) {
                flake.x -= width + flake.r * 8;
            }
        }
        fill(false);

        // the drifts melt slowly and slide where they are too steep
        final float melt = 1f - .006f * dt;
        for (int s = 0; s < MAX_SURFACES; s++) {
            final Drift drift = drifts[s];
            if (!drift.valid) {
                continue;
            }
            final float[] h = drift.heights;
            boolean any = false;
            for (int c = 0; c < h.length; c++) {
                if (h[c] > 0) {
                    h[c] *= melt;
                    any = true;
                }
            }
            if (any) {
                final float steep = drift.column * .55f;
                for (int c = 0; c + 1 < h.length; c++) {
                    final float d = h[c] - h[c + 1];
                    if (Math.abs(d) > steep) {
                        final float move = (Math.abs(d) - steep) * .5f * Math.signum(d);
                        h[c] -= move;
                        h[c + 1] += move;
                    }
                }
                for (int c = 0; c < h.length; c++) {
                    final float cap = capAt(drift, drift.rect.left + c * drift.column);
                    if (h[c] > cap) {
                        h[c] = cap;
                    }
                }
                drift.dirty = true;
            }
        }

        // the snow that came off falls and fades
        final float gravity = AndroidUtilities.dpf2(900f);
        for (int i = chunks.size() - 1; i >= 0; i--) {
            final Chunk chunk = chunks.get(i);
            chunk.x += moveX;
            chunk.y += moveY;
            chunk.vy += gravity * dt;
            chunk.x += chunk.vx * dt;
            chunk.y += chunk.vy * dt;
            chunk.alpha -= dt * .9f;
            if (chunk.alpha <= 0 || chunk.y - chunk.r > height) {
                chunks.remove(i);
            }
        }
    }

    private void setUp(Drift drift, RectF r, float radius) {
        drift.rect.set(r);
        drift.radius = Math.min(radius, Math.min(r.width(), r.height()) / 2f);
        drift.column = AndroidUtilities.dpf2(2.5f);
        drift.maxHeight = AndroidUtilities.dpf2(r.width() > AndroidUtilities.dp(160) ? 5.5f : 4f);
        final int columns = (int) Math.ceil(r.width() / drift.column) + 1;
        if (drift.heights == null || drift.heights.length != columns) {
            drift.heights = new float[columns];
        } else {
            java.util.Arrays.fill(drift.heights, 0f);
        }
        drift.valid = true;
        drift.dirty = true;
    }

    /** The glass's top edge at x: flat, curving down at its rounded ends. */
    private static float contour(Drift drift, float x) {
        final RectF r = drift.rect;
        final float radius = drift.radius;
        float dx = 0;
        if (x < r.left + radius) {
            dx = r.left + radius - x;
        } else if (x > r.right - radius) {
            dx = x - (r.right - radius);
        }
        if (dx <= 0 || radius <= 0) {
            return r.top;
        }
        dx = Math.min(dx, radius);
        return r.top + radius - (float) Math.sqrt(radius * radius - dx * dx);
    }

    /** How deep the snow may lie at x: full where the glass is flat, none where it curves down steeply. */
    private static float capAt(Drift drift, float x) {
        final float drop = contour(drift, x) - drift.rect.top;
        final float k = drift.radius > 0 ? 1f - Math.min(1f, drop / (drift.radius * .45f)) : 1f;
        return drift.maxHeight * k;
    }

    private static float heightAt(Drift drift, float x) {
        final float[] h = drift.heights;
        final float p = (x - drift.rect.left) / drift.column;
        final int c = (int) p;
        if (c < 0 || c >= h.length) {
            return 0;
        }
        final float f = p - c;
        return c + 1 < h.length ? h[c] * (1 - f) + h[c + 1] * f : h[c];
    }

    private void settle(Drift drift, float x, float r) {
        final float[] h = drift.heights;
        final int c = Math.round((x - drift.rect.left) / drift.column);
        // a small button catches few flakes: each one lies thicker there, or it stayed bare
        final float narrow = Math.max(1f, Math.min(3f, AndroidUtilities.dpf2(160) / Math.max(1f, drift.rect.width())));
        final float amount = r * 1.1f * narrow;
        for (int d = -2; d <= 2; d++) {
            final int i = c + d;
            if (i < 0 || i >= h.length) {
                continue;
            }
            final float share = d == 0 ? .4f : Math.abs(d) == 1 ? .22f : .08f;
            final float cap = capAt(drift, drift.rect.left + i * drift.column);
            h[i] = Math.min(cap, h[i] + amount * share);
        }
        drift.dirty = true;
    }

    /** The drift's snow comes off and falls: away from (fromX, fromY) when a finger shook it, else just down. */
    private void shed(Drift drift, float fromX, float fromY) {
        final float[] h = drift.heights;
        final boolean pushed = !Float.isNaN(fromX);
        if (h != null) {
            for (int c = 0; c < h.length && chunks.size() < MAX_CHUNKS; c += 2) {
                if (h[c] < AndroidUtilities.dpf2(.6f)) {
                    continue;
                }
                final Chunk chunk = new Chunk();
                final float x = drift.rect.left + c * drift.column;
                chunk.x = x;
                chunk.y = contour(drift, x) - h[c] * .5f;
                chunk.r = Math.min(h[c] * .55f, AndroidUtilities.dpf2(2f));
                if (pushed) {
                    // flung up and away from the finger
                    final float away = Math.signum(x - fromX);
                    chunk.vx = AndroidUtilities.dpf2(away * (40f + 90f * random.nextFloat()));
                    chunk.vy = AndroidUtilities.dpf2(-120f - 120f * random.nextFloat());
                } else {
                    chunk.vx = AndroidUtilities.dpf2(-30f + 60f * random.nextFloat());
                    chunk.vy = AndroidUtilities.dpf2(-60f * random.nextFloat());
                }
                chunk.alpha = .95f;
                chunks.add(chunk);
            }
        }
        drift.valid = false;
        drift.path.rewind();
        drift.edge.rewind();
    }

    private void buildPath(Drift drift) {
        drift.dirty = false;
        final Path path = drift.path;
        final Path edge = drift.edge;
        path.rewind();
        edge.rewind();
        final float[] h = drift.heights;
        float most = 0;
        for (float v : h) {
            most = Math.max(most, v);
        }
        if (most < AndroidUtilities.dpf2(.35f)) {
            return;
        }
        final RectF r = drift.rect;
        final int last = h.length - 1;
        // the snow's top, left to right, smoothed through the middles of the columns
        float px = r.left, py = contour(drift, r.left) - h[0];
        path.moveTo(px, py);
        edge.moveTo(px, py);
        for (int c = 1; c <= last; c++) {
            final float x = Math.min(r.right, r.left + c * drift.column);
            final float y = contour(drift, x) - h[c];
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            edge.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x;
            py = y;
        }
        path.lineTo(px, py);
        edge.lineTo(px, py);
        // and back along the glass
        for (int c = last; c >= 0; c--) {
            final float x = Math.min(r.right, r.left + c * drift.column);
            path.lineTo(x, contour(drift, x));
        }
        path.close();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        lastFrame = 0;
    }
}

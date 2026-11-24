package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import org.telegram.messenger.AndroidUtilities;

/**
 * glassGram: frost on a glass button, for a winter profile (GlassgramBadges.PERK_WINTER_PROFILE):
 * its rim iced, thickest at the top, feathers of frost growing in from the edge, and a few sparks
 * of snow. A round button and a capsule (a pill) each get their own; the same button gets the same
 * frost every time (seed). Nothing moves: whoever draws it redraws it only when the button moves.
 */
public final class GlassgramFrost {

    private static final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pillRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint featherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path clip = new Path();
    private static final RectF rect = new RectF();
    // one feather: its stem and three pairs of branches, as line segments
    private static final int FEATHERS = 9;
    private static final float[] stems = new float[FEATHERS * 4];
    private static final float[] branches = new float[FEATHERS * 6 * 4];
    private static int seedState;

    static {
        // in a unit circle: clear inside, iced from .8 of the radius out
        rimPaint.setShader(new RadialGradient(0, 0, 1, new int[]{0x00DCEBFF, 0x00DCEBFF, 0x52E1EEFF}, new float[]{0, .755f, 1}, Shader.TileMode.CLAMP));
        // from the top of a unit height down
        pillRimPaint.setShader(new LinearGradient(0, 0, 0, .45f, 0x52E1EEFF, 0x00E1EEFF, Shader.TileMode.CLAMP));
        featherPaint.setStyle(Paint.Style.STROKE);
        featherPaint.setStrokeCap(Paint.Cap.ROUND);
        featherPaint.setColor(0xB3F0F8FF);
        sparkPaint.setColor(0xE6FFFFFF);
    }

    private GlassgramFrost() {
    }

    private static float random() {
        seedState = seedState * 1103515245 + 12345;
        return ((seedState >>> 8) & 0xFFFF) / 65536f;
    }

    /**
     * The feathers growing in from the edge, each from (x, y) at the angle given by angleOf:
     * filled into stems and branches; returns how many.
     */
    private interface Anchor {
        // fills out[0] x, out[1] y, out[2] the angle into the glass, for the i-th feather
        void at(int i, float[] out);
    }

    private static final float[] anchor = new float[3];

    private static int feathers(int count, float size, Anchor where) {
        int s = 0, b = 0;
        for (int i = 0; i < count; i++) {
            where.at(i, anchor);
            final float x0 = anchor[0], y0 = anchor[1];
            final double ia = anchor[2] + (random() - .5f) * .5f;
            final float len = size * (.18f + .22f * random());
            final float x1 = x0 + (float) Math.cos(ia) * len, y1 = y0 + (float) Math.sin(ia) * len;
            stems[s++] = x0;
            stems[s++] = y0;
            stems[s++] = x1;
            stems[s++] = y1;
            for (int j = 1; j <= 3; j++) {
                final float t = j / 4f;
                final float px = x0 + (x1 - x0) * t, py = y0 + (y1 - y0) * t;
                final float bl = len * (.35f - .07f * j);
                for (int side = -1; side <= 1; side += 2) {
                    final double ba = ia + side * .75;
                    branches[b++] = px;
                    branches[b++] = py;
                    branches[b++] = px + (float) Math.cos(ba) * bl;
                    branches[b++] = py + (float) Math.sin(ba) * bl;
                }
            }
        }
        return count;
    }

    private static void drawFeathers(Canvas canvas, int count, float alpha) {
        final float dp = AndroidUtilities.density;
        featherPaint.setAlpha((int) (0xB3 * alpha));
        featherPaint.setStrokeWidth(Math.max(1f, dp * .7f));
        canvas.drawLines(stems, 0, count * 4, featherPaint);
        featherPaint.setStrokeWidth(Math.max(.8f, dp * .5f));
        canvas.drawLines(branches, 0, count * 6 * 4, featherPaint);
    }

    /** Frost on a round button: center cx, cy, radius r. */
    public static void drawCircle(Canvas canvas, float cx, float cy, float r, float alpha, int seed) {
        if (r <= 2f || alpha <= 0f) {
            return;
        }
        seedState = seed * 7919 + 17;
        canvas.save();
        clip.rewind();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.save();
        canvas.translate(cx, cy - r * .1f);
        canvas.scale(r * 1.06f, r * 1.06f);
        rimPaint.setAlpha((int) (255 * alpha));
        canvas.drawRect(-2f, -2f, 2f, 2f, rimPaint);
        canvas.restore();
        // from the top half of the edge, two more from the bottom
        final int count = feathers(FEATHERS, r, (i, out) -> {
            final double a = -Math.PI * .95 + random() * Math.PI * .9 + (i > 6 ? Math.PI * .9 : 0);
            out[0] = cx + (float) Math.cos(a) * r;
            out[1] = cy + (float) Math.sin(a) * r;
            out[2] = (float) (a + Math.PI);
        });
        drawFeathers(canvas, count, alpha);
        canvas.restore();
        // a little snow sparkling on the rim
        final float dp = AndroidUtilities.density;
        sparkPaint.setAlpha((int) (0xE6 * alpha));
        for (int i = 0; i < 3; i++) {
            final double a = -Math.PI * .8 + i * .55;
            canvas.drawCircle(cx + (float) Math.cos(a) * r * .93f, cy + (float) Math.sin(a) * r * .93f, dp * .9f, sparkPaint);
        }
    }

    /** Frost on a capsule (a pill): its rect and corner radius. */
    public static void drawPill(Canvas canvas, RectF pill, float radius, float alpha, int seed) {
        if (pill.width() <= 4f || pill.height() <= 4f || alpha <= 0f) {
            return;
        }
        seedState = seed * 7919 + 17;
        final float h = pill.height();
        canvas.save();
        clip.rewind();
        rect.set(pill);
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.save();
        canvas.translate(pill.left, pill.top);
        canvas.scale(1f, h);
        pillRimPaint.setAlpha((int) (255 * alpha));
        canvas.drawRect(0, 0, pill.width(), 1f, pillRimPaint);
        canvas.restore();
        // down from the top edge, and in from both ends
        final int count = feathers(Math.min(FEATHERS, 3 + (int) (pill.width() / (h * 1.2f))), h * 1.3f, (i, out) -> {
            if (i == 0) {
                out[0] = pill.left + h * .12f;
                out[1] = pill.top + h * .5f;
                out[2] = 0f;
            } else if (i == 1) {
                out[0] = pill.right - h * .12f;
                out[1] = pill.top + h * .5f;
                out[2] = (float) Math.PI;
            } else {
                out[0] = pill.left + radius + (pill.width() - 2 * radius) * random();
                out[1] = pill.top;
                out[2] = (float) (Math.PI / 2);
            }
        });
        drawFeathers(canvas, count, alpha);
        canvas.restore();
        final float dp = AndroidUtilities.density;
        sparkPaint.setAlpha((int) (0xE6 * alpha));
        for (int i = 0; i < 3; i++) {
            canvas.drawCircle(pill.left + radius + (pill.width() - 2 * radius) * (.15f + .35f * i), pill.top + dp * 1.5f, dp * .9f, sparkPaint);
        }
    }
}

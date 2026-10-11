package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.GlassgramBadges;

import java.util.Random;

/**
 * glassGram: a badge holder's own effect around their avatar in the profile, in their name
 * colors: sparkles twinkling around it, a glowing halo, or dots orbiting it. It sits behind the
 * avatar and follows it as the header collapses, fading out when the avatar opens wide.
 */
public class GlassgramProfileEffectView extends View {

    public interface Target {
        /** The avatar's circle in this view's coordinates; false when there is none to draw around. */
        boolean getAvatarBounds(RectF out);

        /** 0..1: how much of the effect shows (none while the avatar is open wide). */
        float getEffectVisibility();
    }

    private static final int[] DEFAULT_COLORS = {0xFFFFFFFF, 0xFFB9C3FF};
    private static final int SPARKS = 16;

    private final Target target;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path star = new Path();
    private final RectF avatar = new RectF();
    private final Matrix matrix = new Matrix();
    private final float[] sparkAngle = new float[SPARKS];
    private final float[] sparkDistance = new float[SPARKS];
    private final float[] sparkPhase = new float[SPARKS];
    private final float[] sparkSize = new float[SPARKS];
    private final float[] sparkSpeed = new float[SPARKS];

    private String effect;
    private int[] colors = DEFAULT_COLORS;
    private final long start = SystemClock.elapsedRealtime();

    public GlassgramProfileEffectView(Context context, Target target) {
        super(context);
        this.target = target;
        ringPaint.setStyle(Paint.Style.STROKE);
        final Random random = new Random(7);
        for (int i = 0; i < SPARKS; i++) {
            sparkAngle[i] = random.nextFloat() * 360f;
            sparkDistance[i] = 1.12f + random.nextFloat() * 0.55f;
            sparkPhase[i] = random.nextFloat();
            sparkSize[i] = 2.2f + random.nextFloat() * 2.8f;
            sparkSpeed[i] = (random.nextBoolean() ? 1 : -1) * (4 + random.nextFloat() * 8);
        }
    }

    /** Shows the effect of the badge's holder, or none. */
    public void setBadge(GlassgramBadges.Badge badge) {
        final String newEffect = badge != null ? badge.effect : null;
        final int[] newColors = badge != null && badge.nameColors != null ? badge.nameColors : DEFAULT_COLORS;
        if (java.util.Objects.equals(effect, newEffect) && java.util.Arrays.equals(colors, newColors)) {
            return;
        }
        effect = newEffect;
        colors = newColors;
        setVisibility(effect != null ? VISIBLE : GONE);
        invalidate();
    }

    private int color(float t) {
        if (colors.length == 1) {
            return colors[0];
        }
        t = (t % 1f + 1f) % 1f * (colors.length - 1);
        final int i = Math.min(colors.length - 2, (int) t);
        return ColorUtils.blendARGB(colors[i], colors[i + 1], t - i);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (effect == null || !target.getAvatarBounds(avatar)) {
            return;
        }
        final float visibility = Math.max(0, Math.min(1, target.getEffectVisibility()));
        final float r = avatar.width() / 2f;
        // Too small to carry an effect (the header collapsed into the action bar)
        final float sizeFactor = Math.max(0, Math.min(1, (r - dp(16)) / dp(14)));
        final float alpha = visibility * sizeFactor;
        if (alpha <= 0) {
            invalidate();
            return;
        }
        final float cx = avatar.centerX(), cy = avatar.centerY();
        final float time = (SystemClock.elapsedRealtime() - start) / 1000f;
        switch (effect) {
            case GlassgramBadges.EFFECT_HALO:
                drawHalo(canvas, cx, cy, r, time, alpha);
                break;
            case GlassgramBadges.EFFECT_ORBIT:
                drawOrbit(canvas, cx, cy, r, time, alpha);
                break;
            default:
                drawSparkles(canvas, cx, cy, r, time, alpha);
                break;
        }
        invalidate();
    }

    private void drawSparkles(Canvas canvas, float cx, float cy, float r, float time, float alpha) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < SPARKS; i++) {
            // Each spark twinkles on its own beat while drifting slowly around the avatar
            final float beat = (time * 0.55f + sparkPhase[i]) % 1f;
            final float twinkle = (float) Math.sin(beat * Math.PI);
            if (twinkle <= 0.02f) {
                continue;
            }
            final double angle = Math.toRadians(sparkAngle[i] + time * sparkSpeed[i]);
            final float distance = r * (sparkDistance[i] + 0.08f * beat);
            final float x = cx + (float) Math.cos(angle) * distance;
            final float y = cy + (float) Math.sin(angle) * distance;
            final float size = dp(sparkSize[i]) * (0.5f + 0.5f * twinkle);
            paint.setColor(color(i / (float) SPARKS));
            paint.setAlpha((int) (255 * alpha * twinkle));
            star.rewind();
            star.moveTo(x, y - size);
            star.quadTo(x, y, x + size, y);
            star.quadTo(x, y, x, y + size);
            star.quadTo(x, y, x - size, y);
            star.quadTo(x, y, x, y - size);
            star.close();
            canvas.drawPath(star, paint);
        }
    }

    private void drawHalo(Canvas canvas, float cx, float cy, float r, float time, float alpha) {
        // A soft glow that breathes, and a thin ring of the colors turning around the avatar
        final float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2.2f);
        final float glow = r * (1.45f + 0.08f * pulse);
        final int c = color(0);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, glow,
            new int[]{ColorUtils.setAlphaComponent(c, (int) (150 * alpha)), ColorUtils.setAlphaComponent(c, (int) (60 * alpha * (0.6f + 0.4f * pulse))), 0},
            new float[]{r / glow, (r + (glow - r) * 0.35f) / glow, 1f}, Shader.TileMode.CLAMP));
        paint.setAlpha(255);
        canvas.drawCircle(cx, cy, glow, paint);
        paint.setShader(null);

        final int n = Math.max(2, colors.length);
        final int[] ring = new int[n + 1];
        for (int i = 0; i < n; i++) {
            ring[i] = ColorUtils.setAlphaComponent(colors[i % colors.length], (int) (230 * alpha));
        }
        ring[n] = ring[0];
        final SweepGradient sweep = new SweepGradient(cx, cy, ring, null);
        matrix.setRotate(time * 60f, cx, cy);
        sweep.setLocalMatrix(matrix);
        ringPaint.setShader(sweep);
        ringPaint.setStrokeWidth(dp(2.5f));
        canvas.drawCircle(cx, cy, r + dp(4), ringPaint);
    }

    private void drawOrbit(Canvas canvas, float cx, float cy, float r, float time, float alpha) {
        // Three dots circling the avatar at different speeds, each with a fading tail
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        final float[] radius = {r + dp(9), r + dp(16), r + dp(23)};
        final float[] speed = {80f, -55f, 38f};
        for (int i = 0; i < 3; i++) {
            final int c = color(i / 3f);
            for (int t = 0; t < 7; t++) {
                final double angle = Math.toRadians(time * speed[i] + i * 120 - t * Math.signum(speed[i]) * 5.5f);
                final float x = cx + (float) Math.cos(angle) * radius[i];
                final float y = cy + (float) Math.sin(angle) * radius[i];
                final float fade = 1f - t / 7f;
                paint.setColor(c);
                paint.setAlpha((int) (255 * alpha * fade * fade));
                canvas.drawCircle(x, y, dp(2.6f) * (0.45f + 0.55f * fade), paint);
            }
        }
    }
}

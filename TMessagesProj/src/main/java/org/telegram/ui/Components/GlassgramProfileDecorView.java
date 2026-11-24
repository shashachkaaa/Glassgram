package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * glassGram: a profile's winter decorations (GlassgramBadges.PERK_*), over its header in the winter
 * look: the garland around the avatar (GlassgramAvatarGarland) and, for a winter profile, the frost
 * on its glass buttons (GlassgramFrost). It follows the avatar and the buttons wherever they go:
 * before every frame it asks its Source where they are, and draws again only when that changed,
 * or every GlassgramAvatarGarland.FRAME_MS while the garland blinks. It takes no touches.
 */
public class GlassgramProfileDecorView extends View {

    /** A glass button to frost: its rect, its corner radius (half its height: a circle or a pill) and alpha. */
    public interface Glass {
        void add(RectF rect, float radius, float alpha);
    }

    public interface Source {
        /** Where the avatar's circle is (out: center x, y and radius, in this view); the decorations' alpha, 0 for none. */
        float place(float[] avatar);

        boolean garland();

        boolean frost();

        /** The glass buttons to frost, in this view's coordinates. */
        void forEachGlass(Glass glass);
    }

    private final Source source;
    private final float[] avatar = new float[3];
    private float drawnSum = Float.NaN;
    private float sum;
    private final Glass summing = (rect, radius, alpha) -> sum += rect.left * 3 + rect.top * 5 + rect.right * 7 + rect.bottom * 11 + alpha * 13;
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        if (Math.abs(checksum() - drawnSum) > .3f) {
            invalidate();
        }
        return true;
    };
    private Canvas drawing;
    private float drawingAlpha;
    private int glassIndex;
    private final Glass frosting = (rect, radius, alpha) -> {
        final int seed = glassIndex++;
        final float w = rect.width(), h = rect.height();
        if (w <= 0f || h <= 0f) {
            return;
        }
        if (Math.max(w, h) / Math.min(w, h) < 1.3f) {
            // a round button, stretched as it is pulled: its frost stretched with it
            final float d = (w + h) / 2f;
            drawing.save();
            drawing.translate(rect.centerX(), rect.centerY());
            drawing.scale(w / d, h / d);
            GlassgramFrost.drawCircle(drawing, 0f, 0f, d / 2f, drawingAlpha * alpha, seed);
            drawing.restore();
        } else {
            GlassgramFrost.drawPill(drawing, rect, Math.min(radius, h / 2f), drawingAlpha * alpha, seed);
        }
    };

    public GlassgramProfileDecorView(Context context, Source source) {
        super(context);
        this.source = source;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    /** Where everything is, as one number: when it changes, the decorations are drawn again. */
    private float checksum() {
        final float alpha = source.place(avatar);
        sum = alpha * 1000f;
        if (alpha > 0f) {
            sum += avatar[0] * 3 + avatar[1] * 5 + avatar[2] * 7;
            if (source.frost()) {
                source.forEachGlass(summing);
            }
        }
        return sum;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawnSum = checksum();
        final float alpha = Math.max(0f, Math.min(1f, source.place(avatar)));
        if (alpha <= .01f) {
            return;
        }
        if (source.frost()) {
            drawing = canvas;
            drawingAlpha = alpha;
            glassIndex = 0;
            source.forEachGlass(frosting);
            drawing = null;
        }
        if (source.garland()) {
            GlassgramAvatarGarland.draw(canvas, avatar[0], avatar[1], avatar[2], alpha);
            if (GlassgramAvatarGarland.isBlinking()) {
                postInvalidateDelayed(GlassgramAvatarGarland.FRAME_MS);
            }
        }
    }
}

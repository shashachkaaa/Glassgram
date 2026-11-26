package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.ArrayList;

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

    private static final class GlassPiece {
        final RectF rect = new RectF();
        float radius;
        float alpha;
    }

    private final Source source;
    private final float[] avatar = new float[3];
    private final ArrayList<GlassPiece> glassPieces = new ArrayList<>();
    private int glassCount;
    private float decorationAlpha;
    private boolean garland;
    private boolean geometryReady;
    private float geometrySum;
    private float drawnSum = Float.NaN;

    private final Glass captureGlass = (rect, radius, alpha) -> {
        if (glassCount == glassPieces.size()) {
            glassPieces.add(new GlassPiece());
        }
        GlassPiece piece = glassPieces.get(glassCount++);
        piece.rect.set(rect);
        piece.radius = radius;
        piece.alpha = alpha;
        geometrySum += rect.left * 3 + rect.top * 5 + rect.right * 7 + rect.bottom * 11
                + alpha * 13 + radius * 17;
    };

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        captureGeometry();
        if (Float.isNaN(drawnSum) || Math.abs(geometrySum - drawnSum) > .3f) {
            invalidate();
        }
        return true;
    };

    private void drawFrost(Canvas canvas, GlassPiece piece, int seed) {
        final RectF rect = piece.rect;
        final float w = rect.width(), h = rect.height();
        if (w <= 0f || h <= 0f) {
            return;
        }
        final float alpha = decorationAlpha * piece.alpha;
        if (Math.max(w, h) / Math.min(w, h) < 1.3f) {
            // Stretch the frost with a round button as it is pulled.
            final float d = (w + h) / 2f;
            canvas.save();
            canvas.translate(rect.centerX(), rect.centerY());
            canvas.scale(w / d, h / d);
            GlassgramFrost.drawCircle(canvas, 0f, 0f, d / 2f, alpha, seed);
            canvas.restore();
        } else {
            GlassgramFrost.drawPill(canvas, rect, Math.min(piece.radius, h / 2f), alpha, seed);
        }
    }

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
        geometryReady = false;
        super.onDetachedFromWindow();
    }

    private void captureGeometry() {
        decorationAlpha = Math.max(0f, Math.min(1f, source.place(avatar)));
        boolean visible = decorationAlpha > .01f;
        garland = visible && source.garland();
        glassCount = 0;
        geometrySum = decorationAlpha * 1000f;
        if (visible) {
            geometrySum += avatar[0] * 3 + avatar[1] * 5 + avatar[2] * 7;
            if (garland) {
                geometrySum += 19;
            }
            if (source.frost()) {
                source.forEachGlass(captureGlass);
            }
        }
        geometryReady = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!geometryReady) {
            captureGeometry();
        }
        geometryReady = false;
        drawnSum = geometrySum;
        for (int i = 0; i < glassCount; i++) {
            drawFrost(canvas, glassPieces.get(i), i);
        }
        if (garland) {
            GlassgramAvatarGarland.draw(canvas, avatar[0], avatar[1], avatar[2], decorationAlpha);
            if (GlassgramAvatarGarland.isBlinking()) {
                postInvalidateDelayed(GlassgramAvatarGarland.FRAME_MS);
            }
        }
    }
}

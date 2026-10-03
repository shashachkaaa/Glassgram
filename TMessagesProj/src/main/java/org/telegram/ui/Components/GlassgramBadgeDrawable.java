package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.utils.Choreographer60FpsContent;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stars.StarsReactionsSheet;

/**
 * A Glassgram badge like exteraGram's: a translucent rosette in the color of the name it
 * follows, the glyph in that color, and sparkles twinkling around it.
 *
 * As a drawable (next to names in SimpleTextView) it animates through its callback; the same
 * drawing is used by {@link GlassgramBadgeSpan} inside text.
 */
public class GlassgramBadgeDrawable extends Drawable {

    private static final int FPS = 30;
    public static final float SIZE_TO_TEXT = 1.12f;

    private final Drawable shape;
    private final Drawable glyph;
    private final StarsReactionsSheet.Particles particles;
    private final Rect particleBounds = new Rect();
    private final Runnable invalidate = this::invalidateSelf;
    private int size;
    private int color = 0xFFFFFFFF;
    private int lastColor;
    private long lastProcess;
    /** Called once per frame drawn, for hosts that are not this drawable's callback. */
    Runnable onFrame;

    public GlassgramBadgeDrawable(int glyphResId, int sizePx) {
        size = sizePx;
        shape = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.glassgram_badge_shape).mutate();
        glyph = ContextCompat.getDrawable(ApplicationLoader.applicationContext, glyphResId).mutate();
        particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 8);
    }

    /** The color of the name next to the badge. */
    public void setColor(int color) {
        this.color = color;
    }

    /** Runs on the next frame after each draw, to redraw hosts that are not the callback. */
    public void setOnFrame(Runnable onFrame) {
        this.onFrame = onFrame;
    }

    public void setSize(int sizePx) {
        size = sizePx;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        int badgeSize = Math.min(b.width(), b.height());
        if (badgeSize <= 0) {
            badgeSize = size;
        }
        draw(canvas, b.centerX() - badgeSize / 2, b.centerY() - badgeSize / 2, badgeSize, color);
        if (getCallback() != null) {
            Choreographer60FpsContent.getInstance().addFrameCallbackOnce(invalidate, FPS);
        }
    }

    /** Draws the badge of badgeSize at left, top in color. */
    public void draw(Canvas canvas, int left, int top, int badgeSize, int color) {
        color |= 0xFF000000;
        if (color != lastColor) {
            lastColor = color;
            shape.setColorFilter(new PorterDuffColorFilter(Theme.multAlpha(color, 0.3f), PorterDuff.Mode.SRC_IN));
            glyph.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
        shape.setBounds(left, top, left + badgeSize, top + badgeSize);
        shape.draw(canvas);
        glyph.setBounds(left, top, left + badgeSize, top + badgeSize);
        glyph.draw(canvas);

        // Sparkles in the badge's own coordinates: the same badge can be drawn in several
        // places at once (the profile draws the name twice) and they all must match
        int spread = badgeSize / 3;
        int area = badgeSize + spread * 2;
        particleBounds.set(0, 0, area, area);
        particles.setBounds(particleBounds);
        long now = SystemClock.uptimeMillis();
        if (now - lastProcess >= 12) {
            lastProcess = now;
            particles.process();
        }
        canvas.save();
        canvas.translate(left - spread, top - spread);
        particles.draw(canvas, color);
        canvas.restore();
        if (onFrame != null) {
            Choreographer60FpsContent.getInstance().addFrameCallbackOnce(onFrame, FPS);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        shape.setAlpha(alpha);
        glyph.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * The badge after another drawable (the one SimpleTextView shows after the name's status,
     * like the verified check or the muted icon), drawn as one.
     */
    public static class Row extends Drawable implements Drawable.Callback {

        public final GlassgramBadgeDrawable badge;
        public final Drawable after;
        private final int gap;

        public Row(GlassgramBadgeDrawable badge, Drawable after, int gap) {
            this.badge = badge;
            this.after = after;
            this.gap = gap;
            badge.setCallback(this);
            if (after != null) {
                after.setCallback(this);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return badge.getIntrinsicWidth() + (after != null ? gap + Math.max(0, after.getIntrinsicWidth()) : 0);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(badge.getIntrinsicHeight(), after != null ? after.getIntrinsicHeight() : 0);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float scale = getIntrinsicWidth() > 0 ? b.width() / (float) getIntrinsicWidth() : 1f;
            int bw = (int) (badge.getIntrinsicWidth() * scale);
            int bh = (int) (badge.getIntrinsicHeight() * scale);
            int cy = b.centerY();
            badge.setBounds(b.left, cy - bh / 2, b.left + bw, cy + bh / 2);
            badge.draw(canvas);
            if (after != null) {
                int aw = (int) (after.getIntrinsicWidth() * scale);
                int ah = (int) (after.getIntrinsicHeight() * scale);
                int ax = b.left + bw + (int) (gap * scale);
                after.setBounds(ax, cy - ah / 2, ax + aw, cy + ah / 2);
                after.draw(canvas);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            badge.setAlpha(alpha);
            if (after != null) {
                after.setAlpha(alpha);
            }
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            if (after != null) {
                after.setColorFilter(colorFilter);
            }
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    }
}

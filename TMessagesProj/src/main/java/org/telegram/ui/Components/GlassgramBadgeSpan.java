package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.utils.Choreographer60FpsContent;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stars.StarsReactionsSheet;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * A Glassgram badge inside a name: a rosette in the color of the text around it, the glyph on
 * it, and sparkles twinkling over it, like exteraGram's badges.
 *
 * Views showing the text register with {@link #attach} so the sparkles keep moving; without a
 * host the badge is drawn still.
 */
public class GlassgramBadgeSpan extends ReplacementSpan {

    private static final int FPS = 30;

    private final int size;
    private final Drawable shape;
    private final Drawable glyph;
    private final StarsReactionsSheet.Particles particles;
    private final WeakHashMap<View, Boolean> hosts = new WeakHashMap<>();
    private final Rect bounds = new Rect();
    private final Runnable invalidateHosts = this::invalidateHosts;
    private int lastColor;

    public GlassgramBadgeSpan(int glyphResId, int sizePx) {
        size = sizePx;
        shape = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.glassgram_badge_shape).mutate();
        glyph = ContextCompat.getDrawable(ApplicationLoader.applicationContext, glyphResId).mutate();
        particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 8);
    }

    /** Lets the views drawing text with badges animate their sparkles. */
    public static void attach(View host, CharSequence text) {
        if (host == null || !(text instanceof Spanned)) {
            return;
        }
        Spanned spanned = (Spanned) text;
        GlassgramBadgeSpan[] spans = spanned.getSpans(0, spanned.length(), GlassgramBadgeSpan.class);
        for (GlassgramBadgeSpan span : spans) {
            span.hosts.put(host, Boolean.TRUE);
        }
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) {
            Paint.FontMetricsInt paintFm = paint.getFontMetricsInt();
            fm.ascent = paintFm.ascent;
            fm.descent = paintFm.descent;
            fm.top = paintFm.top;
            fm.bottom = paintFm.bottom;
        }
        return size;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int centerY = y + (fm.descent + fm.ascent) / 2;
        int left = (int) x;
        int topPx = centerY - size / 2;
        draw(canvas, left, topPx, paint.getColor());
    }

    /** Draws the badge at left, top in color, the color of the name it follows. */
    public void draw(Canvas canvas, int left, int top, int color) {
        color |= 0xFF000000;
        if (color != lastColor) {
            lastColor = color;
            shape.setColorFilter(new PorterDuffColorFilter(Theme.multAlpha(color, 0.3f), PorterDuff.Mode.SRC_IN));
            glyph.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
        shape.setBounds(left, top, left + size, top + size);
        shape.draw(canvas);
        glyph.setBounds(left, top, left + size, top + size);
        glyph.draw(canvas);

        // Sparkles around and over the badge
        int spread = size / 3;
        bounds.set(left - spread, top - spread, left + size + spread, top + size + spread);
        particles.setBounds(bounds);
        particles.process();
        particles.draw(canvas, color);
        if (!hosts.isEmpty()) {
            Choreographer60FpsContent.getInstance().addFrameCallbackOnce(invalidateHosts, FPS);
        }
    }

    private void invalidateHosts() {
        ArrayList<View> views = new ArrayList<>(hosts.keySet());
        for (View view : views) {
            if (view != null && view.isAttachedToWindow()) {
                view.invalidate();
            }
        }
    }
}

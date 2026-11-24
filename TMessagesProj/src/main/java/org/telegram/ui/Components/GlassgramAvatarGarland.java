package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramBadges;
import org.telegram.messenger.GlassgramWinter;
import org.telegram.ui.ActionBar.Theme;

/**
 * glassGram: the garland around an avatar, a winter decoration (GlassgramBadges.PERK_GARLAND) worn
 * in the winter look (GlassgramWinter): a wire around the circle with glass bulbs on it, pointing
 * out, in the colors of the theme's accent as the chats' garland (GlassgramGarlandView), a light
 * running round them. At any size: fewer and relatively bigger bulbs on a small avatar (the chats
 * list), more on the profile's.
 * <p>
 * Whoever draws it redraws itself every FRAME_MS while it blinks (isBlinking).
 */
public final class GlassgramAvatarGarland {

    /** How often a view drawing it redraws for the blinking, ms. */
    public static final long FRAME_MS = 50;

    private static final Paint wirePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint socketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint bulbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF oval = new RectF();
    private static final int[] palette = new int[4];
    private static final PorterDuffColorFilter[] glowFilters = new PorterDuffColorFilter[4];
    private static int paletteAccent;

    static {
        wirePaint.setStyle(Paint.Style.STROKE);
        glowPaint.setShader(new RadialGradient(0, 0, 1, new int[]{0xFFFFFFFF, 0x55FFFFFF, 0x00FFFFFF}, new float[]{0, .4f, 1}, Shader.TileMode.CLAMP));
    }

    private GlassgramAvatarGarland() {
    }

    /** Whether this user's avatar wears the garland now (theirs, and the winter look on). */
    public static boolean isWorn(long userId) {
        return userId > 0 && GlassgramBadges.hasPerk(userId, GlassgramBadges.PERK_GARLAND) && GlassgramWinter.isActive();
    }

    /** Whether it blinks (and its view should redraw every FRAME_MS): not in Lite mode or when saving power. */
    public static boolean isBlinking() {
        return GlassgramWinter.isAnimated();
    }

    private static void updatePalette() {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (accent == paletteAccent && glowFilters[0] != null) {
            return;
        }
        paletteAccent = accent;
        final float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        final float[] hues = {hsv[0], 45f, (hsv[0] + 150f) % 360f, (hsv[0] + 240f) % 360f};
        for (int i = 0; i < 4; i++) {
            palette[i] = Color.HSVToColor(new float[]{hues[i], i == 1 ? .62f : .7f, 1f});
            glowFilters[i] = new PorterDuffColorFilter(palette[i], PorterDuff.Mode.SRC_IN);
        }
    }

    /** How lit a bulb is, .25..1: a light running round the ring. */
    private static float lit(int i, float time) {
        return .25f + .75f * Math.max(0f, (float) Math.cos(6.283f * (time * .7f - i / 6f)));
    }

    /** Draws the garland around the avatar's circle (center cx, cy, radius r) at this alpha. */
    public static void draw(Canvas canvas, float cx, float cy, float r, float alpha) {
        if (r <= 1f || alpha <= 0f) {
            return;
        }
        updatePalette();
        final float dp = AndroidUtilities.density;
        final float ringR = r * 1.1f;
        final float spacing = dp * (r < 40 * dp ? 16 : 13);
        final int n = Math.max(10, Math.min(22, Math.round((float) (2 * Math.PI * ringR / spacing))));
        final float ry = Math.max(dp * 2.8f, r * .085f), rx = ry * .72f;
        final float time = isBlinking() ? (SystemClock.uptimeMillis() % 600_000L) / 1000f : 1.3f;
        final boolean dark = Theme.isCurrentThemeDark();

        wirePaint.setColor(dark ? 0x8CC9D1DC : 0x8A4B5563);
        wirePaint.setAlpha((int) (wirePaint.getAlpha() * alpha));
        wirePaint.setStrokeWidth(Math.max(1f, dp));
        canvas.drawCircle(cx, cy, ringR, wirePaint);

        socketPaint.setColor(dark ? 0xFF59616C : 0xFF4B5563);
        socketPaint.setAlpha((int) (255 * alpha));
        final float by = -ry * 1.55f;
        for (int i = 0; i < n; i++) {
            final double a = -Math.PI / 2 + i * 2 * Math.PI / n;
            final float b = lit(i, time);
            final int color = palette[i % 4];
            canvas.save();
            canvas.translate(cx + (float) Math.cos(a) * ringR, cy + (float) Math.sin(a) * ringR);
            // pointing out of the ring, each leaning a little its own way
            canvas.rotate((float) Math.toDegrees(a + Math.PI / 2 + .18 * Math.sin(i * 1.7)));
            canvas.drawRect(-rx * .45f, -ry * .55f, rx * .45f, 0, socketPaint);

            // its light around it
            final float glowR = ry * 2.6f;
            canvas.save();
            canvas.translate(0, by);
            canvas.scale(glowR, glowR);
            glowPaint.setColorFilter(glowFilters[i % 4]);
            glowPaint.setAlpha((int) (255 * .8f * b * alpha * (dark ? 1f : .75f)));
            canvas.drawCircle(0, 0, 1, glowPaint);
            canvas.restore();

            // the glass, darker when off, nearly white inside when lit
            bulbPaint.setColor(color);
            bulbPaint.setAlpha((int) (255 * (.45f + .55f * b) * alpha));
            oval.set(-rx, by - ry, rx, by + ry);
            canvas.drawOval(oval, bulbPaint);
            bulbPaint.setColor(0xFFFFFFFF);
            bulbPaint.setAlpha((int) (255 * .75f * b * alpha));
            oval.set(-rx * .5f, by + ry * .05f - ry * .5f, rx * .5f, by + ry * .05f + ry * .5f);
            canvas.drawOval(oval, bulbPaint);
            // the glint of the glass
            bulbPaint.setAlpha((int) (255 * (.4f + .3f * b) * alpha));
            oval.set(-rx * .6f, by - ry * .65f, -rx * .1f, by - ry * .05f);
            canvas.drawOval(oval, bulbPaint);
            canvas.restore();
        }
    }
}

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * glassGram: a garland along the top of the chats' header for the winter look (GlassgramWinter).
 * A wire hangs in loops across the width, glass bulbs in the colors of the theme's accent hang
 * from it and blink in turns (a chase, every other one, a twinkle), passing from one way to the
 * next. A bulb tapped flashes and swings. It takes only the taps on its bulbs.
 */
public class GlassgramGarlandView extends View {

    private static final long FRAME_MS = 33;
    private static final float SPACING_DP = 62;
    private static final float SAG_DP = 8;

    private final Paint wirePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint socketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bulbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wire = new Path();
    private final RectF oval = new RectF();
    private final int[] palette = new int[4];
    private final PorterDuffColorFilter[] glowFilters = new PorterDuffColorFilter[4];
    private final android.graphics.Matrix glowMatrix = new android.graphics.Matrix();
    private int paletteAccent;

    private float[] bulbX = new float[0], bulbY = new float[0], flash = new float[0], swing = new float[0], swingSpeed = new float[0];
    private int laidOutWidth;
    private float top;
    private boolean running;
    private long lastFrame;

    public GlassgramGarlandView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        wirePaint.setStyle(Paint.Style.STROKE);
        wirePaint.setStrokeWidth(AndroidUtilities.dpf2(1.1f));
        wirePaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setShader(new RadialGradient(0, 0, 1, new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF}, new float[]{0, .35f, 1}, Shader.TileMode.CLAMP));
    }

    /** Where the wire hangs from: under the status bar. */
    public void setTopOffset(float top) {
        if (this.top != top) {
            this.top = top;
            laidOutWidth = 0;
            requestLayout();
            invalidate();
        }
    }

    public void setRunning(boolean running) {
        if (this.running == running) {
            return;
        }
        this.running = running;
        lastFrame = 0;
        if (running) {
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (int) (top + AndroidUtilities.dp(SAG_DP + 22)));
    }

    private void layoutBulbs(int width) {
        laidOutWidth = width;
        final float spacing = AndroidUtilities.dpf2(SPACING_DP);
        final int loops = Math.max(2, Math.round(width / spacing));
        final float step = width / (float) loops;
        final float anchorY = top + AndroidUtilities.dpf2(1.5f), sag = AndroidUtilities.dpf2(SAG_DP);
        wire.rewind();
        final int count = loops * 2;
        bulbX = new float[count];
        bulbY = new float[count];
        flash = new float[count];
        swing = new float[count];
        swingSpeed = new float[count];
        wire.moveTo(-AndroidUtilities.dp(2), anchorY);
        for (int k = 0; k < loops; k++) {
            final float x0 = k * step, x1 = (k + 1) * step, cx = (x0 + x1) / 2f, cy = anchorY + sag * 2;
            wire.quadTo(cx, cy, x1, anchorY);
            // two bulbs on each loop, at a third and two thirds of it
            for (int j = 0; j < 2; j++) {
                final float t = j == 0 ? .33f : .67f;
                final float u = 1 - t;
                bulbX[k * 2 + j] = u * u * x0 + 2 * u * t * cx + t * t * x1;
                bulbY[k * 2 + j] = u * u * anchorY + 2 * u * t * cy + t * t * anchorY;
            }
        }
    }

    private void updatePalette() {
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

    /** How lit a bulb is now, 0.2..1: the way of blinking changes every 8 s, the next fading in. */
    private float lit(int i, float time) {
        final float cycle = time / 8f;
        final int mode = (int) cycle % 3;
        final float blend = Math.min(1f, (cycle - (float) Math.floor(cycle)) * 8f);
        final float now = litBy(mode, i, time), before = litBy((mode + 2) % 3, i, time);
        return before + (now - before) * blend;
    }

    private float litBy(int mode, int i, float time) {
        switch (mode) {
            case 0: // a chase along the wire
                return .2f + .8f * Math.max(0f, (float) Math.cos(6.283f * (time * .7f - i / 6f)));
            case 1: // every other one, in turns
                final float s = (float) Math.sin(time * 3.4f);
                return .2f + .8f * Math.max(0f, i % 2 == 0 ? s : -s);
            default: // a twinkle, each its own
                return .45f + .55f * (.5f + .5f * (float) Math.sin(time * (1.3f + (i * 37 % 11) * .17f) + i * 2.1f));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        if (width <= 0) {
            return;
        }
        if (laidOutWidth != width) {
            layoutBulbs(width);
        }
        updatePalette();
        final long now = SystemClock.uptimeMillis();
        final float dt = lastFrame == 0 ? 0 : Math.min(.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        final float time = (now % 600_000L) / 1000f;
        final boolean dark = Theme.isCurrentThemeDark();

        wirePaint.setColor(dark ? 0x80C9D1DC : 0x8A4B5563);
        canvas.drawPath(wire, wirePaint);

        final float socketW = AndroidUtilities.dpf2(3.2f), socketH = AndroidUtilities.dpf2(3f);
        final float rx = AndroidUtilities.dpf2(3.4f), ry = AndroidUtilities.dpf2(4.8f);
        socketPaint.setColor(dark ? 0xFF59616C : 0xFF4B5563);
        for (int i = 0; i < bulbX.length; i++) {
            // a tapped bulb flashes and swings back to rest
            if (dt > 0) {
                flash[i] = Math.max(0f, flash[i] - dt * 1.6f);
                swingSpeed[i] += (-swing[i] * 60f - swingSpeed[i] * 4f) * dt;
                swing[i] += swingSpeed[i] * dt;
            }
            final float sway = swing[i] + 4f * (float) Math.sin(time * 1.1f + i * .9f);
            final float b = Math.max(lit(i, time), flash[i]);
            final int color = palette[i % 4];

            canvas.save();
            canvas.rotate(sway, bulbX[i], bulbY[i]);
            final float sx = bulbX[i], sy = bulbY[i];
            oval.set(sx - socketW / 2, sy - AndroidUtilities.dpf2(.5f), sx + socketW / 2, sy + socketH);
            canvas.drawRoundRect(oval, AndroidUtilities.dpf2(.8f), AndroidUtilities.dpf2(.8f), socketPaint);
            final float cx = sx, cy = sy + socketH + ry - AndroidUtilities.dpf2(.8f);

            // its light around it
            final float glowR = ry * (2.4f + 1.2f * flash[i]);
            glowMatrix.setScale(glowR, glowR);
            glowMatrix.postTranslate(cx, cy);
            glowPaint.getShader().setLocalMatrix(glowMatrix);
            glowPaint.setColorFilter(glowFilters[i % 4]);
            glowPaint.setAlpha((int) (0xFF * (dark ? .55f : .4f) * b));
            canvas.drawCircle(cx, cy, glowR, glowPaint);

            // the glass: darker when off, nearly white inside when lit
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
            bulbPaint.setColor(ColorUtils.blendARGB(ColorUtils.blendARGB(color, 0xFF000000, .5f), color, b));
            canvas.drawOval(oval, bulbPaint);
            oval.set(cx - rx * .55f, cy - ry * .45f, cx + rx * .55f, cy + ry * .55f);
            bulbPaint.setColor(ColorUtils.blendARGB(color, 0xFFFFFFFF, .65f));
            bulbPaint.setAlpha((int) (0xFF * .85f * b));
            canvas.drawOval(oval, bulbPaint);
            // the glint of the glass
            oval.set(cx - rx * .6f, cy - ry * .7f, cx - rx * .1f, cy - ry * .15f);
            bulbPaint.setColor(0xFFFFFFFF);
            bulbPaint.setAlpha((int) (0xFF * (.35f + .35f * b)));
            canvas.drawOval(oval, bulbPaint);
            canvas.restore();
        }
        if (running) {
            postInvalidateDelayed(Math.max(0, FRAME_MS - (SystemClock.uptimeMillis() - now)));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) {
            return event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_MOVE;
        }
        final float reach = AndroidUtilities.dpf2(13);
        final float socketH = AndroidUtilities.dpf2(3f), ry = AndroidUtilities.dpf2(4.8f);
        for (int i = 0; i < bulbX.length; i++) {
            final float dx = event.getX() - bulbX[i], dy = event.getY() - (bulbY[i] + socketH + ry);
            if (dx * dx + dy * dy <= reach * reach) {
                flash[i] = 1f;
                swingSpeed[i] += (dx < 0 ? 1 : -1) * 160f;
                try {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignore) {
                }
                invalidate();
                return true;
            }
        }
        return false;
    }
}

package org.telegram.ui.music;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LiquidGlassButtonBackground;
import org.telegram.ui.Components.LiquidPressEffect;
import org.telegram.ui.Components.ViewBehindBackdrop;

/**
 * glassGram's music player: a button of Liquid Glass, as the header's. Its glass is the library's
 * LiquidButton over what is behind it in the player (the background in the cover's colors and the
 * views drawn before it), or a tint with the library's highlight where the lens is not there; it
 * swells and leans towards the finger when pressed. Filled, it is the bright pill of the play button.
 */
public class MusicGlassButton extends View {

    private final LiquidPressEffect press;
    private final LiquidGlassButtonBackground glass = new LiquidGlassButtonBackground();
    private final ViewBehindBackdrop backdrop;
    private final RectF rect = new RectF();
    private final RectF moved = new RectF();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private Drawable icon;
    private int iconSize;
    private String text;
    private int iconColor = Color.WHITE;
    private int fillColor;
    private boolean glassShown = true;
    private boolean active;
    private float activeProgress;
    private int glassWidth, glassHeight;

    public MusicGlassButton(Context context) {
        super(context);
        press = new LiquidPressEffect(this);
        press.setUnclipParents(1);
        backdrop = new ViewBehindBackdrop(this);
        textPaint.setTextSize(AndroidUtilities.dp(14));
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextAlign(Paint.Align.CENTER);
        iconSize = AndroidUtilities.dp(24);
    }

    public void setIcon(int resId) {
        setIcon(ContextCompat.getDrawable(getContext(), resId));
    }

    public void setIcon(Drawable drawable) {
        icon = drawable != null ? drawable.mutate() : null;
        invalidate();
    }

    public Drawable getIcon() {
        return icon;
    }

    public void setIconSize(int size) {
        iconSize = size;
        invalidate();
    }

    public void setText(String text) {
        this.text = text;
        invalidate();
    }

    /** The glass's size inside the view, 0 to fill it (the touch area stays the view's). */
    public void setGlassSize(int width, int height) {
        glassWidth = width;
        glassHeight = height;
        invalidate();
    }

    /** A filled pill (the play button): its color, 0 for clear glass. */
    public void setFillColor(int color) {
        fillColor = color;
        invalidate();
    }

    public void setIconColor(int color) {
        iconColor = color;
        invalidate();
    }

    /** Glass under the icon only while it is on (shuffle, repeat, the open page's button) or always. */
    public void setGlassShown(boolean shown) {
        glassShown = shown;
        invalidate();
    }

    public void setActive(boolean active, boolean animated) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        if (!animated) {
            activeProgress = active ? 1f : 0f;
        }
        invalidate();
    }

    public boolean isActiveState() {
        return active;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (isEnabled()) {
            press.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final float target = active ? 1f : 0f;
        if (activeProgress != target) {
            activeProgress = AndroidUtilities.lerp(activeProgress, target, 0.25f);
            if (Math.abs(activeProgress - target) < 0.01f) {
                activeProgress = target;
            } else {
                invalidate();
            }
        }

        final int w = glassWidth > 0 ? Math.min(glassWidth, getWidth()) : getWidth();
        final int h = glassHeight > 0 ? Math.min(glassHeight, getHeight()) : getHeight();
        rect.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        final float radius = Math.min(rect.width(), rect.height()) / 2f;
        final boolean pressed = press.isActive();
        final float glassAlpha = glassShown ? 1f : Math.max(activeProgress, press.getPressProgress());

        if (fillColor != 0) {
            // The bright pill: a filled LiquidButton, or a pill of the color with the press's light
            final RectF g = pressed ? moved : rect;
            if (pressed) {
                press.mapRect(rect.left, rect.top, rect.right, rect.bottom, moved);
            }
            backdrop.setArea(rect);
            final boolean liquid = press.drawGlass(canvas, rect.left, rect.top, rect.right, rect.bottom, radius,
                !backdrop.isDrawing() && backdrop.isAvailable() ? backdrop : null, fillColor, fillColor, 255);
            if (!liquid) {
                fillPaint.setColor(fillColor);
                final float r = Math.min(g.width(), g.height()) / 2f;
                canvas.drawRoundRect(g, r, r, fillPaint);
                if (pressed) {
                    canvas.save();
                    canvas.translate(g.left, g.top);
                    press.drawGlow(canvas, g.width(), g.height(), r);
                    canvas.restore();
                }
            }
        } else if (glassAlpha > 0f) {
            final int alpha = (int) (255 * glassAlpha);
            final RectF g = pressed ? moved : rect;
            if (pressed) {
                press.mapRect(rect.left, rect.top, rect.right, rect.bottom, moved);
            }
            glass.setColors(iconColor, 0xff000000);
            glass.setAlpha(alpha);
            backdrop.setArea(rect);
            final boolean liquid = press.drawGlass(canvas, rect.left, rect.top, rect.right, rect.bottom, radius,
                !backdrop.isDrawing() && backdrop.isAvailable() ? backdrop : null, glass.getGlassTint(), 0, alpha);
            if (!liquid) {
                glass.setBounds(Math.round(g.left), Math.round(g.top), Math.round(g.right), Math.round(g.bottom));
                backdrop.setArea(g);
                glass.setBackdrop(!backdrop.isDrawing() && backdrop.isAvailable() ? backdrop : null);
                glass.draw(canvas);
                if (pressed) {
                    canvas.save();
                    canvas.translate(g.left, g.top);
                    press.drawGlow(canvas, g.width(), g.height(), Math.min(g.width(), g.height()) / 2f);
                    canvas.restore();
                }
            }
        }

        // The content follows the glass
        canvas.save();
        canvas.translate(rect.left, rect.top);
        press.transform(canvas, rect.width(), rect.height());
        canvas.translate(-rect.left, -rect.top);
        final float cx = rect.centerX(), cy = rect.centerY();
        if (icon != null) {
            final int s = iconSize;
            icon.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN));
            icon.setAlpha(isEnabled() ? 255 : 110);
            icon.draw(canvas);
        }
        if (text != null) {
            textPaint.setColor(iconColor);
            textPaint.setAlpha(isEnabled() ? 255 : 110);
            canvas.drawText(text, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
        }
        canvas.restore();

        if (pressed) {
            invalidate();
        }
    }

    /** Draws itself again when what is behind its glass changed (the background crossfading). */
    public void invalidateGlass() {
        invalidate();
    }
}

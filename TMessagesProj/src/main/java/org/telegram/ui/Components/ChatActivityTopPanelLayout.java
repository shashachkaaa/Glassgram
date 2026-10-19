package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

import me.vkryl.android.animator.ListAnimator;

public class ChatActivityTopPanelLayout extends AnimatedLinearLayout {
    public ChatActivityTopPanelLayout(@NonNull Context context) {
        super(context);

        setOrientation(LinearLayout.VERTICAL);
        updateColors();
    }

    BlurredBackgroundDrawable backgroundDrawable;

    public void setBlurredBackground(BlurredBackgroundDrawable background) {
        backgroundDrawable = background;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkBoundsAndClipping();
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final boolean handled = super.dispatchTouchEvent(ev)
                || ev.getAction() == MotionEvent.ACTION_DOWN && backgroundDrawable != null && bgBounds.contains((int) ev.getX(), (int) ev.getY());
        glassTouch(ev, handled);
        return handled;
    }

    // glassGram: the card is Liquid Glass like the bar above it: pressed, it swells, leans towards
    // the finger and stretches with a drag, its panels with it
    private LiquidPressEffect press;
    private boolean pressTracking;
    private final Rect bgBounds = new Rect();
    private final Rect bgPadding = new Rect();
    private final RectF glassRect = new RectF();
    private final RectF glassMovedRect = new RectF();
    private float glassRadius;

    private void glassTouch(MotionEvent ev, boolean handled) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pressTracking = handled && backgroundDrawable != null && getMetadata().getTotalVisibility() > 0
                && glassRect.contains(ev.getX(), ev.getY());
            if (pressTracking && press == null) {
                press = new LiquidPressEffect(this);
                // It swells past the layout, whose parent must not cut it
                press.setUnclipParents(1);
            }
        }
        if (!pressTracking) {
            return;
        }
        press.onTouch(action, ev.getX() - glassRect.left, ev.getY() - glassRect.top);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pressTracking = false;
        }
    }

    // glassGram: rounder, like iOS: a single panel becomes a capsule
    private static final int GLASSGRAM_RADIUS = 26;

    private final Path clipPath = new Path();
    private final RectF clipRectF = new RectF();

    private void checkBoundsAndClipping() {
        final float bgHeight = getMetadata().getTotalHeight();
        final float bgAlpha = getMetadata().getTotalVisibility();

        clipRectF.set(getPaddingLeft(), getPaddingTop(), getMeasuredWidth() - getPaddingRight(), getPaddingTop() + bgHeight);

        final float r = Math.min(dp(GLASSGRAM_RADIUS), Math.min(clipRectF.width(), clipRectF.height()) / 2f);
        clipPath.rewind();
        clipPath.addRoundRect(clipRectF, r, r, Path.Direction.CW);

        if (backgroundDrawable != null) {
            backgroundDrawable.setAlpha((int) (bgAlpha * 255));
            bgBounds.set(getPaddingLeft() - dp(7), 0, getMeasuredWidth() - getPaddingRight() + dp(7), getPaddingTop() + getPaddingBottom() + (int) bgHeight);
            backgroundDrawable.setBounds(bgBounds);
            glassRadius = Math.min(dp(GLASSGRAM_RADIUS), bgHeight / 2);
            backgroundDrawable.setRadius(glassRadius);
            backgroundDrawable.getPadding(bgPadding);
            glassRect.set(bgBounds.left + bgPadding.left, bgBounds.top + bgPadding.top, bgBounds.right - bgPadding.right, bgBounds.bottom - bgPadding.bottom);
        }
    }

    public void updateColors() {
        if (backgroundDrawable != null) {
            backgroundDrawable.updateColors();
        }

        final int color = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int alpha = Color.alpha(color);

        invalidate();
    }

    private FragmentContextView callFragmentContextView;

    public void setCallFragmentContextView(FragmentContextView fragmentContextView) {
        callFragmentContextView = fragmentContextView;
        callFragmentContextView.getCapsuleBlobDrawable().setCallback(this);
        // glassGram: the card draws the track's progress over its rows (see dispatchDraw)
        callFragmentContextView.setAudioProgressDrawer(this::invalidate);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who) || callFragmentContextView != null && callFragmentContextView.getCapsuleBlobDrawable() == who;
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (getMetadata().getTotalVisibility() == 0) return;

        final boolean pressed = backgroundDrawable != null && press != null && press.isActive()
            && glassRect.width() > 0 && glassRect.height() > 0;
        if (backgroundDrawable != null) {
            float radiusScale = 1f;
            if (pressed) {
                // The glass goes where the press moved it and blurs what is there, its corners
                // growing with it; the bounds stay until the next frame sets them again
                press.mapRect(glassRect.left, glassRect.top, glassRect.right, glassRect.bottom, glassMovedRect);
                radiusScale = LiquidPressEffect.radiusScale(glassRect.width(), glassRect.height(), glassMovedRect);
                backgroundDrawable.setBounds(
                    Math.round(glassMovedRect.left) - bgPadding.left, Math.round(glassMovedRect.top) - bgPadding.top,
                    Math.round(glassMovedRect.right) + bgPadding.right, Math.round(glassMovedRect.bottom) + bgPadding.bottom);
            } else {
                backgroundDrawable.setBounds(bgBounds);
            }
            backgroundDrawable.setRadiusScale(radiusScale);
            backgroundDrawable.draw(canvas);
            if (pressed) {
                canvas.save();
                canvas.translate(glassMovedRect.left, glassMovedRect.top);
                press.drawGlow(canvas, glassMovedRect.width(), glassMovedRect.height(), glassRadius * radiusScale);
                canvas.restore();
            }
        }

        // The panels follow the glass under them
        canvas.save();
        if (pressed) {
            canvas.translate(glassRect.left, glassRect.top);
            press.transform(canvas, glassRect.width(), glassRect.height());
            canvas.translate(-glassRect.left, -glassRect.top);
        }

        boolean callDrawn = false;
        if (callFragmentContextView != null) {
            final int style = callFragmentContextView.getCurrentStyle();
            if (style == FragmentContextView.STYLE_ACTIVE_GROUP_CALL || style == FragmentContextView.STYLE_CONNECTING_GROUP_CALL) {
                for (int a = 0, N = getEntriesCount(); a < N; a++) {
                    final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(a);
                    final float top = getPaddingTop() + entry.getRectF().top;
                    final View view = entry.item.view;
                    final float alpha = entry.getVisibility();
                    if (alpha <= 0) {
                        continue;
                    }

                    if (callFragmentContextView == view || callFragmentContextView.getParent() == view) {
                        final CapsuleBlobDrawable capsuleBlobDrawable = callFragmentContextView.getCapsuleBlobDrawable();

                        final int p = capsuleBlobDrawable.getRequiredInset();
                        final int h = dp(36) + p * 2;
                        capsuleBlobDrawable.setBounds(getPaddingLeft() - p, -p, getMeasuredWidth() - getPaddingRight() + p, -p + h);
                        capsuleBlobDrawable.setAlpha((int) (255 * alpha));
                        canvas.save();
                        canvas.translate(0, top);
                        capsuleBlobDrawable.draw(canvas);
                        canvas.restore();

                        callDrawn = true;
                    }
                }
            }
        }

        canvas.save();
        canvas.clipPath(clipPath);
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(a);
            final float top = getPaddingTop() + entry.getRectF().top;
            final View view = entry.item.view;

            final float position = entry.getPosition();
            final float alpha = entry.getVisibility() * Math.min(1, position);

            if (alpha <= 0) {
                continue;
            }

            if (callDrawn && callFragmentContextView != null && (callFragmentContextView == view || callFragmentContextView.getParent() == view)) {
                continue;
            }

            final int wasAlpha = Theme.dividerPaint.getAlpha();
            Theme.dividerPaint.setAlpha((int) (wasAlpha * alpha));
            // glassGram: the dividers stop short of the rounded sides, as on iOS
            final float offsetL = getPaddingLeft() + dp(14) + dp(16) * (1f - alpha);
            final float offsetR = getPaddingRight() + dp(14) + dp(16) * (1f - alpha);
            canvas.drawLine(offsetL, top, getWidth() - offsetR, top, Theme.dividerPaint);
            Theme.dividerPaint.setAlpha(wasAlpha);
        }

        super.dispatchDraw(canvas);
        drawAudioProgress(canvas);
        canvas.restore();
        canvas.restore();
    }

    /**
     * glassGram: the playing track's progress along the bottom of the player's row, inside the glass:
     * the row's bottom is the card's when it is the last, below the glass's own edge.
     */
    private void drawAudioProgress(Canvas canvas) {
        if (callFragmentContextView == null) {
            return;
        }
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(a);
            final View view = entry.item.view;
            if (callFragmentContextView != view && callFragmentContextView.getParent() != view) {
                continue;
            }
            // Not the dividers' alpha: theirs is 0 for the first row, which has none above it
            final float alpha = entry.getVisibility();
            float bottom = getPaddingTop() + entry.getRectF().bottom;
            if (backgroundDrawable != null && glassRect.height() > 0) {
                bottom = Math.min(bottom, glassRect.bottom);
            }
            bottom -= dp(5);
            callFragmentContextView.drawAudioProgress(canvas, getPaddingLeft() + dp(20), getWidth() - getPaddingRight() - dp(20), bottom, alpha);
            return;
        }
    }
}

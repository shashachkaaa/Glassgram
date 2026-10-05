package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.liquidglass.GlassLayer;

/**
 * The backdrop of glass that a view draws over its siblings, for {@link GlassLayer}: its
 * parent's background and the siblings drawn before it, as they are on screen. The glass is
 * then refracted from what is really under it, instead of being a tint over nothing.
 * <p>
 * The glass is recorded when the view draws, and moving or redrawing a sibling does not redraw
 * the view; {@link #isStale} tells when it has to, so the glass follows what is under it.
 */
public class ViewBehindBackdrop implements GlassLayer.Backdrop {

    private final View view;
    private final RectF area = new RectF();
    private boolean drawing;

    // The siblings' place and look when the backdrop was last drawn
    private float[] drawnState = new float[0];
    private float[] checkState = new float[0];
    private int drawnStateLength = -1;

    public ViewBehindBackdrop(View view) {
        this.view = view;
    }

    /** Where the glass is, in the view's coordinates; the backdrop is drawn from its top-left corner. */
    public void setArea(RectF area) {
        this.area.set(area);
    }

    /**
     * Whether the backdrop is being recorded right now. The view drawn again inside it (by a
     * sibling that draws it) must not start the same glass again, that glass is mid-recording.
     */
    public boolean isDrawing() {
        return drawing;
    }

    /** Whether there is anything behind the view to make the glass of. */
    public boolean isAvailable() {
        if (!(view.getParent() instanceof ViewGroup)) {
            return false;
        }
        final ViewGroup parent = (ViewGroup) view.getParent();
        if (parent.getBackground() != null) {
            return true;
        }
        final int index = parent.indexOfChild(view);
        for (int i = 0; i < index; i++) {
            if (isDrawn(parent.getChildAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDrawn(View child) {
        return child.getVisibility() == View.VISIBLE && child.getAlpha() > 0f && child.getWidth() > 0 && child.getHeight() > 0;
    }

    @Override
    public void draw(Canvas canvas) {
        // A sibling that draws the view again would draw this glass inside its own backdrop
        if (drawing || !(view.getParent() instanceof ViewGroup)) {
            return;
        }
        final ViewGroup parent = (ViewGroup) view.getParent();
        drawing = true;
        final int restore = canvas.save();
        try {
            canvas.translate(-(view.getX() + area.left), -(view.getY() + area.top));
            final Drawable background = parent.getBackground();
            if (background != null) {
                background.draw(canvas);
            }
            final int index = parent.indexOfChild(view);
            for (int i = 0; i < index; i++) {
                final View child = parent.getChildAt(i);
                if (!isDrawn(child)) {
                    continue;
                }
                final int childRestore = canvas.save();
                // As the parent places it: position, then translation, scale and rotation
                canvas.translate(child.getLeft(), child.getTop());
                canvas.concat(child.getMatrix());
                if (parent.getClipChildren()) {
                    canvas.clipRect(0, 0, child.getWidth(), child.getHeight());
                }
                if (child.getAlpha() < 1f) {
                    canvas.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), (int) (255 * child.getAlpha()));
                }
                canvas.translate(-child.getScrollX(), -child.getScrollY());
                child.draw(canvas);
                canvas.restoreToCount(childRestore);
            }
        } finally {
            canvas.restoreToCount(restore);
            drawing = false;
        }
        drawnStateLength = collectState(true);
    }

    /** Whether what is behind the view changed since the backdrop was drawn, so the view has to redraw. */
    public boolean isStale() {
        if (drawnStateLength < 0 || !(view.getParent() instanceof ViewGroup)) {
            return false;
        }
        final ViewGroup parent = (ViewGroup) view.getParent();
        final int index = parent.indexOfChild(view);
        for (int i = 0; i < index; i++) {
            if (parent.getChildAt(i).isDirty()) {
                return true;
            }
        }
        final int length = collectState(false);
        if (length != drawnStateLength) {
            return true;
        }
        for (int i = 0; i < length; i++) {
            if (checkState[i] != drawnState[i]) {
                return true;
            }
        }
        return false;
    }

    private static final int STATE_PER_CHILD = 13;

    private int collectState(boolean drawn) {
        if (!(view.getParent() instanceof ViewGroup)) {
            return -1;
        }
        final ViewGroup parent = (ViewGroup) view.getParent();
        final int index = parent.indexOfChild(view);
        final int length = 2 + Math.max(0, index) * STATE_PER_CHILD;
        float[] state = drawn ? drawnState : checkState;
        if (state.length < length) {
            state = new float[length];
            if (drawn) {
                drawnState = state;
            } else {
                checkState = state;
            }
        }
        int k = 0;
        state[k++] = view.getX();
        state[k++] = view.getY();
        for (int i = 0; i < index; i++) {
            final View child = parent.getChildAt(i);
            state[k++] = child.getVisibility();
            state[k++] = child.getAlpha();
            state[k++] = child.getLeft();
            state[k++] = child.getTop();
            state[k++] = child.getRight();
            state[k++] = child.getBottom();
            state[k++] = child.getTranslationX();
            state[k++] = child.getTranslationY();
            state[k++] = child.getScaleX();
            state[k++] = child.getScaleY();
            state[k++] = child.getRotation();
            state[k++] = child.getScrollX();
            state[k++] = child.getScrollY();
        }
        return k;
    }
}

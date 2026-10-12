package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.Locale;

/**
 * glassGram: the zoom slider under the video message camera. The scale is logarithmic, so the
 * ultra-wide lens, 1x and 2x get as much room as 5x and 10x; it snaps to the marked stops.
 */
public class RoundZoomSlider extends View {

    public interface Listener {
        /** The zoom the person picked; finished when they let go. */
        void onZoom(float zoom, boolean finished);
    }

    // marked zooms from 1x up; below 1x only the ultra-wide end is marked
    private static final float[] STOPS = { 1f, 2f, 5f, 10f };

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float minZoom = 1f, maxZoom = 1f, zoom = 1f;
    private Listener listener;
    private boolean dragging;
    private float lastStop = -1;

    public RoundZoomSlider(Context context) {
        super(context);
        backgroundPaint.setColor(0x66000000);
        trackPaint.setColor(0x59FFFFFF);
        trackPaint.setStrokeWidth(dp(1.5f));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        thumbPaint.setColor(0xFFFFFFFF);
        labelPaint.setColor(0xB3FFFFFF);
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(AndroidUtilities.bold());
        valuePaint.setColor(0xFF000000);
        valuePaint.setTextSize(dp(12));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(AndroidUtilities.bold());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Whether there is any zoom to slide over. */
    public boolean hasRange() {
        return maxZoom > minZoom + 0.01f;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** Takes the camera's zoom range and current zoom; while the slider is dragged, the zoom stays the slider's. */
    public void update(float min, float max, float current) {
        boolean changed = false;
        if (Math.abs(min - minZoom) > 0.001f || Math.abs(max - maxZoom) > 0.001f) {
            minZoom = min;
            maxZoom = Math.max(min, max);
            changed = true;
        }
        if (!dragging && Math.abs(current - zoom) > 0.001f) {
            zoom = current;
            changed = true;
        }
        if (changed) {
            invalidate();
        }
    }

    private float trackLeft() {
        return dp(30);
    }

    private float trackRight() {
        return getWidth() - dp(30);
    }

    private float positionOf(float z) {
        if (!hasRange()) return 0;
        final float p = (float) ((Math.log(z) - Math.log(minZoom)) / (Math.log(maxZoom) - Math.log(minZoom)));
        return AndroidUtilities.lerp(trackLeft(), trackRight(), Utilities.clamp(p, 1f, 0f));
    }

    private float zoomAt(float x) {
        final float p = Utilities.clamp((x - trackLeft()) / Math.max(1, trackRight() - trackLeft()), 1f, 0f);
        return (float) Math.exp(Math.log(minZoom) + p * (Math.log(maxZoom) - Math.log(minZoom)));
    }

    private static String format(float z) {
        final float rounded = Math.round(z * 10f) / 10f;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05f) {
            return Math.round(rounded) + "×";
        }
        return String.format(Locale.US, "%.1f×", rounded);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasRange()) return;
        final float h = getHeight(), cy = h / 2f;
        rect.set(0, 0, getWidth(), h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, backgroundPaint);
        canvas.drawLine(trackLeft(), cy, trackRight(), cy, trackPaint);

        final float thumbX = positionOf(zoom);
        final float thumbHalf = dp(23);
        for (float stop : stopsInRange()) {
            final float x = positionOf(stop);
            // the marks under the thumb fade out
            final float distance = Math.abs(x - thumbX);
            final float alpha = Utilities.clamp((distance - thumbHalf) / dp(10), 1f, 0f);
            if (alpha <= 0) continue;
            labelPaint.setAlpha((int) (0xB3 * alpha));
            canvas.drawText(format(stop).replace("×", ""), x, cy + dp(4), labelPaint);
        }

        rect.set(thumbX - thumbHalf, cy - dp(13), thumbX + thumbHalf, cy + dp(13));
        canvas.drawRoundRect(rect, dp(13), dp(13), thumbPaint);
        canvas.drawText(format(zoom), thumbX, cy + dp(4.5f), valuePaint);
    }

    private float[] stopsInRange() {
        int count = 0;
        final float[] stops = new float[STOPS.length + 1];
        if (minZoom < 0.99f) {
            stops[count++] = minZoom;
        }
        for (float stop : STOPS) {
            if (stop >= minZoom - 0.001f && stop <= maxZoom + 0.001f) {
                stops[count++] = stop;
            }
        }
        final float[] result = new float[count];
        System.arraycopy(stops, 0, result, 0, count);
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!hasRange() || getAlpha() < 0.5f) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastStop = -1;
                // fall through
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float z = zoomAt(event.getX());
                // snap to a marked stop when close to it
                for (float stop : stopsInRange()) {
                    if (Math.abs(positionOf(stop) - positionOf(z)) < dp(7)) {
                        z = stop;
                        if (stop != lastStop) {
                            lastStop = stop;
                            try {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                            } catch (Exception ignore) {}
                        }
                        break;
                    }
                }
                zoom = z;
                invalidate();
                if (listener != null) {
                    listener.onZoom(zoom, false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                if (listener != null) {
                    listener.onZoom(zoom, true);
                }
                return true;
        }
        return dragging;
    }
}

package org.telegram.ui.music;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;

/**
 * glassGram's music player: the progress of the track as a line that waves while it plays, as
 * Android's media controls do, and lies flat once it is paused. Dragged, the thumb swells and the
 * time follows it; the track is moved where it is let go.
 */
public class MusicWaveSeekBar extends View {

    public interface Delegate {
        /** While dragging (done false) and once let go (done true). */
        void onSeek(float progress, boolean done);
    }

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint restPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bufferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();

    private Delegate delegate;
    private float progress, bufferedProgress;
    private boolean playing;
    private float amplitude; // 0..1, animated with playing
    private float phase;
    private long lastFrame;

    private boolean dragging, maybeDrag;
    private float dragProgress, downX, downY;
    private float thumbScale = 1f;
    private final int touchSlop;

    public MusicWaveSeekBar(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        playedPaint.setStyle(Paint.Style.STROKE);
        playedPaint.setStrokeCap(Paint.Cap.ROUND);
        playedPaint.setStrokeWidth(AndroidUtilities.dp(3));
        restPaint.setStyle(Paint.Style.STROKE);
        restPaint.setStrokeCap(Paint.Cap.ROUND);
        restPaint.setStrokeWidth(AndroidUtilities.dp(3));
        bufferPaint.set(restPaint);
        setColor(0xffffffff);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setColor(int color) {
        playedPaint.setColor(color);
        thumbPaint.setColor(color);
        restPaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x40));
        bufferPaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x66));
        invalidate();
    }

    public void setProgress(float progress) {
        if (!dragging) {
            this.progress = clamp(progress);
            invalidate();
        }
    }

    public void setBufferedProgress(float progress) {
        bufferedProgress = clamp(progress);
        invalidate();
    }

    public float getProgress() {
        return dragging ? dragProgress : progress;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            invalidate();
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private float left() {
        return getPaddingLeft() + AndroidUtilities.dp(4);
    }

    private float right() {
        return getWidth() - getPaddingRight() - AndroidUtilities.dp(4);
    }

    private float progressAt(float x) {
        final float l = left(), r = right();
        return r > l ? clamp((x - l) / (r - l)) : 0f;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                maybeDrag = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (maybeDrag && !dragging && Math.abs(event.getX() - downX) > touchSlop) {
                    dragging = true;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
                if (dragging) {
                    dragProgress = progressAt(event.getX());
                    if (delegate != null) {
                        delegate.onSeek(dragProgress, false);
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging && maybeDrag) {
                    // a tap moves the track there too
                    dragProgress = progressAt(event.getX());
                    dragging = true;
                }
                // fallthrough
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    progress = dragProgress;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && delegate != null) {
                        delegate.onSeek(dragProgress, true);
                    }
                }
                maybeDrag = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0 : Math.min(48, now - lastFrame) / 1000f;
        lastFrame = now;

        final float targetAmplitude = playing && !dragging ? 1f : 0f;
        amplitude += (targetAmplitude - amplitude) * Math.min(1f, dt * 8f);
        if (Math.abs(targetAmplitude - amplitude) < 0.005f) {
            amplitude = targetAmplitude;
        }
        thumbScale += ((dragging ? 1.6f : 1f) - thumbScale) * Math.min(1f, dt * 14f);
        if (amplitude > 0) {
            // the wave runs towards the thumb
            phase += dt * (float) (Math.PI * 2) * 0.9f;
        }

        final float l = left(), r = right();
        final float cy = getHeight() / 2f;
        final float p = getProgress();
        final float x = l + (r - l) * p;
        final float gap = AndroidUtilities.dp(6);

        // What is still to play: a flat line, the loaded part a little brighter
        if (x + gap < r) {
            canvas.drawLine(x + gap, cy, r, cy, restPaint);
            final float bx = l + (r - l) * bufferedProgress;
            if (bx > x + gap) {
                canvas.drawLine(x + gap, cy, Math.min(bx, r), cy, bufferPaint);
            }
        }

        // What was played: the wave
        final float end = Math.max(l, x - gap);
        if (end > l) {
            final float amp = AndroidUtilities.dp(3.5f) * amplitude;
            if (amp < 0.5f) {
                canvas.drawLine(l, cy, end, cy, playedPaint);
            } else {
                final float wavelength = AndroidUtilities.dp(26);
                final float k = (float) (Math.PI * 2) / wavelength;
                wave.rewind();
                final float step = AndroidUtilities.dp(2);
                boolean first = true;
                for (float px = l; px <= end + 0.5f; px += step) {
                    final float xx = Math.min(px, end);
                    // flattens at both ends so it meets the thumb and the start smoothly
                    final float edge = Math.min(1f, Math.min(xx - l, end - xx) / (wavelength * 0.5f));
                    final float y = cy + (float) Math.sin(xx * k - phase) * amp * edge;
                    if (first) {
                        wave.moveTo(xx, y);
                        first = false;
                    } else {
                        wave.lineTo(xx, y);
                    }
                }
                canvas.drawPath(wave, playedPaint);
            }
        }

        // The thumb: a short upright bar
        final float tw = AndroidUtilities.dp(4) * thumbScale;
        final float th = AndroidUtilities.dp(16) * thumbScale;
        canvas.drawRoundRect(x - tw / 2f, cy - th / 2f, x + tw / 2f, cy + th / 2f, tw / 2f, tw / 2f, thumbPaint);

        if (amplitude > 0 || thumbScale > 1.001f && !dragging || dragging && thumbScale < 1.599f) {
            invalidate();
        } else {
            lastFrame = 0;
        }
    }
}

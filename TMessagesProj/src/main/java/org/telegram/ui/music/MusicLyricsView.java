package org.telegram.ui.music;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

/**
 * glassGram's music player: the lyrics in place of the cover. Timed lyrics keep the line being
 * sung in the upper third, bright and a little larger, the others dimmed and further ones softer;
 * a line tapped moves the track there. Scrolled by hand, the lines stay where they were put for a
 * few seconds, then follow the song again. Plain lyrics are a text to scroll.
 */
public class MusicLyricsView extends View {

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<StaticLayout> layouts = new ArrayList<>();
    private final ArrayList<Float> tops = new ArrayList<>();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final Paint fadePaint = new Paint();

    private MusicLyrics lyrics;
    private int layoutWidth;
    private float contentHeight;
    private float scrollY, targetScrollY;
    private int current = -1;
    private float[] emphasis = new float[0];
    private long userScrolledAt;
    private boolean userScrolling;
    private long lastFrame;
    private Utilities.Callback<Long> onSeek;

    public MusicLyricsView(Context context) {
        super(context);
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(AndroidUtilities.dp(24));
        paint.setColor(0xffffffff);
        scroller = new OverScroller(context);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                userScrolling = true;
                userScrolledAt = SystemClock.elapsedRealtime();
                scrollY = clampScroll(scrollY + dy);
                targetScrollY = scrollY;
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float vx, float vy) {
                userScrolledAt = SystemClock.elapsedRealtime();
                scroller.fling(0, (int) scrollY, 0, (int) -vy, 0, 0, (int) minScroll(), (int) maxScroll());
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (lyrics == null || !lyrics.synced || onSeek == null) {
                    return false;
                }
                final float y = e.getY() + scrollY;
                for (int i = 0; i < layouts.size(); i++) {
                    final float top = tops.get(i);
                    if (y >= top - AndroidUtilities.dp(8) && y <= top + layouts.get(i).getHeight() + AndroidUtilities.dp(8)) {
                        userScrolledAt = 0;
                        onSeek.run(lyrics.lines.get(i).time);
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /** Called with the time (ms) of a tapped line. */
    public void setOnSeek(Utilities.Callback<Long> onSeek) {
        this.onSeek = onSeek;
    }

    public void setLyrics(MusicLyrics lyrics) {
        if (this.lyrics == lyrics) {
            return;
        }
        this.lyrics = lyrics;
        current = -1;
        scrollY = targetScrollY = 0;
        layoutWidth = 0;
        requestLayout();
        invalidate();
    }

    public MusicLyrics getLyrics() {
        return lyrics;
    }

    /** The track's time; the line sung then is brought into place. */
    public void setTime(long timeMs) {
        if (lyrics == null || !lyrics.synced) {
            return;
        }
        final int line = lyrics.lineAt(timeMs);
        if (line != current) {
            current = line;
            invalidate();
        }
    }

    private void buildLayouts(int width) {
        layouts.clear();
        tops.clear();
        if (lyrics == null || width <= 0) {
            contentHeight = 0;
            return;
        }
        layoutWidth = width;
        paint.setTextSize(AndroidUtilities.dp(lyrics.synced ? 25 : 20));
        final float gap = AndroidUtilities.dp(lyrics.synced ? 18 : 6);
        float y = 0;
        for (MusicLyrics.Line line : lyrics.lines) {
            final String text = line.text.isEmpty() ? (lyrics.synced ? "♪" : " ") : line.text;
            final StaticLayout l = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.05f)
                .build();
            layouts.add(l);
            tops.add(y);
            y += l.getHeight() + gap;
        }
        contentHeight = y;
        emphasis = new float[layouts.size()];
    }

    private float anchor() {
        return getHeight() * 0.3f;
    }

    private float minScroll() {
        return lyrics != null && lyrics.synced ? -anchor() : 0;
    }

    private float maxScroll() {
        return Math.max(minScroll(), contentHeight - (lyrics != null && lyrics.synced ? anchor() : getHeight() - AndroidUtilities.dp(40)));
    }

    private float clampScroll(float v) {
        return Math.max(minScroll(), Math.min(maxScroll(), v));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final boolean handled = gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            userScrolling = false;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width != layoutWidth) {
            buildLayouts(width);
        }
        if (layouts.isEmpty()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean animating = false;

        if (scroller.computeScrollOffset()) {
            scrollY = clampScroll(scroller.getCurrY());
            targetScrollY = scrollY;
            animating = true;
        } else if (lyrics.synced && !userScrolling && now - userScrolledAt > 3500) {
            // following the song
            targetScrollY = current >= 0 ? tops.get(current) - anchor() : minScroll();
            targetScrollY = clampScroll(targetScrollY);
            final float d = targetScrollY - scrollY;
            if (Math.abs(d) > 0.5f) {
                scrollY += d * Math.min(1f, dt * 7f);
                animating = true;
            } else {
                scrollY = targetScrollY;
            }
        }

        final int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.translate(getPaddingLeft(), -scrollY);
        for (int i = 0; i < layouts.size(); i++) {
            final StaticLayout l = layouts.get(i);
            final float top = tops.get(i);
            if (top + l.getHeight() < scrollY || top > scrollY + getHeight()) {
                continue;
            }
            float alpha = 1f;
            float scale = 1f;
            if (lyrics.synced) {
                final float target = i == current ? 1f : 0f;
                emphasis[i] += (target - emphasis[i]) * Math.min(1f, dt * 10f);
                if (Math.abs(target - emphasis[i]) > 0.01f) {
                    animating = true;
                } else {
                    emphasis[i] = target;
                }
                final int distance = current < 0 ? i + 1 : Math.abs(i - current);
                final float dim = distance <= 1 ? 0.42f : distance == 2 ? 0.32f : 0.24f;
                alpha = AndroidUtilities.lerp(dim, 1f, emphasis[i]);
                scale = AndroidUtilities.lerp(0.94f, 1f, emphasis[i]);
            } else {
                alpha = 0.92f;
            }
            canvas.save();
            canvas.translate(0, top);
            canvas.scale(scale, scale, 0, l.getHeight() / 2f);
            paint.setAlpha((int) (255 * alpha));
            l.draw(canvas);
            canvas.restore();
        }
        canvas.translate(-getPaddingLeft(), scrollY);
        // the edges fade
        final float fade = AndroidUtilities.dp(36);
        fadePaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
            new int[]{0, 0xff000000, 0xff000000, 0},
            new float[]{0f, fade / getHeight(), 1f - fade / getHeight(), 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), fadePaint);
        canvas.restoreToCount(save);

        if (animating || userScrolledAt != 0 && now - userScrolledAt <= 3600) {
            invalidate();
        } else {
            lastFrame = 0;
        }
    }
}

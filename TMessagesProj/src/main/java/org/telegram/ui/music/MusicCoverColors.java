package org.telegram.ui.music;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.HashMap;

/**
 * glassGram's music player: the colors of a track's cover, and the background made of them. The
 * cover's main colors are taken (Palette) and darkened so that white text stays readable; the
 * background is their gradient with a glow of the brightest one, crossfading from track to track.
 * The mini player takes the same colors (see {@link #get}).
 */
public final class MusicCoverColors {

    /** Background colors: top, middle, bottom, and the accent of the glow and the progress. */
    public final int top, middle, bottom, accent;

    private MusicCoverColors(int top, int middle, int bottom, int accent) {
        this.top = top;
        this.middle = middle;
        this.bottom = bottom;
        this.accent = accent;
    }

    /** The colors without a cover: the dark glass of the client. */
    public static final MusicCoverColors DEFAULT = new MusicCoverColors(0xff2b2a33, 0xff1f1e26, 0xff141319, 0xffb8b2ff);

    private static final HashMap<String, MusicCoverColors> cache = new HashMap<>();
    private static String lastKey;
    private static MusicCoverColors last;

    /** The colors last taken for the playing track, for the mini player; null when there are none yet. */
    @Nullable
    public static MusicCoverColors current() {
        return last;
    }

    @Nullable
    public static MusicCoverColors get(String key) {
        return key != null ? cache.get(key) : null;
    }

    /** Takes the colors of a cover in the background, then calls back on the main thread. */
    public static void take(String key, Bitmap cover, Utilities.Callback<MusicCoverColors> done) {
        if (key != null) {
            final MusicCoverColors cached = cache.get(key);
            if (cached != null) {
                lastKey = key;
                last = cached;
                done.run(cached);
                return;
            }
        }
        if (cover == null || cover.isRecycled()) {
            done.run(DEFAULT);
            return;
        }
        final Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(cover, 64, 64, true);
        } catch (Throwable e) {
            done.run(DEFAULT);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            MusicCoverColors colors;
            try {
                colors = from(Palette.from(small).maximumColorCount(16).generate());
            } catch (Throwable e) {
                colors = DEFAULT;
            }
            final MusicCoverColors result = colors;
            AndroidUtilities.runOnUIThread(() -> {
                if (key != null) {
                    cache.put(key, result);
                    lastKey = key;
                }
                last = result;
                done.run(result);
            });
        });
    }

    private static MusicCoverColors from(Palette palette) {
        final Palette.Swatch dominant = palette.getDominantSwatch();
        final Palette.Swatch vibrant = first(palette.getVibrantSwatch(), palette.getLightVibrantSwatch(), palette.getMutedSwatch(), dominant);
        final Palette.Swatch dark = first(palette.getDarkMutedSwatch(), palette.getDarkVibrantSwatch(), palette.getMutedSwatch(), dominant);
        if (dominant == null) {
            return DEFAULT;
        }
        final int main = dominant.getRgb();
        final int second = vibrant != null ? vibrant.getRgb() : main;
        final int third = dark != null ? dark.getRgb() : main;
        return new MusicCoverColors(
            tone(ColorUtils.blendARGB(second, main, 0.45f), 0.42f),
            tone(main, 0.30f),
            tone(third, 0.16f),
            accent(second)
        );
    }

    private static Palette.Swatch first(Palette.Swatch... swatches) {
        for (Palette.Swatch s : swatches) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** The color at a lightness for a background under white text, its saturation kept moderate. */
    private static int tone(int color, float lightness) {
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[1] = Math.min(hsl[1], 0.55f);
        hsl[2] = Math.min(hsl[2], lightness);
        hsl[2] = Math.max(hsl[2], lightness * 0.55f);
        return ColorUtils.HSLToColor(hsl);
    }

    /** The color for the glow and the progress: light and saturated enough to stand out. */
    private static int accent(int color) {
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[1] = Math.max(0.35f, Math.min(hsl[1], 0.85f));
        hsl[2] = Math.max(0.62f, Math.min(hsl[2], 0.78f));
        return ColorUtils.HSLToColor(hsl);
    }

    public static MusicCoverColors lerp(MusicCoverColors a, MusicCoverColors b, float t) {
        return new MusicCoverColors(
            ColorUtils.blendARGB(a.top, b.top, t),
            ColorUtils.blendARGB(a.middle, b.middle, t),
            ColorUtils.blendARGB(a.bottom, b.bottom, t),
            ColorUtils.blendARGB(a.accent, b.accent, t));
    }

    /** The player's background: the gradient of the colors with a soft glow up top, crossfaded on change. */
    public static class BackgroundDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private MusicCoverColors from = DEFAULT, to = DEFAULT, shown = DEFAULT;
        private ValueAnimator animator;
        private int lastHeight, lastWidth;
        private Runnable onChange;

        public void setOnChange(Runnable onChange) {
            this.onChange = onChange;
        }

        public MusicCoverColors getColors() {
            return shown;
        }

        public void setColors(MusicCoverColors colors, boolean animated) {
            if (colors == null) {
                colors = DEFAULT;
            }
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated) {
                from = to = shown = colors;
                updateShaders();
                return;
            }
            from = shown;
            to = colors;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(520);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.addUpdateListener(a -> {
                shown = MusicCoverColors.lerp(from, to, (float) a.getAnimatedValue());
                updateShaders();
            });
            animator.start();
        }

        private void updateShaders() {
            final Rect b = getBounds();
            if (b.height() <= 0) {
                invalidateSelf();
                return;
            }
            paint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{shown.top, shown.middle, shown.bottom}, new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
            final float r = Math.max(b.width(), b.height()) * 0.55f;
            glowPaint.setShader(new RadialGradient(b.centerX(), b.top + b.height() * 0.22f, r,
                new int[]{ColorUtils.setAlphaComponent(shown.accent, 0x38), ColorUtils.setAlphaComponent(shown.accent, 0)}, null, Shader.TileMode.CLAMP));
            lastHeight = b.height();
            lastWidth = b.width();
            invalidateSelf();
            if (onChange != null) {
                onChange.run();
            }
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            if (bounds.height() != lastHeight || bounds.width() != lastWidth) {
                updateShaders();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            canvas.drawRect(b, paint);
            canvas.drawRect(b, glowPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            glowPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    public static boolean isDark(int color) {
        return ColorUtils.calculateLuminance(color | 0xff000000) < 0.5f;
    }

    private static final java.util.ArrayList<Runnable> listeners = new java.util.ArrayList<>();
    private static org.telegram.messenger.ImageReceiver loader;
    private static String loadingKey;

    /** The colors taken for a track, null when there are none yet. */
    @Nullable
    public static MusicCoverColors forTrack(@Nullable org.telegram.messenger.MessageObject m) {
        return m != null ? cache.get(keyOf(m)) : null;
    }

    /** The key of a track's colors: its document, else its message. */
    public static String keyOf(org.telegram.messenger.MessageObject m) {
        final org.telegram.tgnet.TLRPC.Document document = m.getDocument();
        return document != null ? "doc" + document.id : "msg" + m.getDialogId() + "_" + m.getId();
    }

    /**
     * Takes the colors of the playing track for the mini player, without the full player: from
     * the file's own cover, else from the cover Telegram has for it. The listeners hear of them.
     */
    public static void track(@Nullable org.telegram.messenger.MessageObject m) {
        if (m == null || !m.isMusic()) {
            return;
        }
        final String key = keyOf(m);
        final MusicCoverColors cached = cache.get(key);
        if (cached != null) {
            if (last != cached) {
                lastKey = key;
                last = cached;
                notifyChanged();
            }
            return;
        }
        if (key.equals(loadingKey)) {
            return;
        }
        loadingKey = key;
        final org.telegram.messenger.audioinfo.AudioInfo info = org.telegram.messenger.MediaController.getInstance().getAudioInfo();
        if (info != null && info.getCover() != null) {
            take(key, info.getCover(), c -> notifyChanged());
            return;
        }
        final org.telegram.tgnet.TLRPC.Document document = m.getDocument();
        org.telegram.messenger.ImageLocation location = null;
        final org.telegram.tgnet.TLRPC.PhotoSize thumb = document != null ? org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
        if (thumb instanceof org.telegram.tgnet.TLRPC.TL_photoSize || thumb instanceof org.telegram.tgnet.TLRPC.TL_photoSizeProgressive) {
            location = org.telegram.messenger.ImageLocation.getForDocument(thumb, document);
        } else {
            final String artwork = m.getArtworkUrl(true);
            if (artwork != null) {
                location = org.telegram.messenger.ImageLocation.getForPath(artwork);
            }
        }
        if (location == null) {
            return;
        }
        if (loader == null) {
            loader = new org.telegram.messenger.ImageReceiver();
        }
        loader.setDelegate((imageReceiver, set, isThumb, memCache) -> {
            if (set && key.equals(loadingKey) && imageReceiver.getBitmap() != null) {
                take(key, imageReceiver.getBitmap(), c -> notifyChanged());
            }
        });
        loader.setImage(location, "90_90", (android.graphics.drawable.Drawable) null, null, m, 1);
    }

    /** Called on the main thread when the playing track's colors or their settings change (the mini player). */
    public static void addListener(Runnable listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public static void notifyChanged() {
        for (Runnable r : new java.util.ArrayList<>(listeners)) {
            r.run();
        }
    }
}

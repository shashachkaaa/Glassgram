package org.telegram.ui.Components.blur3.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import org.jspecify.annotations.Nullable;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.utils.glass.source.GlassSourceBitmap;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import java.util.ArrayList;

public class Blur3Utils {
    private Blur3Utils() {

    }

    private static final Matrix matrixTmp = new Matrix();

    public static boolean checkBitmapSourceMatrixScale(@Nullable GlassSourceBitmap source, @Nullable View view) {
        if (source == null || view == null || view.getWidth() == 0 || view.getHeight() == 0) {
            return false;
        }
        final Bitmap bitmap = source.getBitmap();
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {
            return false;
        }

        matrixTmp.reset();
        matrixTmp.setScale(
            (float) view.getWidth() / bitmap.getWidth(),
            (float) view.getHeight() / bitmap.getHeight());
        source.setMatrix(matrixTmp);

        return true;
    }

    private static final ArrayList<RectF> captureRects = new ArrayList<>();
    private static int captureDepth;
    private static final RectF captureTmpChildPos = new RectF();

    public static void captureRelativeParent(IBlur3Capture capture, Canvas canvas, RectF position, View view, ViewGroup parent) {
        captureRelativeParent(capture, canvas, position, view, parent, 255);
    }
    public static void captureRelativeParent(IBlur3Capture capture, Canvas canvas, RectF position, View view, ViewGroup parent, int alpha) {
        if (alpha <= 0 || capture == null) {
            return;
        }

        if (ViewPositionWatcher.computeRectInParent(view, parent, captureTmpChildPos)) {
            final float oX = captureTmpChildPos.left;
            final float oY = captureTmpChildPos.top;
            // Search can capture an embedded chat, which makes a nested capture of its own list.
            if (captureDepth == captureRects.size()) captureRects.add(new RectF());
            final RectF local = captureRects.get(captureDepth++);
            final int saveCount = canvas.getSaveCount();
            try {
                local.set(position);
                local.offset(-oX, -oY);
                if (oX != 0 || oY != 0) {
                    canvas.save();
                    canvas.translate(oX, oY);
                }
                if (alpha != 255) {
                    canvas.saveLayerAlpha(local, alpha);
                }
                capture.capture(canvas, local);
            } finally {
                canvas.restoreToCount(saveCount);
                captureDepth--;
            }
        }
    }

    public static Drawable wrapCenteredDrawable(Drawable drawable, int w, int h) {
        return new Drawable() {
            @Override
            protected void onBoundsChange(@NonNull Rect bounds) {
                super.onBoundsChange(bounds);
                final int x = (bounds.width() - w) / 2;
                final int y = (bounds.height() - h) / 2;
                drawable.setBounds(x, y, x + w, y + h);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                drawable.draw(canvas);
            }

            @Override
            public int getAlpha() {
                return drawable.getAlpha();
            }

            @Override
            public void setAlpha(int alpha) {
                drawable.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(@androidx.annotation.Nullable ColorFilter colorFilter) {

            }

            @Override
            public int getOpacity() {
                return drawable.getOpacity();
            }
        };
    }
}

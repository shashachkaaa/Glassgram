package org.telegram.ui.Components.blur3.drawable;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.liquidglass.BackdropGlass;
import org.telegram.liquidglass.GlassLayer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped;

public class BlurredBackgroundDrawableSource extends BlurredBackgroundDrawable {
    private final BlurredBackgroundSource source;

    public BlurredBackgroundDrawableSource(BlurredBackgroundSource source) {
        this.source = source;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (liquidGlass && drawLiquidGlass(canvas)) {
            return;
        }
        drawSource(canvas, source);
    }

    @Override
    public BlurredBackgroundSource getSource() {
        return source;
    }



    /* Liquid Glass */

    /*
     * Glass over a picture source (a screenshot bitmap behind menus, the chat's wallpaper when
     * the chat blur is off) used to be that picture under a tint: flat, like plastic. It now
     * goes through the library's lens like the RenderNode glass does, refracting the picture at
     * the edges. The picture is drawn as it is, without another blur: the screenshot is already
     * blurred, and the wallpaper is sharp because the chat blur is turned off.
     */
    private static final int BACKDROP_OUTSET_DP = 48;

    private boolean liquidGlass;
    private Object glassLayer;
    private final Paint glassShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix backdropMatrix = new Matrix();
    private Bitmap backdropBitmap;

    /** Draws as Liquid Glass with the library's lens where it can (Android 13 and up). */
    public void setLiquidGlassEffectAllowed() {
        liquidGlass = true;
    }

    private static BlurredBackgroundSource unwrap(BlurredBackgroundSource source) {
        while (source instanceof BlurredBackgroundSourceWrapped) {
            source = ((BlurredBackgroundSourceWrapped) source).getSource();
        }
        return source;
    }

    private boolean drawLiquidGlass(Canvas canvas) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) || !canvas.isHardwareAccelerated()) {
            return false;
        }
        final Rect r = boundProps.boundsWithPadding;
        if (r.isEmpty() || !boundProps.radiiAreSame || Color.alpha(backgroundColor) == 255) {
            return false;
        }
        final BlurredBackgroundSource picture = unwrap(source);
        if (picture == null || picture instanceof BlurredBackgroundSourceColor) {
            // Nothing to refract
            return false;
        }
        if (alpha <= 0) {
            return true;
        }
        if (glassLayer == null) {
            glassLayer = new GlassLayer();
        }

        final int shadow = Theme.multAlpha(shadowColor, shadowAlpha * alpha / 255f);
        if (Color.alpha(shadow) != 0) {
            glassShadowPaint.setColor(0);
            glassShadowPaint.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, shadow);
            boundProps.drawShadows(canvas, glassShadowPaint, inAppKeyboardOptimization);
        }

        final int width = r.width(), height = r.height();
        final float density = AndroidUtilities.density;
        final boolean dark = ColorUtils.calculateLuminance(backgroundColor | 0xFF000000) < 0.5f;
        // The same lens as the RenderNode glass (BackdropGlass.update): sheets and menus get the
        // wide panel lens with depth, bars and buttons the narrow one with dispersion
        final boolean panel = Math.min(width, height) >= BackdropGlass.PanelMinSize * density;
        final int thickness = Math.max(Math.min(
            boundProps.liquidThickness <= 0 ? dp(12) : boundProps.liquidThickness,
            Math.min(width, height) / 4), 1);
        final float lensHeight = panel ? thickness * 2f : thickness;
        final float lensAmount = dp(24) * boundProps.liquidIntensity / 0.75f;
        // A tint as light as on the other glass, or a dense one hides what the lens bends; menus
        // keep it as dense as the glass menus in their own windows (LiquidPanelDrawable) for their text
        final float maxTint = panel ? (dark ? 0.62f : 0.68f) : BackdropGlass.tintAlpha(dark);
        final float tintAlpha = Math.min(Color.alpha(backgroundColor) / 255f, maxTint);
        final int tint = ColorUtils.setAlphaComponent(backgroundColor, (int) (tintAlpha * 255));

        final int saveCount = alpha != 255
            ? canvas.saveLayerAlpha(r.left, r.top, r.right, r.bottom, alpha)
            : canvas.save();
        canvas.translate(r.left, r.top);
        ((GlassLayer) glassLayer).draw(canvas, width, height, boundProps.radii[0], density,
            BackdropGlass.COLOR_NONE, 0f, lensHeight, lensAmount, panel, !panel, dark,
            backdrop, tint, dark ? 0.7f : 1f, 1f);
        canvas.restoreToCount(saveCount);
        return true;
    }

    // A method rather than a lambda in the field: the field is set before the constructor sets source
    private final GlassLayer.Backdrop backdrop = this::drawBackdrop;

    /** What is behind the glass, with (0, 0) at its top-left corner. */
    private void drawBackdrop(Canvas c) {
        final Rect r = boundProps.boundsWithPadding;
        final float e = dp(BACKDROP_OUTSET_DP);
        final BlurredBackgroundSource picture = unwrap(source);
        if (picture instanceof BlurredBackgroundSourceBitmap) {
            // Placed as drawSourceBitmap places it, in the drawable's coordinates
            final BlurredBackgroundSourceBitmap bitmapSource = (BlurredBackgroundSourceBitmap) picture;
            final Bitmap bitmap = bitmapSource.getBitmap();
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            if (bitmap != backdropBitmap) {
                backdropBitmap = bitmap;
                backdropBitmapPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            backdropMatrix.set(bitmapSource.getMatrix());
            backdropMatrix.postTranslate(-sourceOffsetX - r.left, -sourceOffsetY - r.top);
            backdropBitmapPaint.getShader().setLocalMatrix(backdropMatrix);
            c.drawRect(-e, -e, r.width() + e, r.height() + e, backdropBitmapPaint);
        } else if (picture != null) {
            // Placed as drawSourceAny places it
            final float sL = r.left + sourceOffsetX;
            final float sT = r.top + sourceOffsetY;
            c.translate(-sL, -sT);
            picture.draw(c, sL - e, sT - e, sL + r.width() + e, sT + r.height() + e);
        }
    }
}

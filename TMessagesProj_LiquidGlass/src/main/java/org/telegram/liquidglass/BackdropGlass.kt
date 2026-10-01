package org.telegram.liquidglass

import android.graphics.BlurMaskFilter
import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import kotlin.math.ceil
import kotlin.math.min

/**
 * Liquid Glass of one Telegram glass surface, built with Kyant0/AndroidLiquidGlass.
 *
 * The look follows shashachkaaa/ward: the backdrop gets the library's color controls
 * (contrast/saturation tuned per theme), blur and edge lens, and the outline gets the
 * library's ambient highlight. Small surfaces (bars, pills, buttons) refract with
 * dispersion; large ones (sheets, menus) use the wider, dispersion-free panel lens with
 * the depth effect, like ward's `glassPanel`.
 *
 * The caller owns the RenderNodes: it sets [renderEffect] on the node that records the
 * content behind the glass, enlarges that node by [padding] on every side, and calls
 * [drawHighlight] after the tint.
 */
class BackdropGlass {

    private val scope: Any = GlassEffectScope.create()
    private val drawScope = CanvasDrawScope()
    private val emptyCanvas = Canvas(android.graphics.Canvas())

    private val highlightPaint = Paint().apply { style = PaintingStyle.Stroke }
    private val clipPath = Path()
    private var highlightBlurRadius = -1f

    private var width = -1f
    private var height = -1f
    private var radii = FloatArray(4)
    private var density = 0f
    private var refractionHeight = -1f
    private var refractionAmount = -1f
    private var dark = false
    private var built = false

    private var shape = AbsoluteRoundedCornerShape(0f)

    /** Effect for the node that records the content behind the glass, or null below Android 12. */
    var renderEffect: RenderEffect? = null
        private set

    /** How far the recorded content has to extend past the glass bounds, in px. */
    var padding: Float = 0f
        private set

    /**
     * Rebuilds the effect when anything changed.
     *
     * @param radii corner radii in px: top-left, top-right, bottom-right, bottom-left
     * @param refractionHeight width of the refracting band at the edge, px
     * @param refractionAmount how far the band bends the backdrop, px
     * @param dark whether the glass sits on a dark theme
     * @return true if [renderEffect] or [padding] changed
     */
    fun update(
        width: Float,
        height: Float,
        radii: FloatArray,
        density: Float,
        refractionHeight: Float,
        refractionAmount: Float,
        dark: Boolean
    ): Boolean {
        if (built &&
            this.width == width && this.height == height &&
            this.radii.contentEquals(radii) && this.density == density &&
            this.refractionHeight == refractionHeight && this.refractionAmount == refractionAmount &&
            this.dark == dark
        ) {
            return false
        }
        this.width = width
        this.height = height
        this.radii = radii.copyOf()
        this.density = density
        this.refractionHeight = refractionHeight
        this.refractionAmount = refractionAmount
        this.dark = dark
        built = true

        shape = AbsoluteRoundedCornerShape(radii[0], radii[1], radii[2], radii[3])
        val size = Size(width, height)
        drawScope.draw(Density(density), LayoutDirection.Ltr, emptyCanvas, size) {
            GlassEffectScope.update(scope, this)
        }
        GlassEffectScope.setShape(scope, shape)
        GlassEffectScope.reset(scope)

        val panel = min(width, height) >= PanelMinSize * density
        with(GlassEffectScope.asScope(scope)) {
            // Under the glass there is the app's own background: on dark themes the blurred
            // copy turns into a flat grey, so lift and stretch it; on light ones tone it
            // down, otherwise the glass washes out to white.
            if (dark) {
                colorControls(brightness = 0.03f, contrast = 1.12f, saturation = 1.15f)
            } else {
                colorControls(brightness = -0.03f, contrast = 1.06f, saturation = 1.5f)
            }
            blur((if (panel) PanelBlurRadius else BlurRadius).dp.toPx())
            if (panel) {
                lens(refractionHeight * 2f, refractionAmount, depthEffect = true, chromaticAberration = false)
            } else {
                lens(refractionHeight, refractionAmount, depthEffect = false, chromaticAberration = true)
            }
        }

        val scopeApi = GlassEffectScope.asScope(scope)
        renderEffect = scopeApi.renderEffect
        padding = scopeApi.padding
        return true
    }

    /** Draws the library's ambient highlight along the glass outline, at (0, 0). */
    fun drawHighlight(canvas: android.graphics.Canvas) {
        if (!built || width <= 0f || height <= 0f) return
        val highlight = Highlight.Ambient
        val size = Size(width, height)
        drawScope.draw(Density(density), LayoutDirection.Ltr, Canvas(canvas), size) {
            val outline = shape.createOutline(size, layoutDirection, this)

            highlightPaint.color = highlight.style.color
            highlightPaint.alpha = highlight.style.color.alpha * highlight.alpha * if (dark) 0.7f else 1f
            highlightPaint.blendMode = highlight.style.blendMode
            highlightPaint.strokeWidth = ceil(min(highlight.width.toPx(), size.minDimension / 2f)) * 2f
            val blurRadius = highlight.blurRadius.toPx()
            if (blurRadius != highlightBlurRadius) {
                highlightBlurRadius = blurRadius
                highlightPaint.asFrameworkPaint().maskFilter =
                    if (blurRadius > 0f) BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL) else null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                highlightPaint.shader = with(highlight.style) {
                    createShader(shape, GlassEffectScope.asShaderCache(scope))
                }
            }

            val c = drawContext.canvas
            c.save()
            when (outline) {
                is Outline.Rounded -> {
                    clipPath.reset()
                    clipPath.addRoundRect(outline.roundRect)
                    c.clipPath(clipPath)
                }
                is Outline.Rectangle -> c.clipRect(outline.rect)
                is Outline.Generic -> c.clipPath(outline.path)
            }
            c.drawOutline(outline, highlightPaint)
            c.restore()
        }
    }

    companion object {
        /** Blur radius under bars, pills and buttons, dp. */
        const val BlurRadius = 8f

        /** Blur radius under sheets and menus, dp: they cover content that should stay readable through them. */
        const val PanelBlurRadius = 6f

        /** Surfaces at least this tall and wide are treated as panels, dp. */
        const val PanelMinSize = 120f

        /** Blur and color controls need RenderEffect (Android 12); the lens needs RuntimeShader (Android 13). */
        @JvmStatic
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /** Alpha of the tint over the glass, so the backdrop stays visible like in ward. */
        @JvmStatic
        fun tintAlpha(dark: Boolean): Float = if (dark) 0.35f else 0.45f
    }
}


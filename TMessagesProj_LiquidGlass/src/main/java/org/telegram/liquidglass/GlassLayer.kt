package org.telegram.liquidglass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.min

/**
 * One piece of Liquid Glass drawn inside an ordinary View, the way `Modifier.drawBackdrop`
 * draws it in Compose: the backdrop is recorded into a RenderNode that carries the library's
 * effects (color filter, blur, lens), clipped to the glass shape, covered with the surface
 * color and outlined with the library's highlight.
 *
 * The backdrop is whatever the caller draws in [Backdrop.draw], in glass-local coordinates.
 * It must not include the glass itself: a backdrop that draws its own glass is a loop and
 * crashes the RenderThread (see the library's FAQ).
 */
@RequiresApi(Build.VERSION_CODES.S)
class GlassLayer {

    fun interface Backdrop {
        /** Draws what is behind the glass; (0, 0) is the glass's top-left corner. */
        fun draw(canvas: Canvas)
    }

    private val glass = BackdropGlass()
    private val node = RenderNode("LiquidGlassLayer")
    private val clip = Path()
    private val radii = FloatArray(4)

    /**
     * Draws the glass at (0, 0) of [canvas], which must be hardware accelerated.
     *
     * @param cornerRadius px, clamped to a capsule
     * @param colorMode one of the `BackdropGlass.COLOR_*` constants
     * @param blurRadius px, 0 for none
     * @param lensHeight px, 0 for no lens
     * @param lensAmount px, 0 for no lens
     * @param surfaceColor drawn over the backdrop inside the shape; transparent for none
     * @param highlightAlpha opacity of the library's ambient highlight, 0 for none
     * @param highlightScale scale of the highlight's width and blur
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        cornerRadius: Float,
        density: Float,
        colorMode: Int,
        blurRadius: Float,
        lensHeight: Float,
        lensAmount: Float,
        depthEffect: Boolean,
        chromaticAberration: Boolean,
        dark: Boolean,
        backdrop: Backdrop,
        surfaceColor: Int,
        highlightAlpha: Float,
        highlightScale: Float
    ) {
        if (width <= 0f || height <= 0f) return
        val r = min(cornerRadius, min(width, height) / 2f)
        radii.fill(r)
        if (glass.updateEffect(
                width, height, radii, density, colorMode, blurRadius,
                lensHeight, lensAmount, depthEffect, chromaticAberration, dark
            )
        ) {
            node.setRenderEffect(glass.renderEffect)
        }

        val pad = ceil(glass.padding).toInt()
        node.setPosition(-pad, -pad, ceil(width).toInt() + pad, ceil(height).toInt() + pad)
        val recording = node.beginRecording()
        recording.translate(pad.toFloat(), pad.toFloat())
        backdrop.draw(recording)
        node.endRecording()

        canvas.save()
        clip.rewind()
        clip.addRoundRect(0f, 0f, width, height, r, r, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRenderNode(node)
        if (Color.alpha(surfaceColor) != 0) {
            canvas.drawColor(surfaceColor)
        }
        canvas.restore()

        glass.drawHighlight(canvas, highlightAlpha, highlightScale)
    }

    companion object {
        @JvmStatic
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

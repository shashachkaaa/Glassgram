package org.telegram.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.telegram.liquidglass.catalog.InteractiveHighlight
import org.telegram.liquidglass.catalog.LiquidButton
import org.telegram.liquidglass.catalog.LiquidButtonMotion

/**
 * The catalog's [LiquidButton] for a Telegram button: its glass, its highlight and its press,
 * drawn by the button's owner in its own canvas ([drawInto]), under the content the owner
 * draws itself, which follows the press with [getContentScaleX] and the rest.
 *
 * The view needs a window for its composition but must not be drawn by a parent, as the owner
 * draws it in its place among its own drawing; so it lives out of sight (gone) in the root of
 * the owner's window ([attach]), and is measured and placed by [drawInto].
 */
class LiquidButtonView(context: Context) : AbstractComposeView(context) {

    /** What is under the button, with (0, 0) at its top-left corner. */
    fun interface Source {
        fun draw(canvas: Canvas)
    }

    /** The owner redraws: the press moved the button. */
    fun interface InvalidateListener {
        fun onInvalidate()
    }

    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val highlight = InteractiveHighlight(scope)
    private val motion = LiquidButtonMotion()
    private var watchJob: Job? = null

    private var source: Source? = null
    private var invalidateListener: InvalidateListener? = null

    private var tintState by mutableIntStateOf(0)
    private var surfaceColorState by mutableIntStateOf(0)
    private var cornerRadiusState by mutableFloatStateOf(Float.POSITIVE_INFINITY)
    private var backdropTick by mutableIntStateOf(0)

    private var buttonWidth = 0
    private var buttonHeight = 0
    private val overflow = (OVERFLOW_DP * context.resources.displayMetrics.density).toInt()

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        visibility = View.GONE
    }

    fun setSource(source: Source?) {
        this.source = source
    }

    fun setInvalidateListener(listener: InvalidateListener?) {
        invalidateListener = listener
    }

    /** The catalog's tint (the filled buttons), 0 for none. */
    fun setTint(color: Int) {
        tintState = color
    }

    /** The catalog's surface color over the glass, 0 for none. */
    fun setSurfaceColor(color: Int) {
        surfaceColorState = color
    }

    /** px; infinite for a capsule. */
    fun setCornerRadius(radius: Float) {
        cornerRadiusState = radius
    }

    /* The press, fed by the owner from its own touches, in the button's coordinates */

    fun press(x: Float, y: Float) = highlight.press(Offset(x, y))

    fun move(x: Float, y: Float) = highlight.move(Offset(x, y))

    fun release() = highlight.release()

    fun isActive(): Boolean = highlight.isActive

    fun getPressProgress(): Float = highlight.pressProgress

    /** The press transform of a button of this size, scaled about its middle; read the getters after. */
    fun computeMotion(width: Float, height: Float) {
        motion.update(width, height, FOUR_DP * resources.displayMetrics.density, highlight.pressProgress, highlight.offset)
    }

    fun getContentScaleX(): Float = motion.scaleX

    fun getContentScaleY(): Float = motion.scaleY

    fun getContentTranslationX(): Float = motion.translationX

    fun getContentTranslationY(): Float = motion.translationY

    /** Puts the view, out of sight, in the root of [owner]'s window; call when the owner is attached. */
    fun attach(owner: View) {
        val root = owner.rootView as? ViewGroup ?: return
        if (parent === root) {
            return
        }
        (parent as? ViewGroup)?.removeView(this)
        root.addView(this, ViewGroup.LayoutParams(0, 0))
    }

    fun detach() {
        (parent as? ViewGroup)?.removeView(this)
    }

    /**
     * Draws the button with its top-left corner at ([left], [top]) of [canvas], [width] by
     * [height]; false when it can't be drawn yet (no window, a software canvas), and the owner
     * draws its old glass instead.
     */
    fun drawInto(canvas: Canvas, left: Float, top: Float, width: Int, height: Int): Boolean {
        if (!isAttachedToWindow || !canvas.isHardwareAccelerated || width <= 0 || height <= 0) {
            return false
        }
        if (width != buttonWidth || height != buttonHeight || isLayoutRequested) {
            buttonWidth = width
            buttonHeight = height
            measure(
                View.MeasureSpec.makeMeasureSpec(width + 2 * overflow, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height + 2 * overflow, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, width + 2 * overflow, height + 2 * overflow)
        }
        // What is under the button changes with every frame of its owner
        backdropTick++
        Snapshot.sendApplyNotifications()
        canvas.save()
        canvas.translate(left - overflow, top - overflow)
        draw(canvas)
        canvas.restore()
        return true
    }

    override fun onAttachedToWindow() {
        ComposeOwners.install(this)
        super.onAttachedToWindow()
        watchJob?.cancel()
        watchJob = scope.launch {
            snapshotFlow { highlight.pressProgress to highlight.offset }
                .collect { invalidateListener?.onInvalidate() }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.coroutineContext.cancelChildren()
        watchJob = null
    }

    /**
     * A software canvas (a picture of the screen, like the chats list's blur under a chat's
     * preview) can't take the glass's shaders, so the glass is left out of it.
     */
    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
        }
    }

    private val backdrop = object : Backdrop {
        override val isCoordinatesDependent: Boolean = true

        override fun DrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?
        ) {
            backdropTick
            val source = source ?: return
            // As the library's own backdrops: what is behind does not swell with the button
            var sx = 1f
            var sy = 1f
            if (layerBlock != null) {
                motion.update(size.width, size.height, FOUR_DP * density.density, highlight.pressProgress, highlight.offset)
                sx = motion.scaleX
                sy = motion.scaleY
            }
            scale(1f / sx, 1f / sy, Offset.Zero) {
                drawIntoCanvas { source.draw(it.nativeCanvas) }
            }
        }
    }

    @Composable
    override fun Content() {
        val density = LocalDensity.current
        val radius = cornerRadiusState
        LiquidButton(
            backdrop = backdrop,
            interactiveHighlight = highlight,
            modifier = Modifier
                .fillMaxSize()
                .padding(with(density) { overflow.toDp() }),
            tint = if (tintState != 0) Color(tintState) else Color.Unspecified,
            surfaceColor = if (surfaceColorState != 0) Color(surfaceColorState) else Color.Unspecified,
            cornerRadius = if (radius.isInfinite()) Dp.Infinity else with(density) { radius.toDp() }
        )
    }

    companion object {
        /** Room around the button for its swell, the lean towards the finger and the library's shadow (24dp, 4dp down). */
        const val OVERFLOW_DP = 32
        private const val FOUR_DP = 4f

        /** The glass needs RenderEffect. */
        @JvmStatic
        fun isSupported(): Boolean = LiquidToggleView.isSupported()
    }
}

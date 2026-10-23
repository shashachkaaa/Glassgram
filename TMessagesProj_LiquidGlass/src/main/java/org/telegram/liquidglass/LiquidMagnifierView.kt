package org.telegram.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import org.telegram.liquidglass.catalog.LiquidMagnifier

/**
 * The catalog's [LiquidMagnifier] for text selection, in place of the system's magnifier: drawn
 * by its owner in its own canvas ([drawInto]) over the text, like [LiquidButtonView], which it
 * lives as: out of sight in the root of the owner's window, for its composition.
 */
class LiquidMagnifierView(context: Context) : AbstractComposeView(context) {

    /** What is under the magnifier, with (0, 0) at its top-left corner. */
    fun interface Source {
        fun draw(canvas: Canvas)
    }

    private val slot = WindowRootSlot(this)
    private var source: Source? = null
    private var backdropTick by mutableIntStateOf(0)
    private var drawnWidth = 0
    private var drawnHeight = 0

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        visibility = View.GONE
    }

    fun setSource(source: Source?) {
        this.source = source
    }

    fun attach(owner: View) = slot.attach(owner)

    fun detach() = slot.detach()

    /** Draws the magnifier with its top-left corner at ([left], [top]); false when it can't yet. */
    fun drawInto(canvas: Canvas, left: Float, top: Float): Boolean {
        if (!isAttachedToWindow || !canvas.isHardwareAccelerated) {
            return false
        }
        val density = resources.displayMetrics.density
        val width = (WIDTH_DP * density).toInt()
        val height = (HEIGHT_DP * density).toInt()
        if (width != drawnWidth || height != drawnHeight || isLayoutRequested) {
            drawnWidth = width
            drawnHeight = height
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, width, height)
        }
        backdropTick++
        Snapshot.sendApplyNotifications()
        canvas.save()
        canvas.translate(left, top)
        draw(canvas)
        canvas.restore()
        return true
    }

    override fun onAttachedToWindow() {
        ComposeOwners.install(this)
        super.onAttachedToWindow()
    }

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
            drawIntoCanvas { source.draw(it.nativeCanvas) }
        }
    }

    @Composable
    override fun Content() {
        LiquidMagnifier(backdrop = backdrop, modifier = Modifier.fillMaxSize())
    }

    companion object {
        /** The catalog's magnifier. */
        const val WIDTH_DP = 128
        const val HEIGHT_DP = 96

        /** How far below the magnifier what it shows is. */
        const val OFFSET_DP = org.telegram.liquidglass.catalog.MAGNIFIER_OFFSET_DP

        @JvmStatic
        fun isSupported(): Boolean = LiquidToggleView.isSupported()
    }
}

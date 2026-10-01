package org.telegram.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * Liquid Glass panel rendered with [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Backdrop).
 *
 * The content behind the glass is not a Compose tree, so it is supplied by a [Source] that draws it
 * in the coordinate space of [Source.getSourceView]. The glass capsule fills this view minus [setInset].
 */
class LiquidGlassPanelView(context: Context) : AbstractComposeView(context) {

    interface Source {
        /** The view whose coordinate space [draw] uses. */
        fun getSourceView(): View?

        /** Draws the content behind the glass, in [getSourceView] coordinates. */
        fun draw(canvas: Canvas)
    }

    private var source: Source? = null

    private var sourceOffset by mutableStateOf(Offset.Zero)
    private var insetPx by mutableFloatStateOf(0f)
    private var cornerRadiusPx by mutableFloatStateOf(-1f)
    private var surfaceColorState by mutableIntStateOf(0)
    private var isDarkState by mutableStateOf(false)
    private var redrawTick by mutableIntStateOf(0)

    private val location = IntArray(2)
    private val sourceLocation = IntArray(2)

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateSourceOffset()
        true
    }

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun setSource(source: Source?) {
        this.source = source
        invalidateBackdrop()
    }

    fun setInset(px: Float) {
        insetPx = px
    }

    /** Corner radius in px; a negative value makes a capsule. */
    fun setCornerRadius(px: Float) {
        cornerRadiusPx = px
    }

    fun setSurfaceColor(color: Int) {
        surfaceColorState = color
    }

    fun setDark(dark: Boolean) {
        isDarkState = dark
    }

    /** Forces the backdrop to be re-recorded, e.g. after the source was replaced. */
    fun invalidateBackdrop() {
        redrawTick++
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        super.onDetachedFromWindow()
    }

    private fun updateSourceOffset() {
        val sourceView = source?.getSourceView() ?: return
        getLocationInWindow(location)
        sourceView.getLocationInWindow(sourceLocation)
        val offset = Offset(
            (location[0] - sourceLocation[0]).toFloat(),
            (location[1] - sourceLocation[1]).toFloat()
        )
        if (offset != sourceOffset) {
            sourceOffset = offset
        }
    }

    private val backdrop = object : Backdrop {
        override val isCoordinatesDependent: Boolean = true

        override fun DrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?
        ) {
            redrawTick
            val source = source ?: return
            val position = sourceOffset + (coordinates?.positionInRoot() ?: Offset.Zero)
            translate(-position.x, -position.y) {
                source.draw(drawContext.canvas.nativeCanvas)
            }
        }
    }

    @Composable
    override fun Content() {
        val shape = remember(cornerRadiusPx) {
            if (cornerRadiusPx < 0f) RoundedCornerShape(50) else RoundedCornerShape(cornerRadiusPx)
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(with(LocalDensity.current) { insetPx.toDp() })
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(4f.dp.toPx())
                        lens(16f.dp.toPx(), 32f.dp.toPx())
                    },
                    highlight = {
                        if (isDarkState) Highlight.Default.copy(alpha = 0.6f) else Highlight.Default
                    },
                    shadow = {
                        Shadow(radius = 8f.dp, color = Color.Black.copy(alpha = if (isDarkState) 0.3f else 0.12f))
                    },
                    onDrawSurface = {
                        drawRect(Color(surfaceColorState))
                    }
                )
        )
    }

    companion object {
        /** Refraction (lens) needs RuntimeShader, i.e. Android 13+. */
        @JvmStatic
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
}

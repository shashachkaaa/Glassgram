package org.telegram.liquidglass

import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/**
 * What is behind a Compose view of the library (a slider's or a toggle's), drawn by its owner
 * as a [Backdrop], so the glass on it bends what is really under it at its edges, as the
 * buttons' and the tabs' glass does, instead of bending nothing.
 */
class SourceBackdrop : Backdrop {

    /** Draws what is behind the Compose view, with (0, 0) at the view's top-left corner. */
    fun interface Source {
        fun draw(canvas: Canvas)
    }

    var source: Source? = null

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val source = source ?: return
        // From the glass's place in the view to the view's corner, where the owner's picture starts
        val offset = coordinates?.positionInRoot() ?: Offset.Zero
        translate(-offset.x, -offset.y) {
            drawIntoCanvas { source.draw(it.nativeCanvas) }
        }
    }
}

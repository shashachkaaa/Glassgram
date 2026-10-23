package org.telegram.liquidglass.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * LiquidButton from the Kyant0/AndroidLiquidGlass catalog, unchanged in look and motion.
 *
 * Differences, for Telegram's buttons: the button is the glass alone, and its content stays
 * Telegram's views (icons, the avatar and the name), which follow it with the same transform
 * ([LiquidButtonMotion]); it is pressed through the [interactiveHighlight] its owner drives, as
 * the touches go to those views with their own clicks and long presses; its corners can be
 * smaller than a capsule's ([cornerRadius]), for cards; a wide button swells and stretches
 * by the catalog's 4dp along each side rather than by 4dp of its height on both, which grew
 * the full-width ones by tens of dp past the edges of their sheets; and its tint, surface and
 * corners are read as it is drawn, not composed, as its owner draws it at once after setting
 * them (a composition would come a frame later, after the owner's last drawing).
 */
@Composable
fun LiquidButton(
    backdrop: Backdrop,
    interactiveHighlight: InteractiveHighlight,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: () -> Color = { Color.Unspecified },
    surfaceColor: () -> Color = { Color.Unspecified },
    cornerRadius: () -> Dp = { Dp.Infinity }
) {
    val motion = remember { LiquidButtonMotion() }
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = {
                    val radius = cornerRadius()
                    if (radius == Dp.Infinity) Capsule() else RoundedRectangle(radius)
                },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = if (isInteractive) {
                    {
                        motion.update(
                            size.width,
                            size.height,
                            4f.dp.toPx(),
                            interactiveHighlight.pressProgress,
                            interactiveHighlight.offset
                        )
                        translationX = motion.translationX
                        translationY = motion.translationY
                        scaleX = motion.scaleX
                        scaleY = motion.scaleY
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    val tintColor = tint()
                    val surface = surfaceColor()
                    if (tintColor.isSpecified) {
                        drawRect(tintColor, blendMode = BlendMode.Hue)
                        drawRect(tintColor.copy(alpha = 0.75f))
                    }
                    if (surface.isSpecified) {
                        drawRect(surface)
                    }
                }
            )
            .then(
                if (isInteractive) {
                    interactiveHighlight.modifier
                } else {
                    Modifier
                }
            )
    )
}

/**
 * The catalog's LiquidButton layerBlock: pressed, the button swells by 4dp, leans towards the
 * finger as it moves (a tanh of the drag, at most the button's smaller size) and stretches
 * along the drag. Scaled about the button's middle, as a graphics layer is.
 */
class LiquidButtonMotion {
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set
    var translationX = 0f
        private set
    var translationY = 0f
        private set

    fun update(width: Float, height: Float, fourDp: Float, progress: Float, offset: Offset) {
        if (width <= 0f || height <= 0f) {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            return
        }
        // The catalog swells by 4dp of the height on both axes; along each side here (see above)
        val swell = fourDp * progress
        val scaleBaseX = 1f + swell / width
        val scaleBaseY = 1f + swell / height

        val maxOffset = min(width, height)
        val initialDerivative = 0.05f
        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

        val maxDimension = max(width, height)
        val offsetAngle = atan2(offset.y, offset.x)
        scaleX = scaleBaseX + fourDp / width * min(1f, abs(cos(offsetAngle) * offset.x / maxDimension))
        scaleY = scaleBaseY + fourDp / height * min(1f, abs(sin(offsetAngle) * offset.y / maxDimension))
    }
}

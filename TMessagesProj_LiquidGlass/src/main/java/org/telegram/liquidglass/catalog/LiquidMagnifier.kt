package org.telegram.liquidglass.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.shapes.Capsule

/**
 * The magnifier of the Kyant0/AndroidLiquidGlass catalog (its MagnifierContent), unchanged in look:
 * a glass capsule with a deep lens and a rainbow fringe at its edge, an inner shadow, showing what
 * lies [MAGNIFIER_OFFSET_DP] below it at 1.5x.
 *
 * Differences: it is the magnifier alone, sized by its caller, over the text being selected
 * rather than the demo's paragraph and cursor.
 */
@Composable
fun LiquidMagnifier(
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                lens(
                    8f.dp.toPx(),
                    24f.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = true
                )
            },
            innerShadow = { InnerShadow(radius = 16f.dp) },
            onDrawBackdrop = { drawBackdrop ->
                val offset = MAGNIFIER_OFFSET_DP.dp.toPx()
                withTransform(
                    {
                        scale(1.5f, 1.5f)
                        translate(top = -offset)
                    },
                    drawBackdrop
                )
            }
        )
    )
}

/** How far below the magnifier what it shows is, as in the catalog. */
const val MAGNIFIER_OFFSET_DP = 80f

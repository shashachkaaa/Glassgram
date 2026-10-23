package org.telegram.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.telegram.liquidglass.catalog.DampedDragAnimation
import org.telegram.liquidglass.catalog.InteractiveHighlight
import org.telegram.liquidglass.catalog.LocalLiquidBottomTabScale
import kotlin.math.abs
import kotlin.math.sign

/**
 * The catalog's LiquidBottomTabs for more tabs than fit side by side (a profile's shared media:
 * photos, files, links, music, voice, GIFs, stories...). Not in the catalog: built from its
 * pieces, with its numbers, the same glass capsule and the same drop.
 *
 * Differences: each tab is as wide as its title ([tabWidths]) and the row scrolls inside the
 * capsule, which stays put; the drop rides from tab to tab growing or shrinking to the next
 * one's width, scrolls with the row and is cut at the capsule's ends; a drag along the drop
 * moves it a tab per tab's width, and the row follows it, and keeps the chosen tab in sight.
 * The tabs are left to right.
 */
@Composable
internal fun LiquidScrollTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabWidths: List<Float>,
    modifier: Modifier = Modifier,
    accentColor: Color,
    stiffness: Float,
    blurs: Boolean,
    refracts: Boolean,
    containerColor: Color,
    isLightTheme: Boolean,
    barHeight: Dp,
    barGrowth: Dp,
    dropGrowth: Dp,
    dropLensHeight: Dp,
    dropLensAmount: Dp,
    dropChromaticAberration: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    val tabsCount = tabWidths.size
    val indicatorHeight = barHeight - 8f.dp
    val tabsBackdrop = rememberLayerBackdrop()
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier.scrollable(scrollState, Orientation.Horizontal, reverseDirection = true),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val viewport = constraints.maxWidth.toFloat()
        val edge = with(density) { 4f.dp.toPx() }
        val margin = with(density) { 48f.dp.toPx() }
        val geometry by rememberUpdatedState(TabsGeometry(tabWidths, edge))
        val viewportState = rememberUpdatedState(viewport)

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / viewportState.value).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        // As in LiquidBottomTabs: a tap without a move must not send the drop back
        var didDrag by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = DROP_PRESSED_SCALE,
                stiffness = stiffness,
                onDragStarted = {},
                onDragStopped = {
                    if (didDrag) {
                        val targetIndex =
                            targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                        currentIndex = targetIndex
                        animateToValue(targetIndex.toFloat())
                        didDrag = false
                    }
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    if (dragAmount.x != 0f) didDrag = true
                    // A tab's width of drag moves the drop by a tab, whatever its width
                    val width = geometry.width(targetValue).coerceAtLeast(1f)
                    updateValue(
                        (targetValue + dragAmount.x / width)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }
        // The chosen tab in sight: at once when the row is laid out, smoothly when it changes
        LaunchedEffect(scrollState) {
            var shown = currentIndex
            snapshotFlow { currentIndex to scrollState.maxValue }
                .collectLatest { (index, _) ->
                    val target = geometry.scrollToShow(index, scrollState.value.toFloat(), viewportState.value, margin)
                    if (index == shown) {
                        scrollState.scrollTo(target)
                    } else {
                        shown = index
                        scrollState.animateScrollTo(target)
                    }
                }
        }
        // The dragged drop in sight: the row follows it past the capsule's ends
        LaunchedEffect(scrollState, dampedDragAnimation) {
            snapshotFlow {
                if (didDrag && dampedDragAnimation.pressProgress > 0f) dampedDragAnimation.value else Float.NaN
            }.collect { value ->
                if (!value.isNaN()) {
                    val g = geometry
                    val left = g.start(value) - margin
                    val right = g.start(value) + g.width(value) + margin
                    val scroll = scrollState.value.toFloat()
                    if (left < scroll) {
                        scrollState.dispatchRawDelta(left - scroll)
                    } else if (right > scroll + viewportState.value) {
                        scrollState.dispatchRawDelta(right - scroll - viewportState.value)
                    }
                }
            }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    val g = geometry
                    val value = dampedDragAnimation.value
                    Offset(
                        g.start(value) + g.width(value) / 2f - scrollState.value + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        if (blurs) {
                            vibrancy()
                            blur(8f.dp.toPx())
                        }
                        if (refracts) {
                            lens(24f.dp.toPx(), 24f.dp.toPx(), depthEffect = true)
                        }
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + barGrowth.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(barHeight)
                .fillMaxWidth()
        ) {
            TabsRow(scrollState, Modifier.padding(4f.dp), content)
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Box(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            if (blurs) {
                                vibrancy()
                                blur(8f.dp.toPx())
                            }
                            if (refracts) {
                                lens(
                                    24f.dp.toPx() * progress,
                                    24f.dp.toPx() * progress,
                                    depthEffect = true
                                )
                            }
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(indicatorHeight)
                    .fillMaxWidth()
            ) {
                TabsRow(
                    scrollState,
                    Modifier
                        .padding(horizontal = 4f.dp)
                        .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                    content
                )
            }
        }

        // The drop scrolls with the row, and is cut at the capsule's ends, not above or below
        // them, where it swells when pressed
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    clipRect(0f, -size.height, size.width, size.height * 2f) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = geometry.start(dampedDragAnimation.value) - scrollState.value + panelOffset
                    }
                    .then(interactiveHighlight.gestureModifier)
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule() },
                        effects = {
                            if (refracts) {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    dropLensHeight.toPx() * progress,
                                    dropLensAmount.toPx() * progress,
                                    chromaticAberration = dropChromaticAberration
                                )
                            }
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        shadow = {
                            val progress = dampedDragAnimation.pressProgress
                            Shadow(alpha = progress)
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 8f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            if (!dropGrowth.value.isNaN()) {
                                val growth = dropGrowth.toPx()
                                scaleX = 1f + growth / size.width * (dampedDragAnimation.scaleX - 1f) / (DROP_PRESSED_SCALE - 1f)
                                scaleY = 1f + growth / size.height * (dampedDragAnimation.scaleY - 1f) / (DROP_PRESSED_SCALE - 1f)
                            } else {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                            }
                            // The drag's speed as a share of what is in sight, as across the
                            // catalog's bar, where the tabs fill it
                            val velocity = dampedDragAnimation.velocity / 10f * geometry.velocityScale(viewportState.value)
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                if (isLightTheme) Color.Black.copy(0.1f)
                                else Color.White.copy(0.1f),
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .layout { measurable, _ ->
                        // As wide as the tab under it, between two tabs as wide as both in turn
                        val width = geometry.width(dampedDragAnimation.value).fastRoundToInt().coerceAtLeast(1)
                        val height = indicatorHeight.roundToPx()
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }
    }
}

/** The tabs in a row as wide as their titles, scrolled with [scrollState]: the gesture is the bar's. */
@Composable
private fun TabsRow(scrollState: ScrollState, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxSize()
            .horizontalScroll(scrollState, enabled = false)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Where the tabs lie in the scrolled row, and the drop at a value between two of them. */
private class TabsGeometry(private val widths: List<Float>, edge: Float) {

    private val starts = FloatArray(widths.size + 1).also { starts ->
        starts[0] = edge
        for (i in widths.indices) {
            starts[i + 1] = starts[i] + widths[i]
        }
    }

    private fun index(value: Float): Int = value.toInt().coerceIn(0, (widths.size - 1).coerceAtLeast(0))

    fun start(value: Float): Float {
        if (widths.isEmpty()) return 0f
        val i = index(value)
        val fraction = (value - i).coerceIn(0f, 1f)
        return if (i + 1 < widths.size) lerp(starts[i], starts[i + 1], fraction) else starts[i]
    }

    fun width(value: Float): Float {
        if (widths.isEmpty()) return 0f
        val i = index(value)
        val fraction = (value - i).coerceIn(0f, 1f)
        return if (i + 1 < widths.size) lerp(widths[i], widths[i + 1], fraction) else widths[i]
    }

    /** The scroll that shows tab [index] with [margin] around it, or [scroll] if it is in sight. */
    fun scrollToShow(index: Int, scroll: Float, viewport: Float, margin: Float): Int {
        if (index !in widths.indices) return scroll.toInt()
        val left = starts[index] - margin
        val right = starts[index + 1] + margin
        val target = when {
            left < scroll -> left
            right > scroll + viewport -> right - viewport
            else -> scroll
        }
        return target.coerceAtLeast(0f).toInt()
    }

    /** The drop's speed in tabs, as a share of what is in sight. */
    fun velocityScale(viewport: Float): Float {
        if (widths.size < 2 || viewport <= 0f) return 1f
        return (widths.size - 1) * (widths.sum() / widths.size) / viewport
    }
}

/** The catalog's pressed drop (LiquidBottomTabs). */
private const val DROP_PRESSED_SCALE = 78f / 56f

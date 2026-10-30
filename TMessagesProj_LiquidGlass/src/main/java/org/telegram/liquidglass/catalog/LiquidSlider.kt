package org.telegram.liquidglass.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LiquidSlider from the Kyant0/AndroidLiquidGlass catalog, unchanged in look and motion.
 *
 * Differences, for Telegram's sliders: the colors come from the caller (the theme instead of
 * the system's); the value is a fraction of the track, which lies [trackInset] in from the
 * sides, leaving room for the thumb to swell; the whole slider takes the gesture, a drag
 * started away from the thumb bringing it under the finger, and the thumb swells only once
 * it is dragged sideways, as lists are scrolled from their sliders; [steps] makes it go from
 * notch to notch; [minValue] keeps it from going lower, the track below it drawn faded;
 * [twoSided] fills the track from its middle; [buffered] shows how much of a track is
 * loaded; the owner hears when a drag ends, and [syncTick] with [animate] place the thumb
 * from outside, sliding or jumping.
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (value: Float, stop: Boolean) -> Unit,
    onPressedChange: (pressed: Boolean) -> Unit,
    backdrop: Backdrop,
    accentColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    trackInset: Dp = 0f.dp,
    steps: () -> Int = { 0 },
    minValue: () -> Float = { 0f },
    twoSided: () -> Boolean = { false },
    buffered: () -> Float = { 0f },
    bufferedColor: Color = Color.Transparent,
    syncTick: () -> Int = { 0 },
    animate: () -> Boolean = { true }
) {
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val onValueChangeState = rememberUpdatedState(onValueChange)
    val onPressedChangeState = rememberUpdatedState(onPressedChange)
    val stepsState = rememberUpdatedState(steps)
    val minValueState = rememberUpdatedState(minValue)

    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val insetPx = with(density) { trackInset.toPx() }
        val trackWidth = max(1f, constraints.maxWidth - 2f * insetPx)
        val geometry = rememberUpdatedState(Offset(insetPx, trackWidth))
        // Within this of the thumb's middle a touch takes the thumb itself
        val thumbReach = with(density) { 24f.dp.toPx() }

        val gesture = remember { SliderGesture() }

        fun place(fraction: Float): Float {
            val n = stepsState.value()
            val stepped = if (n > 1) (fraction * (n - 1)).roundToInt() / (n - 1f) else fraction
            return stepped.fastCoerceIn(minValueState.value().fastCoerceIn(0f, 1f), 1f)
        }

        val animationScope = rememberCoroutineScope()
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                // Read without subscribing: a player's progress moves it many times a second
                initialValue = Snapshot.withoutReadObservation { value() }.fastCoerceIn(0f, 1f),
                valueRange = 0f..1f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                pressOnDown = false,
                onDragStarted = { position ->
                    val (inset, width) = geometry.value
                    gesture.moved = Offset.Zero
                    gesture.dragging = false
                    gesture.downFraction = (position.x - inset) / width
                    gesture.onThumb = abs(position.x - inset - progress * width) <= thumbReach
                },
                onDragStopped = {
                    if (gesture.dragging) {
                        gesture.dragging = false
                        onValueChangeState.value(gesture.shown, true)
                        onPressedChangeState.value(false)
                    } else if (!gesture.onThumb) {
                        // A tap on the track: the thumb goes there
                        val target = place(gesture.downFraction)
                        gesture.raw = target
                        gesture.shown = target
                        onPressedChangeState.value(true)
                        animateToValue(target)
                        onValueChangeState.value(target, true)
                        onPressedChangeState.value(false)
                    }
                },
                onDragCancelled = {
                    if (gesture.dragging) {
                        gesture.dragging = false
                        onValueChangeState.value(gesture.shown, true)
                        onPressedChangeState.value(false)
                    }
                },
                onDrag = drag@{ _, dragAmount ->
                    val (_, width) = geometry.value
                    gesture.moved += dragAmount
                    if (!gesture.dragging) {
                        val moved = gesture.moved
                        if (abs(moved.x) <= touchSlop || abs(moved.x) <= abs(moved.y)) {
                            return@drag
                        }
                        gesture.dragging = true
                        press()
                        onPressedChangeState.value(true)
                        // From the thumb it moves along; from elsewhere it comes under the finger
                        gesture.raw = (if (gesture.onThumb) targetValue else gesture.downFraction) + moved.x / width
                    } else {
                        gesture.raw += dragAmount.x / width
                    }
                    gesture.raw = gesture.raw.fastCoerceIn(minValueState.value().fastCoerceIn(0f, 1f), 1f)
                    val shown = place(gesture.raw)
                    if (shown != gesture.shown || targetValue != shown) {
                        gesture.shown = shown
                        updateValue(shown)
                        onValueChangeState.value(shown, false)
                    }
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { value() to syncTick() }
                .collectLatest { (newValue, _) ->
                    if (gesture.dragging) {
                        return@collectLatest
                    }
                    val target = newValue.fastCoerceIn(0f, 1f)
                    gesture.raw = target
                    gesture.shown = target
                    if (dampedDragAnimation.targetValue != target) {
                        if (animate()) {
                            dampedDragAnimation.updateValue(target)
                        } else {
                            dampedDragAnimation.snapToValue(target)
                        }
                    }
                }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .padding(horizontal = trackInset)
                    .layerBackdrop(trackBackdrop)
                    .clip(Capsule())
                    .drawBehind {
                        drawTrack(
                            progress = dampedDragAnimation.progress,
                            minValue = minValue().fastCoerceIn(0f, 1f),
                            twoSided = twoSided(),
                            buffered = buffered().fastCoerceIn(0f, 1f),
                            trackColor = trackColor,
                            accentColor = accentColor,
                            bufferedColor = bufferedColor
                        )
                    }
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .graphicsLayer {
                        translationX = insetPx +
                            (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                                .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f)
                    }
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(
                            backdrop,
                            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                val progress = dampedDragAnimation.pressProgress
                                val scaleX = lerp(2f / 3f, 1f, progress)
                                val scaleY = lerp(0f, 1f, progress)
                                scale(scaleX, scaleY) {
                                    drawBackdrop()
                                }
                            }
                        ),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            blur(8f.dp.toPx() * (1f - progress))
                            // glassGram: bent at its edges as strongly as the buttons' glass (the catalog's
                            // 10 and 14dp barely showed on the flat panels the sliders lie on)
                            lens(
                                12f.dp.toPx() * progress,
                                24f.dp.toPx() * progress,
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress
                            )
                        },
                        shadow = {
                            Shadow(
                                radius = 4f.dp,
                                color = Color.Black.copy(alpha = 0.05f)
                            )
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 4f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(Color.White.copy(alpha = 1f - progress))
                        }
                    )
                    .size(40f.dp, 24f.dp)
            )
        }

        // The whole slider takes the gesture, over the track and the thumb
        Box(
            Modifier
                .matchParentSize()
                .then(dampedDragAnimation.modifier)
        )
    }
}

/** Where a gesture on the slider is: where it went down, how far it went, and the value it led to. */
private class SliderGesture {
    var downFraction = 0f
    var onThumb = false
    var moved = Offset.Zero
    var dragging = false
    var raw = 0f
    var shown = -1f
}

/**
 * The track and its filled part, a capsule as the catalog's: from the start, from the middle
 * when [twoSided], with the part under [minValue] faded, over what [buffered] covers.
 */
private fun DrawScope.drawTrack(
    progress: Float,
    minValue: Float,
    twoSided: Boolean,
    buffered: Float,
    trackColor: Color,
    accentColor: Color,
    bufferedColor: Color
) {
    val radius = CornerRadius(size.height / 2f)
    drawRect(trackColor)
    if (buffered > 0f && bufferedColor.alpha > 0f) {
        drawRoundRect(bufferedColor, size = Size(size.width * buffered, size.height), cornerRadius = radius)
    }
    val x = size.width * progress.fastCoerceIn(0f, 1f)
    if (twoSided) {
        val middle = size.width / 2f
        val from = min(middle, x)
        val to = max(middle, x)
        drawRoundRect(accentColor, topLeft = Offset(from, 0f), size = Size(to - from, size.height), cornerRadius = radius)
        // The middle the fill grows from
        val tick = 1f.dp.toPx()
        drawRect(accentColor, topLeft = Offset(middle - tick, 0f), size = Size(tick * 2f, size.height))
    } else {
        if (minValue > 0f) {
            drawRoundRect(accentColor.copy(alpha = accentColor.alpha * 0.5f), size = Size(size.width * minValue, size.height), cornerRadius = radius)
        }
        drawRoundRect(accentColor, size = Size(x, size.height), cornerRadius = radius)
    }
}

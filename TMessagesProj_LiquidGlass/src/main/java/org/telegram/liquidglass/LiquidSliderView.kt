package org.telegram.liquidglass

import android.content.Context
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.backdrops.emptyBackdrop
import org.telegram.liquidglass.catalog.LiquidSlider

/**
 * The catalog's [LiquidSlider] as a View. Its track lies [setTrackInset] in from the view's
 * sides and across its middle; the view is meant to be larger than its owner by [OVERFLOW_DP]
 * on every side, so the swelling thumb is not cut off.
 */
class LiquidSliderView(context: Context) : AbstractComposeView(context) {

    interface Listener {
        /** The user moved the thumb to [value], a fraction of the track; [stop] when they let go. */
        fun onValueChange(value: Float, stop: Boolean)

        /** The user took the thumb, or let it go. */
        fun onPressedChange(pressed: Boolean)
    }

    private var listener: Listener? = null
    private var valueState by mutableFloatStateOf(0f)
    private var syncTickState by mutableIntStateOf(0)
    private var animateState = true
    private var stepsState by mutableIntStateOf(0)
    private var minValueState by mutableFloatStateOf(0f)
    private var twoSidedState by mutableStateOf(false)
    private var bufferedState by mutableFloatStateOf(0f)
    private var accentColorState by mutableIntStateOf(0xFF0088FF.toInt())
    private var trackColorState by mutableIntStateOf(0x33787878)
    private var bufferedColorState by mutableIntStateOf(0)
    private var trackInsetState by mutableIntStateOf(0)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        clipChildren = false
        clipToPadding = false
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Puts the thumb at [value], a fraction of the track; not while the user holds it. */
    fun setValue(value: Float, animated: Boolean) {
        animateState = animated && isAttachedToWindow
        valueState = value
        syncTickState++
    }

    /** The track's distance from the view's sides, in pixels. */
    fun setTrackInset(px: Int) {
        trackInsetState = px
    }

    /** More than one: the thumb goes from notch to notch, that many over the track. */
    fun setSteps(steps: Int) {
        stepsState = steps
    }

    fun setMinValue(value: Float) {
        minValueState = value
    }

    fun setTwoSided(twoSided: Boolean) {
        twoSidedState = twoSided
    }

    fun setBuffered(value: Float) {
        bufferedState = value
    }

    fun setColors(accent: Int, track: Int, buffered: Int) {
        accentColorState = accent
        trackColorState = track
        bufferedColorState = buffered
    }

    override fun onAttachedToWindow() {
        ComposeOwners.install(this)
        super.onAttachedToWindow()
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

    @Composable
    override fun Content() {
        val backdrop = remember { emptyBackdrop() }
        val density = LocalDensity.current
        // Telegram's sliders go left to right in every language
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LiquidSlider(
                value = { valueState },
                onValueChange = { value, stop -> listener?.onValueChange(value, stop) },
                onPressedChange = { pressed -> listener?.onPressedChange(pressed) },
                backdrop = backdrop,
                accentColor = Color(accentColorState),
                trackColor = Color(trackColorState),
                trackInset = with(density) { trackInsetState.toDp() },
                steps = { stepsState },
                minValue = { minValueState },
                twoSided = { twoSidedState },
                buffered = { bufferedState },
                bufferedColor = Color(bufferedColorState),
                syncTick = { syncTickState },
                animate = { animateState }
            )
        }
    }

    companion object {
        /** Room around the owner's bounds for the thumb, swollen 1.5x, and its shadow. */
        const val OVERFLOW_DP = 16

        /** The slider's glass needs RenderEffect. */
        @JvmStatic
        fun isSupported(): Boolean = LiquidToggleView.isSupported()

        /** The catalog's track: gray at 20% (light) or 36% (dark). */
        @JvmStatic
        fun trackColor(dark: Boolean): Int = if (dark) 0x5C787880 else 0x33787878
    }
}

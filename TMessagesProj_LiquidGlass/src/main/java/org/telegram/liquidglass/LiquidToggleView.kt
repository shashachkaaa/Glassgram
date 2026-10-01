package org.telegram.liquidglass

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.kyant.backdrop.backdrops.emptyBackdrop
import org.telegram.liquidglass.catalog.LiquidToggle

/**
 * The catalog's [LiquidToggle] as a View. The toggle is 64x28dp; the view is larger by
 * [OVERFLOW_DP] on every side so the swelling thumb is not cut off.
 */
class LiquidToggleView(context: Context) : AbstractComposeView(context) {

    fun interface Listener {
        /** The user asked for [checked]; call [setChecked] to accept it. */
        fun onToggle(checked: Boolean)
    }

    private var listener: Listener? = null
    private var checkedState by mutableStateOf(false)
    private var accentColorState by mutableIntStateOf(0xFF34C759.toInt())
    private var trackColorState by mutableIntStateOf(0x33787878)
    private var syncTickState by mutableIntStateOf(0)
    private var animateState = true

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        clipChildren = false
        clipToPadding = false
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setChecked(checked: Boolean, animated: Boolean) {
        animateState = animated && isAttachedToWindow
        checkedState = checked
    }

    fun isChecked(): Boolean = checkedState

    fun setColors(accent: Int, track: Int) {
        accentColorState = accent
        trackColorState = track
    }

    /** Moves the thumb back to [isChecked] after the owner turned a change down. */
    fun resync() {
        syncTickState++
    }

    override fun onAttachedToWindow() {
        ComposeOwners.install(this)
        super.onAttachedToWindow()
    }

    @Composable
    override fun Content() {
        val backdrop = remember { emptyBackdrop() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LiquidToggle(
                selected = { checkedState },
                onSelect = { listener?.onToggle(it) },
                backdrop = backdrop,
                accentColor = Color(accentColorState),
                trackColor = Color(trackColorState),
                syncTick = { syncTickState },
                animate = { animateState }
            )
        }
    }

    companion object {
        const val WIDTH_DP = 64
        const val HEIGHT_DP = 28
        const val OVERFLOW_DP = 14

        /** The toggle's glass needs RenderEffect. */
        @JvmStatic
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

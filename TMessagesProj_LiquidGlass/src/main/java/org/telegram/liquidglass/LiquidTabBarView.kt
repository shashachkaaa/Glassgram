package org.telegram.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import org.telegram.liquidglass.catalog.LiquidBottomTabs
import org.telegram.liquidglass.catalog.LocalLiquidBottomTabScale

/**
 * Telegram's bottom tab bar drawn by LiquidBottomTabs, as in shashachkaaa/ward: the glass
 * capsule, the drop that rides between tabs, swells under the finger, can be dragged and
 * refracts the tabs it passes over, all from the Kyant0/AndroidLiquidGlass catalog.
 *
 * The tabs themselves stay Telegram's views (animated icons, counters, avatar); they live,
 * laid out but not drawn, in their old container, and are painted here into each slot. The
 * content behind the bar comes from a [Source] in the coordinates of its source view.
 */
class LiquidTabBarView(context: Context) : AbstractComposeView(context) {

    interface Source {
        fun getSourceView(): View?

        /** Draws what is behind the bar, in [getSourceView] coordinates. */
        fun draw(canvas: Canvas)
    }

    interface Listener {
        fun onTabSelected(index: Int)

        fun onTabLongPress(index: Int)
    }

    private var source: Source? = null
    private var listener: Listener? = null

    private var tabsState by mutableStateOf<List<View>>(emptyList())
    private var selectedIndexState by mutableIntStateOf(0)
    private var accentColorState by mutableIntStateOf(0)
    private var containerColorState by mutableIntStateOf(0)
    private var lightState by mutableStateOf(true)
    private var insetPx by mutableFloatStateOf(0f)
    private var tabsTick by mutableIntStateOf(0)
    private var backdropTick by mutableIntStateOf(0)

    private val sourceLocation = IntArray(2)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    /**
     * The bar keeps the whole gesture once a finger is on it: it only claimed horizontal drags,
     * so moving the finger down let the screen below take the touch and the drop let go.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        // Also used in Telegram's dialogs, whose windows have no lifecycle
        ComposeOwners.install(this)
        super.onAttachedToWindow()
    }

    fun setSource(source: Source?) {
        this.source = source
        backdropTick++
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setTabs(tabs: List<View>) {
        if (tabs != tabsState) {
            tabsState = ArrayList(tabs)
        }
    }

    fun setSelectedIndex(index: Int) {
        selectedIndexState = index
    }

    fun setAccentColor(color: Int) {
        accentColorState = color
    }

    /** 0 keeps the catalog's own 40% surface. */
    fun setContainerColor(color: Int) {
        containerColorState = color
    }

    fun setLight(light: Boolean) {
        lightState = light
    }

    /** Space between this view's edges and the capsule, px. */
    fun setInset(px: Float) {
        insetPx = px
    }

    /** Repaints the tabs after one of the tab views changed. */
    fun invalidateTabs() {
        tabsTick++
    }

    /** Repaints the glass after the content behind it moved. */
    fun invalidateBackdrop() {
        backdropTick++
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
            val sourceView = source.getSourceView() ?: return
            val position = coordinates?.positionInWindow() ?: return
            sourceView.getLocationInWindow(sourceLocation)
            translate(sourceLocation[0] - position.x, sourceLocation[1] - position.y) {
                drawIntoCanvas { source.draw(it.nativeCanvas) }
            }
        }
    }

    @Composable
    override fun Content() {
        val tabs = tabsState
        if (tabs.isEmpty()) return
        val density = LocalDensity.current
        val inset = with(density) { insetPx.toDp() }

        BoxWithConstraints(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.Center) {
            val barHeight = maxHeight
            key(tabs.size) {
                val external = selectedIndexState.coerceIn(0, tabs.size - 1)
                var shown by remember { mutableIntStateOf(external) }
                LaunchedEffect(external) { shown = external }
                val shownState = rememberUpdatedState(shown)
                val selectedTabIndex = remember { { shownState.value } }
                val externalState = rememberUpdatedState(external)

                LiquidBottomTabs(
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { index ->
                        shown = index
                        // Called for outside changes too; only report the user's own choice
                        if (index != externalState.value) listener?.onTabSelected(index)
                    },
                    backdrop = backdrop,
                    tabsCount = tabs.size,
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = Color(accentColorState),
                    // As in ward: the bar spans the screen, and the catalog's 1000 makes the drop jump
                    stiffness = 320f,
                    blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    refracts = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    containerColor = if (containerColorState != 0) Color(containerColorState) else Color.Unspecified,
                    isLightTheme = lightState,
                    barHeight = barHeight
                ) {
                    tabs.forEachIndexed { index, view ->
                        ViewTab(
                            view = view,
                            onClick = { shown = index },
                            onLongClick = { listener?.onTabLongPress(index) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.ViewTab(view: View, onClick: () -> Unit, onLongClick: () -> Unit) {
        val scale = LocalLiquidBottomTabScale.current
        val onClickState = rememberUpdatedState(onClick)
        val onLongClickState = rememberUpdatedState(onLongClick)
        Box(
            Modifier
                .clip(Capsule())
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClickState.value() },
                        onLongPress = { onLongClickState.value() }
                    )
                }
                .fillMaxHeight()
                .weight(1f)
                .graphicsLayer {
                    val s = scale()
                    scaleX = s
                    scaleY = s
                }
                .drawBehind {
                    tabsTick
                    val w = view.width
                    val h = view.height
                    if (w > 0 && h > 0) {
                        drawIntoCanvas {
                            val canvas = it.nativeCanvas
                            canvas.save()
                            canvas.translate((size.width - w) / 2f, (size.height - h) / 2f)
                            view.draw(canvas)
                            canvas.restore()
                        }
                    }
                }
        )
    }

    companion object {
        /** The glass needs RenderEffect; below Android 12 the bar stays Telegram's own. */
        @JvmStatic
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

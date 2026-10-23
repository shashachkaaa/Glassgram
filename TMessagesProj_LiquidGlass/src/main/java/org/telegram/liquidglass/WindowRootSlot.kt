package org.telegram.liquidglass

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup

/**
 * Keeps [view], out of sight, in the root of another view's window, for a Compose view that
 * needs a window but is drawn by its owner.
 *
 * The root (the window's decor, a FrameLayout) lays out and detaches its children by index, and
 * the owner leaves the window in the middle of that (a screen's views removed while the window
 * lays out), so the view is taken out of the root on the main thread's next turn, not at once.
 */
internal class WindowRootSlot(private val view: View) {

    private val handler = Handler(Looper.getMainLooper())
    private val removal = Runnable { (view.parent as? ViewGroup)?.removeView(view) }

    /** Call outside of a layout or a drawing of the window (posted). */
    fun attach(owner: View) {
        handler.removeCallbacks(removal)
        val root = owner.rootView as? ViewGroup ?: return
        if (view.parent === root) {
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        root.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    fun detach() {
        handler.removeCallbacks(removal)
        handler.post(removal)
    }
}

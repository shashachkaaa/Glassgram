package org.telegram.liquidglass

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Lifecycle and saved state for a Compose host in a window that has none: Telegram's dialogs
 * and sheets are plain [android.app.Dialog]s, and Compose refuses to start without these.
 * Resumed while the host is attached.
 */
internal class ComposeOwners private constructor() : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    companion object {
        /**
         * Gives the window of [view] owners unless it already has them. They go on the root
         * view: Compose looks up the lifecycle for its window recomposer from there, so
         * owners set on the host alone crash it.
         */
        fun install(view: View) {
            val root = view.rootView
            if (root.findViewTreeLifecycleOwner() != null && root.findViewTreeSavedStateRegistryOwner() != null) {
                return
            }
            val owners = ComposeOwners()
            root.setViewTreeLifecycleOwner(owners)
            root.setViewTreeSavedStateRegistryOwner(owners)
        }
    }
}

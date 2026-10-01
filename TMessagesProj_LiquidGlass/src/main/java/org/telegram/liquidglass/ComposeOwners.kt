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
        /** Gives [view] its own owners unless the window already provides them. */
        fun install(view: View) {
            if (view.findViewTreeLifecycleOwner() != null && view.findViewTreeSavedStateRegistryOwner() != null) {
                return
            }
            val owners = ComposeOwners()
            view.setViewTreeLifecycleOwner(owners)
            view.setViewTreeSavedStateRegistryOwner(owners)
        }
    }
}

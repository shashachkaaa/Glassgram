/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

 Modified for glassGram, 2026: counts how long each hook holds the main thread, for glassGram's
 recording of janky frames (mainThreadStats).

*/

package com.exteragram.messenger.plugins.xposed

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.exteragram.messenger.plugins.PluginsConstants
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.hooks.HookFilter
import de.robv.android.xposed.XC_MethodHook
import android.os.Looper
import org.telegram.messenger.FileLog
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

class PyMethodHook : XC_MethodHook, AutoCloseable {
    private val pluginId: String
    private val pythonCallback: PyObject
    private val beforeHook: PyObject?
    private val afterHook: PyObject?
    private var beforeHookedFilters = ArrayList<HookFilter>()
    private var afterHookedFilters = ArrayList<HookFilter>()
    @Volatile
    private var disabled = false
    @Volatile
    private var closed = false

    // glassGram: time on the main thread during a diagnostic recording
    private var mainCalls = 0L
    private var mainNanos = 0L
    private var mainMaxNanos = 0L
    private var methodName: String? = null

    init {
        synchronized(liveHooks) {
            liveHooks.add(this)
        }
    }

    constructor(pluginId: String, pythonCallback: PyObject?) : this(
        pluginId,
        pythonCallback,
        true,
        true
    )

    constructor(pluginId: String, pythonCallback: PyObject?, priority: Int) : this(
        pluginId,
        pythonCallback,
        priority,
        true,
        true
    )

    constructor(
        pluginId: String,
        pythonCallback: PyObject?,
        hasBeforeHook: Boolean,
        hasAfterHook: Boolean
    ) : super() {
        requireNotNull(pythonCallback) { "Python callback object cannot be null" }
        this.pluginId = pluginId
        this.pythonCallback = pythonCallback
        beforeHook = getCallbackIfPresent(
            pythonCallback,
            PluginsConstants.Xposed.BEFORE_HOOKED_METHOD,
            hasBeforeHook
        )
        afterHook = getCallbackIfPresent(
            pythonCallback,
            PluginsConstants.Xposed.AFTER_HOOKED_METHOD,
            hasAfterHook
        )
    }

    constructor(
        pluginId: String,
        pythonCallback: PyObject?,
        priority: Int,
        hasBeforeHook: Boolean,
        hasAfterHook: Boolean
    ) : super(priority) {
        requireNotNull(pythonCallback) { "Python callback object cannot be null" }
        this.pluginId = pluginId
        this.pythonCallback = pythonCallback
        beforeHook = getCallbackIfPresent(
            pythonCallback,
            PluginsConstants.Xposed.BEFORE_HOOKED_METHOD,
            hasBeforeHook
        )
        afterHook = getCallbackIfPresent(
            pythonCallback,
            PluginsConstants.Xposed.AFTER_HOOKED_METHOD,
            hasAfterHook
        )
    }

    fun setBeforeHookedFilters(beforeHookedFilters: ArrayList<HookFilter>) {
        this.beforeHookedFilters = beforeHookedFilters
    }

    fun setAfterHookedFilters(afterHookedFilters: ArrayList<HookFilter>) {
        this.afterHookedFilters = afterHookedFilters
    }

    fun getBeforeHookedFilters(): ArrayList<HookFilter> = beforeHookedFilters

    fun getAfterHookedFilters(): ArrayList<HookFilter> = afterHookedFilters

    override fun beforeHookedMethod(param: MethodHookParam) {
        if (disabled) {
            return
        }

        val callback = beforeHook ?: return
        if (!PluginsController.getInstance().isPluginActive(pluginId)) {
            return
        }
        try {
            if (!executeFilters(beforeHookedFilters, param, true)) {
                return
            }
            timed(param) { callback.call(param) }
        } catch (t: Throwable) {
            handleHookError("beforeHookedMethod", t)
        }
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        if (disabled) {
            return
        }

        val callback = afterHook ?: return
        if (!PluginsController.getInstance().isPluginActive(pluginId)) {
            return
        }
        try {
            if (!executeFilters(afterHookedFilters, param, false)) {
                return
            }
            timed(param) { callback.call(param) }
        } catch (t: Throwable) {
            handleHookError("afterHookedMethod", t)
        }
    }

    private inline fun timed(param: MethodHookParam, call: () -> Unit) {
        if (!recordingMainThreadStats || Looper.myLooper() !== Looper.getMainLooper()) {
            call()
            return
        }
        val start = System.nanoTime()
        try {
            call()
        } finally {
            val spent = System.nanoTime() - start
            synchronized(this) {
                mainCalls++
                mainNanos += spent
                if (spent > mainMaxNanos) mainMaxNanos = spent
                if (methodName == null) methodName = param.method?.let { it.declaringClass.simpleName + "." + it.name }
            }
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        disabled = true
        beforeHook?.close()
        afterHook?.close()
    }

    private fun executeFilters(
        filters: ArrayList<HookFilter>,
        param: MethodHookParam,
        isBefore: Boolean
    ): Boolean {
        for (filter in filters) {
            if (!filter.execute(param, isBefore)) {
                return false
            }
        }
        return true
    }

    private fun getCallbackIfPresent(
        callbackObject: PyObject,
        name: String,
        enabled: Boolean
    ): PyObject? {
        return if (enabled && callbackObject.containsKey(name)) {
            callbackObject[name]
        } else {
            null
        }
    }

    private fun handleHookError(hookMethodName: String, t: Throwable) {
        if (t is PyException && t.message != null && t.message!!.contains("closed")) {
            disabled = true
            FileLog.e("Attempted to call a closed PyObject callback in $pluginId")
            return
        }
        FileLog.e("Plugin '$pluginId' crashed in $hookMethodName: ${t.message}", t)
    }

    companion object {
        @Volatile
        private var recordingMainThreadStats = false

        private val liveHooks: MutableSet<PyMethodHook> = Collections.newSetFromMap(WeakHashMap())

        private fun hooks(): List<PyMethodHook> = synchronized(liveHooks) { ArrayList(liveHooks) }

        /** Starts a diagnostic recording of main-thread hooks. */
        @JvmStatic
        fun startMainThreadStats() {
            for (hook in hooks()) {
                synchronized(hook) {
                    hook.mainCalls = 0
                    hook.mainNanos = 0
                    hook.mainMaxNanos = 0
                }
            }
            recordingMainThreadStats = true
        }

        /** Stops recording and returns the collected hook timings, longest first. */
        @JvmStatic
        fun finishMainThreadStats(): List<String> {
            recordingMainThreadStats = false
            val rows = ArrayList<Pair<Long, String>>()
            for (hook in hooks()) {
                synchronized(hook) {
                    if (hook.mainCalls > 0) {
                        rows.add(hook.mainNanos to String.format(
                            Locale.US, "%s · %s: %d calls, %d ms, max %d ms",
                            hook.pluginId, hook.methodName ?: "?", hook.mainCalls,
                            hook.mainNanos / 1_000_000, hook.mainMaxNanos / 1_000_000
                        ))
                    }
                }
            }
            return rows.sortedByDescending { it.first }.map { it.second }
        }
    }
}

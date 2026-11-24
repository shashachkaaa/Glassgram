package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.plugins.PluginXposedHook;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * glassGram: exteraGram's Xposed hook calling a Python object's before_hooked_method and
 * after_hooked_method, which plugins made for exteraGram build themselves (zwylib's HookUtils).
 * It runs only while its plugin does; Python numbers set as arguments or result are made the
 * hooked method's own types, as in glassGram's own hooks ({@link PluginXposedHook}).
 */
public class PyMethodHook extends XC_MethodHook implements AutoCloseable {

    private final String pluginId;
    private final PyObject pythonCallback;
    private final PyObject beforeHook;
    private final PyObject afterHook;
    private List<?> beforeHookedFilters = new ArrayList<>();
    private List<?> afterHookedFilters = new ArrayList<>();

    private volatile boolean disabled;
    private volatile boolean closed;

    // How long it held the main thread, for the report of stutters, as PluginXposedHook counts
    private static final Thread MAIN = android.os.Looper.getMainLooper().getThread();
    private static final java.util.ArrayList<java.lang.ref.WeakReference<PyMethodHook>> hooks = new java.util.ArrayList<>();
    private volatile String methodName;
    private long mainCalls, mainNanos, mainMaxNanos;

    private void call(PyObject callback, MethodHookParam param) {
        if (Thread.currentThread() != MAIN) {
            callback.call(param);
            return;
        }
        final long start = System.nanoTime();
        try {
            callback.call(param);
        } finally {
            final long spent = System.nanoTime() - start;
            if (methodName == null && param.method != null) {
                methodName = param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName();
            }
            synchronized (this) {
                mainCalls++;
                mainNanos += spent;
                if (spent > mainMaxNanos) {
                    mainMaxNanos = spent;
                }
            }
        }
    }

    private static java.util.List<PyMethodHook> liveHooks() {
        final java.util.ArrayList<PyMethodHook> live = new java.util.ArrayList<>();
        synchronized (hooks) {
            for (int i = hooks.size() - 1; i >= 0; i--) {
                final PyMethodHook hook = hooks.get(i).get();
                if (hook == null) {
                    hooks.remove(i);
                } else {
                    live.add(hook);
                }
            }
        }
        return live;
    }

    public static void resetMainThreadStats() {
        for (PyMethodHook hook : liveHooks()) {
            synchronized (hook) {
                hook.mainCalls = hook.mainNanos = hook.mainMaxNanos = 0;
            }
        }
    }

    /** What these hooks held the main thread for since the counts started, the longest first. */
    public static java.util.List<String> mainThreadStats() {
        final java.util.ArrayList<Object[]> rows = new java.util.ArrayList<>();
        for (PyMethodHook hook : liveHooks()) {
            synchronized (hook) {
                if (hook.mainCalls > 0) {
                    rows.add(new Object[]{hook.mainNanos, hook.mainCalls, hook.mainMaxNanos, hook.pluginId + " · " + hook.methodName + " (exteraGram hook)"});
                }
            }
        }
        java.util.Collections.sort(rows, (a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        final java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            out.add(String.format(java.util.Locale.US, "%s: %d calls, %d ms, max %d ms", row[3], (Long) row[1], (Long) row[0] / 1000000, (Long) row[2] / 1000000));
        }
        return out;
    }

    public PyMethodHook(String pluginId, PyObject pythonCallback) {
        this(pluginId, pythonCallback, PRIORITY_DEFAULT, true, true);
    }

    public PyMethodHook(String pluginId, PyObject pythonCallback, int priority) {
        this(pluginId, pythonCallback, priority, true, true);
    }

    public PyMethodHook(String pluginId, PyObject pythonCallback, boolean hasBeforeHook, boolean hasAfterHook) {
        this(pluginId, pythonCallback, PRIORITY_DEFAULT, hasBeforeHook, hasAfterHook);
    }

    public PyMethodHook(String pluginId, PyObject pythonCallback, int priority, boolean hasBeforeHook, boolean hasAfterHook) {
        super(priority);
        if (pythonCallback == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.pluginId = pluginId;
        this.pythonCallback = pythonCallback;
        this.beforeHook = callbackOf(pythonCallback, PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, hasBeforeHook);
        this.afterHook = callbackOf(pythonCallback, PluginsConstants.Xposed.AFTER_HOOKED_METHOD, hasAfterHook);
        synchronized (hooks) {
            hooks.add(new java.lang.ref.WeakReference<>(this));
        }
    }

    /** Filters (base_plugin's HookFilter) the call must pass for before_hooked_method to run. */
    public void setBeforeHookedFilters(ArrayList<?> filters) {
        beforeHookedFilters = filters == null ? new ArrayList<>() : filters;
    }

    public void setAfterHookedFilters(ArrayList<?> filters) {
        afterHookedFilters = filters == null ? new ArrayList<>() : filters;
    }

    public List<?> getBeforeHookedFilters() {
        return beforeHookedFilters;
    }

    public List<?> getAfterHookedFilters() {
        return afterHookedFilters;
    }

    public String getPluginId() {
        return pluginId;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (disabled || beforeHook == null || !PluginsController.getInstance().isPluginActive(pluginId)) {
            return;
        }
        try {
            if (!passes(beforeHookedFilters, param)) {
                return;
            }
            call(beforeHook, param);
            PluginXposedHook.fixArgs(param);
            final Object result = param.getResult();
            if (result != null) {
                param.setResult(PluginXposedHook.coerce(PluginXposedHook.returnType(param.method), result));
            }
        } catch (Throwable t) {
            onError("before_hooked_method", t);
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        if (disabled || afterHook == null || !PluginsController.getInstance().isPluginActive(pluginId)) {
            return;
        }
        try {
            if (!passes(afterHookedFilters, param)) {
                return;
            }
            call(afterHook, param);
            final Object result = param.getResult();
            if (result != null && !param.hasThrowable()) {
                final Object coerced = PluginXposedHook.coerce(PluginXposedHook.returnType(param.method), result);
                if (coerced != result) {
                    param.setResult(coerced);
                }
            }
        } catch (Throwable t) {
            onError("after_hooked_method", t);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        disabled = true;
    }

    static PyObject callbackOf(PyObject callback, String name, boolean wanted) {
        return wanted && callback.containsKey(name) ? callback.get(name) : null;
    }

    /** Whether the call passes every filter (base_plugin decides what each one means). */
    static boolean passes(List<?> filters, MethodHookParam param) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        final PyObject basePlugin = Python.getInstance().getModule("base_plugin");
        for (Object filter : filters) {
            if (!basePlugin.callAttr("_filter_matches", filter, param).toBoolean()) {
                return false;
            }
        }
        return true;
    }

    private void onError(String where, Throwable t) {
        if (t instanceof PyException && t.getMessage() != null && t.getMessage().contains("closed")) {
            disabled = true;
            return;
        }
        FileLog.e("Plugin '" + pluginId + "' crashed in " + where + ": " + t.getMessage(), t);
        org.telegram.messenger.plugins.PluginsController.log(pluginId, where + " failed: " + t);
    }
}

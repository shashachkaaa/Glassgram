package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.plugins.PluginXposedHook;

import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/**
 * glassGram: exteraGram's Xposed hook putting a Python object's replace_hooked_method in place of
 * the hooked method, which plugins made for exteraGram build themselves. The original method runs
 * while the plugin doesn't, and when the replacement fails: a method returning a number must not
 * get null.
 */
public class PyMethodReplacement extends XC_MethodReplacement implements AutoCloseable {

    private final String pluginId;
    private final PyObject pythonCallback;
    private final PyObject replaceHook;

    private volatile boolean disabled;
    private volatile boolean closed;

    public PyMethodReplacement(String pluginId, PyObject pythonCallback) {
        this(pluginId, pythonCallback, PRIORITY_DEFAULT);
    }

    public PyMethodReplacement(String pluginId, PyObject pythonCallback, int priority) {
        super(priority);
        if (pythonCallback == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.pluginId = pluginId;
        this.pythonCallback = pythonCallback;
        this.replaceHook = PyMethodHook.callbackOf(pythonCallback, PluginsConstants.Xposed.REPLACE_HOOKED_METHOD, true);
        if (replaceHook == null) {
            throw new IllegalArgumentException("Python callback object must have a method named " + PluginsConstants.Xposed.REPLACE_HOOKED_METHOD);
        }
    }

    public String getPluginId() {
        return pluginId;
    }

    @Override
    public Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        if (disabled || !PluginsController.getInstance().isPluginActive(pluginId)) {
            return original(param);
        }
        final PyObject result;
        try {
            result = replaceHook.call(param);
        } catch (Throwable t) {
            onError(t);
            return original(param);
        }
        final Object value = result == null ? null : result.toJava(Object.class);
        return PluginXposedHook.coerce(PluginXposedHook.returnType(param.method), value);
    }

    private static Object original(MethodHookParam param) throws Throwable {
        try {
            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
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

    private void onError(Throwable t) {
        if (t instanceof PyException && t.getMessage() != null && t.getMessage().contains("closed")) {
            disabled = true;
            return;
        }
        FileLog.e("Plugin '" + pluginId + "' crashed in replace_hooked_method: " + t.getMessage(), t);
        org.telegram.messenger.plugins.PluginsController.log(pluginId, "replace_hooked_method failed: " + t);
    }
}

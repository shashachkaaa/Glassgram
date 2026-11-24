package org.telegram.messenger.plugins;

import android.os.Looper;

import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * An Xposed method hook that calls a Python handler (base_plugin._XposedBridgeHandler).
 *
 * Python numbers reach Java as Long or Double whatever the Java type, so arguments and results
 * set from Python are converted back to the hooked method's own types here.
 */
public class PluginXposedHook extends XC_MethodHook {

    private final PyObject handler;
    private final boolean replacement;
    // Whether the plugin does anything before and after the call: Python isn't called for a part it
    // left out, as a hooked method can run thousands of times a second (getUser while the chats
    // are sorted) and each call into Python is slow on a weak phone
    private final boolean before;
    private final boolean after;

    // The hooked method's parameter types, kept: getParameterTypes() copies its array on every call
    private Member typesOf;
    private Class<?>[] types;

    /*
     * How long the plugin held the main thread, for the report of stutters (GlassgramJankRecorder):
     * a hook on a method the screens call for every message or avatar runs Python hundreds of
     * times a second there. Python counts the time in the plugin's own code (inside_ns), the rest
     * of it went to waiting for Python, while another thread had it.
     */
    private static final Thread MAIN = Looper.getMainLooper().getThread();
    private static final ArrayList<WeakReference<PluginXposedHook>> hooks = new ArrayList<>();
    private final String pluginId;
    private volatile String methodName;
    private long mainCalls, mainNanos, mainMaxNanos;

    public PluginXposedHook(PyObject handler, int priority, boolean replacement) {
        this(handler, priority, replacement, true, !replacement);
    }

    public PluginXposedHook(PyObject handler, int priority, boolean replacement, boolean before, boolean after) {
        this(handler, priority, replacement, before, after, null);
    }

    public PluginXposedHook(PyObject handler, int priority, boolean replacement, boolean before, boolean after, String pluginId) {
        super(priority);
        this.handler = handler;
        this.replacement = replacement;
        this.before = before || replacement;
        this.after = after && !replacement;
        this.pluginId = pluginId;
        synchronized (hooks) {
            for (int i = hooks.size() - 1; i >= 0; i--) {
                if (hooks.get(i).get() == null) {
                    hooks.remove(i);
                }
            }
            hooks.add(new WeakReference<>(this));
        }
    }

    private PyObject call(String phase, MethodHookParam param) {
        if (Thread.currentThread() != MAIN) {
            return handler.callAttr(phase, param);
        }
        final long start = System.nanoTime();
        try {
            return handler.callAttr(phase, param);
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

    /** Starts the hooks' counts again. */
    public static void resetMainThreadStats() {
        for (PluginXposedHook hook : liveHooks()) {
            synchronized (hook) {
                hook.mainCalls = hook.mainNanos = hook.mainMaxNanos = 0;
            }
            try {
                hook.handler.callAttr("__setattr__", "inside_ns", 0);
            } catch (Throwable ignore) {
            }
        }
    }

    /** What the plugins' hooks held the main thread for since the counts started, the longest first. */
    public static List<String> mainThreadStats() {
        final ArrayList<long[]> rows = new ArrayList<>();
        final ArrayList<String> names = new ArrayList<>();
        for (PluginXposedHook hook : liveHooks()) {
            final long calls, nanos, max;
            synchronized (hook) {
                calls = hook.mainCalls;
                nanos = hook.mainNanos;
                max = hook.mainMaxNanos;
            }
            if (calls == 0) {
                continue;
            }
            long inside = -1;
            try {
                final PyObject value = hook.handler.callAttr("__getattribute__", "inside_ns");
                inside = value == null ? -1 : value.toLong();
            } catch (Throwable ignore) {
            }
            rows.add(new long[]{nanos, calls, max, inside, names.size()});
            names.add((hook.pluginId != null ? hook.pluginId : "?") + " · " + (hook.methodName != null ? hook.methodName : "?"));
        }
        java.util.Collections.sort(rows, (a, b) -> Long.compare(b[0], a[0]));
        final ArrayList<String> out = new ArrayList<>();
        for (long[] row : rows) {
            out.add(String.format(java.util.Locale.US, "%s: %d calls, %d ms%s, max %d ms",
                    names.get((int) row[4]), row[1], row[0] / 1000000,
                    row[3] >= 0 ? " (in the plugin's code " + row[3] / 1000000 + " ms)" : "", row[2] / 1000000));
        }
        return out;
    }

    private static List<PluginXposedHook> liveHooks() {
        final ArrayList<PluginXposedHook> live = new ArrayList<>();
        synchronized (hooks) {
            for (int i = 0; i < hooks.size(); i++) {
                final PluginXposedHook hook = hooks.get(i).get();
                if (hook != null) {
                    live.add(hook);
                }
            }
        }
        return live;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        if (!before) {
            return;
        }
        if (replacement) {
            Object result;
            try {
                PyObject value = call("replace", param);
                result = value == null ? null : value.toJava(Object.class);
            } catch (Throwable e) {
                // The original method runs when the replacement fails
                FileLog.e(e);
                return;
            }
            param.setResult(coerce(returnType(param.method), result));
            return;
        }
        call("before", param);
        fixArgs(param, parameterTypes(param.method));
        Object result = param.getResult();
        if (result != null) {
            param.setResult(coerce(returnType(param.method), result));
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        if (!after) {
            return;
        }
        call("after", param);
        Object result = param.getResult();
        if (result != null && !param.hasThrowable()) {
            Object coerced = coerce(returnType(param.method), result);
            if (coerced != result) {
                param.setResult(coerced);
            }
        }
    }

    /** The hooked method's return type, or null for a constructor. */
    public static Class<?> returnType(Member member) {
        return member instanceof Method ? ((Method) member).getReturnType() : null;
    }

    private Class<?>[] parameterTypes(Member member) {
        // one hook can be on several methods (hook_all_methods), and on several threads
        synchronized (this) {
            if (member != typesOf) {
                types = typesOf(member);
                typesOf = member;
            }
            return types;
        }
    }

    private static Class<?>[] typesOf(Member member) {
        if (member instanceof Method) {
            return ((Method) member).getParameterTypes();
        } else if (member instanceof Constructor) {
            return ((Constructor<?>) member).getParameterTypes();
        }
        return null;
    }

    /** The call's arguments set from Python, as the hooked method's own types. */
    public static void fixArgs(MethodHookParam param) {
        fixArgs(param, typesOf(param.method));
    }

    private static void fixArgs(MethodHookParam param, Class<?>[] types) {
        if (param.args == null || types == null) {
            return;
        }
        for (int i = 0; i < param.args.length && i < types.length; i++) {
            param.args[i] = coerce(types[i], param.args[i]);
        }
    }

    public static Object coerce(Class<?> type, Object value) {
        if (type == null || value == null || type == void.class || type == Void.class) {
            return value;
        }
        if (value instanceof Number && !type.isInstance(value)) {
            Number number = (Number) value;
            if (type == int.class || type == Integer.class) {
                return number.intValue();
            } else if (type == long.class || type == Long.class) {
                return number.longValue();
            } else if (type == short.class || type == Short.class) {
                return number.shortValue();
            } else if (type == byte.class || type == Byte.class) {
                return number.byteValue();
            } else if (type == float.class || type == Float.class) {
                return number.floatValue();
            } else if (type == double.class || type == Double.class) {
                return number.doubleValue();
            } else if (type == char.class || type == Character.class) {
                return (char) number.intValue();
            }
        }
        if (value instanceof String && (type == char.class || type == Character.class) && ((String) value).length() == 1) {
            return ((String) value).charAt(0);
        }
        return value;
    }
}

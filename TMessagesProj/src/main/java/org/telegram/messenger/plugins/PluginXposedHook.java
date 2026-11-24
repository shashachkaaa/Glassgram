package org.telegram.messenger.plugins;

import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

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

    public PluginXposedHook(PyObject handler, int priority, boolean replacement) {
        this(handler, priority, replacement, true, !replacement);
    }

    public PluginXposedHook(PyObject handler, int priority, boolean replacement, boolean before, boolean after) {
        super(priority);
        this.handler = handler;
        this.replacement = replacement;
        this.before = before || replacement;
        this.after = after && !replacement;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        if (!before) {
            return;
        }
        if (replacement) {
            Object result;
            try {
                PyObject value = handler.callAttr("replace", param);
                result = value == null ? null : value.toJava(Object.class);
            } catch (Throwable e) {
                // The original method runs when the replacement fails
                FileLog.e(e);
                return;
            }
            param.setResult(coerce(returnType(param.method), result));
            return;
        }
        handler.callAttr("before", param);
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
        handler.callAttr("after", param);
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

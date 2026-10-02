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

    public PluginXposedHook(PyObject handler, int priority, boolean replacement) {
        super(priority);
        this.handler = handler;
        this.replacement = replacement;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
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
        fixArgs(param);
        Object result = param.getResult();
        if (result != null) {
            param.setResult(coerce(returnType(param.method), result));
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        if (replacement) {
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

    private static Class<?> returnType(Member member) {
        return member instanceof Method ? ((Method) member).getReturnType() : null;
    }

    private static void fixArgs(MethodHookParam param) {
        if (param.args == null) {
            return;
        }
        Class<?>[] types;
        if (param.method instanceof Method) {
            types = ((Method) param.method).getParameterTypes();
        } else if (param.method instanceof Constructor) {
            types = ((Constructor<?>) param.method).getParameterTypes();
        } else {
            return;
        }
        for (int i = 0; i < param.args.length && i < types.length; i++) {
            param.args[i] = coerce(types[i], param.args[i]);
        }
    }

    static Object coerce(Class<?> type, Object value) {
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

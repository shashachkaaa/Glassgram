package org.telegram.messenger.plugins;

import android.os.Build;

import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;
import com.chaquo.python.PyObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java side of the plugin SDK's class proxies (extera_utils.classes): real Java subclasses made
 * at runtime with DexMaker, whose methods are written in Python.
 * <p>
 * A generated class keeps its Python peer in a field. Its overridden and added methods pack their
 * arguments and call {@link #dispatch}, which calls the Python runtime's {@code invoke}; each
 * overridden method also gets a {@code __super_N} twin that calls the parent's implementation. Its
 * constructors let Python change the arguments before the parent's constructor
 * ({@link #preConstruct}) and make the peer after it ({@link #construct}).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class GlassgramClassProxy {

    public static final String PEER_FIELD = "__glassgram_peer";

    private GlassgramClassProxy() {
    }

    /** What Python asked for: a method to generate (an override, or a new one), a field or a constructor. */
    public static final class Spec {
        public final ArrayList<MethodSpec> methods = new ArrayList<>();
        public final ArrayList<FieldSpec> fields = new ArrayList<>();
        // parameter types of the constructors to make; empty: one per accessible parent constructor
        public final ArrayList<Class<?>[]> constructors = new ArrayList<>();

        public void addOverride(String pyName, String javaName, Class<?>[] params) {
            final MethodSpec spec = new MethodSpec();
            spec.pyName = pyName;
            spec.javaName = javaName;
            spec.params = params;
            spec.override = true;
            methods.add(spec);
        }

        public void addMethod(String pyName, String javaName, Class<?> returnType, Class<?>[] params) {
            final MethodSpec spec = new MethodSpec();
            spec.pyName = pyName;
            spec.javaName = javaName;
            spec.returnType = returnType;
            spec.params = params;
            methods.add(spec);
        }

        public void addField(String name, Class<?> type, String getter, String setter) {
            final FieldSpec spec = new FieldSpec();
            spec.name = name;
            spec.type = type;
            spec.getter = getter;
            spec.setter = setter;
            fields.add(spec);
        }

        public void addConstructor(Class<?>[] params) {
            constructors.add(params);
        }
    }

    static final class MethodSpec {
        String pyName;
        String javaName;
        Class<?> returnType;
        // null: every overridable method of that name
        Class<?>[] params;
        boolean override;
    }

    static final class FieldSpec {
        String name;
        Class<?> type;
        String getter, setter;
    }

    /** A generated method: the Python method it runs, and its Java signature. */
    static final class Generated {
        String pyName;
        String javaName;
        Class<?> returnType;
        Class<?>[] params;
        // the parent method, for overrides
        Method parent;
        String superName;
    }

    /** A generated class: its Python runtime and what it was made of. */
    static final class Runtime {
        PyObject handler;
        Class<?> generated;
        Field peerField;
        final ArrayList<Generated> methods = new ArrayList<>();
        final ArrayList<Class<?>[]> constructors = new ArrayList<>();
    }

    private static final ConcurrentHashMap<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Runtime> runtimesByClass = new ConcurrentHashMap<>();
    private static int counter;

    /** Makes and loads the class, a subclass of base with these interfaces; handler is the Python runtime. */
    public static synchronized Class<?> define(String name, Class<?> base, Class<?>[] interfaces, Spec spec, PyObject handler) throws Exception {
        if (base == null) {
            base = Object.class;
        }
        if (Modifier.isFinal(base.getModifiers())) {
            throw new IllegalArgumentException(base.getName() + " is final and cannot be subclassed");
        }
        if (interfaces == null) {
            interfaces = new Class<?>[0];
        }
        if (name == null || name.isEmpty()) {
            name = "glassgram.proxy." + base.getSimpleName() + "_" + (++counter);
        } else if (runtimes.containsKey(name)) {
            name = name + "_" + (++counter);
        }

        final Runtime runtime = new Runtime();
        runtime.handler = handler;

        final DexMaker dexMaker = new DexMaker();
        final TypeId generated = TypeId.get("L" + name.replace('.', '/') + ";");
        final TypeId superType = TypeId.get(base);
        final TypeId[] interfaceTypes = new TypeId[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaceTypes[i] = TypeId.get(interfaces[i]);
        }
        dexMaker.declare(generated, name + ".generated", Modifier.PUBLIC, superType, interfaceTypes);

        final FieldId peerField = generated.getField(TypeId.OBJECT, PEER_FIELD);
        dexMaker.declare(peerField, Modifier.PUBLIC, null);

        for (FieldSpec field : spec.fields) {
            final TypeId type = TypeId.get(field.type);
            final FieldId fieldId = generated.getField(type, field.name);
            dexMaker.declare(fieldId, Modifier.PUBLIC, null);
            if (field.getter != null) {
                final Code code = dexMaker.declare(generated.getMethod(type, field.getter), Modifier.PUBLIC);
                final Local value = code.newLocal(type);
                code.iget(fieldId, value, code.getThis(generated));
                code.returnValue(value);
            }
            if (field.setter != null) {
                final Code code = dexMaker.declare(generated.getMethod(TypeId.VOID, field.setter, type), Modifier.PUBLIC);
                code.iput(fieldId, code.getThis(generated), code.getParameter(0, type));
                code.returnVoid();
            }
        }

        // The methods, each signature once
        final LinkedHashMap<String, Generated> methods = new LinkedHashMap<>();
        for (MethodSpec spec1 : spec.methods) {
            if (spec1.override) {
                final ArrayList<Method> found = overridable(base, interfaces, spec1.javaName, spec1.params);
                if (found.isEmpty()) {
                    throw new NoSuchMethodException("No overridable method " + spec1.javaName
                        + (spec1.params != null ? Arrays.toString(spec1.params) : "") + " in " + base.getName());
                }
                for (Method method : found) {
                    final Generated g = new Generated();
                    g.pyName = spec1.pyName;
                    g.returnType = method.getReturnType();
                    g.params = method.getParameterTypes();
                    g.javaName = method.getName();
                    // nothing to call for abstract and interface methods
                    g.parent = Modifier.isAbstract(method.getModifiers()) || method.getDeclaringClass().isInterface() ? null : method;
                    methods.put(g.javaName + Arrays.toString(g.params), g);
                }
            } else {
                final Generated g = new Generated();
                g.pyName = spec1.pyName;
                g.returnType = spec1.returnType == null ? void.class : spec1.returnType;
                g.params = spec1.params == null ? new Class<?>[0] : spec1.params;
                g.javaName = spec1.javaName;
                methods.put(g.javaName + Arrays.toString(g.params), g);
            }
        }
        int index = 0;
        for (Generated g : methods.values()) {
            if (g.parent != null) {
                g.superName = "__super_" + index;
                declareSuper(dexMaker, generated, superType, g, g.javaName);
            }
            declareDispatch(dexMaker, generated, name, index, g, g.javaName);
            runtime.methods.add(g);
            index++;
        }

        // The constructors
        final ArrayList<Class<?>[]> constructors = new ArrayList<>();
        if (spec.constructors.isEmpty()) {
            for (Constructor<?> constructor : base.getDeclaredConstructors()) {
                final int modifiers = constructor.getModifiers();
                if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                    constructors.add(constructor.getParameterTypes());
                }
            }
        } else {
            constructors.addAll(spec.constructors);
        }
        for (int i = 0; i < constructors.size(); i++) {
            declareConstructor(dexMaker, generated, superType, name, i, constructors.get(i));
            runtime.constructors.add(constructors.get(i));
        }

        final ClassLoader parent = GlassgramClassProxy.class.getClassLoader();
        final ClassLoader loader;
        if (Build.VERSION.SDK_INT >= 26) {
            loader = new dalvik.system.InMemoryDexClassLoader(ByteBuffer.wrap(dexMaker.generate()), parent);
        } else {
            final File dir = new File(ApplicationLoader.applicationContext.getCodeCacheDir(), "glassgram_proxies");
            dir.mkdirs();
            loader = dexMaker.generateAndLoad(parent, dir);
        }
        final Class<?> result = loader.loadClass(name);
        runtime.generated = result;
        runtime.peerField = result.getField(PEER_FIELD);
        runtimes.put(name, runtime);
        runtimesByClass.put(result, runtime);
        return result;
    }

    /** The methods of that name (and parameter types, when given) a subclass can override. */
    private static ArrayList<Method> overridable(Class<?> base, Class<?>[] interfaces, String name, Class<?>[] params) {
        final LinkedHashMap<String, Method> found = new LinkedHashMap<>();
        final ArrayList<Class<?>> types = new ArrayList<>();
        for (Class<?> type = base; type != null; type = type.getSuperclass()) {
            types.add(type);
        }
        types.addAll(Arrays.asList(interfaces));
        for (int i = 0; i < types.size(); i++) {
            for (Class<?> extra : types.get(i).getInterfaces()) {
                if (!types.contains(extra)) {
                    types.add(extra);
                }
            }
        }
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                final int modifiers = method.getModifiers();
                if (!method.getName().equals(name) || method.isBridge() || method.isSynthetic()
                        || Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)
                        || !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
                    continue;
                }
                if (params != null && !Arrays.equals(params, method.getParameterTypes())) {
                    continue;
                }
                final String key = Arrays.toString(method.getParameterTypes());
                if (found.containsKey(key)) {
                    // the most derived declaration counts
                    continue;
                }
                found.put(key, method);
            }
        }
        final ArrayList<Method> result = new ArrayList<>();
        for (Method method : found.values()) {
            if (!Modifier.isFinal(method.getModifiers())) {
                result.add(method);
            }
        }
        return result;
    }

    /** public R __super_N(params) { return super.name(params); } */
    private static void declareSuper(DexMaker dexMaker, TypeId generated, TypeId superType, Generated g, String javaName) {
        final TypeId returnType = TypeId.get(g.returnType);
        final TypeId[] params = typeIds(g.params);
        final Code code = dexMaker.declare(generated.getMethod(returnType, g.superName, params), Modifier.PUBLIC);
        final Local result = g.returnType == void.class ? null : code.newLocal(returnType);
        final Local self = code.getThis(generated);
        final Local[] args = new Local[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = code.getParameter(i, params[i]);
        }
        code.invokeSuper(superType.getMethod(returnType, javaName, params), result, self, args);
        if (result == null) {
            code.returnVoid();
        } else {
            code.returnValue(result);
        }
    }

    /** public R name(params) { return (R) GlassgramClassProxy.dispatch(this, "class", N, new Object[]{params}); } */
    private static void declareDispatch(DexMaker dexMaker, TypeId generated, String className, int index, Generated g, String javaName) {
        final TypeId returnType = TypeId.get(g.returnType);
        final TypeId[] params = typeIds(g.params);
        final Code code = dexMaker.declare(generated.getMethod(returnType, javaName, params), Modifier.PUBLIC);
        final Local self = code.getThis(generated);
        final Local<Integer> length = code.newLocal(TypeId.INT);
        final Local<Integer> slot = code.newLocal(TypeId.INT);
        final Local array = code.newLocal(TypeId.get(Object[].class));
        final Local boxed = code.newLocal(TypeId.OBJECT);
        final Local<Integer> methodIndex = code.newLocal(TypeId.INT);
        final Local<String> name = code.newLocal(TypeId.STRING);
        final Local raw = code.newLocal(TypeId.OBJECT);
        final Local result = g.returnType == void.class ? null : code.newLocal(returnType);
        final Local[] args = new Local[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = code.getParameter(i, params[i]);
        }

        packArguments(code, g.params, args, length, slot, array, boxed);
        code.loadConstant(name, className);
        code.loadConstant(methodIndex, index);
        final MethodId dispatch = TypeId.get(GlassgramClassProxy.class).getMethod(TypeId.OBJECT, "dispatch",
            TypeId.OBJECT, TypeId.STRING, TypeId.INT, TypeId.get(Object[].class));
        code.invokeStatic(dispatch, raw, self, name, methodIndex, array);
        if (result == null) {
            code.returnVoid();
        } else {
            unpack(code, g.returnType, raw, result);
            code.returnValue(result);
        }
    }

    /** A constructor: Python may change the arguments, the parent's constructor runs, then Python makes the peer. */
    private static void declareConstructor(DexMaker dexMaker, TypeId generated, TypeId superType, String className, int index, Class<?>[] paramClasses) {
        final TypeId[] params = typeIds(paramClasses);
        final Code code = dexMaker.declare(generated.getConstructor(params), Modifier.PUBLIC);
        final Local self = code.getThis(generated);
        final Local<Integer> length = code.newLocal(TypeId.INT);
        final Local<Integer> slot = code.newLocal(TypeId.INT);
        final Local array = code.newLocal(TypeId.get(Object[].class));
        final Local changed = code.newLocal(TypeId.get(Object[].class));
        final Local boxed = code.newLocal(TypeId.OBJECT);
        final Local<Integer> constructorIndex = code.newLocal(TypeId.INT);
        final Local<String> name = code.newLocal(TypeId.STRING);
        final Local[] values = new Local[params.length];
        for (int i = 0; i < params.length; i++) {
            values[i] = code.newLocal(params[i]);
        }
        final Local[] args = new Local[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = code.getParameter(i, params[i]);
        }

        packArguments(code, paramClasses, args, length, slot, array, boxed);
        code.loadConstant(name, className);
        code.loadConstant(constructorIndex, index);
        final TypeId proxy = TypeId.get(GlassgramClassProxy.class);
        code.invokeStatic(proxy.getMethod(TypeId.get(Object[].class), "preConstruct", TypeId.STRING, TypeId.INT, TypeId.get(Object[].class)),
            changed, name, constructorIndex, array);
        for (int i = 0; i < params.length; i++) {
            code.loadConstant(slot, i);
            code.aget(boxed, changed, slot);
            unpack(code, paramClasses[i], boxed, values[i]);
        }
        code.invokeDirect(superType.getConstructor(params), null, self, values);
        code.invokeStatic(proxy.getMethod(TypeId.VOID, "construct", TypeId.OBJECT, TypeId.STRING, TypeId.INT, TypeId.get(Object[].class)),
            null, self, name, constructorIndex, changed);
        code.returnVoid();
    }

    private static void packArguments(Code code, Class<?>[] types, Local[] args, Local<Integer> length, Local<Integer> slot, Local array, Local boxed) {
        code.loadConstant(length, types.length);
        code.newArray(array, length);
        final TypeId proxy = TypeId.get(GlassgramClassProxy.class);
        for (int i = 0; i < types.length; i++) {
            code.loadConstant(slot, i);
            if (types[i].isPrimitive()) {
                code.invokeStatic(proxy.getMethod(TypeId.OBJECT, "box", TypeId.get(types[i])), boxed, args[i]);
                code.aput(array, slot, boxed);
            } else {
                code.aput(array, slot, args[i]);
            }
        }
    }

    /** target = (type) value, unboxing a primitive with the unbox helpers. */
    private static void unpack(Code code, Class<?> type, Local value, Local target) {
        final TypeId proxy = TypeId.get(GlassgramClassProxy.class);
        if (type.isPrimitive()) {
            final String helper = "to" + Character.toUpperCase(type.getName().charAt(0)) + type.getName().substring(1);
            code.invokeStatic(proxy.getMethod(TypeId.get(type), helper, TypeId.OBJECT), target, value);
        } else {
            code.cast(target, value);
        }
    }

    private static TypeId[] typeIds(Class<?>[] classes) {
        final TypeId[] ids = new TypeId[classes.length];
        for (int i = 0; i < classes.length; i++) {
            ids[i] = TypeId.get(classes[i]);
        }
        return ids;
    }

    /* Runtime, called by the generated classes */

    public static Object dispatch(Object self, String className, int index, Object[] args) throws Throwable {
        final Runtime runtime = runtimes.get(className);
        final Generated g = runtime.methods.get(index);
        try {
            final PyObject value = runtime.handler.callAttr("invoke", self, g.pyName, g.superName, args);
            final Object result = value == null ? null : value.toJava(Object.class);
            return PluginXposedHook.coerce(g.returnType, result);
        } catch (Throwable e) {
            // A failing plugin method must not take the app down: the parent's method runs instead
            FileLog.e(e);
            if (g.superName != null) {
                return invokeSuper(runtime, self, g, args);
            }
            return defaultValue(g.returnType);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type.isPrimitive() && type != void.class) return 0;
        return null;
    }

    /** The parameter types of a generated class's constructor, in the order its runtime numbers them. */
    public static Class<?>[] constructorTypes(Class<?> generated, int index) {
        final Runtime runtime = runtimesByClass.get(generated);
        return runtime == null || index < 0 || index >= runtime.constructors.size() ? new Class<?>[0] : runtime.constructors.get(index);
    }

    public static Object[] preConstruct(String className, int index, Object[] args) throws Throwable {
        final Runtime runtime = runtimes.get(className);
        final PyObject value = runtime.handler.callAttr("pre_construct", index, args);
        Object[] result = value == null ? args : value.toJava(Object[].class);
        if (result == null || result.length != args.length) {
            result = args;
        }
        final Class<?>[] types = runtime.constructors.get(index);
        for (int i = 0; i < result.length; i++) {
            result[i] = PluginXposedHook.coerce(types[i], result[i]);
        }
        return result;
    }

    public static void construct(Object self, String className, int index, Object[] args) throws Throwable {
        runtimes.get(className).handler.callAttr("construct", self, index, args);
    }

    /* Called from Python */

    public static Object getPeer(Object self) {
        final Runtime runtime = self == null ? null : runtimesByClass.get(self.getClass());
        if (runtime == null) {
            return null;
        }
        try {
            return runtime.peerField.get(self);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public static void setPeer(Object self, Object peer) throws IllegalAccessException {
        final Runtime runtime = runtimesByClass.get(self.getClass());
        if (runtime != null) {
            runtime.peerField.set(self, peer);
        }
    }

    /** Calls the parent's implementation behind one generated method (its __super_N twin). */
    public static Object callSuperExact(Object self, String superName, Object[] args) throws Throwable {
        final Runtime runtime = runtimesByClass.get(self.getClass());
        if (runtime != null) {
            for (Generated g : runtime.methods) {
                if (superName.equals(g.superName)) {
                    return invokeSuper(runtime, self, g, args);
                }
            }
        }
        throw new NoSuchMethodException(superName);
    }

    /**
     * Calls the parent's implementation of the method a Python method (or a Java name) overrides,
     * picking the overload whose parameters fit the arguments.
     */
    public static Object callSuper(Object self, String name, Object[] args) throws Throwable {
        final Runtime runtime = runtimesByClass.get(self.getClass());
        if (args == null) {
            args = new Object[0];
        }
        if (runtime != null) {
            for (int pass = 0; pass < 2; pass++) {
                for (Generated g : runtime.methods) {
                    if (g.superName == null || g.params.length != args.length || !name.equals(g.pyName) && !name.equals(g.javaName)) {
                        continue;
                    }
                    // first the overloads the arguments fit exactly, then any of the right length
                    if (pass == 0 && !fitsAll(g.params, args)) {
                        continue;
                    }
                    return invokeSuper(runtime, self, g, args);
                }
            }
        }
        throw new NoSuchMethodException("No parent method " + name + " taking " + args.length + " arguments");
    }

    private static boolean fitsAll(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (!fits(types[i], PluginXposedHook.coerce(types[i], args[i]))) {
                return false;
            }
        }
        return true;
    }

    private static Object invokeSuper(Runtime runtime, Object self, Generated g, Object[] args) throws Throwable {
        final Object[] converted = new Object[g.params.length];
        for (int i = 0; i < converted.length && args != null && i < args.length; i++) {
            converted[i] = PluginXposedHook.coerce(g.params[i], args[i]);
        }
        try {
            return runtime.generated.getMethod(g.superName, g.params).invoke(self, converted);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** A new instance through the first constructor whose parameters fit the arguments. */
    public static Object newInstance(Class<?> generated, Object[] args) throws Throwable {
        if (args == null) {
            args = new Object[0];
        }
        Throwable last = null;
        for (Constructor<?> constructor : generated.getConstructors()) {
            final Class<?>[] types = constructor.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            final Object[] converted = new Object[args.length];
            boolean fits = true;
            for (int i = 0; i < args.length; i++) {
                converted[i] = PluginXposedHook.coerce(types[i], args[i]);
                if (!fits(types[i], converted[i])) {
                    fits = false;
                    break;
                }
            }
            if (!fits) {
                continue;
            }
            try {
                return constructor.newInstance(converted);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw last != null ? last : new NoSuchMethodException("No constructor of " + generated.getName() + " takes " + args.length + " arguments like these");
    }

    private static boolean fits(Class<?> type, Object value) {
        if (value == null) {
            return !type.isPrimitive();
        }
        if (!type.isPrimitive()) {
            return type.isInstance(value);
        }
        if (type == boolean.class) return value instanceof Boolean;
        if (type == char.class) return value instanceof Character;
        if (type == int.class) return value instanceof Integer;
        if (type == long.class) return value instanceof Long;
        if (type == float.class) return value instanceof Float;
        if (type == double.class) return value instanceof Double;
        if (type == short.class) return value instanceof Short;
        if (type == byte.class) return value instanceof Byte;
        return false;
    }

    /* Boxing and unboxing for the generated code */

    public static Object box(boolean v) { return v; }
    public static Object box(byte v) { return v; }
    public static Object box(char v) { return v; }
    public static Object box(short v) { return v; }
    public static Object box(int v) { return v; }
    public static Object box(long v) { return v; }
    public static Object box(float v) { return v; }
    public static Object box(double v) { return v; }

    public static boolean toBoolean(Object v) { return v instanceof Boolean ? (Boolean) v : v instanceof Number && ((Number) v).intValue() != 0; }
    public static byte toByte(Object v) { return v instanceof Number ? ((Number) v).byteValue() : 0; }
    public static char toChar(Object v) { return v instanceof Character ? (Character) v : v instanceof Number ? (char) ((Number) v).intValue() : v instanceof String && ((String) v).length() > 0 ? ((String) v).charAt(0) : 0; }
    public static short toShort(Object v) { return v instanceof Number ? ((Number) v).shortValue() : 0; }
    public static int toInt(Object v) { return v instanceof Number ? ((Number) v).intValue() : v instanceof Character ? (Character) v : 0; }
    public static long toLong(Object v) { return v instanceof Number ? ((Number) v).longValue() : 0; }
    public static float toFloat(Object v) { return v instanceof Number ? ((Number) v).floatValue() : 0; }
    public static double toDouble(Object v) { return v instanceof Number ? ((Number) v).doubleValue() : 0; }

    /** Carries any Python object through Java APIs (extera_utils.classes.PyObj). */
    public static final class PyObj {
        public final Object value;

        public PyObj(Object value) {
            this.value = value;
        }

        public Object get() {
            return value;
        }
    }
}

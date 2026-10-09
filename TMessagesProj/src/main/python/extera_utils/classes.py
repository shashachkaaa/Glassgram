"""Class proxies: real Java subclasses whose methods are written in Python.

The exteraGram plugin SDK's ``extera_utils.classes``. The Java class is made at runtime with
DexMaker (``org.telegram.messenger.plugins.GlassgramClassProxy``) and can be passed to any Java
API that expects its parent type::

    from extera_utils.classes import Base, java_subclass, joverride, joverload

    @java_subclass(jclass("android.widget.FrameLayout"))
    class Box(Base):
        def __init__(self, label=""):
            self.label = label

        @joverride("onMeasure")
        def on_measure(self, width_spec, height_spec):
            super().on_measure(width_spec, height_spec)

    box = Box.new_instance(context, init_args=("hello",))
    view = box.java

Not supported here: MVEL method bodies (``jMVELmethod``, ``jMVELoverride``) and ``jclassbuilder``.
"""

import inspect
import threading
from typing import Any

from java import jclass

__all__ = [
    "Base", "java_subclass", "joverride", "joverload", "jmethod", "jMVELmethod", "jMVELoverride",
    "jclassbuilder", "jfield", "jgetmethod", "jsetmethod", "jconstructor", "jpreconstructor", "PyObj",
]

_Proxy = jclass("org.telegram.messenger.plugins.GlassgramClassProxy")
_Spec = jclass("org.telegram.messenger.plugins.GlassgramClassProxy$Spec")
_JPyObj = jclass("org.telegram.messenger.plugins.GlassgramClassProxy$PyObj")
_Class = jclass("java.lang.Class")

_PRIMITIVES = {
    "boolean": jclass("java.lang.Boolean").TYPE,
    "byte": jclass("java.lang.Byte").TYPE,
    "char": jclass("java.lang.Character").TYPE,
    "short": jclass("java.lang.Short").TYPE,
    "int": jclass("java.lang.Integer").TYPE,
    "long": jclass("java.lang.Long").TYPE,
    "float": jclass("java.lang.Float").TYPE,
    "double": jclass("java.lang.Double").TYPE,
    "void": jclass("java.lang.Void").TYPE,
}
_ARRAY_CODES = {"boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I",
                "long": "J", "float": "F", "double": "D"}
_PYTHON_TYPES = {int: "int", float: "double", bool: "boolean", str: "java.lang.String",
                 bytes: "byte[]", object: "java.lang.Object", type(None): "void"}
# the same as names, for string annotations
_PYTHON_TYPE_NAMES = {"int": "int", "float": "double", "bool": "boolean", "str": "java.lang.String",
                      "bytes": "byte[]", "object": "java.lang.Object", "None": "void", "Any": "java.lang.Object"}


def _loader():
    return jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getClassLoader()


def _jtype(t):
    """A java.lang.Class for a type given as a name, a Java class, a Class object or a Python type."""
    if t is None:
        return _PRIMITIVES["void"]
    if isinstance(t, type) and issubclass(t, Base):
        return t.java_class()
    if t in _PYTHON_TYPES:
        t = _PYTHON_TYPES[t]
    if isinstance(t, str):
        name = t.strip()
        if name in _PYTHON_TYPE_NAMES and name not in _PRIMITIVES:
            name = _PYTHON_TYPE_NAMES[name]
        if name in _PRIMITIVES:
            return _PRIMITIVES[name]
        if name.endswith("[]"):
            dims = 0
            while name.endswith("[]"):
                name = name[:-2].strip()
                dims += 1
            element = _ARRAY_CODES.get(name) or "L" + name + ";"
            return _Class.forName("[" * dims + element, False, _loader())
        try:
            return _Class.forName(name, False, _loader())
        except Exception:
            return _Class.forName(name)
    if isinstance(t, _Class):
        return t
    try:
        # A Chaquopy class: its .getClass() is the Java class itself, like Java's `.class`
        klass = t.getClass()
        if isinstance(klass, _Class):
            return klass
    except Exception:
        pass
    raise TypeError(f"Not a Java type: {t!r}")


def _jtypes(types):
    return None if types is None else [_jtype(t) for t in types]


def _to_java(value):
    """Peers go to Java as their Java objects."""
    if isinstance(value, Base):
        return value.java
    return value


# Markers the decorators put on functions

class _Marker:
    def __init__(self, kind, name=None, types=None, returns=None, explicit=False):
        self.kind = kind
        self.name = name
        self.types = types
        self.returns = returns
        self.explicit = explicit


def _mark(fn, marker):
    target = fn.__func__ if isinstance(fn, (staticmethod, classmethod)) else fn
    markers = getattr(target, "_jx_markers", None)
    if markers is None:
        markers = []
        target._jx_markers = markers
    markers.append(marker)
    return fn


def joverride(name=None):
    """Overrides the parent's (or an interface's) Java method of that name, every overload of it."""
    if callable(name):
        return _mark(name, _Marker("override", name.__name__))

    def decorator(fn):
        return _mark(fn, _Marker("override", name or fn.__name__))
    return decorator


def joverload(name, types=None):
    """Overrides one overload of a Java method: the one with these parameter types."""
    if callable(name):
        return _mark(name, _Marker("override", name.__name__))

    def decorator(fn):
        return _mark(fn, _Marker("override", name or fn.__name__, list(types) if types is not None else None))
    return decorator


def jmethod(*args, **kwargs):
    """Adds a new Java method: ``@jmethod``, ``@jmethod(return_type, [param types])`` or
    ``@jmethod(name=..., returns=..., args=[...])``; types not given are read from the annotations."""
    if len(args) == 1 and inspect.isfunction(args[0]) and not kwargs:
        return _mark(args[0], _Marker("method", args[0].__name__))
    name = kwargs.get("name")
    returns = kwargs.get("returns", kwargs.get("return_type", kwargs.get("ret")))
    types = kwargs.get("args", kwargs.get("arg_types", kwargs.get("params", kwargs.get("types"))))
    explicit = "returns" in kwargs or "return_type" in kwargs or "ret" in kwargs
    rest = list(args)
    if rest and not isinstance(rest[0], (list, tuple)):
        returns = rest.pop(0)
        explicit = True
    if rest and isinstance(rest[0], (list, tuple)):
        types = rest.pop(0)

    def decorator(fn):
        return _mark(fn, _Marker("method", name or fn.__name__, list(types) if types is not None else None,
                                 returns, explicit))
    return decorator


def jMVELmethod(*args, **kwargs):
    def decorator(fn):
        return _mark(fn, _Marker("mvel"))
    return decorator(args[0]) if len(args) == 1 and callable(args[0]) and not kwargs else decorator


def jMVELoverride(*args, **kwargs):
    return jMVELmethod(*args, **kwargs)


def jclassbuilder(fn):
    """Changes the DexMaker builder in exteraGram; Glassgram builds the class itself and skips these."""
    return _mark(fn, _Marker("classbuilder"))


def jconstructor(types=None):
    """Runs after the Python __init__, for the constructor with these parameter types (or any)."""
    if callable(types) and not isinstance(types, (list, tuple)):
        return _mark(types, _Marker("constructor"))

    def decorator(fn):
        return _mark(fn, _Marker("constructor", types=list(types) if types is not None else None))
    return decorator


def jpreconstructor(types=None):
    """Changes the constructor's arguments before the parent's constructor runs; returns the new ones."""
    if callable(types) and not isinstance(types, (list, tuple)):
        return _mark(types, _Marker("preconstructor"))

    def decorator(fn):
        return _mark(fn, _Marker("preconstructor", types=list(types) if types is not None else None))
    return decorator


class _Accessor:
    def __init__(self, kind, name=None):
        self.kind = kind
        self.name = name


def jgetmethod(name=None):
    """A Java getter of a jfield, made in Java (it doesn't go through Python)."""
    return _Accessor("get", name)


def jsetmethod(name=None):
    """A Java setter of a jfield, made in Java (it doesn't go through Python)."""
    return _Accessor("set", name)


class jfield:
    """A public Java field of the generated class; on the peer it reads and writes the Java field."""

    def __init__(self, type, default=None, methods=None):
        self.type = type
        self.default = default
        self.methods = list(methods or [])
        self.name = None

    def __set_name__(self, owner, name):
        self.name = name

    def accessors(self):
        getter = setter = None
        cap = self.name[:1].upper() + self.name[1:]
        for method in self.methods:
            if isinstance(method, _Accessor):
                if method.kind == "get":
                    getter = method.name or "get" + cap
                else:
                    setter = method.name or "set" + cap
            elif isinstance(method, str):
                if method.startswith("set"):
                    setter = method
                else:
                    getter = method
        return getter, setter

    def __get__(self, peer, owner=None):
        if peer is None:
            return self
        return getattr(peer.java, self.name)

    def __set__(self, peer, value):
        setattr(peer.java, self.name, _to_java(value))


class PyObj:
    """Carries any Python object through Java APIs."""

    @staticmethod
    def create(value):
        return _JPyObj(value)

    @staticmethod
    def get(holder):
        return holder.get() if holder is not None else None


# The runtime the generated class calls into

_pending = threading.local()


def _accepts(fn, count):
    try:
        params = list(inspect.signature(fn).parameters.values())
    except (TypeError, ValueError):
        return True
    if any(p.kind == p.VAR_POSITIONAL for p in params):
        return True
    positional = [p for p in params if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
    required = [p for p in positional if p.default is p.empty]
    return len(required) <= count <= len(positional)


def _types_match(types, java_types):
    if types is None:
        return True
    try:
        wanted = _jtypes(types)
    except Exception:
        return False
    return len(wanted) == len(java_types) and all(a.equals(b) for a, b in zip(wanted, java_types))


class _Runtime:
    def __init__(self, cls):
        self.cls = cls

    def _hooks(self, kind, index):
        java_types = list(_Proxy.constructorTypes(self.cls._jx_class, index))
        for fn, marker in self.cls._jx_hooks:
            if marker.kind == kind and _types_match(marker.types, java_types):
                yield fn

    def pre_construct(self, index, args):
        args = list(args)
        for fn in self._hooks("preconstructor", index):
            raw = fn.__func__ if isinstance(fn, (staticmethod, classmethod)) else fn
            if _accepts(raw, len(args)):
                result = raw(*args)
            elif _accepts(raw, len(args) + 1):
                result = raw(self.cls, *args)
            elif _accepts(raw, 2):
                result = raw(self.cls, args)
            else:
                result = raw(args)
            if isinstance(result, (list, tuple)):
                args = list(result)
        return [_to_java(a) for a in args]

    def construct(self, jself, index, args):
        cls = self.cls
        stack = getattr(_pending, "stack", None)
        init_args, init_kwargs = None, {}
        if stack and stack[-1][0] is cls:
            init_args, init_kwargs = stack.pop()[1:]
        peer = cls.__new__(cls)
        object.__setattr__(peer, "_jx_java", jself)
        _Proxy.setPeer(jself, peer)
        for name, field in cls._jx_fields:
            if field.default is not None:
                setattr(jself, name, _to_java(field.default))
        args = list(args)
        if init_args is None:
            init = cls.__init__
            init_args = () if init is object.__init__ or _accepts(init, 1) else tuple(args)
        peer.__init__(*init_args, **init_kwargs)
        for fn in self._hooks("constructor", index):
            fn(peer, *args)
        peer.on_post_init()

    def invoke(self, jself, py_name, super_name, args):
        peer = _Proxy.getPeer(jself)
        if peer is None:
            # called by the parent's constructor, before the peer exists
            if super_name is not None:
                return _Proxy.callSuperExact(jself, super_name, list(args))
            return None
        result = getattr(peer, py_name)(*list(args))
        return _to_java(result)


class Base:
    """The Python side of a generated Java class: bind it with ``java_subclass`` or ``bind``."""

    _jx_class = None
    _jx_hooks = ()
    _jx_fields = ()

    def __init__(self, *args, **kwargs):
        pass

    # binding

    @classmethod
    def bind(cls, base, *interfaces, methods=None, constructors=None, custom_name=None):
        if cls.__dict__.get("_jx_class") is not None:
            return cls
        spec = _Spec()
        hooks = []
        overrides = []
        for klass in reversed(cls.__mro__):
            if klass in (Base, object) or not issubclass(klass, Base):
                continue
            for attr, value in klass.__dict__.items():
                raw = value.__func__ if isinstance(value, (staticmethod, classmethod)) else value
                for marker in getattr(raw, "_jx_markers", ()):
                    if marker.kind == "override":
                        overrides.append((attr, marker.name, marker.types))
                    elif marker.kind == "method":
                        returns, types = marker.returns, marker.types
                        if not marker.explicit or types is None:
                            hints = getattr(raw, "__annotations__", {})
                            if not marker.explicit:
                                returns = hints.get("return", None)
                            if types is None:
                                names = [p for p in inspect.signature(raw).parameters][1:]
                                types = [hints.get(p, object) for p in names]
                        spec.addMethod(attr, marker.name, _jtype(returns), _jtypes(types))
                    elif marker.kind == "mvel":
                        raise NotImplementedError(f"{cls.__name__}.{attr}: MVEL methods are not supported in Glassgram")
                    elif marker.kind in ("constructor", "preconstructor"):
                        hooks.append((getattr(cls, attr), marker))
        for entry in methods or ():
            if isinstance(entry, str):
                overrides.append((entry, entry, None))
            elif isinstance(entry, (list, tuple)) and entry:
                overrides.append((entry[0], entry[0], list(entry[1]) if len(entry) > 1 and entry[1] is not None else None))
        for py_name, java_name, types in overrides:
            spec.addOverride(py_name, java_name, _jtypes(types))
        fields = []
        for klass in reversed(cls.__mro__):
            for attr, value in klass.__dict__.items():
                if isinstance(value, jfield):
                    getter, setter = value.accessors()
                    spec.addField(attr, _jtype(value.type), getter, setter)
                    fields.append((attr, value))
        for types in constructors or ():
            spec.addConstructor(_jtypes(types))

        base_class = _jtype(base) if base is not None else None
        runtime = _Runtime(cls)
        generated = _Proxy.define(custom_name, base_class, [_jtype(i) for i in interfaces], spec, runtime)
        cls._jx_class = generated
        cls._jx_hooks = tuple(hooks)
        cls._jx_fields = tuple(fields)
        cls._jx_runtime = runtime

        # super().name(...) in an override calls the parent's Java method
        supers = {}
        for py_name, java_name, _ in overrides:
            for name in {py_name, java_name}:
                supers[name] = _super_caller(name)
        if supers:
            parent = type(f"{cls.__name__}__supers", (Base,), supers)
            cls.__bases__ = tuple(parent if b is Base else b for b in cls.__bases__) \
                if Base in cls.__bases__ else (parent,) + cls.__bases__
        return cls

    @classmethod
    def java_class(cls):
        if cls.__dict__.get("_jx_class") is None:
            raise RuntimeError(f"{cls.__name__} is not bound to a Java class: use @java_subclass(...)")
        return cls._jx_class

    # instances

    @classmethod
    def new_java_instance(cls, *args, init_args=None, init_kwargs=None):
        stack = getattr(_pending, "stack", None)
        if stack is None:
            stack = _pending.stack = []
        stack.append((cls, tuple(init_args) if init_args is not None else None, dict(init_kwargs or {})))
        depth = len(stack)
        try:
            return _Proxy.newInstance(cls.java_class(), [_to_java(a) for a in args])
        finally:
            del stack[depth - 1:]

    @classmethod
    def new_instance(cls, *args, init_args=None, init_kwargs=None):
        return cls.from_java(cls.new_java_instance(*args, init_args=init_args, init_kwargs=init_kwargs))

    @classmethod
    def from_java(cls, obj):
        return _Proxy.getPeer(obj) if obj is not None else None

    @property
    def java(self) -> Any:
        return self.__dict__.get("_jx_java")

    def jrelease(self):
        """Lets go of the Java object: the peer stops answering its calls."""
        java = self.__dict__.get("_jx_java")
        if java is not None:
            _Proxy.setPeer(java, None)
            self.__dict__.pop("_jx_java", None)
            self.on_release()

    def on_post_init(self):
        pass

    def on_release(self):
        pass


def _super_caller(name):
    def call(self, *args):
        return _Proxy.callSuper(self.java, name, [_to_java(a) for a in args])
    call.__name__ = name
    return call


def java_subclass(base, *interfaces, methods=None, constructors=None, custom_name=None):
    """Binds the decorated Base subclass to a new Java subclass of base implementing interfaces."""
    def decorator(cls):
        if not (isinstance(cls, type) and issubclass(cls, Base)):
            raise TypeError("java_subclass decorates a subclass of extera_utils.classes.Base")
        return cls.bind(base, *interfaces, methods=methods, constructors=constructors, custom_name=custom_name)
    return decorator

"""Reflection helpers for Java classes and their non-public fields."""

from typing import Any

from java import jclass

JavaClass = Any
JavaObject = Any

_Class = jclass("java.lang.Class")
_ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")


def find_class(class_name: str):
    try:
        loader = _ApplicationLoader.applicationContext.getClassLoader()
        return _Class.forName(class_name, False, loader)
    except Exception:
        try:
            return _Class.forName(class_name)
        except Exception:
            return None


def _find_field(clazz, field_name: str):
    while clazz is not None:
        try:
            field = clazz.getDeclaredField(field_name)
            field.setAccessible(True)
            return field
        except Exception:
            clazz = clazz.getSuperclass()
    return None


def get_private_field(obj, field_name: str):
    try:
        field = _find_field(obj.getClass(), field_name)
        return field.get(obj) if field is not None else None
    except Exception:
        return None


def set_private_field(obj, field_name: str, new_value: Any) -> bool:
    try:
        field = _find_field(obj.getClass(), field_name)
        if field is None:
            return False
        field.set(obj, _to_field_type(field, new_value))
        return True
    except Exception:
        return False


def get_static_private_field(clazz, field_name: str):
    try:
        field = _find_field(clazz, field_name)
        return field.get(None) if field is not None else None
    except Exception:
        return None


def set_static_private_field(clazz, field_name: str, new_value: Any) -> bool:
    try:
        field = _find_field(clazz, field_name)
        if field is None:
            return False
        field.set(None, _to_field_type(field, new_value))
        return True
    except Exception:
        return False


def _to_field_type(field, value):
    """Boxes Python numbers to the field's own type, so int fields do not get a Long."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return value
    from java.lang import Byte, Double, Float, Integer, Long, Short

    name = field.getType().getName()
    boxes = {
        "int": Integer, "java.lang.Integer": Integer,
        "long": Long, "java.lang.Long": Long,
        "short": Short, "java.lang.Short": Short,
        "byte": Byte, "java.lang.Byte": Byte,
        "float": Float, "java.lang.Float": Float,
        "double": Double, "java.lang.Double": Double,
    }
    box = boxes.get(name)
    if box is None:
        return value
    if box in (Float, Double):
        return box(float(value))
    return box(int(value))

"""Plugin-scoped logcat loggers."""
import contextlib
import os
import sys
import threading
import time
import traceback
import weakref
from dataclasses import dataclass
from enum import IntEnum
from reprlib import Repr

from android.util import Log


class LogLevel(IntEnum):
    VERBOSE = 2
    DEBUG = 3
    INFO = 4
    WARNING = 5
    ERROR = 6
    OFF = 100


@dataclass
class LogRecord:
    level: LogLevel
    tag: str
    message: str
    location: str
    traceback: str
    created: float

    @property
    def text(self):
        prefix = f"[{self.location}] " if self.location else ""
        return prefix + self.message + ("\n" + self.traceback if self.traceback else "")


def dump(obj, depth=2, max_items=20):
    formatter = Repr()
    formatter.maxlevel = depth
    formatter.maxlist = formatter.maxtuple = formatter.maxdict = formatter.maxset = max_items
    if hasattr(type(obj), "getClass"):
        fields = {}
        for field in obj.getClass().getDeclaredFields():
            if len(fields) >= max_items:
                break
            if field.getModifiers() & 8:
                continue
            try:
                field.setAccessible(True)
                fields[str(field.getName())] = field.get(obj)
            except Exception:
                continue
        return formatter.repr(fields)
    return formatter.repr(obj)


_roots = weakref.WeakValueDictionary()
_lock = threading.RLock()
_dispatch = threading.local()


def _level(value):
    return LogLevel[value.upper()] if isinstance(value, str) else LogLevel(value)


class Logger:
    def __init__(self, tag, parent=None):
        self.tag = tag
        self.parent = parent
        self.show_location = True
        self._level = None
        self._children = {}
        self._handlers = []

    @property
    def level(self):
        return self._level

    @level.setter
    def level(self, value):
        self._level = None if value is None else _level(value)

    @property
    def effective_level(self):
        if self.level is not None:
            return self.level
        return self.parent.effective_level if self.parent else LogLevel.INFO

    def is_enabled_for(self, level):
        return self.effective_level <= _level(level) < LogLevel.OFF

    def child(self, name):
        with _lock:
            if name not in self._children:
                self._children[name] = Logger(f"{self.tag}.{name}", self)
            return self._children[name]

    def add_handler(self, handler):
        if handler not in self._handlers:
            self._handlers.append(handler)

    def remove_handler(self, handler):
        if handler not in self._handlers:
            return False
        self._handlers.remove(handler)
        return True

    def log(self, level, msg, *args, exc_info=None, stacklevel=1):
        level = _level(level)
        if not self.is_enabled_for(level):
            return
        location = None
        if self.show_location:
            frame = sys._getframe(stacklevel)
            location = f"{os.path.basename(frame.f_code.co_filename)}:{frame.f_code.co_name}:{frame.f_lineno}"
        if exc_info is True:
            exc_info = sys.exc_info()
        elif isinstance(exc_info, BaseException):
            exc_info = (type(exc_info), exc_info, exc_info.__traceback__)
        trace = "".join(traceback.format_exception(*exc_info)).rstrip() if exc_info else None
        record = LogRecord(level, self.tag, str(msg) % args if args else str(msg), location, trace, time.time())
        for line in record.text.splitlines() or [""]:
            for start in range(0, max(1, len(line)), 900):
                Log.println(int(level), self.tag, line[start:start + 900])
        if getattr(_dispatch, "active", False):
            return
        _dispatch.active = True
        try:
            current = self
            while current is not None:
                for handler in tuple(current._handlers):
                    try:
                        handler(record)
                    except Exception:
                        Log.e("pysdk", traceback.format_exc())
                current = current.parent
        finally:
            _dispatch.active = False

    def dump(self, obj, label=None, *, level=LogLevel.DEBUG, depth=2, max_items=20):
        if self.is_enabled_for(level):
            self.log(level, (label + ": " if label else "") + dump(obj, depth, max_items), stacklevel=2)

    @contextlib.contextmanager
    def timed(self, label, level=LogLevel.DEBUG):
        start = time.perf_counter()
        try:
            yield
        finally:
            self.log(level, "%s took %.1f ms", label, (time.perf_counter() - start) * 1000)

    def exception(self, msg, *args, exc_info=True, stacklevel=1):
        self.log(LogLevel.ERROR, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)


def _method(level):
    def write(self, msg, *args, stacklevel=1, **kwargs):
        self.log(level, msg, *args, stacklevel=stacklevel + 1, **kwargs)
    return write


for _priority in list(LogLevel)[:-1]:
    setattr(Logger, _priority.name.lower(), _method(_priority))


def _plugin_logger(plugin_id):
    with _lock:
        logger = _roots.get(plugin_id)
        if logger is None:
            logger = Logger("plugin." + plugin_id)
            _roots[plugin_id] = logger
        return logger


def get_logger(name=None):
    from _glassgram_engine import caller_plugin
    plugin_id = caller_plugin()
    if not plugin_id:
        return Logger(name or "pysdk")
    return _plugin_logger(plugin_id)

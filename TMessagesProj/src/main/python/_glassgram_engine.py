"""Glassgram plugin engine: loads exteraGram-style .plugin files and dispatches hooks.

Called from org.telegram.messenger.plugins.PluginsController.
"""

import importlib.util
import json
import os
import re
import shutil
import sys
import threading
import traceback
import types

from java import jarray, jclass
from java.lang import Object as JObject

import plugin_settings
from extera_utils.metadata_parser import get_metadata, get_metadata_from_source

_PluginsController = jclass("org.telegram.messenger.plugins.PluginsController")

PLUGIN_EXTENSIONS = (".plugin", ".py")
# Installed plugins are kept as {id}.py, as exteraGram keeps them: plugins made for it read each
# other there (zwylib looks there for the plugins using it), and import each other by id
PLUGIN_FILE_EXTENSION = ".py"
_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{1,31}$")

_lock = threading.RLock()
_records = {}
_plugins_dir = None
_libs_dir = None
_state = {}


class _Record:
    def __init__(self, plugin_id, path, meta):
        self.id = plugin_id
        self.path = path
        self.meta = meta
        self.module = None
        self.instance = None
        self.error = None

    @property
    def enabled(self):
        return bool(_state.get(self.id, {}).get("enabled", False))


def _log(tag, text):
    _PluginsController.log(tag, text)


# Startup

def start(plugins_dir: str):
    global _plugins_dir, _libs_dir
    with _lock:
        _plugins_dir = plugins_dir
        os.makedirs(_plugins_dir, exist_ok=True)
        _libs_dir = os.path.join(_plugins_dir, "libs")
        os.makedirs(_libs_dir, exist_ok=True)
        if _libs_dir not in sys.path:
            sys.path.append(_libs_dir)
        # as in exteraGram: a plugin imports another by its id (import zwylib), and plugins keep
        # modules of their own there (zwylib_companion); after the app's own, which come first
        if _plugins_dir not in sys.path:
            sys.path.append(_plugins_dir)
        _accept_class_objects()
        plugin_settings.init(_plugins_dir)
        _load_state()
        _rename_plugin_files()
        for name in sorted(os.listdir(_plugins_dir)):
            path = os.path.join(_plugins_dir, name)
            if not os.path.isfile(path) or not name.endswith(PLUGIN_EXTENSIONS):
                continue
            try:
                meta = _read_meta(path)
            except Exception as e:
                _log("plugins", "Cannot read %s: %s" % (name, e))
                continue
            if meta["id"] in _records:
                continue
            _records[meta["id"]] = _Record(meta["id"], path, meta)
        for record in list(_records.values()):
            _publish(record)
        for record in _load_order([r for r in _records.values() if r.enabled]):
            _load(record)
        hooks_changed()
    app_event("start")


def _accept_class_objects():
    """Chaquopy's dynamic_proxy and cast want a Java class itself (jclass); plugins made for
    exteraGram also give them a java.lang.Class, as hook_utils.find_class returns it. Both do."""
    import java

    if getattr(java, "_glassgram_class_objects", False):
        return
    class_type = jclass("java.lang.Class")

    def as_java_class(value):
        if isinstance(value, class_type):
            return jclass(value.getName())
        return value

    original_dynamic_proxy = java.dynamic_proxy
    original_cast = java.cast

    def dynamic_proxy(*implements):
        return original_dynamic_proxy(*[as_java_class(i) for i in implements])

    def cast(cls, obj):
        return original_cast(as_java_class(cls), obj)

    dynamic_proxy.__doc__ = original_dynamic_proxy.__doc__
    cast.__doc__ = original_cast.__doc__
    java.dynamic_proxy = dynamic_proxy
    java.cast = cast
    java._glassgram_class_objects = True


def _rename_plugin_files():
    """Plugins installed as {id}.plugin, as glassGram kept them before, become {id}.py."""
    for name in os.listdir(_plugins_dir):
        if not name.endswith(".plugin"):
            continue
        path = os.path.join(_plugins_dir, name)
        if not os.path.isfile(path):
            continue
        try:
            target = os.path.join(_plugins_dir, _read_meta(path)["id"] + PLUGIN_FILE_EXTENSION)
            if not os.path.exists(target):
                os.replace(path, target)
        except Exception as e:
            _log("plugins", "Cannot rename %s: %s" % (name, e))


def _load_order(records):
    """The plugins another one imports (import zwylib) first, so they are there for it."""
    by_id = {r.id: r for r in records}
    imports = {}
    for record in records:
        try:
            with open(record.path, "r", encoding="utf-8-sig") as f:
                source = f.read()
        except Exception:
            source = ""
        imports[record.id] = [other for other in sorted(by_id) if other != record.id and re.search(
            r"^\s*(?:from\s+%s\s+import\b|import\s+%s\b)" % (re.escape(other), re.escape(other)), source, re.M)]
    ordered = []
    done = set()

    def visit(plugin_id, path):
        if plugin_id in done or plugin_id in path:
            return
        path.add(plugin_id)
        for other in imports.get(plugin_id, ()):
            visit(other, path)
        path.discard(plugin_id)
        done.add(plugin_id)
        ordered.append(by_id[plugin_id])

    for record in records:
        visit(record.id, set())
    return ordered


def stop():
    with _lock:
        for record in list(_records.values()):
            if record.instance is not None:
                _unload(record)


def _state_path():
    return os.path.join(_plugins_dir, "plugins_state.json")


def _load_state():
    global _state
    try:
        with open(_state_path(), "r", encoding="utf-8") as f:
            data = json.load(f)
        _state = data if isinstance(data, dict) else {}
    except Exception:
        _state = {}


def _save_state():
    tmp = _state_path() + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(_state, f)
    os.replace(tmp, _state_path())


# Metadata

def _normalize_meta(raw: dict, fallback_id: str) -> dict:
    plugin_id = raw.get("__id__") or fallback_id
    if not isinstance(plugin_id, str) or not _ID_RE.match(plugin_id):
        raise ValueError("Invalid plugin id: %r" % (plugin_id,))
    name = raw.get("__name__") or plugin_id
    app_version = raw.get("__app_version__")
    if not app_version and raw.get("__min_version__"):
        app_version = ">=" + str(raw.get("__min_version__"))
    requirements = raw.get("__requirements__") or []
    if isinstance(requirements, str):
        requirements = [requirements]
    return {
        "id": plugin_id,
        "name": str(name),
        "description": str(raw.get("__description__") or ""),
        "author": str(raw.get("__author__") or ""),
        "version": str(raw.get("__version__") or "1.0"),
        "icon": str(raw.get("__icon__") or ""),
        "app_version": str(app_version or ""),
        "sdk_version": str(raw.get("__sdk_version__") or ""),
        "requirements": [str(r) for r in requirements],
    }


def _read_meta(path: str) -> dict:
    fallback = os.path.splitext(os.path.basename(path))[0]
    return _normalize_meta(get_metadata(path), fallback)


def read_metadata(path: str):
    """[id, name, version, author, description, icon] of a plugin file, for the install dialog."""
    with open(path, "r", encoding="utf-8-sig") as f:
        source = f.read()
    meta = _normalize_meta(get_metadata_from_source(source), "")
    return jarray(JObject)([meta["id"], meta["name"], meta["version"], meta["author"], meta["description"],
                           meta["icon"], "1" if meta["id"] in _records else "0"])


def _publish(record: _Record):
    meta = record.meta
    has_settings = False
    if record.instance is not None:
        has_settings = type(record.instance).create_settings is not _base().BasePlugin.create_settings
    _PluginsController.putPluginInfo(
        record.id, meta["name"], meta["description"], meta["author"], meta["version"], meta["icon"],
        record.enabled, record.error, has_settings, record.path)


def _base():
    import base_plugin

    return base_plugin


# Loading

def _version_tuple(text: str):
    parts = re.findall(r"\d+", text or "")
    return tuple(int(p) for p in parts) if parts else (0,)


def _check_version(requirement: str, current: str) -> bool:
    requirement = (requirement or "").strip()
    if not requirement:
        return True
    match = re.match(r"^(>=|<=|==|>|<)?\s*(.+)$", requirement)
    op, version = match.group(1) or ">=", match.group(2)
    a, b = _version_tuple(current), _version_tuple(version)
    return {
        ">=": a >= b, "<=": a <= b, "==": a == b, ">": a > b, "<": a < b,
    }[op]


def _load(record: _Record) -> bool:
    if record.instance is not None:
        return True
    record.error = None
    try:
        from _sdk_version import __version__ as sdk_version

        if not _check_version(record.meta["sdk_version"], sdk_version):
            raise RuntimeError("The plugin needs plugin SDK %s, this app has %s"
                               % (record.meta["sdk_version"], sdk_version))
        if record.meta["requirements"]:
            import _glassgram_pip

            _glassgram_pip.install(record.meta["requirements"], _libs_dir)
        with open(record.path, "r", encoding="utf-8-sig") as f:
            source = f.read()
        module_name = "plugins." + record.id.replace("-", "_")
        module = types.ModuleType(module_name)
        module.__file__ = record.path
        module.__dict__["__glassgram_plugin_id__"] = record.id
        sys.modules[module_name] = module
        # others import it by its id (import zwylib), this very module and not a copy of its file
        if _may_take_name(record.id):
            sys.modules[record.id] = module
        # Plugins set __name__ to their display name, which becomes the module of their classes
        display_name = record.meta["name"]
        registered_display = display_name not in sys.modules
        if registered_display:
            sys.modules[display_name] = module
        code = compile(source, record.path, "exec")
        exec(code, module.__dict__)
        if registered_display:
            sys.modules.pop(display_name, None)
        record.module = module
        plugin_class = _find_plugin_class(module)
        if plugin_class is None:
            raise RuntimeError("No class inheriting from BasePlugin was found")
        instance = plugin_class.__new__(plugin_class)
        meta = record.meta
        instance.id = record.id
        instance.name = meta["name"]
        instance.description = meta["description"]
        instance.author = meta["author"]
        instance.version = meta["version"]
        instance.icon = meta["icon"] or None
        instance.min_version = meta["app_version"]
        instance.requirements = list(meta["requirements"])
        plugin_class.__init__(instance)
        instance.id = record.id
        record.instance = instance
        instance.on_plugin_load()
        instance.enabled = True
        instance.initialized = True
        _compat_engine_call("onPluginLoaded", record.id, instance)
    except Exception:
        record.error = traceback.format_exc()
        _log(record.id, "Failed to load:\n" + record.error)
        _compat_engine_call("unloadPlugin", record.id)
        _release_name(record.id, record.module or sys.modules.get("plugins." + record.id.replace("-", "_")))
        if record.instance is not None:
            try:
                _base()._cleanup(record.instance)
            except Exception:
                pass
        record.instance = None
        _publish(record)
        hooks_changed()
        return False
    _publish(record)
    hooks_changed()
    return True


def _may_take_name(plugin_id):
    """Whether a plugin's module may go by its id: not when that is a module of the app's or a
    library's (a plugin named "json" stays plugins.json)."""
    if not plugin_id.isidentifier():
        return False
    existing = sys.modules.get(plugin_id)
    if existing is not None:
        return _is_plugins_module(existing)
    try:
        spec = importlib.util.find_spec(plugin_id)
    except Exception:
        return False
    return spec is None or _in_plugins_dir(getattr(spec, "origin", None))


def _is_plugins_module(module):
    return "__glassgram_plugin_id__" in module.__dict__ or _in_plugins_dir(getattr(module, "__file__", None))


def _in_plugins_dir(path):
    if not path or not _plugins_dir:
        return False
    return os.path.dirname(os.path.abspath(path)) == os.path.abspath(_plugins_dir)


def _release_name(plugin_id, module):
    if module is not None and sys.modules.get(plugin_id) is module:
        sys.modules.pop(plugin_id, None)


def _compat_engine_call(method, *args):
    """Tells exteraGram's engine (com.exteragram.messenger.plugins.PythonPluginsEngine), which
    plugins made for exteraGram watch, of a plugin loaded or going."""
    try:
        engine = jclass("com.exteragram.messenger.plugins.PythonPluginsEngine").INSTANCE
        getattr(engine, method)(*args)
    except Exception:
        _log("plugins", "%s failed:\n%s" % (method, traceback.format_exc()))


def _find_plugin_class(module):
    base = _base().BasePlugin
    found = None
    for value in list(module.__dict__.values()):
        if isinstance(value, type) and issubclass(value, base) and value is not base:
            if value.__module__ == module.__dict__.get("__name__") or found is None:
                found = value
    return found


def _unload(record: _Record):
    instance = record.instance
    if instance is None:
        return
    # first: plugins hooking exteraGram's unloadPlugin see it while the plugin still runs
    _compat_engine_call("unloadPlugin", record.id)
    try:
        instance.on_plugin_unload()
    except Exception:
        _log(record.id, "on_plugin_unload failed:\n" + traceback.format_exc())
    try:
        _base()._cleanup(instance)
    except Exception:
        _log(record.id, traceback.format_exc())
    instance.enabled = False
    record.instance = None
    _release_name(record.id, record.module)
    record.module = None
    sys.modules.pop("plugins." + record.id.replace("-", "_"), None)
    hooks_changed()


def plugin_id_for_module(module_name):
    if not module_name:
        return None
    if module_name.startswith("plugins."):
        rest = module_name[len("plugins."):]
        for plugin_id in _records:
            if plugin_id.replace("-", "_") == rest:
                return plugin_id
    for record in _records.values():
        if record.meta["name"] == module_name:
            return record.id
    return None


def plugin_id_for_globals(globals_dict):
    return globals_dict.get("__glassgram_plugin_id__")


# Management, called from the UI

def set_enabled(plugin_id: str, enabled: bool) -> bool:
    with _lock:
        record = _records.get(plugin_id)
        if record is None:
            return False
        _state.setdefault(plugin_id, {})["enabled"] = bool(enabled)
        _save_state()
        if enabled:
            ok = _load(record)
        else:
            _unload(record)
            record.error = None
            ok = True
        _publish(record)
        return ok


def install(path: str, enable: bool = True):
    """Copies a plugin file into the plugins folder and loads it; returns the plugin id."""
    with _lock:
        meta = _read_meta(path)
        plugin_id = meta["id"]
        old = _records.get(plugin_id)
        if old is not None:
            _unload(old)
            if old.path != path and os.path.exists(old.path):
                os.remove(old.path)
        target = os.path.join(_plugins_dir, plugin_id + PLUGIN_FILE_EXTENSION)
        if os.path.abspath(path) != os.path.abspath(target):
            shutil.copyfile(path, target)
        record = _Record(plugin_id, target, meta)
        _records[plugin_id] = record
        if enable:
            _state.setdefault(plugin_id, {})["enabled"] = True
            _save_state()
        _publish(record)
        if record.enabled:
            _load(record)
        return plugin_id


def load_from_file(plugin_id: str, path: str = None):
    """exteraGram's PythonPluginsEngine.loadPlugin: reads an installed plugin from its file again
    (the one named, or its own when that is not there) and loads it when it is on."""
    with _lock:
        record = _records.get(plugin_id)
        if not path or not os.path.isfile(path):
            path = record.path if record is not None else None
        if not path or not os.path.isfile(path):
            raise FileNotFoundError("No file of plugin %r" % (plugin_id,))
        meta = _read_meta(path)
        if record is not None:
            _unload(record)
        record = _Record(meta["id"], path, meta)
        _records[record.id] = record
        _publish(record)
        if record.enabled:
            _load(record)
        return record.id


def uninstall(plugin_id: str):
    with _lock:
        record = _records.pop(plugin_id, None)
        if record is None:
            return
        _unload(record)
        try:
            os.remove(record.path)
        except OSError:
            pass
        _state.pop(plugin_id, None)
        _save_state()
        plugin_settings.clear_settings(plugin_id)
        _PluginsController.removePluginInfo(plugin_id)
        hooks_changed()


def get_instance(plugin_id: str):
    record = _records.get(plugin_id)
    return record.instance if record is not None else None


def get_settings(plugin_id: str):
    instance = get_instance(plugin_id)
    if instance is None:
        return []
    try:
        return list(instance.create_settings() or [])
    except Exception:
        _log(plugin_id, "create_settings failed:\n" + traceback.format_exc())
        return []


def create_sub_settings(plugin_id: str, factory):
    try:
        return list(factory() or [])
    except Exception:
        _log(plugin_id, "create_sub_fragment failed:\n" + traceback.format_exc())
        return []


def get_setting_value(plugin_id: str, key: str, default):
    return plugin_settings.get_setting(plugin_id, key, default)


def setting_changed(plugin_id: str, setting, value):
    """Stores a value edited in the settings UI and calls the row's on_change."""
    kind = getattr(setting, "type", None)
    if kind == "switch":
        value = bool(value)
    elif kind == "selector":
        value = int(value)
    elif value is not None:
        value = str(value)
    plugin_settings.set_setting(plugin_id, setting.key, value)
    callback = getattr(setting, "on_change", None)
    if callback is not None:
        try:
            callback(value)
        except Exception:
            _log(plugin_id, "on_change failed:\n" + traceback.format_exc())


def call(plugin_id: str, fn, *args):
    """Calls a plugin callback from the UI, keeping its errors out of the app."""
    if fn is None:
        return None
    try:
        return fn(*args)
    except Exception:
        _log(plugin_id, traceback.format_exc())
        return None


def export_settings(plugin_id: str) -> str:
    return json.dumps(plugin_settings.get_all_settings(plugin_id), ensure_ascii=False)


def app_event(name: str):
    event = _base().AppEvent(name)
    for record in _loaded():
        try:
            record.instance.on_app_event(event)
        except Exception:
            _log(record.id, "on_app_event failed:\n" + traceback.format_exc())


def open_file(place: int, file, file_name: str, message, activity, fragment) -> bool:
    from file_utils import FilesController

    try:
        return bool(FilesController._open(place, file, file_name, message, activity, fragment))
    except Exception:
        _log("plugins", traceback.format_exc())
        return False


# Hooks

def _loaded():
    return [r for r in list(_records.values()) if r.instance is not None]


def hooks_changed():
    exact = set()
    substrings = set()
    send_message = False
    for record in _loaded():
        instance = record.instance
        for name, (match_substring, _priority) in _base()._state(instance).hooks.items():
            (substrings if match_substring else exact).add(name)
        if _base()._state(instance).send_message_priority is not None:
            send_message = True
    _PluginsController.setHooks(
        jarray(jclass("java.lang.String"))(sorted(exact)),
        jarray(jclass("java.lang.String"))(sorted(substrings)),
        send_message)


def _hooked(name: str):
    """(priority, instance) of plugins hooking name, highest priority first."""
    result = []
    for record in _loaded():
        instance = record.instance
        for hook_name, (match_substring, priority) in _base()._state(instance).hooks.items():
            if hook_name == name or (match_substring and hook_name in name):
                result.append((priority, record.id, instance))
                break
    result.sort(key=lambda x: -x[0])
    return result


def _apply(result, current, attr):
    """(new value, stop) for a HookResult; new value is None for CANCEL."""
    base = _base()
    if result is None or not isinstance(result, base.HookResult):
        return current, False, False
    strategy = result.strategy
    if strategy == base.HookStrategy.CANCEL:
        return None, True, True
    if strategy in (base.HookStrategy.MODIFY, base.HookStrategy.MODIFY_FINAL):
        value = getattr(result, attr)
        if value is not None:
            current = value
        return current, strategy == base.HookStrategy.MODIFY_FINAL, False
    return current, False, False


def _run(account, plugin_id, fn, *args):
    from client_utils import account_scope

    try:
        with account_scope(account):
            return fn(*args)
    except Exception:
        _log(plugin_id, "%s failed:\n%s" % (getattr(fn, "__name__", "hook"), traceback.format_exc()))
        return None


def pre_request(name: str, account: int, request):
    for _priority, plugin_id, instance in _hooked(name):
        result = _run(account, plugin_id, instance.pre_request_hook, name, account, request)
        request, stop, cancelled = _apply(result, request, "request")
        if cancelled:
            return None
        if stop:
            break
    return request


def post_request(name: str, account: int, response, error):
    for _priority, plugin_id, instance in _hooked(name):
        result = _run(account, plugin_id, instance.post_request_hook, name, account, response, error)
        base = _base()
        if isinstance(result, base.HookResult):
            if result.strategy == base.HookStrategy.CANCEL:
                return None
            if result.strategy in (base.HookStrategy.MODIFY, base.HookStrategy.MODIFY_FINAL):
                if result.response is not None:
                    response = result.response
                if result.error is not None:
                    error = result.error
                if result.strategy == base.HookStrategy.MODIFY_FINAL:
                    break
    return jarray(JObject)([response, error])


def on_update(name: str, account: int, update):
    for _priority, plugin_id, instance in _hooked(name):
        result = _run(account, plugin_id, instance.on_update_hook, name, account, update)
        update, stop, cancelled = _apply(result, update, "update")
        if cancelled:
            return None
        if stop:
            break
    return update


def on_updates(name: str, account: int, updates):
    for _priority, plugin_id, instance in _hooked(name):
        result = _run(account, plugin_id, instance.on_updates_hook, name, account, updates)
        updates, stop, cancelled = _apply(result, updates, "updates")
        if cancelled:
            return None
        if stop:
            break
    return updates


def on_send_message(account: int, params):
    hooked = []
    for record in _loaded():
        priority = _base()._state(record.instance).send_message_priority
        if priority is not None:
            hooked.append((priority, record.id, record.instance))
    hooked.sort(key=lambda x: -x[0])
    for _priority, plugin_id, instance in hooked:
        result = _run(account, plugin_id, instance.on_send_message_hook, account, params)
        params, stop, cancelled = _apply(result, params, "params")
        if cancelled:
            return None
        if stop:
            break
    return params

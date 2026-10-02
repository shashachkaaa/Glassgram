"""Plugin base class, hook results, menu items and Xposed-style method hooks."""

import traceback
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from java import jclass

import plugin_settings

_PluginsController = jclass("org.telegram.messenger.plugins.PluginsController")


@dataclass
class PluginMetadata:
    id: str
    name: str
    description: str
    author: str
    version: str
    icon: str
    min_version: str
    requirements: List[str]


class HookStrategy(Enum):
    CANCEL = "cancel"
    MODIFY = "modify"
    DEFAULT = "default"
    MODIFY_FINAL = "modify_final"


class IntentHookType(Enum):
    BEFORE = 0
    AFTER = 1


@dataclass
class HookResult:
    strategy: HookStrategy = HookStrategy.DEFAULT
    request: Any = None
    response: Any = None
    update: Any = None
    updates: Any = None
    error: Any = None
    params: Any = None


class PluginError(Exception):
    def __init__(self, message: str, plugin_id: Optional[str] = None) -> None:
        super().__init__(message)
        self.plugin_id = plugin_id


class AppEvent(Enum):
    START = "start"
    STOP = "stop"
    PAUSE = "pause"
    RESUME = "resume"


class MenuItemType(Enum):
    MESSAGE_CONTEXT_MENU = 0
    DRAWER_MENU = 1
    MAIN_MENU = 2
    CHAT_ACTION_MENU = 3
    PROFILE_ACTION_MENU = 4


@dataclass
class MenuItemData:
    menu_type: MenuItemType
    text: str
    on_click: Callable[[Dict[str, Any]], None]
    item_id: Optional[str] = None
    icon: Optional[str] = None
    subtext: Optional[str] = None
    condition: Optional[str] = None
    priority: int = 0


# Xposed-style hooks

class XposedHook(ABC):
    pass


class MethodReplacement(XposedHook):
    @abstractmethod
    def replace_hooked_method(self, param) -> Any:
        ...


class MethodHook(XposedHook):
    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


@dataclass
class HookFilterData:
    filter_type: str
    arg_index: Optional[int] = None
    or_filters: Any = None
    mvel_expression: Optional[str] = None
    instance_of: Any = None
    object: Any = None

    def to_java_filter(self):
        return self

    def matches(self, param) -> bool:
        kind = self.filter_type
        if kind == "or":
            return any(_filter_data(f).matches(param) for f in (self.or_filters or ()))
        if kind.startswith("result"):
            result = param.getResult()
            if kind == "result_is_null":
                return result is None
            if kind == "result_not_null":
                return result is not None
            if kind == "result_is_true":
                return result is True or result == True  # noqa: E712
            if kind == "result_is_false":
                return result is False or result == False  # noqa: E712
            if kind == "result_instance_of":
                return result is not None and self.instance_of.isInstance(result)
            if kind == "result_equal":
                return result == self.object
            if kind == "result_not_equal":
                return result != self.object
        if kind.startswith("arg"):
            args = param.args
            if args is None or self.arg_index is None or self.arg_index >= len(args):
                return False
            arg = args[self.arg_index]
            if kind == "arg_is_null":
                return arg is None
            if kind == "arg_not_null":
                return arg is not None
            if kind == "arg_is_true":
                return arg is True or arg == True  # noqa: E712
            if kind == "arg_is_false":
                return arg is False or arg == False  # noqa: E712
            if kind == "arg_instance_of":
                return arg is not None and self.instance_of.isInstance(arg)
            if kind == "arg_equal":
                return arg == self.object
            if kind == "arg_not_equal":
                return arg != self.object
        if kind == "condition":
            return _evaluate_condition(self.mvel_expression, param, self.object)
        return True


def _evaluate_condition(expression: Optional[str], param, obj) -> bool:
    """Evaluates simple MVEL-like conditions as Python expressions; unsupported ones pass."""
    if not expression:
        return True
    source = expression.replace("&&", " and ").replace("||", " or ").replace("!=", " __NE__ ")
    source = source.replace("!", " not ").replace(" __NE__ ", " != ")
    source = source.replace("null", "None").replace("true", "True").replace("false", "False")
    if "instanceof" in source:
        return True
    try:
        return bool(eval(source, {"__builtins__": {}}, {"param": param, "object": obj, "this": param.thisObject}))
    except Exception:
        return True


def _filter_data(f) -> HookFilterData:
    if isinstance(f, HookFilter):
        return f.filter_data
    return f


class HookFilter(Enum):
    RESULT_IS_NULL = "result_is_null"
    RESULT_IS_TRUE = "result_is_true"
    RESULT_IS_FALSE = "result_is_false"
    RESULT_NOT_NULL = "result_not_null"

    @property
    def filter_data(self):
        return HookFilterData(self.value)

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return HookFilterData("result_instance_of", instance_of=clazz)

    @staticmethod
    def ResultEqual(value):
        return HookFilterData("result_equal", object=value)

    @staticmethod
    def ResultNotEqual(value):
        return HookFilterData("result_not_equal", object=value)

    @staticmethod
    def ArgumentIsNull(index: int):
        return HookFilterData("arg_is_null", arg_index=index)

    @staticmethod
    def ArgumentIsTrue(index: int):
        return HookFilterData("arg_is_true", arg_index=index)

    @staticmethod
    def ArgumentIsFalse(index: int):
        return HookFilterData("arg_is_false", arg_index=index)

    @staticmethod
    def ArgumentNotNull(index: int):
        return HookFilterData("arg_not_null", arg_index=index)

    @staticmethod
    def ArgumentIsInstanceOf(index: int, clazz):
        return HookFilterData("arg_instance_of", arg_index=index, instance_of=clazz)

    @staticmethod
    def ArgumentEqual(index: int, value):
        return HookFilterData("arg_equal", arg_index=index, object=value)

    @staticmethod
    def ArgumentNotEqual(index: int, value):
        return HookFilterData("arg_not_equal", arg_index=index, object=value)

    @staticmethod
    def Condition(condition: str, object: Any = None):
        return HookFilterData("condition", mvel_expression=condition, object=object)

    @staticmethod
    def Or(*filters):
        return HookFilterData("or", or_filters=tuple(filters))


def hook_filters(*filters):
    """Runs the decorated hook method only when every filter matches."""

    def decorator(fn):
        fn.__hook_filters__ = tuple(filters)
        return fn

    return decorator


def fn_hook_filters(field_name: str):
    def decorator(fn):
        fn.__hook_filters_field__ = field_name
        return fn

    return decorator


def _passes(filters, param) -> bool:
    for f in filters or ():
        if not _filter_data(f).matches(param):
            return False
    return True


class BaseHook(MethodHook):
    def __init__(self, plugin=None, *, before: Optional[Callable] = None, after: Optional[Callable] = None,
                 before_filters: Optional[List[Any]] = None, after_filters: Optional[List[Any]] = None) -> None:
        self.plugin = plugin
        self.before = before
        self.after = after
        self.before_filters = before_filters or []
        self.after_filters = after_filters or []

    def before_hooked_method(self, param) -> None:
        if self.before is not None and _passes(self.before_filters, param):
            self.before(param)

    def after_hooked_method(self, param) -> None:
        if self.after is not None and _passes(self.after_filters, param):
            self.after(param)


class _XposedBridgeHandler:
    """Called from Java for each hooked call; applies filters and keeps plugin errors contained."""

    def __init__(self, plugin, handler):
        self.plugin = plugin
        self.handler = handler

    def _method_filters(self, name):
        fn = getattr(self.handler, name, None)
        return getattr(fn, "__hook_filters__", None) if fn is not None else None

    def before(self, param):
        try:
            if _passes(self._method_filters("before_hooked_method"), param):
                self.handler.before_hooked_method(param)
        except Exception:
            self.plugin._report_error("before_hooked_method", traceback.format_exc())

    def after(self, param):
        try:
            if _passes(self._method_filters("after_hooked_method"), param):
                self.handler.after_hooked_method(param)
        except Exception:
            self.plugin._report_error("after_hooked_method", traceback.format_exc())

    def replace(self, param):
        try:
            return self.handler.replace_hooked_method(param)
        except Exception:
            self.plugin._report_error("replace_hooked_method", traceback.format_exc())
            return None


class XposedUnavailable(PluginError):
    pass


def _xposed_bridge():
    try:
        return jclass("de.robv.android.xposed.XposedBridge")
    except Exception:
        raise XposedUnavailable("Xposed method hooks are not available in this Glassgram build")


def _make_java_hook(plugin, xposed_hook, priority, before, after, before_filters, after_filters):
    _xposed_bridge()
    if xposed_hook is None:
        xposed_hook = BaseHook(plugin, before=before, after=after, before_filters=before_filters,
                               after_filters=after_filters)
    replacement = isinstance(xposed_hook, MethodReplacement) or (
        hasattr(xposed_hook, "replace_hooked_method") and not hasattr(xposed_hook, "before_hooked_method"))
    handler = _XposedBridgeHandler(plugin, xposed_hook)
    java_hook_class = jclass("org.telegram.messenger.plugins.PluginXposedHook")
    java_hook = java_hook_class(handler, int(priority if priority is not None else 50), bool(replacement))
    plugin._xposed_handlers.append(handler)
    return java_hook


class BasePlugin:
    """Base class of every plugin; the engine fills in the metadata after construction."""

    def __new__(cls, *args, **kwargs):
        instance = super().__new__(cls)
        instance._init_base()
        return instance

    def __init_subclass__(cls, **kwargs) -> None:
        super().__init_subclass__(**kwargs)

    def _init_base(self):
        if getattr(self, "_base_ready", False):
            return
        self._base_ready = True
        self.id = ""
        self.name = ""
        self.description = ""
        self.author = ""
        self.min_version = ""
        self.version = "1.0"
        self.requirements = []
        self.icon = None
        self.error_message = None
        self.enabled = False
        self.initialized = False
        self._hooks = {}
        self._send_message_hook_priority = None
        self._menu_items = []
        self._unhooks = []
        self._xposed_handlers = []
        self._file_hooks = []
        self._intent_hooks = []

    def __init__(self) -> None:
        self._init_base()

    # Lifecycle, overridden by plugins

    def on_plugin_load(self) -> None:
        pass

    def on_plugin_unload(self) -> None:
        pass

    def create_settings(self) -> List[Any]:
        return []

    def on_app_event(self, event_type: AppEvent):
        pass

    def pre_request_hook(self, request_name: str, account: int, request: Any) -> HookResult:
        return HookResult()

    def post_request_hook(self, request_name: str, account: int, response: Any, error: Any) -> HookResult:
        return HookResult()

    def on_update_hook(self, update_name: str, account: int, update: Any) -> HookResult:
        return HookResult()

    def on_updates_hook(self, container_name: str, account: int, updates: Any) -> HookResult:
        return HookResult()

    def on_send_message_hook(self, account: int, params: Any) -> HookResult:
        return HookResult()

    # Hook registration

    def add_hook(self, name: str, match_substring: bool = False, priority: int = 0):
        self._hooks[str(name)] = (bool(match_substring), int(priority))
        _engine().hooks_changed()

    def add_on_send_message_hook(self, priority: int = 0):
        self._send_message_hook_priority = int(priority)
        _engine().hooks_changed()

    def remove_hook(self, name: str):
        if name == "on_send_message_hook":
            self._send_message_hook_priority = None
        else:
            self._hooks.pop(str(name), None)
        _engine().hooks_changed()

    def add_file_hook(self, file_info):
        from file_utils import FilesController

        secret = FilesController.register(file_info)
        self._file_hooks.append((file_info.ext, secret))
        return secret

    def remove_file_hook(self, ext, secret) -> None:
        from file_utils import FilesController

        FilesController.unregister(ext, secret)
        self._file_hooks = [h for h in self._file_hooks if h != (ext, secret)]

    def add_intent_hook(self, info, type: IntentHookType):
        from intents import IntentsManager

        if type == IntentHookType.AFTER:
            handle = IntentsManager.new_global_after_handler(info)
        else:
            handle = IntentsManager.new_global_before_handler(info)
        self._intent_hooks.append(handle.handler_id)
        return handle

    def remove_intent_hook(self, handler_id):
        from intents import IntentsManager

        handler_id = getattr(handler_id, "handler_id", handler_id)
        IntentsManager.unhandle(handler_id)
        if handler_id in self._intent_hooks:
            self._intent_hooks.remove(handler_id)

    # Settings

    def get_setting(self, key: str, default: Any = None) -> Any:
        return plugin_settings.get_setting(self.id, key, default)

    def set_setting(self, key: str, value: Any, reload_settings: bool = False):
        plugin_settings.set_setting(self.id, key, value)
        if reload_settings:
            _PluginsController.reloadSettings(self.id)

    def export_settings(self) -> dict:
        return plugin_settings.get_all_settings(self.id)

    def import_settings(self, settings: dict, reload_settings: bool = True):
        plugin_settings.set_all_settings(self.id, settings)
        if reload_settings:
            _PluginsController.reloadSettings(self.id)

    # Xposed

    def hook_method(self, method_or_constructor, xposed_hook: Any = None, priority: Optional[int] = None, *,
                    before: Optional[Callable] = None, after: Optional[Callable] = None,
                    before_filters: Optional[List[Any]] = None, after_filters: Optional[List[Any]] = None):
        try:
            java_hook = _make_java_hook(self, xposed_hook, priority, before, after, before_filters, after_filters)
            unhook = _xposed_bridge().hookMethod(method_or_constructor, java_hook)
        except Exception:
            self._report_error("hook_method", traceback.format_exc())
            return None
        if unhook is not None:
            self._unhooks.append(unhook)
        return unhook

    def hook_all_methods(self, hook_class, method_name: str, xposed_hook: Any = None, priority: Optional[int] = None,
                         *, before: Optional[Callable] = None, after: Optional[Callable] = None,
                         before_filters: Optional[List[Any]] = None, after_filters: Optional[List[Any]] = None):
        try:
            java_hook = _make_java_hook(self, xposed_hook, priority, before, after, before_filters, after_filters)
            unhooks = list(_xposed_bridge().hookAllMethods(hook_class, method_name, java_hook).toArray())
        except Exception:
            self._report_error("hook_all_methods", traceback.format_exc())
            return None
        self._unhooks.extend(unhooks)
        return unhooks

    def hook_all_constructors(self, hook_class, xposed_hook: Any = None, priority: Optional[int] = None, *,
                              before: Optional[Callable] = None, after: Optional[Callable] = None,
                              before_filters: Optional[List[Any]] = None, after_filters: Optional[List[Any]] = None):
        try:
            java_hook = _make_java_hook(self, xposed_hook, priority, before, after, before_filters, after_filters)
            unhooks = list(_xposed_bridge().hookAllConstructors(hook_class, java_hook).toArray())
        except Exception:
            self._report_error("hook_all_constructors", traceback.format_exc())
            return None
        self._unhooks.extend(unhooks)
        return unhooks

    def unhook_method(self, unhook):
        try:
            unhook.unhook()
        except Exception:
            self._report_error("unhook_method", traceback.format_exc())
        if unhook in self._unhooks:
            self._unhooks.remove(unhook)

    # Misc

    def log(self, message: str):
        _PluginsController.log(self.id or "plugin", str(message))

    def client(self, account: Optional[int] = None):
        from client_utils import get_client

        return get_client(account)

    def add_menu_item(self, menu_item_data: MenuItemData) -> Optional[str]:
        data = menu_item_data
        item_id = data.item_id or uuid.uuid4().hex
        plugin = self

        def on_click(java_context):
            context = {}
            try:
                for key in java_context.keySet().toArray():
                    context[str(key)] = java_context.get(key)
            except Exception:
                pass
            try:
                data.on_click(context)
            except Exception:
                plugin._report_error("menu item", traceback.format_exc())

        menu_type = data.menu_type.value if isinstance(data.menu_type, MenuItemType) else int(data.menu_type)
        _PluginsController.addMenuItem(
            self.id, item_id, int(menu_type), str(data.text), None if data.subtext is None else str(data.subtext),
            data.icon, int(data.priority or 0), data.condition, on_click)
        self._menu_items.append(item_id)
        return item_id

    def remove_menu_item(self, item_id: str) -> bool:
        if item_id in self._menu_items:
            self._menu_items.remove(item_id)
        return bool(_PluginsController.removeMenuItem(self.id, item_id))

    def _report_error(self, where: str, trace: str):
        _PluginsController.log(self.id or "plugin", "%s failed:\n%s" % (where, trace))

    def _cleanup(self):
        """Removes everything the plugin registered; called by the engine on unload."""
        for unhook in list(self._unhooks):
            try:
                unhook.unhook()
            except Exception:
                pass
        self._unhooks.clear()
        self._xposed_handlers.clear()
        for item_id in list(self._menu_items):
            try:
                _PluginsController.removeMenuItem(self.id, item_id)
            except Exception:
                pass
        self._menu_items.clear()
        for ext, secret in list(self._file_hooks):
            try:
                self.remove_file_hook(ext, secret)
            except Exception:
                pass
        for handler_id in list(self._intent_hooks):
            try:
                self.remove_intent_hook(handler_id)
            except Exception:
                pass
        self._hooks.clear()
        self._send_message_hook_priority = None


def _engine():
    import _glassgram_engine

    return _glassgram_engine

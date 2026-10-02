"""Setting rows for plugin settings pages, returned from BasePlugin.create_settings()."""

import traceback
from dataclasses import dataclass, field
from typing import Any, Callable, List, Optional

from java import jclass

_PluginsController = jclass("org.telegram.messenger.plugins.PluginsController")
_PluginItemFactory = jclass("org.telegram.messenger.plugins.PluginItemFactory")


class PluginsConstants:
    class Settings:
        TYPE_HEADER = "header"
        TYPE_DIVIDER = "divider"
        TYPE_SWITCH = "switch"
        TYPE_SELECTOR = "selector"
        TYPE_INPUT = "input"
        TYPE_TEXT = "text"
        TYPE_EDIT_TEXT = "edit_text"
        TYPE_CUSTOM = "custom"


@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[bool], None]] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_SWITCH, init=False)
    on_long_click: Optional[Callable] = field(default=None, compare=False, repr=False)
    link_alias: Optional[str] = None


@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: List[str]
    icon: Optional[str] = None
    on_change: Optional[Callable[[int], None]] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_SELECTOR, init=False)
    on_long_click: Optional[Callable] = field(default=None, compare=False, repr=False)
    link_alias: Optional[str] = None


@dataclass
class Input:
    key: str
    text: str
    default: Optional[str] = ""
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[str], None]] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_INPUT, init=False)
    on_long_click: Optional[Callable] = field(default=None, compare=False, repr=False)
    link_alias: Optional[str] = None


@dataclass
class Text:
    text: str
    subtext: Optional[str] = None
    icon: Optional[str] = None
    accent: bool = False
    red: bool = False
    on_click: Optional[Callable] = field(default=None, compare=False, repr=False)
    create_sub_fragment: Optional[Callable[[], List[Any]]] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_TEXT, init=False)
    on_long_click: Optional[Callable] = field(default=None, compare=False, repr=False)
    link_alias: Optional[str] = None


@dataclass
class Header:
    text: str
    type: str = field(default=PluginsConstants.Settings.TYPE_HEADER, init=False)


@dataclass
class Divider:
    text: Optional[str] = None
    type: str = field(default=PluginsConstants.Settings.TYPE_DIVIDER, init=False)


@dataclass
class EditText:
    key: str
    hint: str
    default: Optional[str] = ""
    multiline: bool = False
    max_length: int = 0
    mask: Optional[str] = None
    on_change: Optional[Callable[[str], None]] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_EDIT_TEXT, init=False)


@dataclass
class Custom:
    type: str = field(default=PluginsConstants.Settings.TYPE_CUSTOM, init=False)
    item: Any = field(default=None, compare=False, repr=False)
    view: Any = field(default=None, compare=False, repr=False)
    factory: Any = field(default=None, compare=False, repr=False)
    factory_args: Any = field(default=None, compare=False, repr=False)
    on_click: Optional[Callable] = field(default=None, compare=False, repr=False)
    on_long_click: Optional[Callable] = field(default=None, compare=False, repr=False)
    create_sub_fragment: Optional[Callable[[], List[Any]]] = field(default=None, compare=False, repr=False)
    link_alias: Optional[str] = None

    def __post_init__(self) -> None:
        if self.item is None and self.view is None and self.factory is None:
            raise ValueError("Custom setting needs item, view or factory")


def _log_error():
    _PluginsController.log("plugins", traceback.format_exc())


class _FactoryDelegate:
    """Called from the Java factory; each callback is optional."""

    def __init__(self, factory):
        self.factory = factory

    def create_view(self, context, list_view, current_account, class_guid, resources_provider):
        try:
            return self.factory.create_view(context, list_view, current_account, class_guid, resources_provider)
        except Exception:
            _log_error()
            return None

    def bind_view(self, view, item, divider, adapter, list_view):
        try:
            self.factory.bind_view(view, item, divider, adapter, list_view)
        except Exception:
            _log_error()

    def attached_view(self, list_view, view, item):
        if self.factory.attached_view is None:
            return
        try:
            self.factory.attached_view(list_view, view, item)
        except Exception:
            _log_error()

    def on_click(self, plugin, item, view):
        if self.factory.on_click is None:
            return
        try:
            self.factory.on_click(plugin, item, view)
        except Exception:
            _log_error()

    def on_long_click(self, plugin, item, view):
        if self.factory.on_long_click is None:
            return False
        try:
            return bool(self.factory.on_long_click(plugin, item, view))
        except Exception:
            _log_error()
            return False

    def create_item(self, plugin, setting, args):
        if self.factory.create_item is None:
            return None
        try:
            return self.factory.create_item(plugin, setting, args)
        except Exception:
            _log_error()
            return None

    def equals(self, a, b):
        try:
            return bool(self.factory.equals(a, b))
        except Exception:
            _log_error()
            return False

    def content_equals(self, a, b):
        try:
            return bool(self.factory.content_equals(a, b))
        except Exception:
            _log_error()
            return False


class _JavaHandle:
    def __init__(self, java):
        self.java = java


class SimpleSettingFactory:
    """Custom setting rows built from Python callbacks."""

    def __init__(self, create_view: Callable, bind_view: Callable, *, is_clickable: bool = False,
                 is_shadow: bool = False, create_item: Optional[Callable] = None, on_click: Optional[Callable] = None,
                 on_long_click: Optional[Callable] = None, attached_view: Optional[Callable] = None,
                 equals: Optional[Callable] = None, content_equals: Optional[Callable] = None):
        self.create_view = create_view
        self.bind_view = bind_view
        self.is_clickable = is_clickable
        self.is_shadow = is_shadow
        self.create_item = create_item
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.attached_view = attached_view
        self.equals = equals
        self.content_equals = content_equals
        self._delegate = _FactoryDelegate(self)
        java = _PluginItemFactory.Delegate(
            self._delegate, bool(is_clickable), bool(is_shadow), equals is not None, content_equals is not None)
        self.instance = _JavaHandle(java)

    def __call__(self, *args, create_sub_fragment=None, link_alias=None):
        return Custom(
            factory=self.instance.java,
            factory_args=list(args) if args else None,
            create_sub_fragment=create_sub_fragment,
            link_alias=link_alias,
        )

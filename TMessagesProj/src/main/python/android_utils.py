"""Small Android helpers: UI thread, listeners, logging and clipboard."""

import traceback
from typing import Any

from java import dynamic_proxy, jclass
from java.lang import Runnable

from android.view import View

_AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
_PluginsController = jclass("org.telegram.messenger.plugins.PluginsController")


def _plugin_tag():
    try:
        from extera_utils.get_caller import get_plugin_id

        return get_plugin_id() or "plugins"
    except Exception:
        return "plugins"


class _R(dynamic_proxy(Runnable)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        try:
            self.fn()
        except Exception:
            _PluginsController.log(_plugin_tag(), traceback.format_exc())


def R(fn):
    """A java.lang.Runnable that calls fn."""
    return _R(fn)


class OnClickListener(dynamic_proxy(View.OnClickListener)):
    def __init__(self, fn: callable):
        super().__init__()
        self.fn = fn

    def onClick(self, _view):
        try:
            self.fn(_view)
        except Exception:
            _PluginsController.log(_plugin_tag(), traceback.format_exc())


class OnLongClickListener(dynamic_proxy(View.OnLongClickListener)):
    def __init__(self, fn: callable):
        super().__init__()
        self.fn = fn

    def onLongClick(self, _view):
        try:
            return bool(self.fn(_view))
        except Exception:
            _PluginsController.log(_plugin_tag(), traceback.format_exc())
            return False


def run_on_ui_thread(func: callable, delay: int = 0):
    from client_utils import _wrap_scoped

    _AndroidUtilities.runOnUIThread(R(_wrap_scoped(func)), int(delay))


def log(data: Any):
    tag = _plugin_tag()
    if data is None or isinstance(data, (str, int, float, bool)):
        _PluginsController.log(tag, str(data))
    else:
        _PluginsController.logObject(tag, data)


def copy_to_clipboard(text: str):
    def copy():
        if _AndroidUtilities.addToClipboard(str(text)):
            try:
                from ui.bulletin import BulletinHelper

                BulletinHelper.show_copied_to_clipboard()
            except Exception:
                pass

    _AndroidUtilities.runOnUIThread(R(copy))

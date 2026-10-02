"""Telegram-style bulletins (snackbars) shown at the bottom of the current screen."""

from typing import Callable, Optional

from java import jclass

_PluginsBridge = jclass("org.telegram.messenger.plugins.PluginsBridge")
_Bulletin = jclass("org.telegram.ui.Components.Bulletin")


def _runnable(fn):
    if fn is None:
        return None
    from android_utils import R

    return R(fn)


def _show(kind, text="", subtitle=None, icon=0, button=None, on_button=None, on_action=None,
          fragment=None, duration=-1, amount=1, file_type=None):
    _PluginsBridge.showBulletin(
        fragment, kind, None if text is None else str(text), None if subtitle is None else str(subtitle),
        int(icon or 0), None if button is None else str(button), _runnable(on_button), _runnable(on_action),
        int(duration), int(amount), file_type,
    )


class BulletinHelper:
    DURATION_SHORT = int(_Bulletin.DURATION_SHORT)
    DURATION_LONG = int(_Bulletin.DURATION_LONG)
    DURATION_PROLONG = int(_Bulletin.DURATION_PROLONG)

    @classmethod
    def show_info(cls, message: str, fragment=None):
        _show("info", message, fragment=fragment)

    @classmethod
    def show_error(cls, message: str, fragment=None):
        _show("error", message, fragment=fragment)

    @classmethod
    def show_success(cls, message: str, fragment=None):
        _show("success", message, fragment=fragment)

    @classmethod
    def show_simple(cls, text: str, icon_res_id: int, fragment=None):
        _show("simple", text, icon=icon_res_id, fragment=fragment)

    @classmethod
    def show_two_line(cls, title: str, subtitle: str, icon_res_id: int, fragment=None):
        _show("two_line", title, subtitle=subtitle, icon=icon_res_id, fragment=fragment)

    @classmethod
    def show_with_button(cls, text: str, icon_res_id: int, button_text: str, on_click: Optional[Callable[[], None]],
                         fragment=None, duration: int = DURATION_LONG):
        _show("button", text, icon=icon_res_id, button=button_text, on_button=on_click, fragment=fragment,
              duration=duration)

    @classmethod
    def show_undo(cls, text: str, on_undo: Callable[[], None], on_action: Optional[Callable[[], None]] = None,
                  subtitle: Optional[str] = None, fragment=None):
        _show("undo", text, subtitle=subtitle, on_button=on_undo, on_action=on_action, fragment=fragment)

    @classmethod
    def show_copied_to_clipboard(cls, message: Optional[str] = None, fragment=None):
        _show("copied", message, fragment=fragment)

    @classmethod
    def show_link_copied(cls, is_private_link_info: bool = False, fragment=None):
        _show("link", None, fragment=fragment, amount=1 if is_private_link_info else 0)

    @classmethod
    def show_file_saved_to_gallery(cls, is_video: bool = False, amount: int = 1, fragment=None):
        _show("gallery", None, fragment=fragment, amount=amount, file_type="VIDEO" if is_video else "PHOTO")

    @classmethod
    def show_file_saved_to_downloads(cls, file_type_enum_name: str = "UNKNOWN", amount: int = 1, fragment=None):
        _show("downloads", None, fragment=fragment, amount=amount, file_type=file_type_enum_name)

"""A Pythonic wrapper over Telegram's AlertDialog.Builder."""

import traceback
from typing import Callable, Dict, List, Optional

from java import dynamic_proxy, jarray, jclass, jint
from java.lang import String as JString
from java.util import HashMap

from android.content import DialogInterface

_AlertDialog = jclass("org.telegram.ui.ActionBar.AlertDialog")
_Builder = jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")
_PluginsController = jclass("org.telegram.messenger.plugins.PluginsController")


def _log_error():
    _PluginsController.log("plugins", traceback.format_exc())


class _ButtonClickListenerProxy(dynamic_proxy(_AlertDialog.OnButtonClickListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.py_callable = py_callable
        self.builder_instance = builder_instance

    def onClick(self, dialog_java_instance, which):
        if self.py_callable is None:
            return
        try:
            self.py_callable(self.builder_instance, which)
        except Exception:
            _log_error()


class _ItemsClickListenerProxy(dynamic_proxy(DialogInterface.OnClickListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.py_callable = py_callable
        self.builder_instance = builder_instance

    def onClick(self, dialog_java_instance, which):
        if self.py_callable is None:
            return
        try:
            self.py_callable(self.builder_instance, which)
        except Exception:
            _log_error()


class _DismissListenerProxy(dynamic_proxy(DialogInterface.OnDismissListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.py_callable = py_callable
        self.builder_instance = builder_instance

    def onDismiss(self, dialog_java_instance):
        if self.py_callable is None:
            return
        try:
            self.py_callable(self.builder_instance)
        except Exception:
            _log_error()


class _CancelListenerProxy(dynamic_proxy(DialogInterface.OnCancelListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.py_callable = py_callable
        self.builder_instance = builder_instance

    def onCancel(self, dialog_java_instance):
        if self.py_callable is None:
            return
        try:
            self.py_callable(self.builder_instance)
        except Exception:
            _log_error()


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = int(_AlertDialog.ALERT_TYPE_MESSAGE)
    ALERT_TYPE_LOADING = int(_AlertDialog.ALERT_TYPE_LOADING)
    ALERT_TYPE_SPINNER = int(_AlertDialog.ALERT_TYPE_SPINNER)
    BUTTON_POSITIVE = int(DialogInterface.BUTTON_POSITIVE)
    BUTTON_NEGATIVE = int(DialogInterface.BUTTON_NEGATIVE)
    BUTTON_NEUTRAL = int(DialogInterface.BUTTON_NEUTRAL)

    def __init__(self, context, progress_style: int = ALERT_TYPE_MESSAGE, resources_provider=None):
        self._context = context
        self._builder = _Builder(context, int(progress_style), resources_provider)
        self._dialog = None
        self._listeners = []

    def _keep(self, listener):
        # Java holds the proxies weakly through Python, so keep them alive with the builder
        self._listeners.append(listener)
        return listener

    def get_context(self):
        return self._context

    def set_title(self, title: str) -> "AlertDialogBuilder":
        self._builder.setTitle(title)
        return self

    def set_message(self, message: str) -> "AlertDialogBuilder":
        self._builder.setMessage(message)
        return self

    def set_message_text_view_clickable(self, clickable: bool) -> "AlertDialogBuilder":
        self._builder.setMessageTextViewClickable(bool(clickable))
        return self

    def set_positive_button(self, text: str, listener: Optional[Callable] = None) -> "AlertDialogBuilder":
        self._builder.setPositiveButton(text, self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def set_negative_button(self, text: str, listener: Optional[Callable] = None) -> "AlertDialogBuilder":
        self._builder.setNegativeButton(text, self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def set_neutral_button(self, text: str, listener: Optional[Callable] = None) -> "AlertDialogBuilder":
        self._builder.setNeutralButton(text, self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def make_button_red(self, button_type: int) -> "AlertDialogBuilder":
        self._builder.makeRed(int(button_type))
        return self

    def set_on_back_button_listener(self, listener: Optional[Callable] = None) -> "AlertDialogBuilder":
        self._builder.setOnBackButtonListener(self._keep(_ButtonClickListenerProxy(listener, self)))
        return self

    def set_view(self, view, height: int = -2) -> "AlertDialogBuilder":
        self._builder.setView(view, int(height))
        return self

    def set_items(self, items: List[str], listener: Optional[Callable] = None,
                  icons: Optional[List[int]] = None) -> "AlertDialogBuilder":
        java_items = jarray(JString)([str(i) for i in items])
        proxy = self._keep(_ItemsClickListenerProxy(listener, self))
        if icons is not None:
            self._builder.setItems(java_items, jarray(jint)([int(i) for i in icons]), proxy)
        else:
            self._builder.setItems(java_items, proxy)
        return self

    def set_on_dismiss_listener(self, listener: Optional[Callable] = None) -> "AlertDialogBuilder":
        self._builder.setOnDismissListener(self._keep(_DismissListenerProxy(listener, self)))
        return self

    def set_on_cancel_listener(self, listener: Optional[Callable] = None) -> "AlertDialogBuilder":
        self._builder.setOnCancelListener(self._keep(_CancelListenerProxy(listener, self)))
        return self

    def set_top_image(self, res_id: int, background_color: int) -> "AlertDialogBuilder":
        self._builder.setTopImage(int(res_id), int(background_color))
        return self

    def set_top_drawable(self, drawable, background_color: int) -> "AlertDialogBuilder":
        self._builder.setTopImage(drawable, int(background_color))
        return self

    def set_top_animation(self, res_id: int, size: int, auto_repeat: bool, background_color: int,
                          layer_colors: Optional[Dict[str, int]] = None) -> "AlertDialogBuilder":
        if layer_colors:
            from java.lang import Integer

            java_map = HashMap()
            for key, value in layer_colors.items():
                java_map.put(str(key), Integer(int(value)))
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color), java_map)
        else:
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color))
        return self

    def set_top_animation_is_new(self, is_new: bool) -> "AlertDialogBuilder":
        self._builder.setTopAnimationIsNew(bool(is_new))
        return self

    def set_dim_enabled(self, enabled: bool) -> "AlertDialogBuilder":
        self._builder.setDimEnabled(bool(enabled))
        return self

    def set_dialog_button_color_key(self, theme_key: int) -> "AlertDialogBuilder":
        self._builder.setDialogButtonColorKey(int(theme_key))
        return self

    def set_blurred_background(self, blur: bool, blur_behind_if_possible: bool = True) -> "AlertDialogBuilder":
        self._builder.setBlurredBackground(bool(blur))
        return self

    def create(self) -> "AlertDialogBuilder":
        self._dialog = self._builder.create()
        return self

    def show(self) -> "AlertDialogBuilder":
        if self._dialog is None:
            self._dialog = self._builder.show()
        else:
            self._dialog.show()
        return self

    def dismiss(self) -> None:
        if self._dialog is not None:
            self._dialog.dismiss()

    def get_dialog(self):
        return self._dialog

    def get_button(self, button_type: int):
        if self._dialog is None:
            return None
        return self._dialog.getButton(int(button_type))

    def set_progress(self, progress: int):
        if self._dialog is not None:
            self._dialog.setProgress(int(progress))

    def set_cancelable(self, cancelable: bool):
        if self._dialog is not None:
            self._dialog.setCancelable(bool(cancelable))

    def set_canceled_on_touch_outside(self, cancel: bool):
        if self._dialog is not None:
            self._dialog.setCanceledOnTouchOutside(bool(cancel))

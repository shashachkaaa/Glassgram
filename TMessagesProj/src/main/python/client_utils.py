"""Helpers for queues, requests, controllers and sending or editing messages."""

import contextlib
import threading
import traceback
from typing import Any, Optional

from java import dynamic_proxy, jclass
from java.util import ArrayList

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

_Utilities = jclass("org.telegram.messenger.Utilities")
_AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
_UserConfig = jclass("org.telegram.messenger.UserConfig")
_AccountInstance = jclass("org.telegram.messenger.AccountInstance")
_MessagesController = jclass("org.telegram.messenger.MessagesController")
_ContactsController = jclass("org.telegram.messenger.ContactsController")
_MediaDataController = jclass("org.telegram.messenger.MediaDataController")
_ConnectionsManager = jclass("org.telegram.tgnet.ConnectionsManager")
_LocationController = jclass("org.telegram.messenger.LocationController")
_NotificationsController = jclass("org.telegram.messenger.NotificationsController")
_MessagesStorage = jclass("org.telegram.messenger.MessagesStorage")
_SendMessagesHelper = jclass("org.telegram.messenger.SendMessagesHelper")
_FileLoader = jclass("org.telegram.messenger.FileLoader")
_SecretChatHelper = jclass("org.telegram.messenger.SecretChatHelper")
_DownloadController = jclass("org.telegram.messenger.DownloadController")
_NotificationCenter = jclass("org.telegram.messenger.NotificationCenter")
_MediaController = jclass("org.telegram.messenger.MediaController")
_LaunchActivity = jclass("org.telegram.ui.LaunchActivity")
_PluginsController = jclass("org.telegram.messenger.plugins.PluginsController")
_PluginsBridge = jclass("org.telegram.messenger.plugins.PluginsBridge")
_RequestDelegate = jclass("org.telegram.tgnet.RequestDelegate")
_NotificationCenterDelegate = jclass("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate")

_scope = threading.local()
_warned = set()


def get_selected_account() -> int:
    return int(_UserConfig.selectedAccount)


def get_hook_account() -> Optional[int]:
    stack = getattr(_scope, "stack", None)
    return stack[-1] if stack else None


@contextlib.contextmanager
def account_scope(account: Optional[int]):
    stack = getattr(_scope, "stack", None)
    if stack is None:
        stack = _scope.stack = []
    stack.append(account)
    try:
        yield
    finally:
        stack.pop()


def _wrap_scoped(fn):
    """Carries the current account scope over to code that runs later on another thread."""
    account = get_hook_account()
    if account is None:
        return fn

    def scoped():
        with account_scope(account):
            return fn()

    return scoped


def _account(account: Optional[int], helper: str) -> int:
    if account is not None:
        return int(account)
    selected = get_selected_account()
    hook_account = get_hook_account()
    if hook_account is not None and hook_account != selected and helper not in _warned:
        _warned.add(helper)
        _PluginsController.log("plugins", (
            "%s() is using account %d because it is the one selected in the UI, but it was called while "
            "handling account %d. Pass account=%d, or use get_client(%d), if you meant that account. "
            "Logged once per helper." % (helper, selected, hook_account, hook_account, hook_account)))
    return selected


def get_queue_by_name(queue_name: str):
    if queue_name == PLUGINS_QUEUE:
        return _PluginsController.pluginsQueue
    try:
        return getattr(_Utilities, queue_name)
    except AttributeError:
        return None


def run_on_queue(fn: callable, queue_name: str = PLUGINS_QUEUE, delay: int = 0):
    from android_utils import R

    queue = get_queue_by_name(queue_name)
    if queue is None:
        raise ValueError("Unknown queue: %s" % queue_name)
    queue.postRunnable(R(_wrap_scoped(fn)), int(delay))


class RequestCallback(dynamic_proxy(_RequestDelegate)):
    def __init__(self, fn: callable, account: Optional[int] = None):
        super().__init__()
        self.fn = fn
        self.account = account

    def run(self, response, error):
        try:
            with account_scope(self.account):
                self.fn(response, error)
        except Exception:
            _PluginsController.log("plugins", traceback.format_exc())


def send_request(request: Any, fn: callable, *, account: Optional[int] = None) -> int:
    acc = _account(account, "send_request")
    callback = fn if isinstance(fn, RequestCallback) else RequestCallback(fn, acc)
    return int(_ConnectionsManager.getInstance(acc).sendRequest(request, callback))


def get_last_fragment():
    return _LaunchActivity.getSafeLastFragment()


def get_account_instance(account: Optional[int] = None):
    return _AccountInstance.getInstance(_account(account, "get_account_instance"))


def get_messages_controller(account: Optional[int] = None):
    return _MessagesController.getInstance(_account(account, "get_messages_controller"))


def get_contacts_controller(account: Optional[int] = None):
    return _ContactsController.getInstance(_account(account, "get_contacts_controller"))


def get_media_data_controller(account: Optional[int] = None):
    return _MediaDataController.getInstance(_account(account, "get_media_data_controller"))


def get_connections_manager(account: Optional[int] = None):
    return _ConnectionsManager.getInstance(_account(account, "get_connections_manager"))


def get_location_controller(account: Optional[int] = None):
    return _LocationController.getInstance(_account(account, "get_location_controller"))


def get_notifications_controller(account: Optional[int] = None):
    return _NotificationsController.getInstance(_account(account, "get_notifications_controller"))


def get_messages_storage(account: Optional[int] = None):
    return _MessagesStorage.getInstance(_account(account, "get_messages_storage"))


def get_send_messages_helper(account: Optional[int] = None):
    return _SendMessagesHelper.getInstance(_account(account, "get_send_messages_helper"))


def get_file_loader(account: Optional[int] = None):
    return _FileLoader.getInstance(_account(account, "get_file_loader"))


def get_secret_chat_helper(account: Optional[int] = None):
    return _SecretChatHelper.getInstance(_account(account, "get_secret_chat_helper"))


def get_download_controller(account: Optional[int] = None):
    return _DownloadController.getInstance(_account(account, "get_download_controller"))


def get_notifications_settings(account: Optional[int] = None):
    return _MessagesController.getNotificationsSettings(_account(account, "get_notifications_settings"))


def get_notification_center(account: Optional[int] = None):
    return _NotificationCenter.getInstance(_account(account, "get_notification_center"))


def get_media_controller():
    return _MediaController.getInstance()


def get_user_config(account: Optional[int] = None):
    return _UserConfig.getInstance(_account(account, "get_user_config"))


def _parse(text, parse_mode):
    """(text, Java entity list or None) for an optional parse_mode."""
    if text is None or parse_mode is None:
        return text, None
    from extera_utils.text_formatting import parse_text, to_tlrpc_entities

    parsed = parse_text(str(text), parse_mode)
    return parsed["message"], to_tlrpc_entities(parsed["entities"])


def _reply_object(acc: int, peer: int, reply):
    if reply is None or reply == 0:
        return None
    if isinstance(reply, int):
        return _PluginsBridge.replyMessageObject(acc, int(peer), int(reply))
    return reply


def _entities_list(value):
    if value is None:
        return ArrayList()
    if isinstance(value, (list, tuple)):
        from extera_utils.text_formatting import to_tlrpc_entities

        return to_tlrpc_entities(list(value))
    return value


def send_message(params: dict, parse_mode: str = None, *, account: Optional[int] = None):
    acc = _account(account, "send_message")
    params = dict(params)
    peer = int(params.pop("peer"))
    message = params.pop("message", None)
    caption = params.get("caption")
    entities = params.pop("entities", None)
    if parse_mode is not None:
        if message is not None:
            message, entities = _parse(message, parse_mode)
        elif caption is not None:
            params["caption"], entities = _parse(caption, parse_mode)
    send_params = _SendMessagesHelper.SendMessageParams.of(message, peer)
    send_params.entities = _entities_list(entities)
    if "replyToMsg" in params:
        send_params.replyToMsg = _reply_object(acc, peer, params.pop("replyToMsg"))
    if "replyToTopMsg" in params:
        send_params.replyToTopMsg = _reply_object(acc, peer, params.pop("replyToTopMsg"))
    for key, value in params.items():
        if key == "params" and isinstance(value, dict):
            from java.util import HashMap

            java_map = HashMap()
            for k, v in value.items():
                java_map.put(str(k), str(v))
            value = java_map
        setattr(send_params, key, value)

    def send():
        _SendMessagesHelper.getInstance(acc).sendMessage(send_params)

    from android_utils import run_on_ui_thread

    run_on_ui_thread(send)


def send_text(peer: int, text: str, *, account: Optional[int] = None, parse_mode: Optional[str] = None, **kwargs):
    params = {"peer": peer, "message": text}
    params.update(kwargs)
    send_message(params, parse_mode, account=_account(account, "send_text"))


def _send_media(kind: int, helper: str, peer: int, file_path: str, caption: str, account, parse_mode, kwargs,
                high_quality: bool = False):
    acc = _account(account, helper)
    text, entities = _parse(caption or "", parse_mode)
    if entities is None:
        entities = _entities_list(kwargs.get("entities"))
    reply = _reply_object(acc, peer, kwargs.get("replyToMsg"))
    _PluginsBridge.sendMedia(
        acc, int(peer), str(file_path), text or "", entities, int(kind), bool(high_quality), reply,
        bool(kwargs.get("notify", True)), int(kwargs.get("scheduleDate", 0)),
        bool(kwargs.get("hasMediaSpoilers", False)),
    )


def send_photo(peer: int, file_path: str, caption: str = "", high_quality: bool = False, *,
               account: Optional[int] = None, parse_mode: Optional[str] = None, **kwargs):
    _send_media(0, "send_photo", peer, file_path, caption, account, parse_mode, kwargs, high_quality)


def send_video(peer: int, file_path: str, caption: str = "", *, account: Optional[int] = None,
               parse_mode: Optional[str] = None, **kwargs):
    _send_media(1, "send_video", peer, file_path, caption, account, parse_mode, kwargs)


def send_document(peer: int, file_path: str, caption: str = "", *, account: Optional[int] = None,
                  parse_mode: Optional[str] = None, **kwargs):
    _send_media(2, "send_document", peer, file_path, caption, account, parse_mode, kwargs)


def send_audio(peer: int, file_path: str, caption: str = "", *, account: Optional[int] = None,
               parse_mode: Optional[str] = None, **kwargs):
    _send_media(3, "send_audio", peer, file_path, caption, account, parse_mode, kwargs)


def edit_message(message_obj: Any, text: Optional[str] = None, file_path: Optional[str] = None,
                 with_spoiler: bool = False, *, account: Optional[int] = None,
                 parse_mode: Optional[str] = None, **kwargs):
    if parse_mode is not None and str(parse_mode).lower() not in ("html", "markdown", "md", "markdownv2"):
        raise ValueError("Unsupported parse_mode: %s" % parse_mode)
    acc = int(account) if account is not None else int(message_obj.currentAccount)
    entities = None
    if text is not None:
        text, entities = _parse(text, parse_mode)
        if entities is None:
            entities = _entities_list(kwargs.get("entities"))
    _PluginsBridge.editMessage(acc, message_obj, text, entities, file_path, bool(with_spoiler))


class AccountClient:
    def __init__(self, account: int):
        self.account = int(account)

    def __eq__(self, other):
        return isinstance(other, AccountClient) and other.account == self.account

    def __hash__(self):
        return hash(self.account)

    def __repr__(self):
        return "AccountClient(%d)" % self.account

    def get_account_instance(self):
        return get_account_instance(self.account)

    def get_messages_controller(self):
        return get_messages_controller(self.account)

    def get_contacts_controller(self):
        return get_contacts_controller(self.account)

    def get_media_data_controller(self):
        return get_media_data_controller(self.account)

    def get_connections_manager(self):
        return get_connections_manager(self.account)

    def get_location_controller(self):
        return get_location_controller(self.account)

    def get_notifications_controller(self):
        return get_notifications_controller(self.account)

    def get_messages_storage(self):
        return get_messages_storage(self.account)

    def get_send_messages_helper(self):
        return get_send_messages_helper(self.account)

    def get_file_loader(self):
        return get_file_loader(self.account)

    def get_secret_chat_helper(self):
        return get_secret_chat_helper(self.account)

    def get_download_controller(self):
        return get_download_controller(self.account)

    def get_notifications_settings(self):
        return get_notifications_settings(self.account)

    def get_notification_center(self):
        return get_notification_center(self.account)

    def get_user_config(self):
        return get_user_config(self.account)

    def send_request(self, request: Any, fn: callable) -> int:
        return send_request(request, fn, account=self.account)

    def send_message(self, params: dict, parse_mode: str = None):
        return send_message(params, parse_mode, account=self.account)

    def send_text(self, peer: int, text: str, *, parse_mode: Optional[str] = None, **kwargs):
        return send_text(peer, text, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_photo(self, peer: int, file_path: str, caption: str = "", high_quality: bool = False, *,
                   parse_mode: Optional[str] = None, **kwargs):
        return send_photo(peer, file_path, caption, high_quality, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_document(self, peer: int, file_path: str, caption: str = "", *, parse_mode: Optional[str] = None, **kwargs):
        return send_document(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_video(self, peer: int, file_path: str, caption: str = "", *, parse_mode: Optional[str] = None, **kwargs):
        return send_video(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_audio(self, peer: int, file_path: str, caption: str = "", *, parse_mode: Optional[str] = None, **kwargs):
        return send_audio(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def edit_message(self, message_obj: Any, text: Optional[str] = None, file_path: Optional[str] = None,
                     with_spoiler: bool = False, *, parse_mode: Optional[str] = None, **kwargs):
        return edit_message(message_obj, text, file_path, with_spoiler, account=self.account,
                            parse_mode=parse_mode, **kwargs)


def get_client(account: Optional[int] = None) -> AccountClient:
    if account is None:
        account = get_hook_account()
    if account is None:
        account = get_selected_account()
    return AccountClient(account)


class NotificationCenterDelegate(dynamic_proxy(_NotificationCenterDelegate)):
    def __init__(self):
        super().__init__()

    def didReceivedNotification(self, id: int, account: int, args):
        pass

"""
What a plugin can do, read from its code before it is installed, as exteraGram shows it: what it
does to the device, to the app and to the account, each with the names it uses (the Telegram
requests it sends, the methods it hooks, the hosts it reaches) and the lines of code where it does
it. The code is only read (its syntax tree), never run: what it downloads or decrypts while
running can't be seen, and the dangerous ways of hiding code are what _glassgram_scan looks for.

analyze_source() gives a JSON text:
  {"level": "danger" | "warning" | "info" | "none",
   "groups": [["device" | "app" | "account", level], ...],
   "items": [{"id", "group", "level", "items": [names], "notes": [codes], "lines": [[n, code]], "more": n}]}
The app knows the ids and the notes, and words them.
"""

import ast
import json
import re

import _glassgram_scan

LEVELS = ("none", "info", "warning", "danger")
GROUPS = ("device", "app", "account")
_LINES_SHOWN = 6
_ITEMS_SHOWN = 12
_LINE_LENGTH = 110

# Every kind of thing found: its group and how much it matters
_KINDS = {
    # the device
    "network": ("device", "info"),
    "files": ("device", "info"),
    "files_delete": ("device", "warning"),
    "shell": ("device", "warning"),
    "native": ("device", "warning"),
    "other_apps": ("device", "warning"),
    "clipboard": ("device", "info"),
    "device_data": ("device", "warning"),
    # the app
    "restart": ("app", "warning"),
    "settings": ("app", "warning"),
    "hooks": ("app", "warning"),
    "request_hooks": ("app", "warning"),
    "message_hooks": ("app", "warning"),
    "plugins": ("app", "info"),
    "dynamic_code": ("app", "warning"),
    "blob": ("app", "warning"),
    "hidden_code": ("app", "danger"),
    "remote_code": ("app", "danger"),
    "bytecode": ("app", "danger"),
    "known": ("app", "danger"),
    "anti_review": ("app", "danger"),
    # the account
    "requests": ("account", "info"),
    "messages": ("account", "info"),
    "database": ("account", "warning"),
    "stars": ("account", "warning"),
    "gifts": ("account", "warning"),
    "chat_admin": ("account", "warning"),
    "security": ("account", "danger"),
    "session": ("account", "danger"),
}

# Telegram requests the app asks about before a plugin sends them (PluginActionGuard has the same)
SENSITIVE = {
    "stars": {
        "payments.sendStarsForm", "payments.sendPaymentForm", "messages.sendPaidReaction",
        "payments.fulfillStarsSubscription", "payments.changeStarsSubscription",
        "payments.sendStarGiftOffer", "payments.upgradeStarGift",
    },
    "gifts": {
        "payments.transferStarGift", "payments.convertStarGift", "payments.updateStarGiftPrice",
        "payments.resolveStarGiftOffer", "payments.craftStarGift", "payments.getStarGiftWithdrawalUrl",
    },
    "security": {
        "account.updatePasswordSettings", "account.getPasswordSettings", "auth.resetAuthorizations",
        "account.resetAuthorization", "account.setAuthorizationTTL", "account.changeAuthorizationSettings",
        "auth.acceptLoginToken", "auth.exportAuthorization", "account.deleteAccount", "account.changePhone",
        "account.sendChangePhoneCode", "account.setPrivacy", "account.resetPassword", "auth.logOut",
        "account.acceptAuthorization", "account.resetWebAuthorization", "account.resetWebAuthorizations",
        "account.registerPasskey", "account.deletePasskey", "account.sendVerifyEmailCode",
        "account.verifyEmail", "auth.recoverPassword", "auth.resetLoginEmail",
    },
    "chat_admin": {
        "channels.editCreator", "channels.editAdmin", "messages.editChatAdmin", "channels.editBanned",
        "messages.deleteChatUser", "channels.deleteChannel", "messages.deleteChat", "channels.deleteHistory",
        "channels.deleteParticipantHistory", "messages.editChatDefaultBannedRights", "channels.updateUsername",
        "messages.migrateChat", "channels.convertToGigagroup", "messages.deleteHistory",
    },
}

# The app's classes of Telegram's types, by the namespace of their requests
_TL_NAMESPACES = {"TL_stars": "payments", "TL_payments": "payments", "TL_account": "account"}
# A request is named for what it does: the types (messages.chatFull) are not requests
_VERBS = re.compile(
    r"^(get|send|set|edit|delete|update|toggle|search|resolve|report|read|save|upload|join|leave|create|"
    r"invite|export|import|check|request|reset|accept|transfer|convert|upgrade|install|uninstall|start|"
    r"block|unblock|add|remove|reorder|mark|fave|clear|change|confirm|register|cancel|hide|apply|assign|"
    r"launch|fulfill|craft|forward|pin|unpin|migrate|discard|load|log|recover|decline|verify|unregister|"
    r"approve|hide|view|translate|transcribe|rate|summarize|prolong|bind|unbind|deactivate|activate|"
    r"discard|purchase|buy|claim|exchange|refund|connect|toggle|readd|reconnect|close|open|finish|init)")

_NETWORK_MODULES = {"requests", "urllib", "urllib2", "urllib3", "http", "httpx", "aiohttp", "socket",
                    "websocket", "websockets", "ftplib", "smtplib"}
_NETWORK_CALLS = {"urlopen", "openConnection", "create_connection", "urlretrieve"}
_NETWORK_JAVA = ("java.net.", "okhttp3.", "javax.net.")
_FILE_CALLS = {"remove", "unlink", "rmtree", "rename", "replace", "makedirs", "mkdir", "rmdir", "copy",
               "copyfile", "copy2", "copytree", "move", "write_text", "write_bytes", "listdir", "walk",
               "scandir", "chmod", "write_file", "read_file", "delete_file", "ensure_dir_exists"}
_FILE_DELETE = {"remove", "unlink", "rmtree", "rmdir", "delete", "delete_file", "deleteRecursive"}
_FILE_JAVA = ("java.io.File", "java.io.FileOutputStream", "java.io.FileInputStream", "java.nio.file.")
_SHELL_CALLS = {"system", "popen", "execv", "execve", "execl", "execlp", "spawnl", "spawnv"}
_NATIVE_JAVA = ("DexClassLoader", "InMemoryDexClassLoader", "PathClassLoader", "BaseDexClassLoader")
_OTHER_APPS_CALLS = {"startActivity", "startActivityForResult", "sendBroadcast", "sendOrderedBroadcast",
                     "startService", "startForegroundService", "bindService", "createChooser", "openUrl",
                     "open_url", "openUri", "startIntentSender"}
_CLIPBOARD_CALLS = {"addToClipboard", "setPrimaryClip", "getPrimaryClip", "copy_to_clipboard"}
_DEVICE_DATA = ("ContactsContract", "LocationManager", "getLastKnownLocation", "requestLocationUpdates",
                "CameraManager", "android.hardware.Camera", "AudioRecord", "MediaRecorder", "SmsManager",
                "TelephonyManager", "ANDROID_ID", "AccountManager", "getInstalledPackages",
                "getInstalledApplications", "getRunningAppProcesses", "WifiManager", "getDeviceId", "getImei")
_RESTART_CALLS = {"killProcess", "restartApp", "restart_app", "finishAffinity", "triggerRebirth",
                  "_exit", "exitProcess"}
_PREFS_WRITE = {"putBoolean", "putInt", "putLong", "putString", "putFloat", "putStringSet"}
_CONFIGS = ("SharedConfig", "ExteraConfig", "GlassgramConfig", "NekoConfig", "BuildVars", "UserConfig")
_HOOK_CALLS = {"hook_method": "method", "hookMethod": "method", "hook_all_methods": "named",
               "hookAllMethods": "named", "hook_all_constructors": "constructors",
               "hookAllConstructors": "constructors", "find_and_hook_method": "find",
               "findAndHookMethod": "find", "hook_constructor": "method"}
_REQUEST_HOOKS = {"add_hook"}
_MESSAGE_HOOKS = {"add_on_send_message_hook"}
_SEND_REQUEST = {"send_request", "sendRequest", "sendRequestSync", "sendRequestTyped"}
_SEND_MESSAGE = {"send_message", "sendMessage", "send_text", "send_document", "send_photo", "send_video",
                 "edit_message", "forwardMessages", "send_media"}
_DATABASE = {"getDatabase", "executeFast", "queryFinalized", "executeInt", "getDatabaseFile"}
_CLASS_LOOKUPS = {"jclass", "find_class", "findClass", "forName", "loadClass", "findClassIfExists"}
_REFLECT_METHOD = {"getDeclaredMethod", "getMethod"}
_REFLECT_METHODS = {"getDeclaredMethods", "getMethods"}
_REFLECT_CONSTRUCTOR = {"getDeclaredConstructor", "getConstructor"}
_REFLECT_CONSTRUCTORS = {"getDeclaredConstructors", "getConstructors"}


def _dotted(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return _dotted(node.value) + "." + node.attr
    if isinstance(node, ast.Call):
        return _dotted(node.func) + "()"
    if isinstance(node, ast.Subscript):
        return _dotted(node.value) + "[]"
    return "?"


def _last(func):
    if isinstance(func, ast.Name):
        return func.id
    if isinstance(func, ast.Attribute):
        return func.attr
    return None


def _string(node):
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None


def _short_class(name):
    """org.telegram.ui.ArticleViewer$Sheet -> ArticleViewer.Sheet"""
    return name.rsplit(".", 1)[-1].replace("$", ".")


def _tl_request(class_name, outer=None):
    """payments.sendStarsForm for TLRPC.TL_payments_sendStarsForm, TL_stars.transferStarGift; None for a type."""
    if outer and outer != "TLRPC":
        namespace = _TL_NAMESPACES.get(outer)
        if namespace is None:
            if not outer.startswith("TL_"):
                return None
            namespace = outer[3:].lower()
        name = class_name[3:] if class_name.startswith("TL_") else class_name
        if name.startswith(namespace + "_"):
            name = name[len(namespace) + 1:]
        return namespace + "." + name if _VERBS.match(name) else None
    match = re.match(r"^TL_([a-z]+)_([A-Za-z0-9]+)$", class_name)
    if match is None:
        return None
    namespace, name = match.group(1), match.group(2)
    return namespace + "." + name if _VERBS.match(name) else None


class _Value:
    """What a name holds, as far as reading the code tells: a Java class, a method of it, methods of it."""

    def __init__(self, kind, cls=None, name=None):
        self.kind = kind
        self.cls = cls
        self.name = name


class _Analyzer(ast.NodeVisitor):

    def __init__(self, source):
        self.lines = source.splitlines()
        self.found = {}
        self.env = [{}]
        self.java_classes = set()
        self.modules = set()
        self.module_aliases = {}
        self.functions = []

    # What was found

    def _entry(self, kind):
        entry = self.found.get(kind)
        if entry is None:
            entry = self.found[kind] = {"items": [], "notes": [], "lines": [], "seen": set(), "more": 0}
        return entry

    def add(self, kind, node=None, item=None, note=None):
        entry = self._entry(kind)
        if item and item not in entry["items"]:
            entry["items"].append(item)
        if note and note not in entry["notes"]:
            entry["notes"].append(note)
        if node is not None and getattr(node, "lineno", None):
            line = node.lineno
            if line not in entry["seen"]:
                entry["seen"].add(line)
                if len(entry["lines"]) < _LINES_SHOWN:
                    entry["lines"].append([line, self._code(node)])
                else:
                    entry["more"] += 1

    def _code(self, node):
        text = self.lines[node.lineno - 1].strip() if 0 < node.lineno <= len(self.lines) else ""
        cut = len(text) > _LINE_LENGTH or (getattr(node, "end_lineno", node.lineno) or node.lineno) > node.lineno
        if len(text) > _LINE_LENGTH:
            text = text[:_LINE_LENGTH].rstrip()
        return text + (" …" if cut else "")

    # Names

    def lookup(self, name):
        for scope in reversed(self.env):
            if name in scope:
                return scope[name]
        return None

    def bind(self, target, value):
        """Gives a name what it now holds; None when that is not known, which hides what it held before."""
        if isinstance(target, ast.Name):
            self.env[-1][target.id] = value
        elif isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name) and target.value.id == "self":
            # self.x = ...: the class's methods share it
            self.env[0]["self." + target.attr] = value
        elif isinstance(target, (ast.Tuple, ast.List)):
            for element in target.elts:
                self.bind(element, None)

    def value_of(self, node):
        if isinstance(node, ast.Name):
            return self.lookup(node.id)
        if isinstance(node, ast.Attribute):
            if isinstance(node.value, ast.Name) and node.value.id == "self":
                return self.lookup("self." + node.attr)
            owner = self.value_of(node.value)
            if owner is not None and owner.kind == "class":
                if node.attr in ("class_", "_class"):
                    return owner
                if node.attr[:1].isupper():
                    # an inner class, as Chaquopy shows it
                    return _Value("class", owner.cls + "$" + node.attr)
            return None
        if isinstance(node, ast.Call):
            name = _last(node.func)
            if name in _CLASS_LOOKUPS and node.args:
                text = _string(node.args[0])
                if text:
                    return _Value("class", text.replace("/", "."))
                return None
            owner = self.value_of(node.func.value) if isinstance(node.func, ast.Attribute) else None
            if owner is None:
                return None
            if owner.kind == "class":
                if name == "getClass":
                    return owner
                if name in _REFLECT_METHOD:
                    text = _string(node.args[0]) if node.args else None
                    return _Value("method", owner.cls, text)
                if name in _REFLECT_CONSTRUCTOR:
                    return _Value("method", owner.cls, "<init>")
                if name in _REFLECT_METHODS:
                    return _Value("methods", owner.cls)
                if name in _REFLECT_CONSTRUCTORS:
                    return _Value("constructors", owner.cls)
            return None
        if isinstance(node, ast.Subscript):
            owner = self.value_of(node.value)
            if owner is not None and owner.kind == "methods":
                return _Value("method", owner.cls, None)
            if owner is not None and owner.kind == "constructors":
                return _Value("method", owner.cls, "<init>")
        return None

    # Statements, in order, so names hold what they were given before

    def visit_Import(self, node):
        for alias in node.names:
            self.modules.add(alias.name)
            self.modules.add(alias.name.split(".")[0])
            self.module_aliases[alias.asname or alias.name.split(".")[0]] = alias.name
            if alias.name.startswith(("java.", "android.", "org.telegram.", "com.exteragram.", "androidx.")):
                self.java_classes.add(alias.name)

    def visit_ImportFrom(self, node):
        module = node.module or ""
        self.modules.add(module)
        self.modules.add(module.split(".")[0])
        java = module.startswith(("java.", "android.", "androidx.", "org.", "com.", "kotlin.", "dalvik."))
        for alias in node.names:
            self.modules.add(alias.name)
            if java and module != "java":
                full = module + "." + alias.name
                self.java_classes.add(full)
                self.env[0][alias.asname or alias.name] = _Value("class", full)
            else:
                self.module_aliases[alias.asname or alias.name] = module + "." + alias.name

    def visit_Assign(self, node):
        self.generic_visit(node)
        value = self.value_of(node.value)
        for target in node.targets:
            self.bind(target, value)
            self._config_write(target, node)

    def visit_AnnAssign(self, node):
        self.generic_visit(node)
        if node.value is not None:
            self.bind(node.target, self.value_of(node.value))

    def visit_AugAssign(self, node):
        self.generic_visit(node)
        self._config_write(node.target, node)

    def _config_write(self, target, node):
        if isinstance(target, ast.Attribute):
            owner = _dotted(target.value)
            for config in _CONFIGS:
                if owner == config or owner.endswith("." + config):
                    self.add("settings", node, config)

    def visit_For(self, node):
        self.visit(node.iter)
        value = self.value_of(node.iter)
        if value is not None and value.kind in ("methods", "constructors"):
            self.bind(node.target, _Value("method", value.cls, "<init>" if value.kind == "constructors" else None))
        else:
            self.bind(node.target, None)
        for statement in node.body + node.orelse:
            self.visit(statement)

    def _function(self, node):
        self.env.append({})
        self.functions.append(node)
        for statement in node.body:
            self.visit(statement)
        self.functions.pop()
        self.env.pop()

    def visit_FunctionDef(self, node):
        for decorator in node.decorator_list:
            self.visit(decorator)
        self._function(node)

    visit_AsyncFunctionDef = visit_FunctionDef

    def visit_Lambda(self, node):
        self.visit(node.body)

    # Calls

    def visit_Call(self, node):
        self.generic_visit(node)
        name = _last(node.func)
        dotted = _dotted(node.func)
        if name is None:
            return
        root = dotted.split(".")[0]
        root_module = self.module_aliases.get(root, root)

        # Java classes named by text
        if name in _CLASS_LOOKUPS and node.args and _string(node.args[0]):
            self.java_classes.add(_string(node.args[0]))

        # the device
        if root_module.split(".")[0] in _NETWORK_MODULES and name not in ("quote", "unquote", "urlencode", "urlparse", "urljoin"):
            self.add("network", node)
        elif name in _NETWORK_CALLS:
            self.add("network", node)
        if name == "open" and isinstance(node.func, ast.Name):
            self.add("files", node, _string(node.args[0]) if node.args else None)
        elif name in _FILE_CALLS and root_module.split(".")[0] in ("os", "shutil", "pathlib", "file_utils", "Path"):
            self.add("files_delete" if name in _FILE_DELETE else "files", node, _string(node.args[0]) if node.args else None)
        elif name in _FILE_DELETE and isinstance(node.func, ast.Attribute) and "File" in dotted:
            self.add("files_delete", node)
        if root_module.split(".")[0] in ("subprocess", "pty") or (root_module == "os" and name in _SHELL_CALLS) \
                or (name == "exec" and "Runtime" in dotted) or name == "ProcessBuilder":
            self.add("shell", node)
        if name in ("loadLibrary", "load") and dotted.endswith(("System.loadLibrary", "System.load")):
            self.add("native", node, _string(node.args[0]) if node.args else None)
        if name in _OTHER_APPS_CALLS:
            self.add("other_apps", node)
        if name == "Intent" or dotted.endswith(".Intent"):
            action = _string(node.args[0]) if node.args else None
            if node.args and isinstance(node.args[0], ast.Attribute) and node.args[0].attr.startswith("ACTION_"):
                action = node.args[0].attr
            self.add("other_apps", node, action)
        if name in _CLIPBOARD_CALLS:
            self.add("clipboard", node)
        if name in _RESTART_CALLS or (name == "exit" and dotted.endswith("System.exit")):
            self.add("restart", node, self._call_label(node.func, dotted, name))

        # the app
        if name == "edit" and not node.args and isinstance(node.func, ast.Attribute):
            # the app's preferences opened to be written
            owner = node.func.value
            prefs = _string(owner.args[0]) if isinstance(owner, ast.Call) and _last(owner.func) == "getSharedPreferences" and owner.args else None
            self.add("settings", node, prefs)
        elif name in _PREFS_WRITE and "edit()" in dotted:
            self.add("settings", node)
        elif any(dotted.startswith(config + ".") or ("." + config + ".") in dotted for config in _CONFIGS) \
                and re.match(r"^(save|toggle|set|put|clear|reset|remove)", name):
            self.add("settings", node, dotted.split(".")[-2] if dotted.count(".") else None)
        if name in _HOOK_CALLS:
            self._hook(node, _HOOK_CALLS[name])
        if name in _REQUEST_HOOKS and node.args:
            self.add("request_hooks", node, _string(node.args[0]))
        if name in _MESSAGE_HOOKS:
            self.add("message_hooks", node)
        if "PluginsController" in dotted or name in ("get_plugin", "getPlugin", "get_plugins"):
            self.add("plugins", node)

        # the account
        if name in _SEND_REQUEST:
            self.add("requests", node)
            if node.args:
                self._request_argument(node.args[0], node)
        if name in _SEND_MESSAGE or dotted.endswith("SendMessagesHelper.getInstance"):
            self.add("messages", node)
        if name in _DATABASE:
            self.add("database", node)
        if "StarsController" in dotted and re.match(r"^(pay|buy|send|transfer|convert|upgrade)", name):
            self.add("stars", node, "StarsController." + name)

        # Telegram's requests, made: TLRPC.TL_payments_sendStarsForm(), TL_stars.transferStarGift()
        request = self._tl_constructor(node.func)
        if request is not None:
            self.add("requests", None, request)
            for kind, names in SENSITIVE.items():
                if request in names:
                    self.add(kind, node, request)

    def _call_label(self, func, dotted, name):
        """Process.killProcess for the call's function: its class's name, or the name before it."""
        if isinstance(func, ast.Attribute):
            owner = self.value_of(func.value)
            if owner is not None and owner.kind == "class":
                return _short_class(owner.cls) + "." + name
            parts = dotted.split(".")
            if len(parts) >= 2 and "()" not in parts[-2]:
                return parts[-2] + "." + name
        return name

    def _tl_constructor(self, func):
        if isinstance(func, ast.Attribute):
            outer = func.value.attr if isinstance(func.value, ast.Attribute) else func.value.id if isinstance(func.value, ast.Name) else None
            if func.attr.startswith("TL_") or (outer or "").startswith("TL_"):
                return _tl_request(func.attr, outer)
        elif isinstance(func, ast.Call) and _last(func.func) in _CLASS_LOOKUPS and func.args and _string(func.args[0]):
            # jclass("org.telegram.tgnet.TLRPC$TL_contacts_resolveUsername")()
            text = _string(func.args[0]).rsplit(".", 1)[-1]
            if "$" in text:
                outer, inner = text.split("$", 1)
                return _tl_request(inner, outer)
        elif isinstance(func, ast.Name) and func.id.startswith("TL_"):
            value = self.lookup(func.id)
            if value is not None and value.kind == "class" and "$" in value.cls:
                outer, inner = value.cls.rsplit(".", 1)[-1].split("$", 1)
                return _tl_request(inner, outer)
            return _tl_request(func.id)
        return None

    def _request_argument(self, argument, node):
        """The request a send_request sends, when the code says which."""
        if isinstance(argument, ast.Call):
            request = self._tl_constructor(argument.func)
            if request is not None:
                self.add("requests", None, request)

    def _hook(self, node, how):
        args = node.args
        target = None
        if how == "method" and args:
            value = self.value_of(args[0])
            if value is not None and value.kind == "method":
                target = _short_class(value.cls) + "." + (value.name or self._compared_name() or "*")
                if value.name == "<init>":
                    target = _short_class(value.cls) + " (constructor)"
        elif how == "named" and len(args) >= 2:
            value = self.value_of(args[0])
            text = _string(args[1])
            if value is not None and value.kind == "class" and text:
                target = _short_class(value.cls) + "." + text
        elif how == "constructors" and args:
            value = self.value_of(args[0])
            if value is not None and value.kind == "class":
                target = _short_class(value.cls) + " (constructors)"
        elif how == "find" and len(args) >= 2:
            cls = _string(args[0])
            if cls is None:
                value = self.value_of(args[0])
                cls = value.cls if value is not None and value.kind == "class" else None
            text = _string(args[1])
            if cls and text:
                target = _short_class(cls) + "." + text
        if target:
            self.add("hooks", node, target)
        else:
            self.add("hooks", node, note="hooks_unresolved")

    def _compared_name(self):
        """The method name a loop over a class's methods picks (if m.getName() == "dismiss")."""
        if not self.functions:
            return None
        names = []
        for inner in ast.walk(self.functions[-1]):
            if isinstance(inner, ast.Compare) and isinstance(inner.left, ast.Call) and _last(inner.left.func) == "getName":
                for comparator in inner.comparators:
                    if _string(comparator):
                        names.append(_string(comparator))
                    elif isinstance(comparator, (ast.Tuple, ast.List, ast.Set)):
                        names.extend(s for s in (_string(e) for e in comparator.elts) if s)
        names = sorted(set(names))
        if not names:
            return None
        return names[0] if len(names) == 1 else "{" + ", ".join(names) + "}"


def _texts(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            yield node


def analyze_source(source):
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        return json.dumps({"level": "warning", "groups": [["app", "warning"]],
                           "items": [{"id": "syntax", "group": "app", "level": "warning", "items": [str(e.msg)],
                                      "notes": [], "lines": [[e.lineno or 0, (e.text or "").strip()[:_LINE_LENGTH]]],
                                      "more": 0}]})
    analyzer = _Analyzer(source)
    for statement in tree.body:
        analyzer.visit(statement)

    # Java classes used by name: the network, files, the device's data, loading code
    for cls in analyzer.java_classes:
        if cls.startswith(_NETWORK_JAVA):
            analyzer.add("network", None, None)
        if cls.startswith(_FILE_JAVA):
            analyzer.add("files", None, None)
        if any(part in cls for part in _NATIVE_JAVA):
            analyzer.add("native", None, _short_class(cls))
        if any(part in cls for part in _DEVICE_DATA):
            analyzer.add("device_data", None, _short_class(cls))
    for node in _texts(tree):
        for part in _DEVICE_DATA:
            if part in node.value and len(node.value) < 200:
                analyzer.add("device_data", node, part)
                break
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and node.attr in _DEVICE_DATA:
            analyzer.add("device_data", node, node.attr)
    if analyzer.modules & {"ctypes", "cffi"}:
        analyzer.add("native", None, "ctypes")
    if "network" in analyzer.found or analyzer.modules & _NETWORK_MODULES:
        hosts = []
        for node in _texts(tree):
            for host in _glassgram_scan._HOST.findall(node.value):
                host = host.lower().strip(".")
                if host and host not in hosts:
                    hosts.append(host)
        for host in hosts:
            analyzer.add("network", None, host)
        if not analyzer.found.get("network"):
            analyzer.add("network")

    # How it may hide code, as the check before had it
    for finding in _glassgram_scan.scan_source(source):
        level, code, detail = (finding.split("|", 2) + ["", ""])[:3]
        if code in _KINDS:
            analyzer.add(code, None, detail or None)
        elif code == "native_code":
            analyzer.add("native")
        elif code == "shell":
            analyzer.add("shell")

    # Sent requests that are also hooked or sensitive are told with them: the plain list is the rest
    entry = analyzer.found.get("requests")
    if entry is not None:
        entry["items"].sort()

    items = []
    group_levels = {}
    top = "none"
    for kind, entry in analyzer.found.items():
        group, level = _KINDS.get(kind, ("app", "info"))
        if kind == "requests" and not entry["lines"] and not entry["items"]:
            continue
        shown = entry["items"][:_ITEMS_SHOWN]
        if len(entry["items"]) > _ITEMS_SHOWN:
            shown.append("+%d" % (len(entry["items"]) - _ITEMS_SHOWN))
        items.append({"id": kind, "group": group, "level": level, "items": shown, "notes": entry["notes"],
                      "lines": entry["lines"], "more": entry["more"]})
        if LEVELS.index(level) > LEVELS.index(group_levels.get(group, "none")):
            group_levels[group] = level
        if LEVELS.index(level) > LEVELS.index(top):
            top = level
    items.sort(key=lambda i: (-LEVELS.index(i["level"]), GROUPS.index(i["group"]), i["id"]))
    return json.dumps({
        "level": top,
        "groups": [[group, group_levels[group]] for group in GROUPS if group in group_levels],
        "items": items,
    }, ensure_ascii=False)


def analyze_file(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return analyze_source(f.read())

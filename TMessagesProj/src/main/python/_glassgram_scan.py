"""
What a plugin's code may do that one should know before installing it: run code hidden in it or
downloaded, read Telegram's session files, match a known malicious loader. The code is only read
(its syntax tree and its strings), never run, so whatever it hides is found by how it hides it.

A finding is "level|code|detail": level "danger" or "warning", code one the install dialog knows,
detail a name or a host to show with it ("" when none).
"""

import ast
import re

# A module of these decodes data: run code with one, and the code was hidden.
_DECODERS = {"base64", "binascii", "zlib", "lzma", "bz2", "gzip", "marshal", "codecs", "zipimport"}
_NETWORK = {"urllib", "urllib2", "urllib3", "requests", "httpx", "aiohttp", "http", "socket", "ftplib", "websocket"}
_NETWORK_JAVA = ("java.net.URL", "java.net.Socket", "HttpURLConnection", "okhttp3.")
_SHELL = {("os", "system"), ("os", "popen"), ("os", "execv"), ("os", "execve"), ("os", "spawnl")}
_DEX = ("InMemoryDexClassLoader", "DexClassLoader", "PathClassLoader", "BaseDexClassLoader")
# Telegram's session (its auth keys) and its config, read as files: what a stolen account is made of.
_SESSION = ("tgnet.dat", "userconfing.xml", "userconfig.xml", "auth_key", "authKey", "getAuthKey",
            "datacenters", "cache4.db")
# A loader seen spread in repacked plugins: it downloads and runs code from its server on load.
_KNOWN = ("silent-team", "_ld_open", "ld-plugin|", "_sm_singleton", "ld_boot.txt", "/v1/stage?code=")
# Text asking AI reviewers not to look into the code: nothing honest hides that way.
_ANTI_REVIEW = re.compile(
    r"\b(LLM|AI|GPT|assistant|agent)s?\b.{0,200}?\b(must not|do not|don't|never|refuse)\b.{0,120}?"
    r"(deobfuscat|decompil|disassembl|reverse[- ]?engineer)",
    re.IGNORECASE | re.DOTALL)
_BLOB = re.compile(r"[A-Za-z0-9+/=\r\n]{2000,}|[0-9A-Za-z!#$%&()*+\-;<=>?@^_`{|}~]{2000,}")
_HOST = re.compile(r"https?://([A-Za-z0-9.\-]+)")
_HOSTS_SHOWN = 4


def _module_names(tree):
    """The modules imported, with their packages, and the names imported from them."""
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                names.add(alias.name)
        elif isinstance(node, ast.ImportFrom) and node.module:
            names.add(node.module)
            for alias in node.names:
                names.add(alias.name)
        elif (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "__import__"
              and node.args and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str)):
            names.add(node.args[0].value)
    roots = set()
    for name in names:
        roots.add(name)
        roots.add(name.split(".")[0])
    return roots


def _strings(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            yield node.value


def _call_name(func):
    if isinstance(func, ast.Name):
        return None, func.id
    if isinstance(func, ast.Attribute):
        owner = func.value.id if isinstance(func.value, ast.Name) else None
        return owner, func.attr
    return None, None


def _dynamic_code(tree):
    """Whether the code runs code it makes while running (exec, eval, compile of no literal)."""
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        owner, name = _call_name(node.func)
        if name in ("exec", "eval", "compile") and owner in (None, "builtins", "__builtins__"):
            if node.args and not (isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str)):
                return True
        # getattr(builtins, "exec") and the like
        if name == "getattr" and len(node.args) >= 2 and isinstance(node.args[1], ast.Constant) \
                and node.args[1].value in ("exec", "eval", "compile"):
            return True
    return False


def _bytecode(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            owner, name = _call_name(node.func)
            if (owner, name) in (("marshal", "loads"), ("types", "CodeType"), ("types", "FunctionType")):
                return True
    return False


def _shell(tree, modules):
    if "subprocess" in modules or "pty" in modules:
        return True
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and _call_name(node.func) in _SHELL:
            return True
    return False


def scan_source(source):
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    findings = []

    def add(level, code, detail=""):
        findings.append("%s|%s|%s" % (level, code, detail))

    modules = _module_names(tree)
    strings = list(_strings(tree))
    text = "\n".join(strings)

    for marker in _KNOWN:
        if marker in source:
            add("danger", "known", marker)
            break
    if _ANTI_REVIEW.search(text):
        add("danger", "anti_review")

    dynamic = _dynamic_code(tree)
    decodes = bool(modules & _DECODERS)
    network = bool(modules & _NETWORK) or any(name in text for name in _NETWORK_JAVA)
    if dynamic and decodes:
        add("danger", "hidden_code")
    if dynamic and network:
        add("danger", "remote_code")
    if dynamic and not decodes and not network:
        add("warning", "dynamic_code")
    if _bytecode(tree):
        add("danger", "bytecode")

    for name in _SESSION:
        if name in text:
            add("danger", "session", name)
            break

    if any(name in text or name in modules for name in _DEX):
        add("warning", "native_code")
    if _shell(tree, modules):
        add("warning", "shell")
    if any(_BLOB.search(s) for s in strings if len(s) >= 2000):
        add("warning", "blob")

    if findings:
        hosts = []
        for s in strings:
            for host in _HOST.findall(s):
                host = host.lower().strip(".")
                if host and host not in hosts:
                    hosts.append(host)
        if hosts:
            shown = ", ".join(hosts[:_HOSTS_SHOWN])
            if len(hosts) > _HOSTS_SHOWN:
                shown += " +%d" % (len(hosts) - _HOSTS_SHOWN)
            add("info", "hosts", shown)
    return findings


def scan_file(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return scan_source(f.read())

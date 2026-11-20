"""ADB development transport. Android shell/root credentials are checked on every connection."""
import base64
import codecs
import hashlib
import json
import os
import socket
import struct
import tempfile
import threading
import zipfile
import zlib

_listener = None
_peer = None
_debugging = False
_limit = 16 * 1024**2


def stop_debugger():
    global _debugging
    if _debugging:
        import debugpy._vendored.force_pydevd
        import pydevd
        pydevd.stoptrace()
        _debugging = False


def configure(enabled):
    global _listener
    if not enabled:
        old, _listener = _listener, None
        for sock in (_peer, old):
            if sock:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()
        stop_debugger()
    elif _listener is None:
        from java import jclass
        package = jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getPackageName()
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.bind("\0" + str(package) + ".plugins")
            sock.listen(1)
        except BaseException:
            sock.close()
            raise
        _listener = sock
        threading.Thread(target=serve, args=(sock,), daemon=True, name="PluginDevServer").start()
    return _listener is not None


def serve(listener):
    global _peer
    import _glassgram_engine as engine
    while _listener is listener:
        try:
            conn, _ = listener.accept()
            with conn:
                _, uid, _ = struct.unpack("3i", conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                if uid not in (0, 2000):
                    continue
                _peer = conn
                conn.settimeout(120)
                decoder = codecs.getincrementaldecoder("utf-8")()
                pending = ""
                while _listener is listener:
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    pending += decoder.decode(chunk)
                    if len(pending) > _limit:
                        break
                    pending = pending.lstrip()
                    while pending:
                        try:
                            request, end = json.JSONDecoder().raw_decode(pending)
                        except ValueError:
                            break
                        pending = pending[end:].lstrip()
                        if not isinstance(request, dict):
                            raise ValueError("Expected JSON object")
                        with engine._lock:
                            if _listener is not listener:
                                return
                            try:
                                response = dispatch(engine, request)
                            except Exception as exc:
                                response = {"ok": False, "error": str(exc)}
                        response["#"] = request.get("#")
                        conn.sendall(json.dumps(response).encode())
        except (OSError, ValueError):
            pass
        finally:
            if _listener in (None, listener):
                _peer = None


def dispatch(engine, request):
    global _debugging
    cmd = request["@"]
    if cmd == "ping":
        return {"pong": True}
    if cmd == "elyx_ping":
        return {"ok": True, "elyx": True}
    if cmd in ("get_plugins", "get_elyx_plugins"):
        return {"ok": True, "plugins": [dict(r.meta, enabled=r.enabled, loaded=r.instance is not None,
                                            error=r.error) for r in engine._records.values()
                                      if cmd == "get_plugins" or r.environment]}
    if cmd == "start_debugger":
        if request.get("host", "127.0.0.1") not in ("localhost", "127.0.0.1"):
            raise ValueError("Use adb reverse for the IDE connection")
        import debugpy
        debugpy.connect(("127.0.0.1", int(request.get("port", 5678))))
        _debugging = True
        return {"ok": True}
    if cmd == "stop_debugger":
        stop_debugger()
        return {"ok": True}
    if cmd in ("elyx_changes", "elyx_compare_folder") and "data" in request:
        compressed = base64.b64decode(request["data"], validate=True)
        decoder = zlib.decompressobj(47)
        raw = decoder.decompress(compressed, _limit)
        if not decoder.eof or decoder.unused_data:
            raise ValueError("Invalid or oversized change batch")
        request = json.loads(raw)
    plugin_id = request["plugin_id"]
    record = engine._records.get(plugin_id)
    if cmd == "write_plugin":
        with tempfile.NamedTemporaryFile(dir=engine._plugins_dir) as f:
            f.write(request["content"].encode())
            f.flush()
            if engine._read_meta(f.name)["id"] != plugin_id:
                raise ValueError("Plugin id mismatch")
            engine.install(f.name)
    elif record is None:
        raise ValueError("Unknown plugin")
    elif cmd in ("enable_plugin", "disable_plugin"):
        engine.set_enabled(plugin_id, cmd == "enable_plugin")
    elif cmd == "reload_plugin":
        engine.load_from_file(plugin_id)
    elif cmd == "remove_plugin":
        engine.uninstall(plugin_id)
    elif cmd in ("elyx_changes", "elyx_compare_folder"):
        if not record.environment:
            raise ValueError("Enable the Elyx plugin first")
        files = dict(record.environment.archive.files)
        if cmd == "elyx_compare_folder":
            hashes = request["hashes"]
            return {"ok": True, "created_or_modified": [k for k in hashes if k not in files or
                    hashlib.sha256(files[k]).hexdigest() != hashes[k]], "deleted": sorted(files.keys() - hashes.keys())}
        from _glassgram_elyx import safe_name
        for change in request["changes"]:
            path = safe_name(change["path"])
            kind = change["#"]
            if kind in ("deleted", "moved"):
                dest = safe_name(change["dest_path"]) if kind == "moved" else None
                for key in list(files):
                    if key == path or key.startswith(path + "/"):
                        value = files.pop(key)
                        if dest:
                            files[dest + key[len(path):]] = value
            elif kind in ("created", "modified"):
                if not change.get("is_directory"):
                    files[path] = base64.b64decode(change["content"], validate=True)
            else:
                raise ValueError("Unknown change type")
        with tempfile.NamedTemporaryFile(dir=engine._plugins_dir) as f:
            with zipfile.ZipFile(f, "w", zipfile.ZIP_DEFLATED) as out:
                for key, data in files.items():
                    out.writestr(key, data)
            f.flush()
            if engine._read_meta(f.name)["id"] != plugin_id:
                raise ValueError("Plugin id mismatch")
            engine.install(f.name)
    else:
        raise ValueError("Unknown command")
    record = engine._records.get(plugin_id)
    if record and record.error:
        raise RuntimeError(record.error)
    return {"ok": True, "success": True, "reloaded": cmd == "elyx_changes", "errors": []}

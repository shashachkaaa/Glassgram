"""Persistent per-plugin settings, kept as one JSON file per plugin."""

import json
import os
import threading
from typing import Any

_lock = threading.RLock()
_dir = None


class _Cache(dict):
    """The settings by plugin id, each read from its file when first asked for. Plugins made for
    exteraGram reach into it as exteraGram's _settings_cache (zwylib removes keys there)."""

    def __contains__(self, plugin_id):
        return dict.__contains__(self, plugin_id) or (_dir is not None and os.path.exists(_path(plugin_id)))

    def __missing__(self, plugin_id):
        return _load(plugin_id)


_cache = _Cache()
_settings_cache = _cache


def init(plugins_dir_path: str, all_shared_prefs=None):
    global _dir
    with _lock:
        _dir = os.path.join(plugins_dir_path, "settings")
        os.makedirs(_dir, exist_ok=True)
        _cache.clear()


def _path(plugin_id: str) -> str:
    return os.path.join(_dir, plugin_id + ".json")


def _load(plugin_id: str) -> dict:
    data = _cache.get(plugin_id)
    if data is not None:
        return data
    data = {}
    if _dir is not None:
        try:
            with open(_path(plugin_id), "r", encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                data = loaded
        except FileNotFoundError:
            pass
        except Exception:
            data = {}
    _cache[plugin_id] = data
    return data


def _save(plugin_id: str):
    if _dir is None:
        return
    data = _cache.get(plugin_id, {})
    tmp = _path(plugin_id) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, _path(plugin_id))


def _save_settings_to_file():
    """Writes every plugin's settings read so far, as exteraGram's of the same name."""
    with _lock:
        for plugin_id in list(_cache.keys()):
            _save(plugin_id)


def get_setting(plugin_id: str, key: str, default: Any) -> Any:
    with _lock:
        return _load(plugin_id).get(key, default)


def set_setting(plugin_id: str, key: str, value: Any):
    with _lock:
        _load(plugin_id)[key] = value
        _save(plugin_id)


def clear_settings(plugin_id: str):
    with _lock:
        _cache[plugin_id] = {}
        try:
            os.remove(_path(plugin_id))
        except OSError:
            pass


def get_all_settings(plugin_id: str) -> Any:
    with _lock:
        return dict(_load(plugin_id))


def set_all_settings(plugin_id: str, settings: dict):
    with _lock:
        _cache[plugin_id] = dict(settings or {})
        _save(plugin_id)

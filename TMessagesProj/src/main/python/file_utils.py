"""Plugin directories and simple file helpers."""

import os
import secrets
import threading
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, List, Optional

from java import jclass

_ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")
_FileLoader = jclass("org.telegram.messenger.FileLoader")
_PluginsController = jclass("org.telegram.messenger.plugins.PluginsController")


def get_plugins_dir() -> str:
    return str(_PluginsController.getPluginsDir().getAbsolutePath())


def get_cache_dir() -> str:
    return str(_ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath())


def get_files_dir() -> str:
    return str(_ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath())


def _media_dir(kind: int) -> str:
    directory = _FileLoader.getDirectory(kind)
    return str(directory.getAbsolutePath()) if directory is not None else get_cache_dir()


def get_images_dir() -> str:
    return _media_dir(_FileLoader.MEDIA_DIR_IMAGE)


def get_videos_dir() -> str:
    return _media_dir(_FileLoader.MEDIA_DIR_VIDEO)


def get_audios_dir() -> str:
    return _media_dir(_FileLoader.MEDIA_DIR_AUDIO)


def get_documents_dir() -> str:
    return _media_dir(_FileLoader.MEDIA_DIR_DOCUMENT)


def read_file(file_path: str) -> Optional[str]:
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return None


def read_file_bytes(file_path: str) -> Optional[bytes]:
    try:
        with open(file_path, "rb") as f:
            return f.read()
    except Exception:
        return None


def write_file(file_path: str, content: str):
    ensure_dir_exists(os.path.dirname(file_path))
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)


def write_file_bytes(file_path: str, content: bytes):
    ensure_dir_exists(os.path.dirname(file_path))
    with open(file_path, "wb") as f:
        f.write(content)


def delete_file(file_path: str) -> bool:
    try:
        os.remove(file_path)
        return True
    except OSError:
        return False


def ensure_dir_exists(dir_path: str):
    if dir_path:
        os.makedirs(dir_path, exist_ok=True)


def list_dir(path: str, recursive: bool = False, include_files: bool = True, include_dirs: bool = False,
             extensions: Optional[List[str]] = None) -> List[str]:
    result = []
    exts = tuple(e.lower() if e.startswith(".") else "." + e.lower() for e in extensions) if extensions else None

    def accept_file(name):
        return include_files and (exts is None or name.lower().endswith(exts))

    if recursive:
        for root, dirs, files in os.walk(path):
            if include_dirs:
                result.extend(os.path.join(root, d) for d in dirs)
            result.extend(os.path.join(root, f) for f in files if accept_file(f))
    else:
        try:
            for name in os.listdir(path):
                full = os.path.join(path, name)
                if os.path.isdir(full):
                    if include_dirs:
                        result.append(full)
                elif accept_file(name):
                    result.append(full)
        except OSError:
            pass
    return result


class staticproperty:
    def __init__(self, fget):
        self.fget = fget

    def __get__(self, obj, owner):
        return self.fget()


class FilesController:
    """Registry of plugin handlers for files of a given extension opened from chats."""

    _lock = threading.RLock()
    _handlers = {}

    @staticproperty
    def DIRECT_FILE_ICONS() -> bool:
        return False

    @staticproperty
    def SUPPORT_ICONS() -> bool:
        return False

    class Place(Enum):
        UNKNOWN = 1
        ChatActivity = 2
        FilteredSearchView = 3
        SharedMediaLayout = 4
        SearchDownloadsContainer = 5
        ChannelAdminLogActivity = 6

    @dataclass
    class FileInfo:
        ext: str
        on_click: Callable = field(compare=False, repr=False)
        whitelist_places: list = field(default_factory=list, kw_only=True)
        blacklist_places: list = field(default_factory=list, kw_only=True)
        get_icon: Optional[Callable] = field(default=None, compare=False, repr=False, kw_only=True)

        def __post_init__(self) -> None:
            self.ext = self.ext.lower().lstrip(".")

    @dataclass
    class OnClickArgs:
        place: object
        file: object = field(compare=False, repr=False)
        file_name: str
        message: object = field(compare=False, repr=False)
        activity: object = field(compare=False, repr=False)
        parent_fragment: object = field(default=None, compare=False, repr=False)

    class ExtensionAlreadyRegistered(Exception):
        def __init__(self, ext: str) -> None:
            super().__init__("Extension already registered: %s" % ext)

    class ExtensionNotRegistered(Exception):
        def __init__(self, ext: str) -> None:
            super().__init__("Extension not registered: %s" % ext)

    class SecretInvalid(Exception):
        def __init__(self, ext: str, secret: str) -> None:
            super().__init__("Invalid secret for extension: %s" % ext)

    @classmethod
    def register(cls, file_info: "FilesController.FileInfo") -> str:
        with cls._lock:
            if file_info.ext in cls._handlers:
                raise cls.ExtensionAlreadyRegistered(file_info.ext)
            secret = secrets.token_hex(8)
            cls._handlers[file_info.ext] = (file_info, secret)
            _PluginsController.setFileExtensionHandled(file_info.ext, True)
            return secret

    @classmethod
    def unregister(cls, ext: str, secret: str):
        ext = ext.lower().lstrip(".")
        with cls._lock:
            entry = cls._handlers.get(ext)
            if entry is None:
                raise cls.ExtensionNotRegistered(ext)
            if entry[1] != secret:
                raise cls.SecretInvalid(ext, secret)
            del cls._handlers[ext]
            _PluginsController.setFileExtensionHandled(ext, False)

    @classmethod
    def _open(cls, place_value: int, file, file_name: str, message, activity, fragment) -> bool:
        ext = os.path.splitext(str(file_name))[1].lower().lstrip(".")
        with cls._lock:
            entry = cls._handlers.get(ext)
        if entry is None:
            return False
        info = entry[0]
        try:
            place = cls.Place(int(place_value))
        except ValueError:
            place = cls.Place.UNKNOWN
        if info.whitelist_places and place not in info.whitelist_places:
            return False
        if place in info.blacklist_places:
            return False
        info.on_click(cls.OnClickArgs(place, file, str(file_name), message, activity, fragment))
        return True

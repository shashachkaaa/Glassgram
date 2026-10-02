"""Handlers for links and intents opened in the app."""

import re
import threading
import uuid
from dataclasses import dataclass, field
from typing import Callable, List, Optional
from urllib.parse import parse_qsl, urlparse


class IntentsManager:
    _lock = threading.RLock()
    _handlers = {}

    @dataclass
    class HandlerInfo:
        callback: Callable
        scheme: Optional[str] = field(default=None)
        host: Optional[str] = field(default=None)
        path: Optional[str] = field(default=None)
        required_path_args_names: Optional[List[str]] = field(default=None)
        priority: Optional[int] = field(default=0)
        action: Optional[str] = field(default=None)
        whitelist_flags: Optional[List[int]] = field(default=None)
        blacklist_flags: Optional[List[int]] = field(default=None)
        type: Optional[str] = field(default=None)
        categories: Optional[List[str]] = field(default=None)

    @dataclass
    class _HandlerInfo:
        handler_id: str
        callback: Callable = field(compare=False, repr=False)
        hook_type: str = "before"
        priority: int = 0
        scheme: Optional[str] = None
        host: Optional[str] = None
        path: Optional[str] = None
        path_regex: Optional[str] = None
        required_path_args_names: tuple = field(default_factory=tuple)
        action: Optional[str] = None
        whitelist_flags: tuple = field(default_factory=tuple)
        blacklist_flags: tuple = field(default_factory=tuple)
        type: Optional[str] = None
        categories: tuple = field(default_factory=tuple)

    @dataclass
    class HandlerHandle:
        handler_id: str

        def unhandle(self) -> None:
            IntentsManager.unhandle(self.handler_id)

    class HandlerNotRegistered(Exception):
        def __init__(self, handler_id: str) -> None:
            super().__init__("Handler not registered: %s" % handler_id)

    @staticmethod
    def parse(url: str) -> dict:
        parsed = urlparse(url)
        result = dict(parse_qsl(parsed.query))
        result["scheme"] = parsed.scheme
        result["host"] = parsed.netloc
        result["path"] = parsed.path
        return result

    @classmethod
    def _add(cls, info: "IntentsManager.HandlerInfo", hook_type: str) -> "IntentsManager.HandlerHandle":
        handler_id = uuid.uuid4().hex
        path_regex = None
        if info.path:
            path_regex = "^" + re.sub(r"\\\{(\w+)\\\}", r"(?P<\1>[^/]+)", re.escape(info.path)) + "$"
        record = cls._HandlerInfo(
            handler_id, info.callback, hook_type, info.priority or 0, info.scheme, info.host, info.path, path_regex,
            tuple(info.required_path_args_names or ()), info.action, tuple(info.whitelist_flags or ()),
            tuple(info.blacklist_flags or ()), info.type, tuple(info.categories or ()),
        )
        with cls._lock:
            cls._handlers[handler_id] = record
        return cls.HandlerHandle(handler_id)

    @classmethod
    def new_global_before_handler(cls, info: "IntentsManager.HandlerInfo") -> "IntentsManager.HandlerHandle":
        return cls._add(info, "before")

    @classmethod
    def new_global_after_handler(cls, info: "IntentsManager.HandlerInfo") -> "IntentsManager.HandlerHandle":
        return cls._add(info, "after")

    @classmethod
    def unhandle(cls, handler_id: str):
        with cls._lock:
            if cls._handlers.pop(handler_id, None) is None:
                raise cls.HandlerNotRegistered(handler_id)

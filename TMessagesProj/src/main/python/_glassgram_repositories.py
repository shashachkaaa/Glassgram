"""Read schema-1 HTTPS plugin repositories and verify files before preview."""
import hashlib
import json
import os
import re
import tempfile
import time
from collections import OrderedDict
from urllib.parse import urljoin, urlparse

import requests

_cache = OrderedDict()
INDEX = "extera-plugins.json"


def index_url(value):
    url = urlparse(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        raise ValueError("An HTTPS repository URL is required")
    parts = url.path.strip("/").split("/")
    if url.hostname == "github.com" and len(parts) >= 2:
        if len(parts) != 2 and (len(parts) < 4 or parts[2] != "tree"):
            raise ValueError("Choose a repository or branch")
        ref = parts[3:] if len(parts) >= 4 else ["HEAD"]
        return "https://raw.githubusercontent.com/" + "/".join(parts[:2] + ref + [INDEX])
    if parts[-1] != INDEX:
        raise ValueError("Expected an extera-plugins.json URL")
    return value


def fetch(url, limit, headers=None):
    for redirect in range(6):
        parts = urlparse(url)
        if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
            raise ValueError("Invalid HTTPS download URL")
        with requests.get(url, headers=headers, stream=True, allow_redirects=False, timeout=(10, 20)) as response:
            if response.is_redirect:
                url = urljoin(url, response.headers["Location"])
                continue
            if response.status_code == 304:
                return None, url, {}
            response.raise_for_status()
            data = bytearray()
            for chunk in response.iter_content(65536):
                if len(data) + len(chunk) > limit:
                    raise ValueError("Download exceeds size limit")
                data.extend(chunk)
            return data, url, response.headers
    raise ValueError("Too many redirects")


def entries(url, refresh=False):
    cached = _cache.get(url)
    if cached and not refresh and time.monotonic() - cached[0] < 600:
        return cached[1]
    headers = cached[2] if cached else {}
    try:
        data, base, response = fetch(index_url(url), 1024**2, headers)
    except requests.RequestException:
        parts = urlparse(url).path.strip("/").split("/")
        if urlparse(url).hostname != "github.com" or len(parts) < 4 or parts[2] != "tree":
            raise
        mirror = "https://cdn.jsdelivr.net/gh/%s/%s@%s/" % (parts[0], parts[1], parts[3])
        data, base, response = fetch(mirror + "/".join(parts[4:] + [INDEX]), 1024**2)
    if data is None and cached:
        _cache[url] = (time.monotonic(), cached[1], headers)
        return cached[1]
    index = json.loads(data)
    if index.get("schema") != 1 or not isinstance(index["plugins"], list):
        raise ValueError("Unsupported repository schema")
    result = []
    seen = set()
    for row in index["plugins"]:
        plugin_id, name, version = (row[k] for k in ("id", "name", "version"))
        if not all(isinstance(v, str) and v for v in (plugin_id, name, version)) or plugin_id in seen:
            raise ValueError("Invalid repository entry")
        seen.add(plugin_id)
        file_url = urljoin(base, row["file"])
        if not re.fullmatch("[0-9a-f]{64}", row["sha256"]) or not 0 < row["size"] <= 10 * 1024**2:
            continue
        result.append(dict(id=plugin_id, name=name, version=version, file=file_url, size=row["size"], sha256=row["sha256"]))
    result = json.dumps(result)
    headers = {key: response[value] for key, value in (("If-None-Match", "ETag"),
               ("If-Modified-Since", "Last-Modified")) if value in response}
    _cache[url] = (time.monotonic(), result, headers)
    if len(_cache) > 16:
        _cache.popitem(last=False)
    return result


def download(row_json, directory):
    # the plugin's metadata as exteraGram's engine reads it
    from extera_utils.metadata_parser import get_metadata
    row = json.loads(row_json)
    data, _, _ = fetch(row["file"], row["size"])
    if len(data) != row["size"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
        raise ValueError("Repository file size or checksum mismatch")
    with tempfile.NamedTemporaryFile(dir=directory, delete=False, prefix="plugin_import_", suffix=".plugin") as file:
        file.write(data)
    try:
        meta = get_metadata(file.name)
        if (meta["id"], meta["version"]) != (row["id"], row["version"]):
            raise ValueError("Repository plugin ID or version mismatch")
        return file.name
    except Exception:
        os.remove(file.name)
        raise

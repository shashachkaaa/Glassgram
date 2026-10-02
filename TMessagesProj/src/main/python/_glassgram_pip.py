"""Installs pure-Python wheels from PyPI for plugin __requirements__."""

import importlib.metadata
import json
import os
import re
import tempfile
import threading
import urllib.request
import zipfile

from packaging.markers import Marker
from packaging.requirements import Requirement
from packaging.specifiers import SpecifierSet
from packaging.utils import canonicalize_name
from packaging.version import InvalidVersion, Version

_lock = threading.RLock()
_PYPI = "https://pypi.org/pypi/%s/json"


class PipError(Exception):
    pass


def _installed_path(libs_dir):
    return os.path.join(libs_dir, ".installed.json")


def _read_installed(libs_dir):
    try:
        with open(_installed_path(libs_dir), "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _write_installed(libs_dir, data):
    with open(_installed_path(libs_dir), "w", encoding="utf-8") as f:
        json.dump(data, f)


def _available_version(name, libs_dir, installed):
    key = canonicalize_name(name)
    if key in installed:
        return installed[key]
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return None


def _fetch_json(name):
    request = urllib.request.Request(_PYPI % name, headers={"User-Agent": "Glassgram plugins"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise PipError("Package not found: %s" % name)
        raise


def _pure_wheel(files):
    for f in files:
        filename = f.get("filename", "")
        if f.get("packagetype") == "bdist_wheel" and filename.endswith("-none-any.whl") and (
                "-py3-" in filename or "-py2.py3-" in filename or "-py3.py2-" in filename):
            return f
    return None


def _pick(name, specifier: SpecifierSet):
    data = _fetch_json(name)
    candidates = []
    for version_text, files in data.get("releases", {}).items():
        try:
            version = Version(version_text)
        except InvalidVersion:
            continue
        if version.is_prerelease and not specifier.prereleases:
            continue
        if not specifier.contains(version, prereleases=True):
            continue
        wheel = _pure_wheel(files)
        if wheel is not None and not any(f.get("yanked") for f in files if f is wheel):
            candidates.append((version, wheel))
    if not candidates:
        raise PipError("No pure-Python wheel found for %s%s" % (name, specifier))
    candidates.sort(key=lambda c: c[0])
    return candidates[-1]


def _download(url, target):
    request = urllib.request.Request(url, headers={"User-Agent": "Glassgram plugins"})
    with urllib.request.urlopen(request, timeout=120) as response, open(target, "wb") as out:
        while True:
            chunk = response.read(65536)
            if not chunk:
                break
            out.write(chunk)


def _requires(wheel_path):
    with zipfile.ZipFile(wheel_path) as z:
        for entry in z.namelist():
            if re.match(r"^[^/]+\.dist-info/METADATA$", entry):
                text = z.read(entry).decode("utf-8", "replace")
                return [line.split(":", 1)[1].strip() for line in text.splitlines()
                        if line.startswith("Requires-Dist:")]
    return []


def _marker_ok(requirement: Requirement):
    if requirement.marker is None:
        return True
    try:
        return requirement.marker.evaluate({"extra": ""})
    except Exception:
        return False


def install(requirements, libs_dir):
    with _lock:
        os.makedirs(libs_dir, exist_ok=True)
        installed = _read_installed(libs_dir)
        queue = [Requirement(r) for r in requirements]
        seen = set()
        while queue:
            req = queue.pop(0)
            if not _marker_ok(req):
                continue
            key = canonicalize_name(req.name)
            if key in seen:
                continue
            seen.add(key)
            current = _available_version(req.name, libs_dir, installed)
            if current is not None and req.specifier.contains(current, prereleases=True):
                continue
            if current is not None and key in installed:
                raise PipError("Dependency conflict: %s %s is installed, %s needed"
                               % (req.name, current, req.specifier))
            version, wheel = _pick(req.name, req.specifier)
            fd, tmp = tempfile.mkstemp(suffix=".whl")
            os.close(fd)
            try:
                _download(wheel["url"], tmp)
                for dependency in _requires(tmp):
                    try:
                        queue.append(Requirement(dependency))
                    except Exception:
                        pass
                with zipfile.ZipFile(tmp) as z:
                    z.extractall(libs_dir)
            finally:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
            installed[key] = str(version)
            _write_installed(libs_dir, installed)
        importlib.invalidate_caches()

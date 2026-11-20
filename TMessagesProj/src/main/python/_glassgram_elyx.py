"""Source-based Elyx archives. Each installed archive has its own package namespace."""
import builtins
import importlib
import importlib.util
import io
import json
from functools import lru_cache
import traceback
import os
import re
from pathlib import Path
import sys
import tempfile
import types
import zipfile

import plugin_settings


def structured(name, data):
    if str(name).endswith(".json"):
        return json.loads(data)
    import yaml
    return yaml.safe_load(data)


def safe_name(name):
    if not isinstance(name, str) or not name or "\\" in name or any(
            part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("Invalid archive member: %r" % name)
    return name


class Archive:
    def __init__(self, path):
        if os.path.getsize(path) > 10 * 1024**2:
            raise ValueError("Archive exceeds 10 MiB")
        self.expanded_size = 0
        self.members = 0
        self.has_bytecode = False
        self.wheels = {}
        self.files = self.unpack(path)
        self.refmap = self.mapping("refmap")
        wheel_dir = safe_name(self.refmap.get("wheels", "wheels"))
        for name, data in self.files.items():
            if Path(name).parent.as_posix() == wheel_dir and name.endswith(".whl"):
                self.wheels[name] = self.unpack(io.BytesIO(data))
        self.meta = {k.strip("_"): v for k, v in self.mapping("metainfo", self.refmap.get("metainfo")).items()}
        self.catalog = {}
        self.locale_sources = {}
        location = safe_name(self.refmap.get("strings", self.refmap.get("locales", "strings")))
        for name, data in self.files.items():
            path = Path(name)
            if name != location and str(path.parent) != location:
                continue
            locale = path.stem.rsplit("_", 1)[-1] if "_" in path.stem else "en"
            if path.suffix in (".yaml", ".yml", ".json"):
                self.catalog[locale] = structured(name, data)
            elif path.suffix == ".py":
                self.locale_sources[locale] = name
        description = self.meta.get("description")
        if isinstance(description, str) and "{" in description:
            strings = Strings(self.catalog)
            self.meta["description"] = re.sub(r"\{(\w+)\}", lambda m: str(strings.get(m[1])), description)
        self.entry = safe_name(self.refmap.get("main", "main.py"))
        if self.entry not in self.files and self.entry.endswith(".py") and self.entry + "c" in self.files:
            self.entry += "c"
        if not self.entry.endswith((".py", ".pyc")) or self.entry not in self.files:
            raise ValueError("Missing Python entry point: " + self.entry)
        if not self.meta.get("id"):
            raise ValueError("Missing Elyx plugin id")

    def unpack(self, stream):
        files = {}
        with zipfile.ZipFile(stream) as archive:
            for member in archive.infolist():
                self.members += 1
                self.expanded_size += member.file_size
                if self.members > 4096 or self.expanded_size > 32 * 1024**2:
                    raise ValueError("Expanded archive too large")
                if member.is_dir():
                    continue
                name = safe_name(member.filename)
                if name in files or name.lower().endswith((".so", ".pyd", ".dll")):
                    raise ValueError("Duplicate or unsupported native member: " + name)
                data = archive.read(member)
                if name.endswith(".pyc"):
                    if data[:4] != importlib.util.MAGIC_NUMBER or len(data) < 16:
                        raise ValueError("Incompatible bytecode: " + name)
                    self.has_bytecode = True
                files[name] = data
        return files

    def mapping(self, stem, name=None):
        if name is None:
            name = next((stem + ext for ext in (".yaml", ".yml", ".json") if stem + ext in self.files), None)
        value = structured(name, self.files[safe_name(name)])
        if not isinstance(value, dict):
            raise ValueError("Expected a mapping in " + name)
        return value

    def sources(self):
        return [data.decode("utf-8-sig") for files in (self.files, *self.wheels.values())
                for name, data in files.items() if name.endswith(".py")]


class Settings:
    def __init__(self, plugin_id):
        self.plugin_id = plugin_id

    def get(self, key, default=None):
        return plugin_settings.get_setting(self.plugin_id, key, default)

    def set(self, key, value, reload_settings=False):
        plugin_settings.set_setting(self.plugin_id, key, value)
        if reload_settings:
            from java import jclass
            jclass("org.telegram.messenger.plugins.PluginsController").reloadSettings(self.plugin_id)

    def clear_settings(self):
        plugin_settings.clear_settings(self.plugin_id)

    def get_settings(self):
        return plugin_settings.get_all_settings(self.plugin_id)

    get_setting = __getitem__ = __call__ = get
    set_setting = __setitem__ = set


class Asset:
    def __init__(self, path, filename=None, name=None):
        path = Path(path) / filename if filename else Path(path)
        self.path = path
        self.path_str = str(path)
        self.name = name or path.stem
        self.filename = path.name
        self.ext = path.suffix.lstrip(".")

    @property
    def java_file(self):
        from java import jclass
        return jclass("java.io.File")(self.path_str)

    def content_bytes(self):
        return self.path.read_bytes()

    def content_string(self):
        return self.path.read_text("utf-8-sig")

    def content_json(self):
        return json.loads(self.content_string())

    def content_yaml(self):
        return structured(self.path, self.content_bytes())

    def content(self):
        if self.ext in ("yaml", "yml", "json"):
            return structured(self.path, self.content_bytes())
        return self.content_string() if self.ext in ("txt", "svg", "xml") else self.content_bytes()

    @classmethod
    def from_path(cls, path):
        return cls(path)

    @classmethod
    def temp_asset_from_url(cls, url, filename):
        from _glassgram_repositories import fetch
        data, _, _ = fetch(url, 10 * 1024**2)
        temp = tempfile.TemporaryDirectory()
        path = Path(temp.name) / safe_name(filename)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        asset = cls(path)
        asset._temp = temp
        return asset

    def to_drawable(self):
        from java import jclass
        return jclass("android.graphics.drawable.Drawable").createFromPath(self.path_str)

    def to_image_location(self):
        from java import jclass
        return jclass("org.telegram.messenger.ImageLocation").getForPath(self.path_str)

    def _drawable(self, bitmap):
        from java import jclass
        resources = jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getResources()
        return jclass("android.graphics.drawable.BitmapDrawable")(resources, bitmap)

    def to_bitmap_drawable(self, width=32, height=32):
        from java import jclass
        bitmap = jclass("android.graphics.BitmapFactory").decodeFile(self.path_str)
        return self._drawable(jclass("android.graphics.Bitmap").createScaledBitmap(bitmap, width, height, True))

    def to_svg_bitmap(self, width=32, height=32, white=False):
        from java import jclass
        return jclass("org.telegram.messenger.SvgHelper").getBitmap(self.java_file, width, height, white)

    def to_svg_drawable(self, width=None, height=None):
        from java import jclass
        if width is None or height is None:
            return jclass("org.telegram.messenger.SvgHelper").getDrawable(self.content_string())
        return self._drawable(self.to_svg_bitmap(width, height))

    def to_svg_thumb(self, color_key, alpha=1.0):
        from java import jclass
        drawable = self.to_svg_drawable()
        color = jclass("org.telegram.ui.ActionBar.Theme").getColor(color_key)
        drawable.setTint(color)
        drawable.setAlpha(round(255 * max(0, min(1, alpha))))
        return drawable

    def to_lottie_drawable(self, width=32, height=32):
        from java import jclass
        dp = jclass("org.telegram.messenger.AndroidUtilities").dp
        return jclass("org.telegram.ui.Components.RLottieDrawable")(
            self.java_file, None, dp(width), dp(height), None, False, None, 0, False)


class Assets:
    def __init__(self, path):
        self.dir_path = Path(path)

    def __iter__(self):
        return (p.stem for p in self.dir_path.iterdir())

    def __len__(self):
        return sum(1 for _ in self)

    def get(self, name):
        first, _, tail = safe_name(name).partition("/")
        path = next((p for p in self.dir_path.iterdir() if p.name == first or p.stem == first), None)
        if path is None:
            raise KeyError(name)
        child = Assets(path) if path.is_dir() else Asset(path)
        return child.get(tail) if tail else child

    __getitem__ = __getattr__ = get


class Strings:
    def __init__(self, catalog):
        self.catalog = catalog

    def get_with_locale(self, key, locale=None, default=None):
        if locale is None:
            from java import jclass
            locale = str(jclass("java.util.Locale").getDefault().getLanguage())
        for lang in (locale, locale.split("-")[0], "en"):
            if key in self.catalog.get(lang, {}):
                return self.catalog[lang][key]
        return key if default is None else default

    def get(self, key, default=None):
        return self.get_with_locale(key, default=default)

    __getattr__ = __getitem__ = get

    def __call__(self, key, *args, locale=None, default=None, **kwargs):
        value = self.get_with_locale(key, locale, default)
        return value.format(*args, **kwargs) if isinstance(value, str) and (args or kwargs) else value

    def pluralize(self, count, key, locale=None):
        forms = self.get_with_locale(key, locale)
        n = abs(count)
        idx = 0 if n % 10 == 1 and n % 100 != 11 else 1 if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14 else 2
        return forms[idx] if isinstance(forms, list) and len(forms) >= 3 else forms


def callback_method(fn, returns, default):
    def call(self, *args):
        try:
            value = fn(self, *args)
            return value if returns else None
        except Exception:
            traceback.print_exc()
            return default
    return call


@lru_cache(maxsize=64)
def gen(java_class, method_name, return_value=False, default_value=None):
    from java import dynamic_proxy
    def init(self, callback, *extra):
        self.callback = lambda *args: callback(*args, *extra)
    def invoke(self, *args):
        return self.callback(*args)
    return type("CallbackProxy", (dynamic_proxy(java_class),), {
        "__init__": init, method_name: callback_method(invoke, return_value, default_value)})


def gen2(java_class, return_value=False, **methods):
    from java import dynamic_proxy
    def bind(callback):
        return callback_method(lambda self, *args: callback(*args), return_value, None)
    return type("CallbackProxy", (dynamic_proxy(java_class),), {k: bind(v) for k, v in methods.items()})


@lru_cache(maxsize=128)
def expression(script):
    from java import jclass
    return jclass("org.mvel2.MVEL").compileExpression(script)


def mvel_execute(script, data, to_type=None, java_instance=None):
    from java import jclass
    variables = jclass("java.util.HashMap")()
    for key, value in data.items():
        variables.put(key, value)
    result = jclass("org.mvel2.MVEL").executeExpression(expression(script), java_instance, variables)
    return result if to_type is None else to_type(result)


class Data(dict):
    __getattr__ = dict.__getitem__


class DataModule(types.ModuleType):
    def __getitem__(self, key):
        return self.data[key]

    def get(self, key, default=None):
        return self.data.get(key, default)


SUFFIXES = (".py", ".pyc", ".json", ".yaml", ".yml", ".txt")


class Environment:
    def __init__(self, archive, plugin_id, directory):
        self.temp = tempfile.TemporaryDirectory(dir=directory)
        self.root = Path(self.temp.name)
        self.prefix = "_elyx_" + plugin_id.encode().hex()
        self.plugin_id = plugin_id
        self.archive = archive
        self.facade = types.ModuleType("elyx")
        self.facade.__dict__.update(Asset=Asset, Assets=Assets, Strings=Strings, SettingsController=Settings,
                                    AssetNotFoundException=KeyError, AssetsDirNotFoundException=FileNotFoundError,
                                    gen=gen, gen2=gen2, mvel_execute=mvel_execute)
        def proxy(name):
            from java import jclass
            interfaces = {"OnClickListener": ("android.view.View$OnClickListener", "onClick"),
                          "Runnable": ("java.lang.Runnable", "run")}
            if name in ("Callback", "Callback2", "Callback3", "CallbackReturn"):
                interface, method = "org.telegram.messenger.Utilities$" + name, "run"
            elif name in interfaces:
                interface, method = interfaces[name]
            else:
                raise AttributeError(name)
            return gen(jclass(interface), method, name == "CallbackReturn")
        self.facade.__getattr__ = proxy
        self.facade.get_environment = lambda: {key: vars(self.facade)[key] for key in
                                               ("assets", "strings", "metainfo", "refmap", "settings")}
        self.facade.metainfo = archive.meta
        self.facade.refmap = archive.refmap
        self.facade.settings = Settings(plugin_id)
        self.facade.import_module = self.import_module
        for name, data in archive.files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.facade.assets = Assets(self.root / safe_name(archive.refmap.get("assets", "assets")))
        self.facade.strings = Strings(dict(archive.catalog))
        self.roots = [self.root / Path(archive.entry).parent, self.root]
        for i, members in enumerate(archive.wheels.values()):
            root = Path(self.temp.name + "/.wheel_libs") / str(i)
            for name, value in members.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(value)
            self.roots.append(root)

    def local(self, first):
        return any((root / first).is_dir() or any((root / (first + ext)).is_file() for ext in SUFFIXES)
                   for root in self.roots)

    def import_module(self, name, package=None):
        if name.startswith("."):
            return importlib.import_module(name, package or self.prefix)
        return importlib.import_module(self.prefix + "." + name if self.local(name.split(".")[0]) else name)

    def import_hook(self, name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0:
            if name == "elyx":
                return self.facade
            first = name.split(".")[0]
            if first and self.local(first):
                module = self.import_module(name)
                for attr in fromlist or ():
                    if not hasattr(module, attr) and hasattr(module, "__path__") and attr.isidentifier():
                        self.import_module(name + "." + attr)
                return module if fromlist else self.import_module(first)
        return builtins.__import__(name, globals, locals, fromlist, level)

    def find_spec(self, fullname, path=None, target=None):
        if not fullname.startswith(self.prefix + "."):
            return None
        leaf = fullname.rpartition(".")[2]
        for root in path or ():
            candidate = Path(root) / leaf
            candidates = [candidate / "__init__.py", candidate / "__init__.pyc"]
            for file in candidates + [candidate.with_suffix(ext) for ext in SUFFIXES]:
                if file.is_file():
                    return importlib.util.spec_from_file_location(fullname, file, loader=self,
                        submodule_search_locations=[str(candidate)] if file in candidates else None)
        return None

    def create_module(self, spec):
        if Path(spec.origin).suffix in SUFFIXES[2:]:
            return DataModule(spec.name)
        return None

    def exec_module(self, module):
        module.__dict__.update(__glassgram_plugin_id__=self.plugin_id,
                               __builtins__=dict(vars(builtins), __import__=self.import_hook))
        path = Path(module.__spec__.origin)
        if path.suffix == ".pyc":
            code = importlib.machinery.SourcelessFileLoader(module.__name__, str(path)).get_code(module.__name__)
        elif path.suffix == ".py":
            code = compile(path.read_bytes(), str(path), "exec")
        else:
            value = path.read_text("utf-8-sig") if path.suffix == ".txt" else structured(path, path.read_bytes())
            def wrap(obj):
                return Data((k, wrap(v)) for k, v in obj.items()) if isinstance(obj, dict) else obj
            module.data = module.content = wrap(value)
            if isinstance(value, dict):
                module.__dict__.update({k: wrap(v) for k, v in value.items() if isinstance(k, str)
                                        and k.isidentifier() and not k.startswith("_") and k not in vars(module)})
            return
        exec(code, module.__dict__)

    def load(self):
        package = types.ModuleType(self.prefix)
        package.__path__ = list(map(str, self.roots))
        sys.modules[self.prefix] = package
        sys.meta_path.insert(0, self)
        for lang, name in self.archive.locale_sources.items():
            module = self.import_module(".".join(Path(name).with_suffix("").parts))
            self.facade.strings.catalog[lang] = {k: v for k, v in vars(module).items()
                                                 if not k.startswith("_") and isinstance(v, (str, list, dict, int, float))}
        return self.import_module(Path(self.archive.entry).stem)

    def close(self):
        if self in sys.meta_path:
            sys.meta_path.remove(self)
        for key in list(sys.modules):
            if key == self.prefix or key.startswith(self.prefix + "."):
                sys.modules.pop(key, None)
        self.temp.cleanup()

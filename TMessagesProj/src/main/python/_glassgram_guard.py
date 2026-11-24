"""glassGram: which plugin's code sends a request the user decides on (PluginActionGuard)."""

import sys


def caller_plugin(plugin_ids):
    """The plugin whose code is on this thread's stack, innermost first, or "" when none is. A
    plugin's module is named by its id (exteraGram's engine imports it so); its frames are there
    under the Java call it made."""
    ids = set(str(plugin_id) for plugin_id in plugin_ids)
    if not ids:
        return ""
    frame = sys._getframe(1)
    while frame is not None:
        name = frame.f_globals.get("__name__") or ""
        top = name.split(".", 1)[0]
        if top in ids:
            return top
        frame = frame.f_back
    return ""

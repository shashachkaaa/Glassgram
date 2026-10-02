import inspect

ELYX_PLUGIN_NAMESPACE = "plugins."


def get_plugin_id(fn=None):
    """Id of the plugin whose code is calling, found from the module of the caller."""
    import _glassgram_engine

    if fn is not None:
        module = getattr(fn, "__module__", None)
        plugin_id = _glassgram_engine.plugin_id_for_module(module)
        if plugin_id:
            return plugin_id
    frame = inspect.currentframe()
    try:
        while frame is not None:
            plugin_id = _glassgram_engine.plugin_id_for_globals(frame.f_globals)
            if plugin_id:
                return plugin_id
            frame = frame.f_back
    finally:
        del frame
    return None

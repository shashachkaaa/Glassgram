package com.exteragram.messenger.plugins;

import android.util.Log;

import com.chaquo.python.PyObject;

import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.PluginInfo;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * glassGram: exteraGram's Python plugin engine as plugins made for exteraGram reach it. The
 * plugins run in glassGram's engine (_glassgram_engine), which tells this one of each plugin
 * loaded and unloaded: {@link #pluginInstances} holds the running ones, and {@link #unloadPlugin}
 * is called as each one goes, so a plugin hooking it sees that (zwylib cleans up after the
 * plugins using it there).
 */
public class PythonPluginsEngine implements PluginsController.PluginsEngine {

    public static final PythonPluginsEngine INSTANCE = new PythonPluginsEngine();

    /** The running plugins' objects (their BasePlugin) by id. */
    public final ConcurrentHashMap<String, PyObject> pluginInstances = new ConcurrentHashMap<>();

    private PythonPluginsEngine() {
    }

    public static PythonPluginsEngine getInstance() {
        return INSTANCE;
    }

    /** From glassGram's engine: the plugin was loaded and runs. */
    public void onPluginLoaded(String pluginId, PyObject pluginInstance) {
        if (pluginId != null && pluginInstance != null) {
            pluginInstances.put(pluginId, pluginInstance);
        }
    }

    /**
     * From glassGram's engine, as the plugin is unloaded (or failed to load): it no longer runs,
     * and the Xposed hooks it made through {@link PluginsController#addXposedHook} are undone.
     */
    public void unloadPlugin(String pluginId) {
        if (pluginId == null) {
            return;
        }
        pluginInstances.remove(pluginId);
        PluginsController.getInstance().removeXposedHooks(pluginId);
    }

    /** Turns a plugin on or off here and now; the callback gets the error, if any. */
    public void setPluginEnabled(String pluginId, boolean enabled, Utilities.Callback<String> callback) {
        String error = null;
        final PyObject engine = org.telegram.messenger.plugins.PluginsController.getEngine();
        if (engine == null) {
            error = "Plugins engine is not running";
        } else {
            try {
                engine.callAttr("set_enabled", pluginId, enabled);
                final PluginInfo info = org.telegram.messenger.plugins.PluginsController.getPlugin(pluginId);
                if (enabled && info != null) {
                    error = info.error;
                }
            } catch (Throwable e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                org.telegram.messenger.plugins.PluginsController.log(pluginId, Log.getStackTraceString(e));
            }
        }
        if (callback != null) {
            callback.run(error);
        }
    }

    /**
     * Reads an installed plugin from its file again, and loads it when it is on. (exteraGram
     * keeps plugins as {id}.py; glassGram's engine reads its own file of the plugin when the one
     * named is not there.)
     */
    public void loadPlugin(String pluginId, String filePath) throws Exception {
        final PyObject engine = org.telegram.messenger.plugins.PluginsController.getEngine();
        if (engine == null) {
            throw new IllegalStateException("Plugins engine is not running");
        }
        engine.callAttr("load_from_file", pluginId, filePath);
    }

    public void loadPlugin(String pluginId, String filePath, Plugin plugin) throws Exception {
        loadPlugin(pluginId, filePath);
    }

    /** Installs a plugin file, turned on; the callback gets the error, if any. */
    public void loadPluginFromFile(String sourceFilePath, Plugin plugin, Utilities.Callback<String> callback) {
        org.telegram.messenger.plugins.PluginsController.installPlugin(new File(sourceFilePath), true, (id, error) -> {
            if (callback != null) {
                callback.run(error);
            }
        });
    }
}

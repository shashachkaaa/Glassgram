package com.exteragram.messenger.plugins;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.PluginInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * glassGram: exteraGram's plugin controller as plugins made for exteraGram reach it (zwylib
 * lists plugins here, reloads and installs them through the engine, keeps its Xposed hooks here
 * and hooks {@link #setPluginEnabled} to see plugins turned on and off). Plugins run in
 * glassGram's own engine ({@link org.telegram.messenger.plugins.PluginsController}); this
 * shows them as exteraGram's does and passes what plugins ask on to it.
 */
public class PluginsController {

    /** An engine running plugins: glassGram has the Python one only. */
    public interface PluginsEngine {
    }

    public static final ConcurrentHashMap<String, PluginsEngine> engines = new ConcurrentHashMap<>();

    private static final PluginsController instance = new PluginsController();

    static {
        engines.put(PluginsConstants.PYTHON, PythonPluginsEngine.INSTANCE);
    }

    /** The installed plugins by id, kept up to date from glassGram's engine. */
    public final ConcurrentHashMap<String, Plugin> plugins = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<XC_MethodHook.Unhook>> xposedHooks = new ConcurrentHashMap<>();

    private PluginsController() {
    }

    public static PluginsController getInstance() {
        return instance;
    }

    public static ConcurrentHashMap<String, PluginsEngine> getEngines() {
        return engines;
    }

    public static boolean isPluginEngineSupported() {
        return org.telegram.messenger.plugins.PluginsController.isSupported();
    }

    /** Whether a message's file is a plugin. */
    public static boolean isPlugin(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        final String name = messageObject.getDocumentName();
        return name != null && name.toLowerCase().endsWith(PluginsConstants.PLUGINS_EXT);
    }

    public static boolean isPlugin(File file) {
        return file != null && file.getName().toLowerCase().endsWith(PluginsConstants.PLUGINS_EXT);
    }

    // What glassGram's engine says of its plugins

    /** A plugin installed, changed or published again by glassGram's engine. */
    public void onPluginInfo(PluginInfo info) {
        final Plugin plugin = plugins.get(info.id);
        if (plugin != null) {
            plugin.update(info);
        } else {
            plugins.put(info.id, new Plugin(info));
        }
    }

    public void onPluginRemoved(String pluginId) {
        plugins.remove(pluginId);
    }

    // What plugins ask

    /** Whether the plugin is loaded and running. */
    public boolean isPluginActive(String pluginId) {
        return pluginId != null && PythonPluginsEngine.INSTANCE.pluginInstances.containsKey(pluginId);
    }

    public boolean isPluginActive(Plugin plugin) {
        return plugin != null && isPluginActive(plugin.getId());
    }

    public boolean isPluginEnabled(String pluginId) {
        final Plugin plugin = pluginId == null ? null : plugins.get(pluginId);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Turns a plugin on or off, as the plugin list does: the callback gets the error, if any.
     * Plugins hook this to see plugins turned on and off.
     */
    public void setPluginEnabled(String pluginId, boolean enabled, Utilities.Callback<String> callback) {
        PythonPluginsEngine.INSTANCE.setPluginEnabled(pluginId, enabled, callback);
    }

    public void loadPluginSettings() {
        for (String pluginId : plugins.keySet()) {
            loadPluginSettings(pluginId);
        }
    }

    /** Shows the plugin's settings anew where they are open. */
    public void loadPluginSettings(String pluginId) {
        org.telegram.messenger.plugins.PluginsController.reloadSettings(pluginId);
    }

    public void notifyPluginsChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.glassgramPluginsUpdated));
    }

    // Xposed hooks plugins made themselves, undone when the plugin goes

    public void addXposedHook(String pluginId, XC_MethodHook.Unhook unhook) {
        if (pluginId == null || unhook == null) {
            return;
        }
        CopyOnWriteArrayList<XC_MethodHook.Unhook> list = xposedHooks.get(pluginId);
        if (list == null) {
            final CopyOnWriteArrayList<XC_MethodHook.Unhook> created = new CopyOnWriteArrayList<>();
            list = xposedHooks.putIfAbsent(pluginId, created);
            if (list == null) {
                list = created;
            }
        }
        list.add(unhook);
    }

    public void addXposedHooks(String pluginId, ArrayList<XC_MethodHook.Unhook> unhooks) {
        if (unhooks == null) {
            return;
        }
        for (XC_MethodHook.Unhook unhook : unhooks) {
            addXposedHook(pluginId, unhook);
        }
    }

    public void removeXposedHook(String pluginId, XC_MethodHook.Unhook unhook) {
        if (unhook == null) {
            return;
        }
        unhook(unhook);
        final List<XC_MethodHook.Unhook> list = pluginId == null ? null : xposedHooks.get(pluginId);
        if (list != null) {
            list.remove(unhook);
        }
    }

    /** Undoes the plugin's Xposed hooks: it was unloaded. */
    void removeXposedHooks(String pluginId) {
        final List<XC_MethodHook.Unhook> list = TextUtils.isEmpty(pluginId) ? null : xposedHooks.remove(pluginId);
        if (list == null) {
            return;
        }
        for (XC_MethodHook.Unhook unhook : list) {
            unhook(unhook);
        }
    }

    private static void unhook(XC_MethodHook.Unhook unhook) {
        try {
            unhook.unhook();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        final Object callback = unhook.getCallback();
        if (callback instanceof AutoCloseable) {
            try {
                ((AutoCloseable) callback).close();
            } catch (Throwable ignore) {
            }
        }
    }
}

package org.telegram.messenger.plugins;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Glassgram plugin engine: runs exteraGram-style Python plugins through Chaquopy.
 *
 * The Python side lives in src/main/python (_glassgram_engine and the SDK modules); this class
 * starts it, keeps a Java copy of what plugins hook so unhooked calls never cross into Python,
 * and passes requests, updates and outgoing messages through the hooks.
 */
public final class PluginsController {

    private static final String TAG = "GlassgramPlugins";
    private static final int MAX_LOG_LINES = 400;

    public static final DispatchQueue pluginsQueue = new DispatchQueue("pluginsQueue");

    private static volatile boolean started;
    private static volatile boolean starting;
    private static volatile PyObject engine;
    public static volatile String startError;

    private static volatile Set<String> exactHooks = Collections.emptySet();
    private static volatile String[] substringHooks = new String[0];
    private static volatile boolean sendMessageHook;

    private static final ConcurrentHashMap<String, PluginInfo> plugins = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<MenuItem> menuItems = new CopyOnWriteArrayList<>();
    private static final Set<String> handledFileExtensions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Class<?>, String> tlNames = new ConcurrentHashMap<>();
    private static final ArrayList<String> logLines = new ArrayList<>();

    public static final int MENU_MESSAGE_CONTEXT = 0;
    public static final int MENU_DRAWER = 1;
    public static final int MENU_MAIN = 2;
    public static final int MENU_CHAT_ACTION = 3;
    public static final int MENU_PROFILE_ACTION = 4;

    public static class MenuItem {
        public final String pluginId;
        public final String itemId;
        public final int type;
        public final String text;
        public final String subtext;
        public final String icon;
        public final int priority;
        public final String condition;
        final PyObject callback;

        MenuItem(String pluginId, String itemId, int type, String text, String subtext, String icon, int priority, String condition, PyObject callback) {
            this.pluginId = pluginId;
            this.itemId = itemId;
            this.type = type;
            this.text = text;
            this.subtext = subtext;
            this.icon = icon;
            this.priority = priority;
            this.condition = condition;
            this.callback = callback;
        }

        public int getIconResId() {
            return PluginsController.getDrawableId(icon);
        }

        public void click(HashMap<String, Object> context) {
            try {
                callback.call(context);
            } catch (Throwable e) {
                log(pluginId, "Menu item failed: " + e);
            }
        }
    }

    private PluginsController() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    public static boolean isStarted() {
        return started;
    }

    public static boolean isStarting() {
        return starting;
    }

    public static File getPluginsDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "plugins");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Starts the engine at app start when it is turned on. */
    public static void init() {
        GlassgramConfig.load();
        if (GlassgramConfig.pluginsEngine) {
            start();
        }
    }

    public static void start() {
        if (started || starting || !isSupported()) {
            return;
        }
        starting = true;
        pluginsQueue.postRunnable(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
                }
                PyObject module = Python.getInstance().getModule("_glassgram_engine");
                engine = module;
                started = true;
                startError = null;
                module.callAttr("start", getPluginsDir().getAbsolutePath());
            } catch (Throwable e) {
                startError = e.toString();
                FileLog.e(e);
                log("plugins", "Engine failed to start: " + Log.getStackTraceString(e));
            } finally {
                starting = false;
                notifyChanged();
            }
        });
    }

    /** Unloads every plugin; Python itself stays loaded for the rest of the process. */
    public static void stop() {
        if (!started) {
            return;
        }
        final PyObject module = engine;
        started = false;
        exactHooks = Collections.emptySet();
        substringHooks = new String[0];
        sendMessageHook = false;
        pluginsQueue.postRunnable(() -> {
            try {
                if (module != null) {
                    module.callAttr("stop");
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            menuItems.clear();
            notifyChanged();
        });
    }

    public static void setEngineEnabled(boolean enabled) {
        GlassgramConfig.pluginsEngine = enabled;
        GlassgramConfig.putBoolean("pluginsEngine", enabled);
        if (enabled) {
            start();
        } else {
            stop();
        }
    }

    static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.glassgramPluginsUpdated));
    }

    // Called from Python

    public static void putPluginInfo(String id, String name, String description, String author, String version, String icon, boolean enabled, String error, boolean hasSettings, String path) {
        PluginInfo info = new PluginInfo(id, name, description, author, version, icon, enabled, error, hasSettings, path);
        plugins.put(id, info);
        notifyChanged();
    }

    public static void removePluginInfo(String id) {
        plugins.remove(id);
        removeMenuItems(id);
        notifyChanged();
    }

    public static void setHooks(String[] exact, String[] substrings, boolean sendMessage) {
        exactHooks = new HashSet<>(Arrays.asList(exact));
        substringHooks = substrings;
        sendMessageHook = sendMessage;
    }

    public static void reloadSettings(String pluginId) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.glassgramPluginSettingsReload, pluginId));
    }

    public static void addMenuItem(String pluginId, String itemId, int type, String text, String subtext, String icon, int priority, String condition, PyObject callback) {
        removeMenuItem(pluginId, itemId);
        menuItems.add(new MenuItem(pluginId, itemId, type, text, subtext, icon, priority, condition, callback));
    }

    public static boolean removeMenuItem(String pluginId, String itemId) {
        for (MenuItem item : menuItems) {
            if (TextUtils.equals(item.pluginId, pluginId) && TextUtils.equals(item.itemId, itemId)) {
                return menuItems.remove(item);
            }
        }
        return false;
    }

    private static void removeMenuItems(String pluginId) {
        for (MenuItem item : menuItems) {
            if (TextUtils.equals(item.pluginId, pluginId)) {
                menuItems.remove(item);
            }
        }
    }

    public static void setFileExtensionHandled(String ext, boolean handled) {
        if (handled) {
            handledFileExtensions.add(ext);
        } else {
            handledFileExtensions.remove(ext);
        }
    }

    public static void log(String tag, String text) {
        String line = "[" + tag + "] " + text;
        Log.i(TAG, line);
        synchronized (logLines) {
            logLines.add(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.remove(0);
            }
        }
    }

    public static void logObject(String tag, Object object) {
        log(tag, String.valueOf(object));
    }

    public static String getLogs() {
        synchronized (logLines) {
            return TextUtils.join("\n", logLines);
        }
    }

    public static void clearLogs() {
        synchronized (logLines) {
            logLines.clear();
        }
    }

    public static int getDrawableId(String name) {
        if (TextUtils.isEmpty(name)) {
            return 0;
        }
        try {
            return ApplicationLoader.applicationContext.getResources().getIdentifier(name, "drawable", ApplicationLoader.applicationContext.getPackageName());
        } catch (Throwable e) {
            return 0;
        }
    }

    // Plugin list, for the UI

    public static List<PluginInfo> getPlugins() {
        ArrayList<PluginInfo> list = new ArrayList<>(plugins.values());
        Collections.sort(list, Comparator.comparing(p -> p.name.toLowerCase()));
        return list;
    }

    public static PluginInfo getPlugin(String id) {
        return id == null ? null : plugins.get(id);
    }

    public static List<MenuItem> getMenuItems(int type) {
        ArrayList<MenuItem> list = new ArrayList<>();
        if (!started) {
            return list;
        }
        for (MenuItem item : menuItems) {
            if (item.type == type && plugins.containsKey(item.pluginId)) {
                list.add(item);
            }
        }
        Collections.sort(list, (a, b) -> Integer.compare(b.priority, a.priority));
        return list;
    }

    public static PyObject getEngine() {
        return started ? engine : null;
    }

    public static void setPluginEnabled(String id, boolean enabled, Runnable done) {
        pluginsQueue.postRunnable(() -> {
            PyObject module = getEngine();
            if (module != null) {
                try {
                    module.callAttr("set_enabled", id, enabled);
                } catch (Throwable e) {
                    log(id, Log.getStackTraceString(e));
                }
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    /** Metadata of a plugin file: id, name, version, author, description, icon, "1" when installed. */
    public static String[] readPluginFile(File file) throws Exception {
        PyObject module = getEngine();
        if (module == null) {
            throw new IllegalStateException("Plugins engine is not running");
        }
        Object[] values = module.callAttr("read_metadata", file.getAbsolutePath()).toJava(Object[].class);
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] == null ? "" : values[i].toString();
        }
        return result;
    }

    public static void installPlugin(File file, Utilities.Callback2<String, String> done) {
        installPlugin(file, true, done);
    }

    public static void installPlugin(File file, boolean enable, Utilities.Callback2<String, String> done) {
        pluginsQueue.postRunnable(() -> {
            String id = null;
            String error = null;
            PyObject module = getEngine();
            if (module == null) {
                error = "Plugins engine is not running";
            } else {
                try {
                    id = module.callAttr("install", file.getAbsolutePath(), enable).toString();
                    PluginInfo info = plugins.get(id);
                    if (info != null && info.error != null) {
                        error = info.error;
                    }
                } catch (Throwable e) {
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                    log("plugins", Log.getStackTraceString(e));
                }
            }
            final String finalId = id;
            final String finalError = error;
            if (done != null) {
                AndroidUtilities.runOnUIThread(() -> done.run(finalId, finalError));
            }
        });
    }

    public static void uninstallPlugin(String id, Runnable done) {
        pluginsQueue.postRunnable(() -> {
            PyObject module = getEngine();
            if (module != null) {
                try {
                    module.callAttr("uninstall", id);
                } catch (Throwable e) {
                    log(id, Log.getStackTraceString(e));
                }
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public static void onAppEvent(String event) {
        if (!started) {
            return;
        }
        pluginsQueue.postRunnable(() -> {
            PyObject module = getEngine();
            if (module == null) {
                return;
            }
            try {
                module.callAttr("app_event", event);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Lets a plugin open a file tapped in a chat; true when a plugin took it. */
    public static boolean openFile(int place, File file, String fileName, Object message, Object activity, Object fragment) {
        if (!started || handledFileExtensions.isEmpty() || fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || !handledFileExtensions.contains(fileName.substring(dot + 1).toLowerCase())) {
            return false;
        }
        PyObject module = getEngine();
        if (module == null) {
            return false;
        }
        try {
            return module.callAttr("open_file", place, file, fileName, message, activity, fragment).toBoolean();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // Hooks

    /** Name of a TL object as plugins see it, e.g. TL_messages_setTyping or TL_account_updateStatus. */
    public static String tlName(Object object) {
        Class<?> cls = object.getClass();
        String name = tlNames.get(cls);
        if (name == null) {
            name = cls.getSimpleName();
            Class<?> outer = cls.getEnclosingClass();
            if (outer != null && outer != TLRPC.class && !name.startsWith("TL_")) {
                name = outer.getSimpleName() + "_" + name;
            }
            tlNames.put(cls, name);
        }
        return name;
    }

    private static boolean isHooked(String name) {
        if (exactHooks.contains(name)) {
            return true;
        }
        for (String part : substringHooks) {
            if (name.contains(part)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasHook(Object object) {
        return started && object != null && (!exactHooks.isEmpty() || substringHooks.length > 0) && isHooked(tlName(object));
    }

    /** The request to send, possibly changed by plugins, or null when a plugin cancelled it. */
    public static TLObject onPreRequest(int account, TLObject request) {
        PyObject module = engine;
        if (module == null) {
            return request;
        }
        try {
            PyObject result = module.callAttr("pre_request", tlName(request), account, request);
            return result == null ? null : result.toJava(TLObject.class);
        } catch (Throwable e) {
            FileLog.e(e);
            return request;
        }
    }

    /** {response, error} after plugins, or null when a plugin cancelled the callback. */
    public static Object[] onPostRequest(int account, TLObject request, TLObject response, TLRPC.TL_error error) {
        PyObject module = engine;
        if (module == null) {
            return new Object[]{response, error};
        }
        try {
            PyObject result = module.callAttr("post_request", tlName(request), account, response, error);
            return result == null ? null : result.toJava(Object[].class);
        } catch (Throwable e) {
            FileLog.e(e);
            return new Object[]{response, error};
        }
    }

    public static TLRPC.Update onUpdate(int account, TLRPC.Update update) {
        PyObject module = engine;
        if (module == null) {
            return update;
        }
        try {
            PyObject result = module.callAttr("on_update", tlName(update), account, update);
            return result == null ? null : result.toJava(TLRPC.Update.class);
        } catch (Throwable e) {
            FileLog.e(e);
            return update;
        }
    }

    public static TLRPC.Updates onUpdates(int account, TLRPC.Updates updates) {
        PyObject module = engine;
        if (module == null) {
            return updates;
        }
        try {
            PyObject result = module.callAttr("on_updates", tlName(updates), account, updates);
            return result == null ? null : result.toJava(TLRPC.Updates.class);
        } catch (Throwable e) {
            FileLog.e(e);
            return updates;
        }
    }

    public static boolean hasSendMessageHook() {
        return started && sendMessageHook;
    }

    public static SendMessagesHelper.SendMessageParams onSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        PyObject module = engine;
        if (module == null) {
            return params;
        }
        try {
            PyObject result = module.callAttr("on_send_message", account, params);
            return result == null ? null : result.toJava(SendMessagesHelper.SendMessageParams.class);
        } catch (Throwable e) {
            FileLog.e(e);
            return params;
        }
    }
}

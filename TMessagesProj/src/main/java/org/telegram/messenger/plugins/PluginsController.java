package org.telegram.messenger.plugins;

import android.os.Build;
import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.plugins.hooks.MenuItemRecord;
import com.exteragram.messenger.plugins.hooks.PluginsHooks;
import com.exteragram.messenger.utils.PluginLogs;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * glassGram's way into the plugin engine, which is exteraGram's (com.exteragram.messenger.plugins,
 * its Python SDK in plugins-pysdk): what the app and glassGram's plugin screens ask of it, in the
 * shape they had before the engine was exteraGram's. Requests, updates and outgoing messages go
 * through the plugins' hooks only when an active plugin hooks them (hasHook).
 */
public final class PluginsController {

    public static final int MENU_MESSAGE_CONTEXT = 0;
    public static final int MENU_DRAWER = 1;
    public static final int MENU_MAIN = 2;
    public static final int MENU_CHAT_ACTION = 3;
    public static final int MENU_PROFILE_ACTION = 4;

    private static final String[] MENU_TYPES = {
            PluginsConstants.MenuItemTypes.MESSAGE_CONTEXT_MENU,
            PluginsConstants.MenuItemTypes.DRAWER_MENU,
            PluginsConstants.MenuItemTypes.MAIN_MENU,
            PluginsConstants.MenuItemTypes.CHAT_ACTION_MENU,
            PluginsConstants.MenuItemTypes.PROFILE_ACTION_MENU,
    };

    private static final Map<Class<?>, String> tlNames = new ConcurrentHashMap<>();
    private static volatile boolean initRequested;

    /** An item a plugin added to one of the app's menus. */
    public static class MenuItem {
        public final String pluginId;
        public final String itemId;
        public final String text;
        public final String subtext;
        private final MenuItemRecord record;

        MenuItem(MenuItemRecord record) {
            this.record = record;
            this.pluginId = record.getPluginId();
            this.itemId = record.getItemId();
            this.text = record.getText() == null ? "" : record.getText();
            this.subtext = record.getSubtext();
        }

        public int getIconResId() {
            return record.getIconResId();
        }

        public void click(HashMap<String, Object> context) {
            com.exteragram.messenger.plugins.PluginsController.runOnPluginsQueue(() -> record.executeClick(context));
        }
    }

    private PluginsController() {
    }

    private static com.exteragram.messenger.plugins.PluginsController engine() {
        return com.exteragram.messenger.plugins.PluginsController.getInstance();
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    /** Whether the engine is on and has started (plugins may not have loaded yet). */
    public static boolean isStarted() {
        return initRequested && ExteraConfig.pluginsEngine && engine().isInitialized();
    }

    public static boolean isStarting() {
        return initRequested && ExteraConfig.pluginsEngine && !engine().isInitialized();
    }

    /** Why the plugins can't run, as the engine's screen shows it; null when they can. */
    public static String getStartError() {
        if (!isStarted() || com.exteragram.messenger.plugins.PluginsController.isPluginEngineAvailable()) {
            return null;
        }
        if (ExteraConfig.pluginsSafeMode) {
            return null;
        }
        final CharSequence state = PythonPluginsEngine.Updater.getVersion();
        return state == null ? null : state.toString();
    }

    /** At app start: the engine's settings, and the engine itself when it is on. */
    public static void init() {
        GlassgramConfig.load();
        ExteraConfig.loadConfig();
        ExteraConfig.pluginsEngine = isSupported() && GlassgramConfig.pluginsEngine;
        com.exteragram.messenger.plugins.PluginsController.applyArtOpts();
        if (ExteraConfig.pluginsEngine) {
            start();
        }
    }

    private static final java.util.ArrayList<Runnable> whenStarted = new java.util.ArrayList<>();
    private static volatile boolean initializing;

    private static void start() {
        start(null);
    }

    /** Starts the engine (once), then runs then on the main thread. */
    private static void start(Runnable then) {
        synchronized (whenStarted) {
            if (then != null) {
                whenStarted.add(then);
            }
            if (initializing) {
                return;
            }
            if (initRequested && engine().isInitialized()) {
                flushStarted();
                return;
            }
            initRequested = true;
            initializing = true;
        }
        runOnPluginsQueue(() -> {
            migrateGlassgramPlugins();
            engine().init(() -> {
                synchronized (whenStarted) {
                    initializing = false;
                    flushStarted();
                }
                notifyChanged();
            });
        });
    }

    private static void flushStarted() {
        final ArrayList<Runnable> runs = new ArrayList<>(whenStarted);
        whenStarted.clear();
        for (Runnable run : runs) {
            AndroidUtilities.runOnUIThread(run);
        }
    }

    /**
     * Turns the engine on if it is off, then runs then on the main thread once it has started (its
     * plugins may still be loading).
     */
    public static void whenStarted(Runnable then) {
        if (!isSupported()) {
            return;
        }
        if (!GlassgramConfig.pluginsEngine) {
            GlassgramConfig.pluginsEngine = true;
            GlassgramConfig.putBoolean("pluginsEngine", true);
            ExteraConfig.pluginsEngine = true;
            notifyChanged();
        }
        start(then);
    }

    /**
     * Plugins installed before the engine was exteraGram's: their files are where exteraGram's
     * engine keeps them (files/plugins/<id>.py); which were on, and their settings, move to where it
     * keeps those (plugin_settings preferences, plugins/plugin_settings.json). Once.
     */
    private static void migrateGlassgramPlugins() {
        final java.io.File dir = new java.io.File(ApplicationLoader.getFilesDirFixed(), PluginsConstants.PLUGINS);
        final java.io.File state = new java.io.File(dir, "plugins_state.json");
        if (!state.isFile()) {
            return;
        }
        try {
            final org.json.JSONObject plugins = new org.json.JSONObject(readText(state));
            final android.content.SharedPreferences.Editor enabled = ApplicationLoader.applicationContext
                    .getSharedPreferences("plugin_settings", android.content.Context.MODE_PRIVATE).edit();
            final java.util.Iterator<String> ids = plugins.keys();
            while (ids.hasNext()) {
                final String id = ids.next();
                final org.json.JSONObject plugin = plugins.optJSONObject(id);
                if (plugin != null && plugin.has("enabled")) {
                    enabled.putBoolean(com.exteragram.messenger.plugins.PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + id, plugin.optBoolean("enabled"));
                }
            }
            enabled.apply();
            final java.io.File settingsDir = new java.io.File(dir, "settings");
            final java.io.File settings = new java.io.File(dir, "plugin_settings.json");
            final java.io.File[] files = settingsDir.listFiles();
            if (!settings.exists() && files != null) {
                final org.json.JSONObject all = new org.json.JSONObject();
                for (java.io.File file : files) {
                    final String name = file.getName();
                    if (name.endsWith(".json")) {
                        try {
                            all.put(name.substring(0, name.length() - 5), new org.json.JSONObject(readText(file)));
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(settings)) {
                    out.write(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!state.renameTo(new java.io.File(dir, "plugins_state.json.migrated"))) {
            state.delete();
        }
    }

    private static String readText(java.io.File file) throws java.io.IOException {
        final byte[] data = new byte[(int) Math.min(file.length(), 16 * 1024 * 1024)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < data.length) {
                final int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(data, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static void setEngineEnabled(boolean enabled) {
        enabled &= isSupported();
        GlassgramConfig.pluginsEngine = enabled;
        GlassgramConfig.putBoolean("pluginsEngine", enabled);
        ExteraConfig.pluginsEngine = enabled;
        if (enabled) {
            start();
        } else if (initRequested) {
            initRequested = false;
            engine().shutdown(PluginsController::notifyChanged);
        }
        notifyChanged();
    }

    /** Restarts the engine, in safe mode (no plugin loads) or not. */
    public static void restart(boolean safeMode) {
        ExteraConfig.setPluginsSetting("pluginsSafeMode", safeMode);
        if (ExteraConfig.pluginsEngine) {
            initRequested = true;
            engine().restart(safeMode);
        }
    }

    public static void runOnPluginsQueue(Runnable runnable) {
        com.exteragram.messenger.plugins.PluginsController.runOnPluginsQueue(runnable);
    }

    static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginsUpdated));
    }

    // --- The plugins ---------------------------------------------------------------------------

    private static PluginInfo info(Plugin plugin) {
        final com.exteragram.messenger.plugins.PluginsController controller = engine();
        final String id = plugin.getId();
        final Throwable error = plugin.getError();
        String errorText = null;
        if (error != null) {
            errorText = error.getMessage() != null ? error.getMessage() : error.toString();
        } else if (plugin.isNotResponding()) {
            errorText = "Not responding";
        }
        String path = null;
        try {
            path = controller.getPluginPath(id);
        } catch (Throwable ignore) {
        }
        return new PluginInfo(id, plugin.getName(), plugin.getDescription(), plugin.getAuthor(), plugin.getVersion(), plugin.getIcon(),
                plugin.isEnabled() || error != null, errorText, controller.hasPluginSettings(id), path);
    }

    public static List<PluginInfo> getPlugins() {
        final ArrayList<PluginInfo> list = new ArrayList<>();
        for (Plugin plugin : engine().getPlugins().values()) {
            list.add(info(plugin));
        }
        Collections.sort(list, (a, b) -> a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT)));
        return list;
    }

    public static PluginInfo getPlugin(String id) {
        if (id == null) {
            return null;
        }
        final Plugin plugin = engine().getPlugins().get(id);
        return plugin == null ? null : info(plugin);
    }

    /** exteraGram's model of an installed plugin, for its screens. */
    public static Plugin getExteraPlugin(String id) {
        return id == null ? null : engine().getPlugins().get(id);
    }

    /** Turns a plugin on or off; done gets null, or why it couldn't, on the main thread. */
    public static void setPluginEnabled(String id, boolean enabled, Runnable done) {
        engine().setPluginEnabled(id, enabled, error -> {
            if (error != null) {
                log(id, error);
            }
            notifyChanged();
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public static void uninstallPlugin(String id, Runnable done) {
        engine().deletePlugin(id, error -> {
            if (error != null) {
                log(id, error);
            }
            notifyChanged();
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    /**
     * A plugin file's {id, name, version, author, description, icon}, read by the engine that runs
     * it; on the plugins' queue, the engine started. Throws when the file isn't a plugin.
     */
    public static String[] readMetadata(java.io.File file) throws Exception {
        final com.exteragram.messenger.plugins.PluginsController.PluginsEngine engine =
                com.exteragram.messenger.plugins.PluginsController.getPluginEngine(file);
        if (engine == null) {
            throw new IllegalArgumentException("Not a plugin: " + file.getName());
        }
        final Map<String, String> meta = engine.parsePluginMetadata(file.getAbsolutePath());
        if (meta == null || TextUtils.isEmpty(meta.get("id"))) {
            throw new IllegalArgumentException("A plugin needs __id__ and __name__");
        }
        final String[] keys = {"id", "name", "version", "author", "description", "icon"};
        final String[] values = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            final String value = meta.get(keys[i]);
            values[i] = value == null ? "" : value;
        }
        return values;
    }

    // --- Menus ---------------------------------------------------------------------------------

    public static List<MenuItem> getMenuItems(int type) {
        return getMenuItems(type, new HashMap<>());
    }

    public static List<MenuItem> getMenuItems(int type, HashMap<String, Object> context) {
        final ArrayList<MenuItem> list = new ArrayList<>();
        if (!isStarted() || type < 0 || type >= MENU_TYPES.length) {
            return list;
        }
        try {
            for (MenuItemRecord record : engine().getMenuItemsForLocation(MENU_TYPES[type], context)) {
                list.add(new MenuItem(record));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return list;
    }

    // --- Events and hooks ----------------------------------------------------------------------

    /** The app was paused, stopped or resumed ("pause", "stop", "resume"), or started ("start"). */
    public static void onAppEvent(String event) {
        if (!isStarted()) {
            return;
        }
        final String type;
        switch (event) {
            case "start": type = PluginsConstants.APP_START; break;
            case "stop": type = PluginsConstants.APP_STOP; break;
            case "pause": type = PluginsConstants.APP_PAUSE; break;
            case "resume": type = PluginsConstants.APP_RESUME; break;
            default: type = event;
        }
        runOnPluginsQueue(() -> engine().executeOnAppEvent(type));
    }

    /** Name of a TL object as plugins hook it, e.g. TL_messages_setTyping or TL_account_updateStatus. */
    public static String tlName(Object object) {
        final Class<?> cls = object.getClass();
        String name = tlNames.get(cls);
        if (name == null) {
            name = cls.getSimpleName();
            final Class<?> outer = cls.getEnclosingClass();
            if (outer != null && outer != TLRPC.class && !name.startsWith("TL_")) {
                name = outer.getSimpleName() + "_" + name;
            }
            tlNames.put(cls, name);
        }
        return name;
    }

    /** Whether an active plugin hooks this request, update or updates. */
    public static boolean hasHook(Object object) {
        return object != null && isStarted() && engine().hasEventHook(tlName(object));
    }

    /** The ids of the plugins hooking this request, comma-separated (PluginActionGuard). */
    static String hookingPlugins(TLObject request) {
        return TextUtils.join(",", engine().getPluginsHooking(tlName(request)));
    }

    /** The request to send, possibly changed by plugins, or null when a plugin cancelled it. */
    public static TLObject onPreRequest(int account, TLObject request) {
        try {
            return engine().executePreRequestHook(tlName(request), account, request);
        } catch (Throwable e) {
            FileLog.e(e);
            return request;
        }
    }

    /** {response, error} after plugins. */
    public static Object[] onPostRequest(int account, TLObject request, TLObject response, TLRPC.TL_error error) {
        try {
            final PluginsHooks.PostRequestResult result = engine().executePostRequestHook(tlName(request), account, response, error);
            return new Object[]{result.getResponse(), result.getError()};
        } catch (Throwable e) {
            FileLog.e(e);
            return new Object[]{response, error};
        }
    }

    public static TLRPC.Update onUpdate(int account, TLRPC.Update update) {
        try {
            return engine().executeUpdateHook(tlName(update), account, update);
        } catch (Throwable e) {
            FileLog.e(e);
            return update;
        }
    }

    public static TLRPC.Updates onUpdates(int account, TLRPC.Updates updates) {
        try {
            return engine().executeUpdatesHook(tlName(updates), account, updates);
        } catch (Throwable e) {
            FileLog.e(e);
            return updates;
        }
    }

    public static boolean hasSendMessageHook() {
        return isStarted() && engine().hasEventHook(PluginsConstants.SEND_MESSAGE_HOOK);
    }

    public static SendMessagesHelper.SendMessageParams onSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        try {
            return engine().executeSendMessageHook(account, params);
        } catch (Throwable e) {
            FileLog.e(e);
            return params;
        }
    }

    /**
     * The app is crashing: when a plugin's code is on the crashing thread, the engine turns that
     * plugin off on the next start (or starts in safe mode when it can't tell which), as exteraGram's.
     */
    public static void onCrash(Thread thread, Throwable exception) {
        if (!initRequested) {
            return;
        }
        try {
            String pluginId = engine().getWatchdog().executingPlugin(thread);
            if (pluginId == null && !isPluginsStack(exception)) {
                return;
            }
            ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean("had_crash", true)
                    .putString("crashed_plugin_id", pluginId)
                    .commit();
        } catch (Throwable ignore) {
        }
    }

    private static boolean isPluginsStack(Throwable exception) {
        for (Throwable e = exception; e != null; e = e.getCause()) {
            for (StackTraceElement frame : e.getStackTrace()) {
                final String name = frame.getClassName();
                if (name.startsWith("com.chaquo.python.") || name.startsWith("com.exteragram.messenger.plugins.xposed.")
                        || name.startsWith("LSPHooker_")) {
                    return true;
                }
            }
            if (e.getCause() == e) {
                break;
            }
        }
        return false;
    }

    // --- Logs and the rest ---------------------------------------------------------------------

    public static void log(String tag, String text) {
        PluginLogs.add("[" + (TextUtils.isEmpty(tag) ? "plugins" : tag) + "] " + text, null);
    }

    public static String getLogs() {
        return TextUtils.join("\n", PluginLogs.get());
    }

    public static void clearLogs() {
        PluginLogs.clear();
    }

    /** The developer mode of exteraGram's engine: its dev server, for the SDK's tools. */
    public static boolean isDevMode() {
        return ExteraConfig.pluginsDevMode;
    }

    public static void setDevMode(boolean enabled) {
        ExteraConfig.setPluginsSetting("pluginsDevMode", enabled);
        if (isStarted()) {
            runOnPluginsQueue(() -> engine().checkDevServers());
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
}

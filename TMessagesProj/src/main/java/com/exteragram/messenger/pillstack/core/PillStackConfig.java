/* Based on exteraGram, Copyright @exteraGram, 2022-2026.
 * GPL v3 or later, with ADDITIONAL_TERMS.md. Modified for glassGram, 2026.
 * Upstream: https://github.com/exteraSquad/exteraGram
 */
package com.exteragram.messenger.pillstack.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;

public final class PillStackConfig {
    public static final String CONFIG_NAME = "pillstackconfig";
    private static final List<Integer> active = new CopyOnWriteArrayList<>();
    private static final List<Integer> hidden = new CopyOnWriteArrayList<>();
    private static final Set<Integer> pending = ConcurrentHashMap.newKeySet();
    private static volatile boolean loaded;
    private static boolean infinite = true;
    private static int last = -1;

    public static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(CONFIG_NAME, Context.MODE_PRIVATE);
    }
    public static SharedPreferences.Editor getEditor() { return getPreferences().edit(); }
    private static void readList(List<Integer> list, String value) {
        list.clear();
        if (value == null) return;
        for (String part : value.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            try { int id = Integer.parseInt(part.trim()); if (!list.contains(id)) list.add(id); }
            catch (NumberFormatException ignored) {}
        }
    }
    private static String writeList(List<Integer> list) {
        StringBuilder b = new StringBuilder();
        for (Integer id : list) { if (b.length() > 0) b.append(','); b.append(id); }
        return b.toString();
    }
    public static synchronized void loadConfig() {
        if (loaded) return;
        SharedPreferences p = getPreferences();
        readList(active, p.getString("activePills", ""));
        readList(hidden, p.getString("hiddenPills", ""));
        infinite = p.getBoolean("infiniteScrolling", true);
        last = p.getInt("lastActivePillId", -1);
        loaded = true;
    }
    public static synchronized void reloadConfig() { loaded = false; loadConfig(); }
    public static boolean getConfigLoaded() { return loaded; }
    public static List<Integer> getActivePills() { loadConfig(); return active; }
    public static List<Integer> getHiddenPills() { loadConfig(); return hidden; }
    public static ArrayList<Integer> getDefaultActivePills() { return new ArrayList<>(); }
    public static boolean getInfiniteScrolling() { loadConfig(); return infinite; }
    public static void setInfiniteScrolling(boolean value) { loadConfig(); infinite = value; getEditor().putBoolean("infiniteScrolling", value).apply(); }
    public static int getLastActivePillId() { loadConfig(); return last; }
    public static void setLastActivePillId(int id) { saveLastActivePillId(id); }
    public static void saveLastActivePillId(int id) { loadConfig(); last = id; getEditor().putInt("lastActivePillId", id).apply(); }
    public static void savePillsLayout() {
        loadConfig();
        getEditor().putString("activePills", writeList(active)).putString("hiddenPills", writeList(hidden)).apply();
    }
    public static void sanitizePills() {
        loadConfig();
        // Unknown IDs may belong to plugins that have not yet loaded. Keep their positions.
        hidden.removeAll(active);
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            if (!active.contains(info.id()) && !hidden.contains(info.id())) hidden.add(info.id());
        }
        savePillsLayout();
    }
    public static void notifySettingsChanged(int... ids) {
        if (ids.length == 0) for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) pending.add(info.id());
        Object[] args = new Object[ids.length];
        for (int i = 0; i < ids.length; i++) { pending.add(ids[i]); args[i] = ids[i]; }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.pillStackSettingsChanged, args));
    }
    public static boolean checkAndClearPendingUpdate(int id) { return pending.remove(id); }
    public static boolean shouldUpdatePill(Object[] args, int... ids) {
        if (args == null || args.length == 0 || ids.length == 0) return true;
        for (Object arg : args) for (int id : ids) if (Integer.valueOf(id).equals(arg)) return true;
        return false;
    }
}

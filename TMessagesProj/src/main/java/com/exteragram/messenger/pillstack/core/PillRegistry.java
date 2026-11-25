/* Based on exteraGram, Copyright @exteraGram, 2022-2026.
 * GPL v3 or later, with ADDITIONAL_TERMS.md. Modified for glassGram, 2026.
 * Upstream: https://github.com/exteraSquad/exteraGram
 */
package com.exteragram.messenger.pillstack.core;

import android.content.Context;
import androidx.annotation.Keep;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

/** Plugin-provided pills; the built-in exteraGram weather/currency pills are not bundled. */
@Keep
public final class PillRegistry {
    public interface PillCreator {
        BasePill create(Context context, Theme.ResourcesProvider resourcesProvider);
    }

    // Java 8 compatible replacement for upstream's record, with identical accessors.
    @Keep
    public static final class PillInfo {
        private final int id, iconRes, iconColorTop, iconColorBottom;
        private final CharSequence name;
        private final PillCreator creator;
        public PillInfo(int id, CharSequence name, int iconRes, int iconColorTop, int iconColorBottom, PillCreator creator) {
            this.id = id; this.name = name; this.iconRes = iconRes;
            this.iconColorTop = iconColorTop; this.iconColorBottom = iconColorBottom; this.creator = creator;
        }
        public int id() { return id; }
        public CharSequence name() { return name; }
        public int iconRes() { return iconRes; }
        public int iconColorTop() { return iconColorTop; }
        public int iconColorBottom() { return iconColorBottom; }
        public PillCreator creator() { return creator; }
    }

    private static final Map<Integer, PillInfo> registry = new LinkedHashMap<>();
    private static boolean batchRegistration;

    public static synchronized void beginTransaction() { batchRegistration = true; }
    public static synchronized void endTransaction() { batchRegistration = false; changed(); }
    public static synchronized void register(PillInfo info) {
        if (info == null) return;
        registry.put(info.id(), info);
        if (!PillStackConfig.getActivePills().contains(info.id()) && !PillStackConfig.getHiddenPills().contains(info.id())) {
            PillStackConfig.getHiddenPills().add(info.id());
            PillStackConfig.savePillsLayout();
        }
        if (!batchRegistration) changed();
    }
    public static synchronized PillInfo getPillInfo(int id) { return registry.get(id); }
    public static synchronized Collection<PillInfo> getRegisteredPills() { return new ArrayList<>(registry.values()); }
    public static synchronized boolean isRegistered(int id) { return registry.containsKey(id); }
    public static synchronized void activatePill(int id) {
        if (!isRegistered(id)) return;
        if (!PillStackConfig.getActivePills().contains(id)) {
            PillStackConfig.getHiddenPills().remove(Integer.valueOf(id));
            PillStackConfig.getActivePills().add(id);
            PillStackConfig.savePillsLayout();
            changed();
        }
    }
    public static synchronized void unregister(int id) {
        if (registry.remove(id) == null) return;
        PillStackConfig.getActivePills().remove(Integer.valueOf(id));
        PillStackConfig.getHiddenPills().remove(Integer.valueOf(id));
        PillStackConfig.savePillsLayout();
        if (!batchRegistration) changed();
    }
    public static void changed() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.pillStackLayoutChanged));
    }
}

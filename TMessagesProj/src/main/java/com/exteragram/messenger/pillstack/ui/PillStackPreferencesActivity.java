/* Based on exteraGram, Copyright @exteraGram, 2022-2026.
 * GPL v3 or later, with ADDITIONAL_TERMS.md. Modified for glassGram, 2026.
 * Upstream: https://github.com/exteraSquad/exteraGram
 */
package com.exteragram.messenger.pillstack.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import com.exteragram.messenger.pillstack.core.PillRegistry;
import com.exteragram.messenger.pillstack.core.PillStackConfig;

public class PillStackPreferencesActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int INFINITE = -1001;
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.PillStackPills); }
    @Override public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackLayoutChanged);
        return super.onFragmentCreate();
    }
    @Override public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackLayoutChanged);
        super.onFragmentDestroy();
    }
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (listView != null) listView.adapter.update(true);
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(INFINITE, LocaleController.getString(R.string.PillStackInfiniteScrolling))
                .setChecked(PillStackConfig.getInfiniteScrolling()));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.PillStackPills)));
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            items.add(UItem.asCheck(info.id(), info.name()).setChecked(PillStackConfig.getActivePills().contains(info.id())));
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == INFINITE) {
            PillStackConfig.setInfiniteScrolling(!PillStackConfig.getInfiniteScrolling());
        } else if (PillRegistry.isRegistered(item.id)) {
            Integer id = item.id;
            if (PillStackConfig.getActivePills().remove(id)) {
                if (!PillStackConfig.getHiddenPills().contains(id)) PillStackConfig.getHiddenPills().add(id);
            } else {
                PillStackConfig.getHiddenPills().remove(id);
                PillStackConfig.getActivePills().add(id);
            }
            PillStackConfig.savePillsLayout();
            PillRegistry.changed();
        }
        if (listView != null) listView.adapter.update(true);
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}

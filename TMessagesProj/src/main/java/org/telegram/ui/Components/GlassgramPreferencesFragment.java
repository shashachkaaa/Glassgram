package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;

/** Shared settings chrome: native theme surfaces, rounded sections and the existing glass header. */
public abstract class GlassgramPreferencesFragment extends UniversalFragment {
    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    protected boolean useFadeDrawableForActionBar() {
        return false;
    }

    /** Lists with individually rendered cards (the plugin list) do not need another section around them. */
    protected boolean usePreferencesCards() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        final View view = super.createView(context);
        ThemeBind.backgroundColor(contentView, Theme.key_windowBackgroundGray, getResourceProvider());
        listView.adapter.setApplyBackground(false);
        if (usePreferencesCards()) {
            listView.setSections(dp(16), dp(21), false);
            final GlassgramSettingsCells.CardRenderer cards = new GlassgramSettingsCells.CardRenderer(getResourceProvider());
            listView.setSectionBackgroundRenderer(cards::draw);
        }
        return view;
    }

    @Override
    protected void onViewCreated(View view) {
        super.onViewCreated(view);
        if (listView != null && simpleActionBar != null) {
            if (usePreferencesCards()) {
                // BaseFragmentSimpleGlass enables the capture decoration. Restore our rounded renderer
                // while keeping the section capture enabled for the glass.
                listView.setLegacySections(true);
            }
            simpleActionBar.setBackground(getBaseSimpleGlass().createActionBarBlurDrawable(simpleActionBar));
        }
    }
}

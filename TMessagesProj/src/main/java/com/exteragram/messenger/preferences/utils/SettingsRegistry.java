package com.exteragram.messenger.preferences.utils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

/**
 * glassGram: the part of exteraGram's SettingsRegistry its plugin engine uses: its links to a
 * plugin's setting (t.me/exteraSettings?p=<plugin>&s=<setting>, which glassGram opens too) and the
 * notice for a setting that isn't there.
 */
public final class SettingsRegistry {

    public static final String deeplinkPrefix = "exteraSettings";
    public static final String deeplinkPluginIdQueryKey = "p";
    public static final String deeplinkSettingQueryKey = "s";
    public static final String SHRUG_MAN = "🤷‍♂️";

    private static final SettingsRegistry instance = new SettingsRegistry();

    private SettingsRegistry() {
    }

    public static SettingsRegistry getInstance() {
        return instance;
    }

    public void onSettingNotFound() {
        onSettingNotFound(LaunchActivity.getLastFragment());
    }

    public void onSettingNotFound(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        BulletinFactory.of(fragment).createEmojiBulletin(SHRUG_MAN, LocaleController.getString(R.string.NoSuchSetting)).show();
    }
}

package com.exteragram.messenger.utils.text;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

/** glassGram: the part of exteraGram's LocaleUtils its plugin engine uses. */
public final class LocaleUtils {

    private LocaleUtils() {
    }

    public static String getAppName() {
        return "glassGram";
    }

    /** The "Copy" button of a notice (exteraGram's draws an icon in it). */
    public static CharSequence createCopySpan(BaseFragment fragment) {
        return LocaleController.getString(R.string.Copy);
    }
}

package com.exteragram.messenger.utils;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Keep;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * glassGram: where intents the app gets pass first, as in exteraGram: the SDK's IntentsManager
 * hooks handleIntent so that a plugin can handle a link or an intent (its hook returns true to take
 * it). The body only logs: it is long enough that ART doesn't inline it into LaunchActivity, where
 * the hook would miss it.
 */
@Keep
public final class IntentsController {

    private IntentsController() {
    }

    /** Whether a plugin handled the intent; the app leaves it then. */
    @Keep
    public static boolean handleIntent(Intent intent) {
        if (intent == null || !BuildVars.LOGS_ENABLED) {
            return false;
        }
        final String action = intent.getAction();
        final Uri data = intent.getData();
        final StringBuilder line = new StringBuilder("intent");
        if (action != null) {
            line.append(' ').append(action);
        }
        if (data != null) {
            line.append(' ').append(data.getScheme()).append("://").append(data.getHost());
        }
        if (intent.getType() != null) {
            line.append(' ').append(intent.getType());
        }
        FileLog.d(line.toString());
        return false;
    }
}

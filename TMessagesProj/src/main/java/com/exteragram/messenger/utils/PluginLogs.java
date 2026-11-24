package com.exteragram.messenger.utils;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/**
 * glassGram: the last lines plugins and their engine logged (AppUtils.log, the SDK's log_utils),
 * for the log screen in glassGram's plugin settings and for sharing with a plugin's author.
 */
public final class PluginLogs {

    private static final int MAX_LINES = 600;
    private static final ArrayList<String> lines = new ArrayList<>();

    private PluginLogs() {
    }

    public static void add(String line, Throwable error) {
        if (error != null) {
            final StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            line = line + "\n" + trace;
        }
        synchronized (lines) {
            lines.add(line);
            while (lines.size() > MAX_LINES) {
                lines.remove(0);
            }
        }
    }

    /** A line a plugin wrote itself, from the SDK's log handler. */
    public static void plugin(String pluginId, String level, String message) {
        add("[" + (pluginId == null ? "plugins" : pluginId) + "] " + (level == null ? "" : level + ": ") + message, null);
        Log.i("Plugins", String.valueOf(message));
    }

    public static ArrayList<String> get() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    public static void clear() {
        synchronized (lines) {
            lines.clear();
        }
    }
}

package org.telegram.messenger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.widget.Toast;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keeps the last crash on the device and offers to copy it on the next start, so a crash
 * can be reported without enabling debug logs.
 */
public final class GlassgramCrashReporter {

    private static final String FILE_NAME = "glassgram_last_crash.txt";
    private static boolean installed;
    private static boolean shown;

    // The screens made since the start, weakly: a crash's report says how many of each are still
    // in memory against how many are open, so a screen kept after it closed shows (an out-of-memory
    // crash's trace says only where the memory ran out, not what holds it)
    private static final ArrayList<WeakReference<BaseFragment>> screens = new ArrayList<>();
    private static int screensPruneAt = 64;

    private GlassgramCrashReporter() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static void install(Context context) {
        if (installed || context == null) {
            return;
        }
        installed = true;
        final Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            try {
                StringWriter trace = new StringWriter();
                exception.printStackTrace(new PrintWriter(trace));
                String report = "glassGram " + BuildVars.BUILD_VERSION_STRING
                        + "\nAndroid " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL
                        + "\nThread: " + thread.getName()
                        + "\n\n" + trace;
                FileOutputStream out = new FileOutputStream(file(appContext), false);
                out.write(report.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Throwable ignore) {
            }
            try {
                // after the crash itself, so the report is there even when there is no memory for this
                final String memory = memoryReport();
                FileOutputStream out = new FileOutputStream(file(appContext), true);
                out.write(memory.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Throwable ignore) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, exception);
            }
        });
    }

    /** Counts a screen made (BaseFragment) for the report of a crash. */
    public static void trackScreen(BaseFragment screen) {
        synchronized (screens) {
            if (screens.size() >= screensPruneAt) {
                for (int i = screens.size() - 1; i >= 0; i--) {
                    if (screens.get(i).get() == null) {
                        screens.remove(i);
                    }
                }
                screensPruneAt = Math.max(64, screens.size() * 2);
            }
            screens.add(new WeakReference<>(screen));
        }
    }

    /** The memory used, and the screens open and in memory, by kind. */
    private static String memoryReport() {
        final Runtime runtime = Runtime.getRuntime();
        final long mb = 1024 * 1024;
        final StringBuilder report = new StringBuilder("\nMemory: ")
                .append((runtime.totalMemory() - runtime.freeMemory()) / mb).append(" of ").append(runtime.maxMemory() / mb)
                .append(" MB, native ").append(Debug.getNativeHeapAllocatedSize() / mb).append(" MB");
        final TreeMap<String, Integer> open = new TreeMap<>();
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity != null) {
            countOpen(open, activity.getActionBarLayout());
            countOpen(open, activity.getRightActionBarLayout());
            countOpen(open, activity.getLayersActionBarLayout());
        }
        final TreeMap<String, Integer> alive = new TreeMap<>();
        synchronized (screens) {
            for (int i = 0; i < screens.size(); i++) {
                final BaseFragment screen = screens.get(i).get();
                if (screen != null) {
                    count(alive, screen);
                }
            }
        }
        report.append("\nScreens open: ").append(open.isEmpty() ? "-" : open.toString());
        report.append("\nScreens in memory: ").append(alive.isEmpty() ? "-" : alive.toString());
        boolean kept = false;
        for (Map.Entry<String, Integer> entry : alive.entrySet()) {
            final Integer opened = open.get(entry.getKey());
            final int extra = entry.getValue() - (opened == null ? 0 : opened);
            if (extra > 1) {
                report.append(kept ? ", " : "\nKept after closing: ").append(entry.getKey()).append(" x").append(extra);
                kept = true;
            }
        }
        return report.append('\n').toString();
    }

    private static void countOpen(TreeMap<String, Integer> open, INavigationLayout layout) {
        final List<BaseFragment> stack = layout == null ? null : layout.getFragmentStack();
        if (stack == null) {
            return;
        }
        for (int i = 0; i < stack.size(); i++) {
            final BaseFragment screen = stack.get(i);
            if (screen != null) {
                count(open, screen);
            }
        }
    }

    private static void count(TreeMap<String, Integer> counts, BaseFragment screen) {
        final String name = screen.getClass().getSimpleName();
        final Integer count = counts.get(name);
        counts.put(name, count == null ? 1 : count + 1);
    }

    /** Shows the saved crash once, with a button that copies it, and forgets it. */
    public static void showIfNeeded(Activity activity) {
        if (shown || activity == null) {
            return;
        }
        // read every time, so an exit already reported as a crash of Java isn't reported again
        final String systemReport = systemExitReport(activity);
        File f = file(activity);
        String report;
        if (f.exists()) {
            try {
                byte[] data = new byte[(int) Math.min(f.length(), 64 * 1024)];
                FileInputStream in = new FileInputStream(f);
                int read = in.read(data);
                in.close();
                report = new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            } catch (Throwable e) {
                report = null;
            }
            f.delete();
        } else {
            // a crash in native code or the app hanging never reaches the handler above
            report = systemReport;
        }
        shown = true;
        if (report == null || report.isEmpty()) {
            return;
        }
        // to glassGram's server too, anonymously, unless the statistics are off
        GlassgramStats.sendCrash(report);
        final String text = report;
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString(R.string.GlassgramCrashTitle));
            builder.setMessage(LocaleController.getString(R.string.GlassgramCrashText));
            builder.setPositiveButton(LocaleController.getString(R.string.GlassgramCrashCopy), (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("glassGram crash", text));
                    Toast.makeText(activity, LocaleController.getString(R.string.TextCopied), Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
            builder.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static final String EXIT_SEEN_KEY = "glassgram_last_exit_seen";
    private static final int TRACE_LIMIT = 48 * 1024;

    /**
     * Android 11+: the system's record of how the app last ended, when that was a crash in native
     * code, the app hanging (ANR) or another crash, as a report; null otherwise or when already shown.
     */
    private static String systemExitReport(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        try {
            final android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return null;
            }
            final List<android.app.ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 8);
            if (exits == null || exits.isEmpty()) {
                return null;
            }
            final android.content.SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            final long seen = preferences.getLong(EXIT_SEEN_KEY, 0);
            android.app.ApplicationExitInfo last = null;
            for (android.app.ApplicationExitInfo exit : exits) {
                // the app's own process, not a service of it
                if (context.getPackageName().equals(exit.getProcessName()) && (last == null || exit.getTimestamp() > last.getTimestamp())) {
                    last = exit;
                }
            }
            if (last == null || last.getTimestamp() <= seen) {
                return null;
            }
            preferences.edit().putLong(EXIT_SEEN_KEY, last.getTimestamp()).apply();
            // the first look, as after an update: only an exit of just now is news
            if (seen == 0 && System.currentTimeMillis() - last.getTimestamp() > 60 * 60 * 1000L) {
                return null;
            }
            final String kind;
            switch (last.getReason()) {
                case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: kind = "native crash"; break;
                case android.app.ApplicationExitInfo.REASON_ANR: kind = "ANR (not responding)"; break;
                case android.app.ApplicationExitInfo.REASON_CRASH: kind = "crash"; break;
                case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: kind = "initialization failure"; break;
                case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: kind = "excessive resource usage"; break;
                default: return null;
            }
            final StringBuilder report = new StringBuilder("glassGram ").append(BuildVars.BUILD_VERSION_STRING)
                    .append("\nAndroid ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ")
                    .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("\nExit: ").append(kind)
                    .append("\n\n").append(kind)
                    .append(", status ").append(last.getStatus())
                    .append(", ").append(new java.util.Date(last.getTimestamp()))
                    .append("\nDescription: ").append(last.getDescription())
                    .append("\nMemory: PSS ").append(last.getPss() / 1024).append(" MB, RSS ").append(last.getRss() / 1024).append(" MB\n");
            final String trace = readTrace(last, last.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE);
            if (trace != null && !trace.isEmpty()) {
                report.append('\n').append(trace);
            }
            return report.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * The trace the system kept: an ANR's is text; a native crash's is a tombstone in protobuf, of
     * which the readable runs (the signal, the libraries and functions of the backtrace) are kept.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private static String readTrace(android.app.ApplicationExitInfo exit, boolean binary) {
        try (java.io.InputStream in = exit.getTraceInputStream()) {
            if (in == null) {
                return null;
            }
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0 && bytes.size() < 4 * 1024 * 1024) {
                bytes.write(buffer, 0, read);
            }
            final byte[] data = bytes.toByteArray();
            if (!binary) {
                final String text = new String(data, StandardCharsets.UTF_8);
                return text.length() > TRACE_LIMIT ? text.substring(0, TRACE_LIMIT) : text;
            }
            final StringBuilder text = new StringBuilder();
            int start = -1;
            for (int i = 0; i <= data.length && text.length() < TRACE_LIMIT; i++) {
                final boolean printable = i < data.length && data[i] >= 0x20 && data[i] < 0x7f;
                if (printable) {
                    if (start < 0) start = i;
                } else if (start >= 0) {
                    if (i - start >= 5) {
                        text.append(new String(data, start, i - start, StandardCharsets.US_ASCII)).append('\n');
                    }
                    start = -1;
                }
            }
            return text.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}

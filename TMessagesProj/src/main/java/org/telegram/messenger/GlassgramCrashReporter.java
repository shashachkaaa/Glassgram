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
        File f = file(activity);
        if (!f.exists()) {
            return;
        }
        shown = true;
        String report;
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
}

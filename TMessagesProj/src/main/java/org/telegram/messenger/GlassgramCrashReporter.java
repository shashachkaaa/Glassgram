package org.telegram.messenger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the last crash on the device and offers to copy it on the next start, so a crash
 * can be reported without enabling debug logs.
 */
public final class GlassgramCrashReporter {

    private static final String FILE_NAME = "glassgram_last_crash.txt";
    private static boolean installed;
    private static boolean shown;

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
                String report = "Glassgram " + BuildVars.BUILD_VERSION_STRING
                        + "\nAndroid " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL
                        + "\nThread: " + thread.getName()
                        + "\n\n" + trace;
                FileOutputStream out = new FileOutputStream(file(appContext), false);
                out.write(report.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Throwable ignore) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, exception);
            }
        });
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
        final String text = report;
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString(R.string.GlassgramCrashTitle));
            builder.setMessage(LocaleController.getString(R.string.GlassgramCrashText));
            builder.setPositiveButton(LocaleController.getString(R.string.GlassgramCrashCopy), (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Glassgram crash", text));
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

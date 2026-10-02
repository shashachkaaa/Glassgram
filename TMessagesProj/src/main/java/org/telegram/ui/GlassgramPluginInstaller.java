package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Installs plugin files tapped in chats or picked from storage. */
public final class GlassgramPluginInstaller {

    public static final String EXTENSION = ".plugin";

    private GlassgramPluginInstaller() {
    }

    /** Handles a tapped document when it is a plugin, or a file type a plugin registered; true when handled. */
    public static boolean openFromChat(BaseFragment fragment, MessageObject message) {
        if (message == null || message.getDocument() == null) {
            return false;
        }
        String name = message.getDocumentName();
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        File file = null;
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            File f = new File(message.messageOwner.attachPath);
            if (f.exists()) {
                file = f;
            }
        }
        if (file == null) {
            File f = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
            if (f != null && f.exists()) {
                file = f;
            }
        }
        if (file == null) {
            return false;
        }
        if (name.toLowerCase().endsWith(EXTENSION)) {
            showInstallDialog(fragment, file);
            return true;
        }
        return PluginsController.openFile(2, file, name, message, fragment.getParentActivity(), fragment);
    }

    /** Copies a picked file to the cache and asks to install it. */
    public static void installFromUri(BaseFragment fragment, Uri uri) {
        PluginsController.pluginsQueue.postRunnable(() -> {
            File copy = new File(ApplicationLoader.applicationContext.getCacheDir(), "plugin_import" + EXTENSION);
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(copy)) {
                if (in == null) {
                    throw new IllegalStateException("No input");
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> showError(fragment, getString(R.string.GlassgramPluginBadFile)));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> showInstallDialog(fragment, copy));
        });
    }

    public static void showInstallDialog(BaseFragment fragment, File file) {
        if (!PluginsController.isSupported()) {
            showError(fragment, getString(R.string.GlassgramPluginsUnsupported));
            return;
        }
        if (!PluginsController.isStarted()) {
            // The engine starts on the same queue, so reading the file waits for it
            PluginsController.setEngineEnabled(true);
        }
        PluginsController.pluginsQueue.postRunnable(() -> {
            String[] meta;
            try {
                meta = PluginsController.readPluginFile(file);
            } catch (Throwable e) {
                FileLog.e(e);
                String reason = PluginsController.startError != null ? PluginsController.startError : getString(R.string.GlassgramPluginBadFile);
                AndroidUtilities.runOnUIThread(() -> showError(fragment, reason));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> showConfirm(fragment, file, meta));
        });
    }

    private static void showConfirm(BaseFragment fragment, File file, String[] meta) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final String name = meta[1];
        final boolean update = "1".equals(meta[6]);
        String author = TextUtils.isEmpty(meta[3]) ? "" : " · " + meta[3];
        String description = TextUtils.isEmpty(meta[4]) ? "" : meta[4] + "\n\n";
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(getString(update ? R.string.GlassgramPluginUpdateTitle : R.string.GlassgramPluginInstallTitle));
        builder.setMessage(name + "\n" + formatString(R.string.GlassgramPluginVersion, meta[2]) + author + "\n\n" + description + getString(R.string.GlassgramPluginTrustWarning));
        builder.setPositiveButton(getString(update ? R.string.GlassgramPluginUpdate : R.string.GlassgramPluginInstall), (dialog, which) -> {
            PluginsController.installPlugin(file, (id, error) -> {
                if (error != null) {
                    showError(fragment, formatString(R.string.GlassgramPluginInstallFailed, firstLine(error)));
                } else {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, formatString(R.string.GlassgramPluginInstalledToast, name)).show();
                }
                if (id != null && !(fragment instanceof GlassgramPluginSettingsActivity)) {
                    fragment.presentFragment(new GlassgramPluginSettingsActivity(id));
                }
            });
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static String firstLine(String text) {
        String trimmed = text.trim();
        int lastBreak = trimmed.lastIndexOf('\n');
        // Python tracebacks end with the exception itself
        return lastBreak >= 0 ? trimmed.substring(lastBreak + 1) : trimmed;
    }

    private static void showError(BaseFragment fragment, String text) {
        if (fragment != null && fragment.getParentActivity() != null) {
            BulletinFactory.of(fragment).createErrorBulletin(text).show();
        } else {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        }
    }
}

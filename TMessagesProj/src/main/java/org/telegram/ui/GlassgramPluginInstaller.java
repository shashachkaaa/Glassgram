package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

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
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final BottomSheet sheet = new BottomSheet(activity, false, resourcesProvider);
        sheet.fixNavigationBar();

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(20), dp(16), dp(8));
        layout.addView(createPluginHeader(activity, fragment.getCurrentAccount(), name, meta[2], meta[3], meta[4], meta[5], resourcesProvider),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final boolean[] enableAfter = {update || GlassgramConfig.pluginsEnableAfterInstall};

        ButtonWithCounterView button = new ButtonWithCounterView(activity, resourcesProvider);
        button.setText(getString(update ? R.string.GlassgramPluginUpdate : R.string.GlassgramPluginInstall), false);
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));
        button.setOnClickListener(v -> {
            button.setLoading(true);
            if (!update) {
                GlassgramConfig.pluginsEnableAfterInstall = enableAfter[0];
                GlassgramConfig.putBoolean("pluginsEnableAfterInstall", enableAfter[0]);
            }
            PluginsController.installPlugin(file, enableAfter[0], (id, error) -> {
                sheet.dismiss();
                if (error != null) {
                    showError(fragment, formatString(R.string.GlassgramPluginInstallFailed, firstLine(error)));
                } else if (id != null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                            formatString(R.string.GlassgramPluginInstalledToast, name), getString(R.string.Settings),
                            () -> fragment.presentFragment(new GlassgramPluginSettingsActivity(id))).show();
                }
            });
        });

        if (!update) {
            LinearLayout checkRow = new LinearLayout(activity);
            checkRow.setOrientation(LinearLayout.HORIZONTAL);
            checkRow.setGravity(Gravity.CENTER);
            checkRow.setPadding(dp(12), dp(10), dp(12), dp(10));
            checkRow.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 8, 8));
            CheckBox2 checkBox = new CheckBox2(activity, 21, resourcesProvider);
            checkBox.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
            checkBox.setDrawUnchecked(true);
            checkBox.setDrawBackgroundAsArc(10);
            checkBox.setChecked(enableAfter[0], false);
            checkRow.addView(checkBox, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
            TextView checkText = new TextView(activity);
            checkText.setText(getString(R.string.GlassgramPluginEnableAfterInstall));
            checkText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            checkText.setTypeface(AndroidUtilities.bold());
            checkText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            checkRow.addView(checkText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            checkRow.setOnClickListener(v -> {
                enableAfter[0] = !enableAfter[0];
                checkBox.setChecked(enableAfter[0], true);
            });
            layout.addView(checkRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        } else {
            layout.addView(new View(activity), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));
        }

        sheet.setCustomView(layout);
        fragment.showDialog(sheet);
    }

    /** Icon, name, "Version · @author" and description of a plugin, centered like exteraGram's. */
    public static View createPluginHeader(Context context, int account, String name, String version, String author, String description, String icon, Theme.ResourcesProvider resourcesProvider) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        BackupImageView imageView = new BackupImageView(context);
        boolean hasSticker = false;
        if (!TextUtils.isEmpty(icon) && icon.contains("/")) {
            int slash = icon.lastIndexOf('/');
            try {
                int index = Integer.parseInt(icon.substring(slash + 1).trim());
                MediaDataController.getInstance(account).setPlaceholderImageByIndex(imageView, icon.substring(0, slash), index, "90_90");
                hasSticker = true;
            } catch (NumberFormatException ignore) {
            }
        }
        if (!hasSticker) {
            Drawable drawable = ContextCompat.getDrawable(context, R.drawable.msg_bots).mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider), PorterDuff.Mode.SRC_IN));
            CombinedDrawable combined = new CombinedDrawable(Theme.createCircleDrawable(dp(80), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)), drawable);
            combined.setIconSize(dp(40), dp(40));
            imageView.setImageDrawable(combined);
        }
        layout.addView(imageView, LayoutHelper.createLinear(hasSticker ? 90 : 80, hasSticker ? 90 : 80, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        TextView title = new TextView(context);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        SpannableStringBuilder line = new SpannableStringBuilder(formatString(R.string.GlassgramPluginVersion, version));
        if (!TextUtils.isEmpty(author)) {
            line.append(" · ");
            int start = line.length();
            line.append(author);
            final String handle = author.trim();
            if (handle.startsWith("@") && handle.length() > 1 && !handle.contains(" ")) {
                line.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        Browser.openUrl(context, "https://t.me/" + handle.substring(1));
                    }

                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                        ds.setUnderlineText(true);
                    }
                }, start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                subtitle.setMovementMethod(LinkMovementMethod.getInstance());
            }
        }
        subtitle.setText(line);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTypeface(AndroidUtilities.bold());
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        if (!TextUtils.isEmpty(description)) {
            TextView text = new TextView(context);
            text.setText(AndroidUtilities.replaceTags(description));
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            text.setLineSpacing(dp(2), 1f);
            layout.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 20, 4, 0));
        }
        return layout;
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

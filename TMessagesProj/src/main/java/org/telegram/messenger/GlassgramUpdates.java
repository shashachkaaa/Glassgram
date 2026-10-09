package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glassgram updates: every time the app is opened (like the badges) it asks GitHub for the
 * latest release of this repository. A release newer than this build that has an APK is offered
 * in a dialog with "Download" and "Remind me later"; the APK is downloaded by the app itself and
 * handed to the system installer.
 * <p>
 * Only real releases count: GitHub's "latest release" skips drafts and pre-releases, so the
 * "latest" pre-release that every master build publishes is never offered. This build's version
 * is GLASSGRAM_VERSION_NAME (gradle.properties; release builds take it from their tag).
 * "Remind me later" puts the release off until the next launch of the app.
 */
public final class GlassgramUpdates {

    private static final String API_URL = "https://api.github.com/repos/shashachkaaa/Glassgram/releases/latest";
    /** Without the API (it allows 60 requests an hour, shared with the badges): the page redirects to the latest release's tag. */
    private static final String LATEST_PAGE_URL = "https://github.com/shashachkaaa/Glassgram/releases/latest";
    /** The APK of a release, as the build workflow names it. */
    private static final String DOWNLOAD_URL = "https://github.com/shashachkaaa/Glassgram/releases/download/%1$s/glassgram-v%2$s.apk";

    /** Opens closer than this share one check. */
    private static final long CHECK_INTERVAL = 10 * 60 * 1000L;
    private static final int MAX_NOTES_LENGTH = 1500;
    private static final String DIR = "cache/glassgram-update";

    static final class Release {
        String tag;
        String version;
        String notes;
        String apkUrl;
        long apkSize = -1;
    }

    private static Release available;
    private static long lastCheck;
    private static boolean checking;
    /** The release already offered in this run of the app: offered once, "later" means the next launch. */
    private static String offeredTag;
    private static volatile boolean downloading;
    private static volatile boolean cancelDownload;

    private GlassgramUpdates() {
    }

    /** This build's Glassgram version, like "1.1.0". */
    public static String getCurrentVersion() {
        return BuildVars.GLASSGRAM_VERSION;
    }

    /** Checks for a new release when the app is opened and offers it in this activity. */
    public static void checkOnOpen(Activity activity) {
        // The RuStore edition is updated by the store only
        if (activity == null || GlassgramConfig.STORE_BUILD) {
            return;
        }
        if (available != null) {
            AndroidUtilities.runOnUIThread(() -> offer(activity));
        }
        final long now = System.currentTimeMillis();
        if (checking || now - lastCheck < CHECK_INTERVAL) {
            return;
        }
        checking = true;
        lastCheck = now;
        Utilities.externalNetworkQueue.postRunnable(() -> {
            final Release release = fetchLatest();
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (release == null) {
                    return;
                }
                if (compareVersions(release.version, getCurrentVersion()) > 0) {
                    available = release;
                    // The activity may have been recreated (a rotation) while GitHub answered
                    final Activity launch = org.telegram.ui.LaunchActivity.instance;
                    offer(activity.isFinishing() && launch != null ? launch : activity);
                } else {
                    available = null;
                    // Installed already: the downloaded APK is not needed any more
                    Utilities.globalQueue.postRunnable(() -> deleteDownloads(null));
                }
            });
        });
    }

    private static void offer(Activity activity) {
        final Release release = available;
        if (release == null || downloading || TextUtils.equals(offeredTag, release.tag)
                || activity.isFinishing() || SharedConfig.isWaitingForPasscodeEnter) {
            return;
        }
        offeredTag = release.tag;
        try {
            final StringBuilder message = new StringBuilder(LocaleController.formatString(R.string.GlassgramUpdateText, release.version, getCurrentVersion()));
            if (!TextUtils.isEmpty(release.notes)) {
                message.append("\n\n").append(release.notes);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString(R.string.GlassgramUpdateTitle));
            builder.setMessage(message.toString());
            builder.setPositiveButton(LocaleController.getString(R.string.GlassgramUpdateDownload), (dialog, which) -> download(activity, release));
            builder.setNegativeButton(LocaleController.getString(R.string.GlassgramUpdateLater), null);
            builder.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }



    /* Download and install */

    private static File downloadsDir() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
    }

    private static File apkFile(Release release) {
        return new File(downloadsDir(), "glassgram-v" + release.version + ".apk");
    }

    /** Removes downloaded APKs except keep (all of them for null). */
    private static void deleteDownloads(File keep) {
        final File[] files = downloadsDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (keep == null || !f.equals(keep)) {
                f.delete();
            }
        }
    }

    private static void download(Activity activity, Release release) {
        final File file = apkFile(release);
        if (file.exists() && (release.apkSize <= 0 || file.length() == release.apkSize)) {
            // Downloaded before, e.g. when installing from the app was not allowed yet
            install(activity, file);
            return;
        }
        if (downloading) {
            return;
        }
        downloading = true;
        cancelDownload = false;

        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        progress.setTitle(LocaleController.getString(R.string.GlassgramUpdateTitle));
        progress.setMessage(LocaleController.formatString(R.string.GlassgramUpdateDownloading, release.version));
        progress.setCanceledOnTouchOutside(false);
        progress.setCancelable(false);
        progress.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> cancelDownload = true);
        try {
            progress.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }

        new Thread(() -> {
            final boolean ok = downloadTo(release, file, percent -> AndroidUtilities.runOnUIThread(() -> progress.setProgress(percent)));
            AndroidUtilities.runOnUIThread(() -> {
                downloading = false;
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                if (ok) {
                    install(activity, file);
                } else if (!cancelDownload) {
                    Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.GlassgramUpdateFailed), Toast.LENGTH_LONG).show();
                }
            });
        }, "GlassgramUpdateDownload").start();
    }

    private interface ProgressListener {
        void onProgress(int percent);
    }

    private static boolean downloadTo(Release release, File file, ProgressListener listener) {
        final File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return false;
        }
        deleteDownloads(null);
        final File part = new File(file.getPath() + ".part");
        HttpURLConnection connection = null;
        try {
            // GitHub sends release assets from another host; redirects between https hosts are followed
            connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Glassgram/" + getCurrentVersion());
            if (connection.getResponseCode() != 200) {
                return false;
            }
            final long total = connection.getContentLength() > 0 ? connection.getContentLength() : release.apkSize;
            long done = 0;
            int lastPercent = -1;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (cancelDownload) {
                        return false;
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    if (total > 0) {
                        final int percent = (int) Math.min(100, done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent);
                        }
                    }
                }
            }
            if (total > 0 && done != total || !part.renameTo(file)) {
                return false;
            }
            if (!isOurApk(file)) {
                file.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (part.exists()) {
                part.delete();
            }
        }
    }

    /** Whether the file is an APK of this app, not a broken download or some other package. */
    private static boolean isOurApk(File file) {
        try {
            final Context context = ApplicationLoader.applicationContext;
            final PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getPath(), 0);
            return info != null && context.getPackageName().equals(info.packageName);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void install(Activity activity, File file) {
        // The activity that asked may be gone by the time the download ends
        final Activity launch = org.telegram.ui.LaunchActivity.instance;
        final Activity a = activity.isFinishing() && launch != null ? launch : activity;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.getPackageManager().canRequestPackageInstalls()) {
                // After allowing it the user taps "Download" again, and the APK downloaded now installs
                offeredTag = null;
                AlertsCreator.createApkRestrictedDialog(a, null).show();
                return;
            }
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            final Uri uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? FileProvider.getUriForFile(a, ApplicationLoader.getApplicationId() + ".provider", file)
                : Uri.fromFile(file);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.GlassgramUpdateFailed), Toast.LENGTH_LONG).show();
        }
    }



    /* The latest release */

    private static Release fetchLatest() {
        final String json = get(API_URL);
        if (json != null) {
            try {
                return parseRelease(new JSONObject(json));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        // No API: the tag from the latest release's page, and the APK by the workflow's name for it
        final String tag = latestTagFromPage();
        if (tag == null) {
            return null;
        }
        final Release release = new Release();
        release.tag = tag;
        release.version = versionOf(tag);
        release.apkUrl = String.format(Locale.US, DOWNLOAD_URL, tag, release.version);
        return release;
    }

    private static Release parseRelease(JSONObject root) {
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
            return null;
        }
        final String tag = root.optString("tag_name", null);
        if (TextUtils.isEmpty(tag)) {
            return null;
        }
        // The APK; a release built in several flavors has one per flavor, the regular one is "afat"
        JSONObject apk = null;
        final JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                final JSONObject asset = assets.optJSONObject(i);
                final String name = asset != null ? asset.optString("name", "") : "";
                if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                if (apk == null || name.contains("afat")) {
                    apk = asset;
                }
            }
        }
        if (apk == null || TextUtils.isEmpty(apk.optString("browser_download_url", null))) {
            // Released without an APK yet: nothing to offer
            return null;
        }
        final Release release = new Release();
        release.tag = tag;
        release.version = versionOf(tag);
        release.apkUrl = apk.optString("browser_download_url");
        release.apkSize = apk.optLong("size", -1);
        release.notes = cleanNotes(root.optString("body", ""));
        return release;
    }

    private static String latestTagFromPage() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST_PAGE_URL).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Glassgram/" + getCurrentVersion());
            final String location = connection.getHeaderField("Location");
            if (location == null) {
                return null;
            }
            final int index = location.indexOf("/releases/tag/");
            if (index < 0) {
                return null;
            }
            final String tag = Uri.decode(location.substring(index + "/releases/tag/".length()));
            return TextUtils.isEmpty(tag) ? null : tag;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String get(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Glassgram/" + getCurrentVersion());
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    if (out.size() > 2 * 1024 * 1024) {
                        return null;
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }



    /* Versions and notes */

    /** "v1.2.0" → "1.2.0". */
    private static String versionOf(String tag) {
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /** Compares versions like "1.2.0" number by number; an unreadable one is never newer. */
    static int compareVersions(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) {
            return 0;
        }
        final String[] pa = a.split("[^0-9]+"), pb = b.split("[^0-9]+");
        final int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            final long x = number(pa, i), y = number(pb, i);
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    private static long number(String[] parts, int i) {
        if (i >= parts.length || parts[i].isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(parts[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    /** The release notes as plain text: the Markdown of GitHub releases without its marks. */
    private static String cleanNotes(String body) {
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        final StringBuilder out = new StringBuilder();
        for (String line : body.replace("\r", "").split("\n")) {
            String l = line.trim();
            if (l.startsWith("#")) {
                l = l.replaceFirst("^#+\\s*", "");
            } else if (l.startsWith(">")) {
                l = l.replaceFirst("^>\\s*", "");
            } else if (l.startsWith("- ") || l.startsWith("* ")) {
                l = "• " + l.substring(2);
            }
            final Matcher m = MARKDOWN_LINK.matcher(l);
            l = m.replaceAll("$1");
            l = l.replace("**", "").replace("__", "").replace("`", "");
            if (l.isEmpty() && (out.length() == 0 || out.charAt(out.length() - 1) == '\n' && out.length() > 1 && out.charAt(out.length() - 2) == '\n')) {
                continue;
            }
            out.append(l).append('\n');
        }
        String text = out.toString().trim();
        if (text.length() > MAX_NOTES_LENGTH) {
            text = text.substring(0, MAX_NOTES_LENGTH).trim() + "…";
        }
        return text;
    }
}

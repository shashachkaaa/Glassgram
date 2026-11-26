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
 * Stable release updater for glassGram. All UI state lives on the main thread and is shared by
 * the bottom capsule across accounts and fragment recreation. The APK worker keeps partial
 * downloads on disk and resumes them with HTTP Range; installation is always user initiated.
 */
public final class GlassgramUpdates {
    private static final String API_URL = "https://api.github.com/repos/shashachkaaa/Glassgram/releases/latest";
    private static final String LATEST_PAGE_URL = "https://github.com/shashachkaaa/Glassgram/releases/latest";
    private static final String DOWNLOAD_URL = "https://github.com/shashachkaaa/Glassgram/releases/download/%1$s/glassgram-v%2$s.apk";
    private static final long CHECK_INTERVAL = 10 * 60 * 1000L;
    private static final int MAX_NOTES_LENGTH = 1500;
    private static final String DIR = "cache/glassgram-update";
    private static final String PREFS = "glassgram_update";
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    public static final int HIDDEN = 0, AVAILABLE = 1, DOWNLOADING = 2, PAUSING = 3,
            PAUSED = 4, READY = 5, FAILED = 6;
    public static final int BAR_HEIGHT_DP = 44;

    static final class Release {
        String tag;
        String version;
        String notes;
        String apkUrl;
        long apkSize = -1;
    }

    private static Release available;
    private static Release activeRelease;
    private static Release pendingLatest;
    private static boolean hasPendingLatest;
    private static long lastCheck;
    private static boolean checking, restored;
    private static int state = HIDDEN;
    private static long downloadedBytes;
    private static long totalBytes = -1;
    private static volatile boolean pauseRequested;

    private GlassgramUpdates() {
    }

    public static String getCurrentVersion() {
        return BuildVars.GLASSGRAM_VERSION;
    }

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean validTag(String tag) {
        return tag != null && tag.matches("[vV]?[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    private static boolean usable(Release release) {
        return release != null && validTag(release.tag) && versionOf(release.tag).equals(release.version)
                && release.apkUrl != null && release.apkUrl.startsWith("https://github.com/shashachkaaa/Glassgram/releases/download/")
                && compareVersions(release.version, getCurrentVersion()) > 0;
    }

    private static void restore() {
        if (restored || GlassgramConfig.STORE_BUILD || ApplicationLoader.applicationContext == null) {
            return;
        }
        restored = true;
        final android.content.SharedPreferences p = prefs();
        final Release release = new Release();
        release.tag = p.getString("tag", null);
        release.version = p.getString("version", null);
        release.apkUrl = p.getString("url", null);
        release.apkSize = p.getLong("size", -1);
        if (usable(release)) {
            available = release;
            readDiskState();
        } else {
            p.edit().clear().apply();
            deleteDownloads(null, null);
        }
    }

    public static boolean hasUpdate() {
        restore();
        return !GlassgramConfig.STORE_BUILD && available != null;
    }

    /** The additional bottom space above the tabs, in pixels, shared with their pages. */
    public static int getBarInset() {
        return hasUpdate() ? AndroidUtilities.dp(BAR_HEIGHT_DP) : 0;
    }

    public static int getState() {
        return hasUpdate() ? state : HIDDEN;
    }

    public static long getTotalBytes() {
        return totalBytes;
    }

    public static long getDownloadedBytes() {
        return downloadedBytes;
    }

    public static int getProgress() {
        return totalBytes > 0 ? (int) Math.max(0, Math.min(100, downloadedBytes * 100.0 / totalBytes)) : -1;
    }

    private static void notifyChanged() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.glassgramUpdateChanged);
    }

    private static void persist() {
        if (available == null) {
            prefs().edit().clear().apply();
        } else {
            prefs().edit().putString("tag", available.tag).putString("version", available.version)
                    .putString("url", available.apkUrl).putLong("size", totalBytes > 0 ? totalBytes : available.apkSize).apply();
        }
    }

    private static void readDiskState() {
        if (available == null) {
            state = HIDDEN;
            downloadedBytes = 0;
            totalBytes = -1;
            return;
        }
        totalBytes = available.apkSize;
        final File file = apkFile(available);
        if (file.isFile() && file.length() > 0 && (totalBytes <= 0 || file.length() == totalBytes)) {
            downloadedBytes = totalBytes = file.length();
            state = READY;
        } else {
            downloadedBytes = partFile(available).length();
            state = downloadedBytes > 0 ? PAUSED : AVAILABLE;
        }
    }

    /** Re-check stable releases on app open. A cached offer remains visible without a connection. */
    public static void checkOnOpen(Activity activity) {
        if (activity == null || GlassgramConfig.STORE_BUILD) {
            return;
        }
        restore();
        notifyChanged();
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
                if (release != null && validTag(release.tag)) {
                    applyLatest(usable(release) ? release : null);
                }
            });
        });
    }

    private static void applyLatest(Release release) {
        if (activeRelease != null) {
            // Do not delete or replace an APK while the worker is still writing it.
            if (release == null || !TextUtils.equals(activeRelease.tag, release.tag)) {
                pendingLatest = release;
                hasPendingLatest = true;
                pauseRequested = true;
                state = PAUSING;
                notifyChanged();
            }
            return;
        }
        if (release != null && available != null && TextUtils.equals(available.tag, release.tag)
                && TextUtils.equals(available.apkUrl, release.apkUrl)) {
            if (release.apkSize > 0 && totalBytes > 0 && release.apkSize != totalBytes) {
                apkFile(available).delete();
                partFile(available).delete();
            } else if (release.apkSize <= 0 && totalBytes > 0) {
                release.apkSize = totalBytes;
            }
        }
        available = release;
        if (release == null) {
            deleteDownloads(null, null);
        } else {
            deleteDownloads(apkFile(release), partFile(release));
        }
        readDiskState();
        persist();
        notifyChanged();
    }

    public static void onBarClick(Activity activity) {
        if (activity == null || !hasUpdate() || activity.isFinishing() || SharedConfig.isWaitingForPasscodeEnter) {
            return;
        }
        if (state == DOWNLOADING) {
            pauseRequested = true;
            state = PAUSING;
            notifyChanged();
            return;
        }
        if (state == PAUSING || activeRelease != null) {
            return;
        }
        final Release release = available;
        final File file = apkFile(release);
        if (state == READY && file.isFile()) {
            if (isOurApk(file)) {
                install(activity, file);
                return;
            }
            file.delete();
        }
        startDownload(release);
    }

    private static File downloadsDir() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
    }

    private static File apkFile(Release release) {
        return new File(downloadsDir(), "glassgram-v" + release.version + ".apk");
    }

    private static File partFile(Release release) {
        return new File(apkFile(release).getPath() + ".part");
    }

    private static void deleteDownloads(File keep, File keepPart) {
        final File[] files = downloadsDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (!f.equals(keep) && !f.equals(keepPart)) {
                f.delete();
            }
        }
    }

    private static void startDownload(Release release) {
        final File file = apkFile(release);
        final File part = partFile(release);
        final long expected = totalBytes > 0 ? totalBytes : release.apkSize;
        activeRelease = release;
        pauseRequested = false;
        downloadedBytes = part.length();
        totalBytes = expected;
        state = DOWNLOADING;
        notifyChanged();
        new Thread(() -> {
            final boolean ok = downloadTo(release, file, part, expected);
            AndroidUtilities.runOnUIThread(() -> {
                final boolean paused = pauseRequested;
                activeRelease = null;
                pauseRequested = false;
                if (hasPendingLatest) {
                    final Release next = pendingLatest;
                    pendingLatest = null;
                    hasPendingLatest = false;
                    applyLatest(next);
                    return;
                }
                if (ok) {
                    downloadedBytes = totalBytes = file.length();
                    state = READY;
                } else {
                    downloadedBytes = part.length();
                    state = paused ? PAUSED : FAILED;
                }
                persist();
                notifyChanged();
            });
        }, "GlassgramUpdateDownload").start();
    }

    private static void publishProgress(Release release, long done, long total) {
        AndroidUtilities.runOnUIThread(() -> {
            if (activeRelease != release) {
                return;
            }
            final boolean sizeChanged = total > 0 && total != totalBytes;
            downloadedBytes = done;
            totalBytes = total;
            if (sizeChanged) {
                persist();
            }
            notifyChanged();
        });
    }

    private static boolean finishDownload(File file, File part, long expected) {
        if (pauseRequested || !part.isFile() || part.length() <= 0
                || expected > 0 && part.length() != expected) {
            return false;
        }
        if (file.exists() && !file.delete() || !part.renameTo(file)) {
            return false;
        }
        if (!isOurApk(file)) {
            file.delete();
            return false;
        }
        return true;
    }

    /** Resume only a valid byte range; when a server ignores Range, restart safely from byte zero. */
    private static boolean downloadTo(Release release, File file, File part, long expected) {
        final File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        deleteDownloads(file, part);
        if (expected > 0 && part.length() > expected) {
            part.delete();
        }
        if (expected > 0 && part.length() == expected) {
            if (finishDownload(file, part, expected)) {
                return true;
            }
            if (pauseRequested) {
                return false;
            }
        }
        for (int attempt = 0; attempt < 2 && !pauseRequested; attempt++) {
            HttpURLConnection connection = null;
            try {
                long offset = part.length();
                connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(15000);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "Glassgram/" + getCurrentVersion());
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (offset > 0) {
                    connection.setRequestProperty("Range", "bytes=" + offset + "-");
                }
                final int response = connection.getResponseCode();
                if (pauseRequested) {
                    return false;
                }
                final long length = connection.getContentLength();
                long total;
                if (response == HttpURLConnection.HTTP_PARTIAL) {
                    final String header = connection.getHeaderField("Content-Range");
                    final Matcher range = CONTENT_RANGE.matcher(header != null ? header : "");
                    if (!range.matches() || Long.parseLong(range.group(1)) != offset
                            || Long.parseLong(range.group(2)) != Long.parseLong(range.group(3)) - 1
                            || expected > 0 && Long.parseLong(range.group(3)) != expected
                            || length > 0 && length != Long.parseLong(range.group(2)) - offset + 1) {
                        part.delete();
                        continue;
                    }
                    total = Long.parseLong(range.group(3));
                } else if (response == HttpURLConnection.HTTP_OK) {
                    // An origin without Range returns the whole file; never append it to a part.
                    offset = 0;
                    total = length > 0 ? length : expected;
                    if (expected > 0 && total > 0 && total != expected) {
                        return false;
                    }
                } else if (response == 416 && offset > 0) {
                    part.delete();
                    continue;
                } else {
                    return false;
                }
                long done = offset;
                int lastPercent = -2;
                long lastReport = 0;
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part, offset > 0)) {
                    publishProgress(release, done, total);
                    final byte[] buffer = new byte[64 * 1024];
                    while (!pauseRequested) {
                        final int read = in.read(buffer);
                        if (read == -1) {
                            break;
                        }
                        if (pauseRequested) {
                            break;
                        }
                        out.write(buffer, 0, read);
                        done += read;
                        if (total > 0 && done > total) {
                            break;
                        }
                        final int percent = total > 0 ? (int) (done * 100.0 / total) : -1;
                        final long now = android.os.SystemClock.elapsedRealtime();
                        if (percent != lastPercent || now - lastReport >= 1000) {
                            lastPercent = percent;
                            lastReport = now;
                            publishProgress(release, done, total);
                        }
                    }
                }
                if (pauseRequested) {
                    return false;
                }
                if (total > 0 && done > total) {
                    part.delete();
                    return false;
                }
                return finishDownload(file, part, total);
            } catch (Exception e) {
                if (!pauseRequested) {
                    FileLog.e(e);
                }
                return false;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return false;
    }

    /** Validate the package before handing it to Android's installer, which checks its signature. */
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
        final Activity launch = org.telegram.ui.LaunchActivity.instance;
        final Activity a = activity.isFinishing() && launch != null ? launch : activity;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.getPackageManager().canRequestPackageInstalls()) {
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

package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * glassGram: anonymous statistics for adapting the app to every phone, sent to glassGram's server
 * (see GlassgramApi) once a day: the device, the app's version, what the cameras can do for video
 * messages, which options are on, and how many accounts. Crashes go there too, on the next start.
 * <p>
 * Nothing names the person: an install is a random id made on install, never a Telegram account,
 * and no number, name or message leaves the phone. "Anonymous statistics" in the settings turns
 * all of it off.
 */
public final class GlassgramStats {

    private static final String PREFS = "glassgram_stats";
    private static final long DAY = 24 * 60 * 60 * 1000L;
    /** Opens closer than this to a try don't try again, sent or not. */
    private static final long RETRY = 60 * 60 * 1000L;

    private static final DispatchQueue queue = new DispatchQueue("glassgramStats");

    private GlassgramStats() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The install's random id, made on first use. */
    public static String installId() {
        final SharedPreferences prefs = prefs();
        String id = prefs.getString("install", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("install", id).apply();
        }
        return id;
    }

    /** When the app is opened: the day's report, if it's due. */
    public static void onOpen() {
        if (!GlassgramConfig.sendStats) {
            return;
        }
        final SharedPreferences prefs = prefs();
        final long now = System.currentTimeMillis();
        if (now < prefs.getLong("next", 0) || now - prefs.getLong("tried", 0) < RETRY) {
            return;
        }
        prefs.edit().putLong("tried", now).apply();
        queue.postRunnable(() -> {
            final JSONObject report = report();
            final GlassgramApi.Response response = GlassgramApi.execute("POST", "/stats/report", report, null);
            if (response.ok()) {
                final long next = Math.max(60 * 60, response.json.optLong("next", DAY / 1000)) * 1000L;
                prefs.edit().putLong("next", System.currentTimeMillis() + next).apply();
            }
        });
    }

    /** A crash the app saved, sent on the next start; the header is left out, the trace goes. */
    public static void sendCrash(String saved) {
        if (!GlassgramConfig.sendStats || saved == null) {
            return;
        }
        final int start = saved.indexOf("\n\n");
        final String trace = start >= 0 ? saved.substring(start + 2) : saved;
        queue.postRunnable(() -> GlassgramApi.execute("POST", "/stats/crash", GlassgramApi.json(
                "install", installId(),
                "app", app(),
                "device", GlassgramApi.json("model", Build.MODEL, "sdk", Build.VERSION.SDK_INT),
                "trace", trace), null));
    }

    // --- What a report says -------------------------------------------------------------------------

    private static JSONObject report() {
        final JSONObject report = GlassgramApi.json(
                "install", installId(),
                "app", app(),
                "device", device(),
                "features", features(),
                "accounts", UserConfig.getActivatedAccountsCount(),
                "language", LocaleController.getInstance().getCurrentLocale() != null
                        ? LocaleController.getInstance().getCurrentLocale().getLanguage() : "");
        try {
            report.put("camera", camera());
        } catch (JSONException ignore) {
        }
        return report;
    }

    public static JSONObject app() {
        return GlassgramApi.json(
                "version", BuildVars.GLASSGRAM_VERSION,
                "telegram", BuildVars.BUILD_VERSION_STRING,
                "build", buildNumber(),
                "edition", GlassgramConfig.STORE_BUILD ? "rustore" : "full");
    }

    private static int buildNumber() {
        try {
            final Context context = ApplicationLoader.applicationContext;
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** The phone: what tells phones apart for adapting the app, nothing about its owner. */
    public static JSONObject device() {
        final Context context = ApplicationLoader.applicationContext;
        final JSONObject device = GlassgramApi.json(
                "manufacturer", Build.MANUFACTURER,
                "brand", Build.BRAND,
                "model", Build.MODEL,
                "device", Build.DEVICE,
                "soc", Build.VERSION.SDK_INT >= 31 ? (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim() : Build.HARDWARE,
                "sdk", Build.VERSION.SDK_INT,
                "release", Build.VERSION.RELEASE);
        try {
            final JSONArray abis = new JSONArray();
            for (String abi : Build.SUPPORTED_ABIS) {
                abis.put(abi);
            }
            device.put("abis", abis);
            final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memory);
                device.put("ram_mb", memory.totalMem / (1024 * 1024));
            }
            final WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                final Display display = windowManager.getDefaultDisplay();
                final DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                device.put("screen", GlassgramApi.json(
                        "width", Math.min(metrics.widthPixels, metrics.heightPixels),
                        "height", Math.max(metrics.widthPixels, metrics.heightPixels),
                        "dpi", metrics.densityDpi,
                        "refresh", Math.round(display.getRefreshRate())));
            }
            final int performance = SharedConfig.getDevicePerformanceClass();
            device.put("performance", performance == SharedConfig.PERFORMANCE_CLASS_HIGH ? "high"
                    : performance == SharedConfig.PERFORMANCE_CLASS_AVERAGE ? "average" : "low");
        } catch (Exception e) {
            FileLog.e(e, false);
        }
        return device;
    }

    /** What the cameras can do for video messages; asked once per version, as it takes a while. */
    private static JSONObject camera() throws JSONException {
        final SharedPreferences prefs = prefs();
        final String key = "camera_" + BuildVars.GLASSGRAM_VERSION;
        final String cached = prefs.getString(key, null);
        if (cached != null) {
            return new JSONObject(cached);
        }
        final int size = GlassgramConfig.roundVideoSize(MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize);
        final JSONObject camera = org.telegram.messenger.camera.CameraReport.summary(ApplicationLoader.applicationContext, size);
        prefs.edit().putString(key, camera.toString()).apply();
        return camera;
    }

    /** Which of glassGram's options are on: what people actually use. */
    private static JSONObject features() {
        return GlassgramApi.json(
                "ghostMode", GlassgramConfig.ghostMode,
                "spySaveDeleted", GlassgramConfig.spySaveDeletedMessages,
                "spySaveEdits", GlassgramConfig.spySaveEditsHistory,
                "spyDisableAds", GlassgramConfig.spyDisableAds,
                "plugins", GlassgramConfig.pluginsEngine,
                "iosTransitions", GlassgramConfig.iosTransitions,
                "glassHeader", GlassgramConfig.glassHeader,
                "splitInput", GlassgramConfig.splitInput,
                "headerBadge", GlassgramConfig.headerBadge,
                "hideStories", GlassgramConfig.hideStories,
                "hideReactions", GlassgramConfig.hideReactions,
                "removeMessageTail", GlassgramConfig.removeMessageTail,
                "showDc", GlassgramConfig.showDc,
                "uploadBoost", GlassgramConfig.uploadBoost,
                "yandexMaps", GlassgramConfig.useYandexMaps,
                "originalQuality", GlassgramConfig.preferOriginalQuality,
                "volumeUnmute", GlassgramConfig.volumeUnmute,
                "autoPause", GlassgramConfig.autoPause,
                "roundFps60", GlassgramConfig.roundFps60,
                "roundStabilization", GlassgramConfig.roundStabilization,
                "roundHorizon", GlassgramConfig.roundHorizon,
                "roundWideCamera", GlassgramConfig.roundWideCamera,
                "roundKeepZoom", GlassgramConfig.roundKeepZoom,
                "roundHighQuality", GlassgramConfig.roundHighQuality);
    }
}

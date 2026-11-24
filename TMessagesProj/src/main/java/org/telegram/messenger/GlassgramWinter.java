package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.Calendar;
import java.util.Locale;

/**
 * glassGram: the winter and New Year look (branding/winter-update-2027.md): snow that falls and
 * settles on the glass, a garland in the chats' header, the gifts' bubbles as tree baubles, snow
 * behind the messages, a winter icon, and on the first open of the new year confetti and a
 * greeting. Until the winter comes the chats' header counts down to it.
 * <p>
 * The days are the phone's own (its time zone) on Telegram's clock, so a phone's wrong clock or a
 * clock turned forward doesn't bring them early. Everything can be turned off in glassGram's
 * settings, goes in Lite mode and when saving power, and the team can turn it off on all phones
 * (GlassgramRemoteConfig, "holidayDecor").
 */
public final class GlassgramWinter {

    /**
     * A test build: the winter is on now, and the New Year's greeting shows on the first open of
     * the build (and from the settings). The release sets it false: the winter comes on WINTER_DAY.
     */
    public static final boolean TEST = true;

    /** The winter comes on this day of December, local time; the header counts down to it before. */
    public static final int WINTER_DAY = 10;
    /** It goes after this day of January (the Old New Year). */
    private static final int WINTER_LAST_JANUARY_DAY = 14;
    /** The New Year's greeting is shown on the first open from January 1 to this day. */
    private static final int GREETING_LAST_JANUARY_DAY = 7;
    /** The header counts down to the winter for this many days before it. */
    private static final int COUNTDOWN_DAYS = 30;

    private static final String PREFS = "glassgram_winter";

    private static long cachedAt;
    private static boolean cachedSeason;

    private GlassgramWinter() {
    }

    /** Now on Telegram's clock (the phone's own until it has talked to the server), ms. */
    public static long now() {
        try {
            final long server = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTimeMillis();
            if (server > 0) {
                return server;
            }
        } catch (Throwable ignore) {
        }
        return System.currentTimeMillis();
    }

    private static Calendar calendar(long time) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        return c;
    }

    /** Whether the days of the winter look are now (from December WINTER_DAY to January 14), or a test build. */
    public static boolean isSeasonNow() {
        if (TEST) {
            return true;
        }
        final Calendar c = calendar(now());
        final int month = c.get(Calendar.MONTH), day = c.get(Calendar.DAY_OF_MONTH);
        return month == Calendar.DECEMBER && day >= WINTER_DAY || month == Calendar.JANUARY && day <= WINTER_LAST_JANUARY_DAY;
    }

    /** Whether the winter look shows: its days (or always, as chosen), and allowed. Cheap: kept for a second. */
    public static boolean isActive() {
        final long uptime = SystemClock.uptimeMillis();
        if (uptime - cachedAt > 1000 || cachedAt == 0) {
            cachedAt = uptime;
            cachedSeason = GlassgramConfig.holidayDecor && GlassgramConfig.holidayMode != GlassgramConfig.HOLIDAY_OFF
                    && (GlassgramConfig.holidayMode == GlassgramConfig.HOLIDAY_ALWAYS || isSeasonNow());
        }
        return cachedSeason;
    }

    /** As isActive, and the phone may move things on the screen for it (Lite mode, saving power). */
    public static boolean isAnimated() {
        return isActive() && LiteMode.isEnabled(LiteMode.FLAG_PARTICLES);
    }

    /** The choice or the rules changed: the look is looked at again at once. */
    public static void onChanged() {
        cachedAt = 0;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.glassgramHolidayChanged);
    }

    // --- The countdown -------------------------------------------------------------------------

    /** When the winter comes this year, ms: December WINTER_DAY, 0:00, local. */
    public static long winterStart() {
        final Calendar c = calendar(now());
        c.set(Calendar.MONTH, Calendar.DECEMBER);
        c.set(Calendar.DAY_OF_MONTH, WINTER_DAY);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** How long until the winter comes, ms, while the header counts down to it; -1 when it doesn't. */
    public static long countdownLeft() {
        if (!GlassgramConfig.holidayDecor || GlassgramConfig.holidayMode == GlassgramConfig.HOLIDAY_OFF) {
            return -1;
        }
        final long left = winterStart() - now();
        return left > 0 && left <= COUNTDOWN_DAYS * 24L * 3600_000L ? left : -1;
    }

    /** The countdown as the header shows it: "Winter in 15 d 07:12:44"; null when it doesn't. */
    public static String countdownText() {
        final long left = countdownLeft();
        if (left < 0) {
            return null;
        }
        final long seconds = left / 1000;
        final long days = seconds / 86400, hours = seconds / 3600 % 24, minutes = seconds / 60 % 60, secs = seconds % 60;
        final String time = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
        return days > 0
                ? LocaleController.formatString(R.string.GlassgramWinterCountdownDays, days, time)
                : LocaleController.formatString(R.string.GlassgramWinterCountdown, time);
    }

    // --- The New Year ---------------------------------------------------------------------------

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The year whose greeting is due now, or 0: from January 1 to 7, on the first open, once a year. */
    private static int greetingYear() {
        if (TEST) {
            return prefs().getBoolean("greeted_test", false) ? 0 : calendar(now()).get(Calendar.YEAR) + 1;
        }
        final Calendar c = calendar(now());
        if (c.get(Calendar.MONTH) != Calendar.JANUARY || c.get(Calendar.DAY_OF_MONTH) > GREETING_LAST_JANUARY_DAY) {
            return 0;
        }
        final int year = c.get(Calendar.YEAR);
        return prefs().getBoolean("greeted_" + year, false) ? 0 : year;
    }

    /** Whether the New Year's confetti and greeting are due now (once a year, and the look allowed). */
    public static boolean isGreetingDue() {
        return GlassgramConfig.holidayDecor && GlassgramConfig.holidayMode != GlassgramConfig.HOLIDAY_OFF && greetingYear() != 0;
    }

    /** The year the greeting is for: the coming one in a test build. */
    public static int greetingYearShown() {
        final Calendar c = calendar(now());
        final int year = c.get(Calendar.YEAR);
        return TEST || c.get(Calendar.MONTH) == Calendar.DECEMBER ? year + 1 : year;
    }

    public static void markGreeted() {
        final int year = greetingYear();
        prefs().edit().putBoolean(TEST ? "greeted_test" : "greeted_" + (year != 0 ? year : calendar(now()).get(Calendar.YEAR)), true).apply();
    }

    // --- The winter icon -----------------------------------------------------------------------

    /** Whether the winter icon was offered (once). */
    public static boolean wasIconOffered() {
        return prefs().getBoolean("icon_offered", false);
    }

    public static void markIconOffered() {
        prefs().edit().putBoolean("icon_offered", true).apply();
    }

    /** The icon before the winter one, to give back: its alias's key, or null. */
    public static String iconBeforeWinter() {
        return prefs().getString("icon_before", null);
    }

    public static void setIconBeforeWinter(String key) {
        prefs().edit().putString("icon_before", key).apply();
    }
}

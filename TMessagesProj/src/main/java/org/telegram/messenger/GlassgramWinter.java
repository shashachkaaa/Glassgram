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
 * greeting. Until the winter comes the chats' header counts down to it. Its days are December 10
 * to January 14, or the ones the team set in its section of the app (GlassgramRemoteConfig, GET
 * /config "winter").
 * <p>
 * The days are the phone's own (its time zone) on Telegram's clock, so a phone's wrong clock or a
 * clock turned forward doesn't bring them early. Everything can be turned off in glassGram's
 * settings, goes in Lite mode and when saving power, and the team can turn it off on all phones
 * (GlassgramRemoteConfig, "holidayDecor").
 */
public final class GlassgramWinter {

    /**
     * A test build: the winter is on now unless the team set its days, and the New Year's greeting
     * shows on the first open of the build (and from the settings). The release sets it false: the
     * winter comes on its days.
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

    /** Whether the days of the winter look are now, or a test build without days set by the team. */
    public static boolean isSeasonNow() {
        final long now = now();
        final long[] days = window(now);
        if (now >= days[0] && now < days[1]) {
            return true;
        }
        return TEST && !hasTeamDays(now);
    }

    // --- Its days -----------------------------------------------------------------------------

    // The days the team set: local midnight of the first day and of the day after the last, ms; 0 for none
    private static boolean teamDaysLoaded;
    private static long teamStart, teamEnd;
    /** The team's days hold this long after they end, so the app's own don't bring the look back in January. */
    private static final long TEAM_DAYS_HOLD = 90 * 24 * 3600_000L;

    private static long midnight(int year, int month, int day) {
        final Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** "2026-12-10" as local midnight of that day, ms; 0 when it isn't a date. */
    private static long parseDay(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return 0;
        }
        try {
            return midnight(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(5, 7)) - 1, Integer.parseInt(text.substring(8, 10)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void loadTeamDays() {
        if (teamDaysLoaded) {
            return;
        }
        teamDaysLoaded = true;
        final long start = parseDay(prefs().getString("team_start", null));
        final long end = parseDay(prefs().getString("team_end", null));
        if (start > 0 && end >= start) {
            teamStart = start;
            // to the end of the last day
            final Calendar c = calendar(end);
            c.add(Calendar.DAY_OF_MONTH, 1);
            teamEnd = c.getTimeInMillis();
        } else {
            teamStart = teamEnd = 0;
        }
    }

    private static boolean hasTeamDays(long now) {
        loadTeamDays();
        return teamStart > 0 && now < teamEnd + TEAM_DAYS_HOLD;
    }

    /**
     * The days the team set in its section of the app, from GET /config ({"start": "2026-12-10",
     * "end": "2027-01-14"}, or null for the app's own); on the main thread.
     */
    public static void applyTeamDays(org.json.JSONObject winter) {
        final String start = winter != null ? winter.optString("start", "") : "";
        final String end = winter != null ? winter.optString("end", "") : "";
        final SharedPreferences prefs = prefs();
        if (start.equals(prefs.getString("team_start", "")) && end.equals(prefs.getString("team_end", ""))) {
            return;
        }
        prefs.edit().putString("team_start", start).putString("team_end", end).apply();
        teamDaysLoaded = false;
        onChanged();
    }

    /** The winter look's days around now: {its first moment, the moment after its last}, ms. */
    private static long[] window(long now) {
        if (hasTeamDays(now)) {
            return new long[]{teamStart, teamEnd};
        }
        // its own: from December WINTER_DAY to January 14, this winter's or the coming one's
        final Calendar c = calendar(now);
        final int year = c.get(Calendar.YEAR);
        final boolean january = c.get(Calendar.MONTH) == Calendar.JANUARY && c.get(Calendar.DAY_OF_MONTH) <= WINTER_LAST_JANUARY_DAY;
        final int startYear = january ? year - 1 : year;
        return new long[]{midnight(startYear, Calendar.DECEMBER, WINTER_DAY), midnight(startYear + 1, Calendar.JANUARY, WINTER_LAST_JANUARY_DAY + 1)};
    }

    /** The first day of the winter look, as the settings show it ("10 Dec"). */
    public static String startText() {
        return LocaleController.getInstance().getFormatterDayMonth().format(window(now())[0]);
    }

    /** Its last day, as the settings show it ("14 Jan"). */
    public static String endText() {
        return LocaleController.getInstance().getFormatterDayMonth().format(window(now())[1] - 12 * 3600_000L);
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

    /** When the winter comes, ms: its first day, 0:00, local. */
    public static long winterStart() {
        return window(now())[0];
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

    /** Whether the winter decorations were offered (once a winter). */
    public static boolean wasDecorOffered() {
        return prefs().getBoolean(decorOfferKey(), false);
    }

    public static void markDecorOffered() {
        prefs().edit().putBoolean(decorOfferKey(), true).apply();
    }

    private static String decorOfferKey() {
        // the winter's New Year: one offer from December to January
        return TEST ? "decor_offered_test" : "decor_offered_" + greetingYearShown();
    }

    /** The icon before the winter one, to give back: its alias's key, or null. */
    public static String iconBeforeWinter() {
        return prefs().getString("icon_before", null);
    }

    public static void setIconBeforeWinter(String key) {
        prefs().edit().putString("icon_before", key).apply();
    }
}

package org.telegram.ui.music;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * glassGram's music player: a track's lyrics. They come from the file's own tags first (ID3's
 * unsynchronised lyrics, often an LRC with times), then, when the player is allowed to, from
 * LRCLIB (lrclib.net, an open database of lyrics): it is sent the track's title, artist, album and
 * length, nothing else. Timed lyrics are lines with their start; plain ones have no times.
 */
public final class MusicLyrics {

    public static final class Line {
        /** Start in ms, -1 for plain lyrics. */
        public final long time;
        public final String text;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    public final ArrayList<Line> lines;
    public final boolean synced;
    /** Where they came from: true for the file's tags, false for LRCLIB. */
    public final boolean fromFile;

    private MusicLyrics(ArrayList<Line> lines, boolean synced, boolean fromFile) {
        this.lines = lines;
        this.synced = synced;
        this.fromFile = fromFile;
    }

    /** The line being sung at a time (ms), -1 before the first. */
    public int lineAt(long timeMs) {
        if (!synced) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).time <= timeMs) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("^\\[[a-zA-Z]+:.*]$");

    /** Lyrics from a text: LRC when it has times, plain otherwise; null when there is nothing. */
    @Nullable
    public static MusicLyrics parse(@Nullable String text, boolean fromFile) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
            return null;
        }
        long offset = 0;
        final Matcher om = OFFSET.matcher(text);
        if (om.find()) {
            try {
                offset = Long.parseLong(om.group(1));
            } catch (Exception ignore) {
            }
        }
        final String[] rows = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        final ArrayList<Line> timed = new ArrayList<>();
        final ArrayList<Line> plain = new ArrayList<>();
        for (String row : rows) {
            final String trimmed = row.trim();
            final Matcher m = TIME.matcher(trimmed);
            int end = 0;
            final ArrayList<Long> times = new ArrayList<>();
            while (m.find() && m.start() == end) {
                final long min = Long.parseLong(m.group(1));
                final long sec = Long.parseLong(m.group(2));
                long frac = 0;
                final String f = m.group(3);
                if (f != null) {
                    frac = Long.parseLong(f);
                    if (f.length() == 1) {
                        frac *= 100;
                    } else if (f.length() == 2) {
                        frac *= 10;
                    }
                }
                times.add(min * 60_000 + sec * 1000 + frac - offset);
                end = m.end();
            }
            final String lineText = trimmed.substring(end).trim();
            if (!times.isEmpty()) {
                for (long t : times) {
                    timed.add(new Line(Math.max(0, t), lineText));
                }
            } else if (!TAG.matcher(trimmed).matches()) {
                plain.add(new Line(-1, trimmed));
            }
        }
        if (!timed.isEmpty()) {
            java.util.Collections.sort(timed, (a, b) -> Long.compare(a.time, b.time));
            return new MusicLyrics(timed, true, fromFile);
        }
        // plain: without the empty lines at the ends
        while (!plain.isEmpty() && plain.get(0).text.isEmpty()) {
            plain.remove(0);
        }
        while (!plain.isEmpty() && plain.get(plain.size() - 1).text.isEmpty()) {
            plain.remove(plain.size() - 1);
        }
        return plain.isEmpty() ? null : new MusicLyrics(plain, false, fromFile);
    }

    /* LRCLIB */

    private static final HashMap<String, MusicLyrics> cache = new HashMap<>();
    private static final HashMap<String, Boolean> missing = new HashMap<>();

    public static String key(String title, String artist, long durationSec) {
        return (title == null ? "" : title.toLowerCase(Locale.ROOT)) + "\u0001" + (artist == null ? "" : artist.toLowerCase(Locale.ROOT)) + "\u0001" + durationSec;
    }

    /** Whether the online lyrics for the track were looked up and there were none. */
    public static boolean isKnownMissing(String key) {
        return missing.containsKey(key);
    }

    @Nullable
    public static MusicLyrics cached(String key) {
        return cache.get(key);
    }

    /** Looks up LRCLIB in the background; calls back on the main thread with the lyrics or null. */
    public static void fetch(String title, String artist, String album, long durationSec, Utilities.Callback<MusicLyrics> done) {
        final String key = key(title, artist, durationSec);
        if (cache.containsKey(key)) {
            done.run(cache.get(key));
            return;
        }
        if (missing.containsKey(key) || TextUtils.isEmpty(title)) {
            done.run(null);
            return;
        }
        Utilities.externalNetworkQueue.postRunnable(() -> {
            MusicLyrics result = null;
            boolean failed = false;
            try {
                // the exact track first (LRCLIB wants its album and length for that), then a search
                // by title and artist, then by both as one query
                if (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(album) && durationSec > 0) {
                    final String body = getOrNull("https://lrclib.net/api/get?track_name=" + enc(title)
                        + "&artist_name=" + enc(artist) + "&album_name=" + enc(album) + "&duration=" + durationSec);
                    if (body != null) {
                        result = fromJson(new JSONObject(body));
                    }
                }
                if (result == null && !TextUtils.isEmpty(artist)) {
                    final String body = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
                    if (body != null) {
                        result = best(new JSONArray(body), durationSec);
                    }
                }
                if (result == null) {
                    final String q = TextUtils.isEmpty(artist) ? title : artist + " " + title;
                    final String body = get("https://lrclib.net/api/search?q=" + enc(q));
                    if (body != null) {
                        result = best(new JSONArray(body), durationSec);
                    }
                }
            } catch (Exception e) {
                failed = true;
                FileLog.e(e);
            }
            final MusicLyrics r = result;
            final boolean f = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (r != null) {
                    cache.put(key, r);
                } else if (!f) {
                    missing.put(key, true);
                }
                done.run(r);
            });
        });
    }

    @Nullable
    private static MusicLyrics fromJson(@Nullable JSONObject o) {
        if (o == null || o.optBoolean("instrumental", false)) {
            return null;
        }
        MusicLyrics l = parse(string(o, "syncedLyrics"), false);
        if (l == null || !l.synced) {
            final MusicLyrics plain = parse(string(o, "plainLyrics"), false);
            if (plain != null && l == null) {
                l = plain;
            }
        }
        return l;
    }

    @Nullable
    private static MusicLyrics best(JSONArray results, long durationSec) {
        MusicLyrics plainFallback = null;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < results.length(); i++) {
                final JSONObject o = results.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                final double d = o.optDouble("duration", 0);
                // the first pass takes a track of about the same length only
                if (pass == 0 && durationSec > 0 && d > 0 && Math.abs(d - durationSec) > 4) {
                    continue;
                }
                final MusicLyrics l = fromJson(o);
                if (l != null && l.synced) {
                    return l;
                }
                if (l != null && plainFallback == null) {
                    plainFallback = l;
                }
            }
            if (plainFallback != null) {
                return plainFallback;
            }
        }
        return null;
    }

    /** A string field; null for a missing one or JSON's null (optString gives "null" for that). */
    @Nullable
    private static String string(JSONObject o, String name) {
        return o.isNull(name) ? null : o.optString(name, null);
    }

    /** A GET's body, null for any failure (an optional lookup). */
    @Nullable
    private static String getOrNull(String url) {
        try {
            return get(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** The body of a GET, null for 404. */
    @Nullable
    private static String get(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            // LRCLIB asks clients to name themselves
            c.setRequestProperty("User-Agent", "glassGram music player (Android)");
            c.setRequestProperty("Accept", "application/json");
            final int code = c.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code / 100 != 2) {
                throw new java.io.IOException("LRCLIB HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}

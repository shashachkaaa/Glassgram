package org.telegram.messenger;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_stars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class GlassgramProfileGift {
    public static final String DUCK = "tester_duck";
    static final GlassgramProfileGift LEGACY_DUCK = new GlassgramProfileGift(DUCK, "", "", true, 0, 0);
    private static final long RETRY_AFTER = 60_000;
    // Accessed on the UI thread. The key includes the account that resolved the gift.
    private static final LinkedHashMap<String, Resolution> models = new LinkedHashMap<>(32, .75f, true);
    private static final int CACHE_LIMIT = 128;

    private static final class Resolution {
        TL_stars.TL_starGiftUnique gift;
        long retryAt;
        boolean pending;
    }

    public final String id;
    public final String slug;
    public final boolean pinned;
    private final String title;
    private final int center;
    private final int edge;

    private GlassgramProfileGift(String id, String slug, String title, boolean pinned, int center, int edge) {
        this.id = id;
        this.slug = slug;
        this.title = title;
        this.pinned = pinned;
        this.center = center;
        this.edge = edge;
    }

    static List<GlassgramProfileGift> parse(JSONArray items) {
        if (items == null) return Collections.emptyList();
        ArrayList<GlassgramProfileGift> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < items.length() && result.size() < 20; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            String slug = item.optString("slug");
            if (!(DUCK.equals(id) && slug.isEmpty()) && (!slug.matches("[A-Za-z][A-Za-z0-9]{0,99}-[0-9]{1,12}")
                    || !id.equals(slug.toLowerCase(Locale.ROOT)))) continue;
            if (!seen.add(id)) continue;
            result.add(new GlassgramProfileGift(id, slug, item.optString("title"), item.optBoolean("pinned"),
                    color(item.optString("center")), color(item.optString("edge"))));
        }
        return Collections.unmodifiableList(result);
    }

    private static int color(String value) {
        return value.matches("#[0-9a-fA-F]{6}") ? 0xFF000000 | Integer.parseInt(value.substring(1), 16) : 0;
    }

    public boolean isDuck() {
        return DUCK.equals(id);
    }

    public String getTitle(TL_stars.TL_starGiftUnique original) {
        if (isDuck()) return LocaleController.getString(R.string.GlassgramTesterDuck);
        return !title.isEmpty() ? title : original != null ? original.title : slug;
    }

    public long visualId() {
        // Keep client gifts in a different id range from Telegram collectibles.
        long hash = 0;
        for (int i = 0; i < id.length(); i++) hash = hash * 31 + id.charAt(i);
        return Long.MIN_VALUE | hash;
    }

    public TL_stars.TL_starGiftUnique getOriginal(int account) {
        if (isDuck()) return null;
        String key = account + ":" + id;
        Resolution resolution = models.get(key);
        if (resolution == null) {
            resolution = new Resolution();
            models.put(key, resolution);
        }
        if (resolution.gift == null && !resolution.pending && SystemClock.elapsedRealtime() >= resolution.retryAt) {
            Resolution entry = resolution;
            entry.pending = true;
            TL_stars.getUniqueStarGift request = new TL_stars.getUniqueStarGift();
            request.slug = slug;
            ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> {
                        entry.pending = false;
                        entry.retryAt = SystemClock.elapsedRealtime() + RETRY_AFTER;
                        if (response instanceof TL_stars.TL_payments_uniqueStarGift
                                && ((TL_stars.TL_payments_uniqueStarGift) response).gift instanceof TL_stars.TL_starGiftUnique) {
                            entry.gift = (TL_stars.TL_starGiftUnique) ((TL_stars.TL_payments_uniqueStarGift) response).gift;
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.glassgramProfileGiftLoaded);
                        }
                        while (models.size() > CACHE_LIMIT) {
                            String oldest = models.keySet().iterator().next();
                            models.remove(oldest);
                        }
                    }));
        }
        return resolution.gift;
    }

    /** A display model with no Telegram ownership or marketplace data. */
    public TL_stars.TL_starGiftUnique appearance(TL_stars.TL_starGiftUnique original) {
        TL_stars.TL_starGiftUnique card = new TL_stars.TL_starGiftUnique();
        card.id = visualId();
        card.title = getTitle(original);
        TL_stars.starGiftAttributeBackdrop backdrop = new TL_stars.starGiftAttributeBackdrop();
        backdrop.center_color = 0x2CAEB4;
        backdrop.edge_color = 0x2364A0;
        backdrop.pattern_color = 0xADEDFA;
        backdrop.text_color = 0xFFFFFF;
        if (original != null) {
            for (TL_stars.StarGiftAttribute attribute : original.attributes) {
                if (attribute instanceof TL_stars.starGiftAttributeBackdrop) {
                    TL_stars.starGiftAttributeBackdrop source = (TL_stars.starGiftAttributeBackdrop) attribute;
                    backdrop.center_color = source.center_color;
                    backdrop.edge_color = source.edge_color;
                    backdrop.pattern_color = source.pattern_color;
                    backdrop.text_color = source.text_color;
                } else if (attribute instanceof TL_stars.starGiftAttributeModel
                        || attribute instanceof TL_stars.starGiftAttributePattern) {
                    card.attributes.add(attribute);
                }
            }
        }
        if (center != 0) backdrop.center_color = center & 0xFFFFFF;
        if (edge != 0) backdrop.edge_color = edge & 0xFFFFFF;
        card.attributes.add(backdrop);
        return card;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GlassgramProfileGift)) return false;
        GlassgramProfileGift gift = (GlassgramProfileGift) other;
        return id.equals(gift.id) && slug.equals(gift.slug) && title.equals(gift.title)
                && pinned == gift.pinned && center == gift.center && edge == gift.edge;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, slug, title, pinned, center, edge);
    }
}

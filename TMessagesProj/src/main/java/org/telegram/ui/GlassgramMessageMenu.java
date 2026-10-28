package org.telegram.ui;

import android.text.TextUtils;

import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The message menu as one has set it (glassGram settings → Chats → Message menu): items left out,
 * and the rest in one's own order. An item is a kind of thing done to a message, which may stand
 * for several of ChatActivity's options (saving to the gallery or to downloads is "save"); options
 * of no kind here (scheduled messages', polls', ads') stay where Telegram puts them.
 */
public final class GlassgramMessageMenu {

    public static final class Kind {
        public final String key;
        public final int title;
        public final int icon;
        final int[] options;

        Kind(String key, int title, int icon, int... options) {
            this.key = key;
            this.title = title;
            this.icon = icon;
            this.options = options;
        }

        boolean has(int option) {
            for (int o : options) {
                if (o == option) {
                    return true;
                }
            }
            return false;
        }
    }

    /** In Telegram's own order. */
    public static final Kind[] KINDS = {
            new Kind("reply", R.string.Reply, R.drawable.menu_reply, ChatActivity.OPTION_REPLY),
            new Kind("thread", R.string.ViewThread, R.drawable.msg_viewreplies, ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD, ChatActivity.OPTION_VIEW_IN_TOPIC),
            new Kind("copy", R.string.Copy, R.drawable.msg_copy, ChatActivity.OPTION_COPY),
            new Kind("copy_link", R.string.CopyLink, R.drawable.msg_link, ChatActivity.OPTION_COPY_LINK),
            new Kind("translate", R.string.TranslateMessage, R.drawable.msg_translate, ChatActivity.OPTION_TRANSLATE),
            new Kind("edit", R.string.Edit, R.drawable.msg_edit, ChatActivity.OPTION_EDIT),
            new Kind("pin", R.string.PinMessage, R.drawable.msg_pin, ChatActivity.OPTION_PIN, ChatActivity.OPTION_UNPIN),
            new Kind("save", R.string.SaveToGallery, R.drawable.msg_gallery, ChatActivity.OPTION_SAVE_TO_GALLERY, ChatActivity.OPTION_SAVE_TO_GALLERY2, ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC),
            new Kind("stickers", R.string.AddToStickers, R.drawable.msg_sticker, ChatActivity.OPTION_ADD_TO_STICKERS_OR_MASKS, ChatActivity.OPTION_ADD_STICKER_TO_FAVORITES, ChatActivity.OPTION_DELETE_STICKER_FROM_FAVORITES, ChatActivity.OPTION_ADD_TO_GIFS),
            new Kind("share", R.string.ShareFile, R.drawable.msg_shareout, ChatActivity.OPTION_SHARE),
            new Kind("forward", R.string.Forward, R.drawable.msg_forward, ChatActivity.OPTION_FORWARD),
            new Kind("statistics", R.string.Statistics, R.drawable.msg_stats, ChatActivity.OPTION_STATISTICS, ChatActivity.OPTION_VIEW_STATISTICS),
            new Kind("report", R.string.ReportChat, R.drawable.msg_report, ChatActivity.OPTION_REPORT_CHAT),
            new Kind("edit_history", R.string.GlassgramEditHistory, R.drawable.msg_recent, ChatActivity.OPTION_GLASSGRAM_EDIT_HISTORY),
            new Kind("metadata", R.string.GlassgramMediaInfo, R.drawable.msg_info, ChatActivity.OPTION_GLASSGRAM_METADATA),
            new Kind("delete", R.string.Delete, R.drawable.msg_delete, ChatActivity.OPTION_DELETE),
    };

    private GlassgramMessageMenu() {
    }

    public static Kind kindOf(int option) {
        for (Kind kind : KINDS) {
            if (kind.has(option)) {
                return kind;
            }
        }
        return null;
    }

    public static Set<String> hiddenKeys() {
        final HashSet<String> set = new HashSet<>();
        if (!TextUtils.isEmpty(GlassgramConfig.messageMenuHidden)) {
            Collections.addAll(set, GlassgramConfig.messageMenuHidden.split(","));
        }
        return set;
    }

    /** All kinds in the order set, those missing from it (new ones) where Telegram has them. */
    public static ArrayList<Kind> orderedKinds() {
        final ArrayList<Kind> result = new ArrayList<>();
        if (!TextUtils.isEmpty(GlassgramConfig.messageMenuOrder)) {
            for (String key : GlassgramConfig.messageMenuOrder.split(",")) {
                for (Kind kind : KINDS) {
                    if (kind.key.equals(key) && !result.contains(kind)) {
                        result.add(kind);
                    }
                }
            }
        }
        for (int i = 0; i < KINDS.length; i++) {
            if (!result.contains(KINDS[i])) {
                result.add(Math.min(i, result.size()), KINDS[i]);
            }
        }
        return result;
    }

    public static boolean isCustomized() {
        return !TextUtils.isEmpty(GlassgramConfig.messageMenuHidden) || !TextUtils.isEmpty(GlassgramConfig.messageMenuOrder);
    }

    public static void save(ArrayList<Kind> order, Set<String> hidden) {
        final StringBuilder orderValue = new StringBuilder();
        for (Kind kind : order) {
            if (orderValue.length() > 0) {
                orderValue.append(',');
            }
            orderValue.append(kind.key);
        }
        // Telegram's order is kept as none, so later kinds land in their places
        boolean telegramOrder = order.size() == KINDS.length;
        for (int i = 0; telegramOrder && i < KINDS.length; i++) {
            telegramOrder = order.get(i) == KINDS[i];
        }
        GlassgramConfig.messageMenuOrder = telegramOrder ? "" : orderValue.toString();
        GlassgramConfig.messageMenuHidden = TextUtils.join(",", hidden);
        GlassgramConfig.putString("messageMenuOrder", GlassgramConfig.messageMenuOrder);
        GlassgramConfig.putString("messageMenuHidden", GlassgramConfig.messageMenuHidden);
    }

    public static void reset() {
        GlassgramConfig.messageMenuOrder = "";
        GlassgramConfig.messageMenuHidden = "";
        GlassgramConfig.putString("messageMenuOrder", "");
        GlassgramConfig.putString("messageMenuHidden", "");
    }

    /**
     * Leaves out the hidden items of a message's menu and puts the others in the order set. The
     * items of no kind keep their places; the others take the places of their kinds, in order.
     */
    public static void apply(ArrayList<Integer> options, ArrayList<CharSequence> items, ArrayList<Integer> icons) {
        if (!isCustomized() || options.size() != items.size() || options.size() != icons.size()) {
            return;
        }
        final Set<String> hidden = hiddenKeys();
        for (int i = options.size() - 1; i >= 0; i--) {
            final Kind kind = kindOf(options.get(i));
            if (kind != null && hidden.contains(kind.key)) {
                options.remove(i);
                items.remove(i);
                icons.remove(i);
            }
        }
        if (TextUtils.isEmpty(GlassgramConfig.messageMenuOrder)) {
            return;
        }
        final ArrayList<Kind> order = orderedKinds();
        final ArrayList<Integer> slots = new ArrayList<>();
        final ArrayList<Integer> entries = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (kindOf(options.get(i)) != null) {
                slots.add(i);
                entries.add(i);
            }
        }
        // stable: two options of one kind (pin and unpin never come together) keep their order
        Collections.sort(entries, (a, b) -> Integer.compare(order.indexOf(kindOf(options.get(a))), order.indexOf(kindOf(options.get(b)))));
        final ArrayList<Integer> oldOptions = new ArrayList<>(options);
        final ArrayList<CharSequence> oldItems = new ArrayList<>(items);
        final ArrayList<Integer> oldIcons = new ArrayList<>(icons);
        for (int i = 0; i < slots.size(); i++) {
            final int slot = slots.get(i);
            final int from = entries.get(i);
            options.set(slot, oldOptions.get(from));
            items.set(slot, oldItems.get(from));
            icons.set(slot, oldIcons.get(from));
        }
    }
}

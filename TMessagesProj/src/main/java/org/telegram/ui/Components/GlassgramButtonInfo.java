package org.telegram.ui.Components;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * glassGram: what an inline button of a bot's message holds, shown on a long press: its text, its
 * callback_data and the rest by its kind, each copied with a tap.
 */
public class GlassgramButtonInfo {

    public static void show(BaseFragment fragment, TL_keyboard.KeyboardButtonProto button, Theme.ResourcesProvider resourcesProvider) {
        if (fragment == null || fragment.getParentActivity() == null || button == null) {
            return;
        }
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<String> values = new ArrayList<>();
        add(labels, values, LocaleController.getString(R.string.GlassgramButtonText), button.getText());

        final TL_keyboard.ButtonTypeProto type = button.getType();
        add(labels, values, LocaleController.getString(R.string.GlassgramButtonType), typeName(type));
        if (type instanceof TL_keyboard.TL_inlineButtonTypeCallback) {
            final TL_keyboard.TL_inlineButtonTypeCallback callback = (TL_keyboard.TL_inlineButtonTypeCallback) type;
            final byte[] data = callback.data;
            final String text = utf8(data);
            if (text != null) {
                add(labels, values, "callback_data", text);
            } else {
                add(labels, values, "callback_data (hex)", hex(data));
            }
            if (callback.requires_password) {
                add(labels, values, LocaleController.getString(R.string.GlassgramButtonPassword), LocaleController.getString(R.string.GlassgramButtonYes));
            }
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeSwitchInline) {
            add(labels, values, LocaleController.getString(R.string.GlassgramButtonQuery), ((TL_keyboard.TL_inlineButtonTypeSwitchInline) type).query);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUserProfile) {
            add(labels, values, LocaleController.getString(R.string.GlassgramButtonUserId), String.valueOf(((TL_keyboard.TL_inlineButtonTypeUserProfile) type).user_id));
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeCopy) {
            add(labels, values, LocaleController.getString(R.string.GlassgramButtonCopyText), ((TL_keyboard.TL_inlineButtonTypeCopy) type).copy_text);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth) {
            final TL_keyboard.TL_inlineButtonTypeUrlAuth auth = (TL_keyboard.TL_inlineButtonTypeUrlAuth) type;
            add(labels, values, "URL", auth.url);
            add(labels, values, "button_id", String.valueOf(auth.button_id));
        }
        final String url = button.getUrl();
        if (!TextUtils.isEmpty(url) && !(type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth)) {
            add(labels, values, "URL", url);
        }

        final CharSequence[] items = new CharSequence[labels.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = labels.get(i) + ": " + values.get(i);
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getParentActivity(), false, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.GlassgramButtonInfo));
        builder.setItems(items, (dialog, which) -> {
            final String value = values.get(which);
            AndroidUtilities.addToClipboard(value);
            BulletinFactory.of(fragment).createCopyBulletin(LocaleController.formatString(R.string.ExactTextCopied, value)).show();
        });
        fragment.showDialog(builder.create());
    }

    private static void add(ArrayList<String> labels, ArrayList<String> values, String label, String value) {
        if (value == null) {
            return;
        }
        labels.add(label);
        values.add(value);
    }

    private static String typeName(TL_keyboard.ButtonTypeProto type) {
        if (type instanceof TL_keyboard.TL_inlineButtonTypeCallback) return "callback";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) return "url";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth) return "login_url";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeWebView) return "web_app";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeSwitchInline) return "switch_inline_query";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeGame) return "callback_game";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeBuy) return "pay";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeUserProfile) return "user_profile";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeCopy) return "copy_text";
        if (type instanceof TL_keyboard.TL_inlineButtonTypeDisabled) return "disabled";
        return type == null ? "—" : type.getClass().getSimpleName();
    }

    /** The bytes as text when they are valid UTF-8 without control characters, otherwise null. */
    private static String utf8(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            final String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\t') {
                    return null;
                }
            }
            return text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String hex(byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}

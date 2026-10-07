package org.telegram.messenger;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

/** Text clean-ups for Glassgram's settings. */
public final class GlassgramText {

    private GlassgramText() {
    }

    /** Combining marks kept on one letter: real diacritics (Vietnamese, Hebrew points...) use up to two or three. */
    private static final int MAX_MARKS = 3;

    private static boolean isCombiningMark(int c) {
        final int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    /** Whether the text has a pile of combining marks ("Zalgo"); cheap, for skipping clean texts. */
    public static boolean hasZalgo(CharSequence text) {
        if (text == null) {
            return false;
        }
        int run = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            if (isCombiningMark(text.charAt(i))) {
                if (++run > MAX_MARKS) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
    }

    /**
     * Without "Zalgo": combining marks past the first few on a letter are removed. Spans move with the
     * text (a Spanned comes back as a SpannableStringBuilder), and a clean text comes back as it is.
     */
    public static CharSequence stripZalgo(CharSequence text) {
        if (!hasZalgo(text)) {
            return text;
        }
        if (text instanceof Spanned) {
            final SpannableStringBuilder builder = new SpannableStringBuilder(text);
            int run = 0;
            int i = 0;
            while (i < builder.length()) {
                if (isCombiningMark(builder.charAt(i))) {
                    if (++run > MAX_MARKS) {
                        builder.delete(i, i + 1);
                        continue;
                    }
                } else {
                    run = 0;
                }
                i++;
            }
            return builder;
        }
        final StringBuilder builder = new StringBuilder(text.length());
        int run = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            final char c = text.charAt(i);
            if (isCombiningMark(c)) {
                if (++run > MAX_MARKS) {
                    continue;
                }
            } else {
                run = 0;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    /** {@link #stripZalgo} when the filter is on. */
    public static CharSequence filter(CharSequence text) {
        return GlassgramConfig.filterZalgo ? stripZalgo(text) : text;
    }

    /** The same for plain strings (names). */
    public static String filter(String text) {
        return GlassgramConfig.filterZalgo && text != null ? stripZalgo(text).toString() : text;
    }
}

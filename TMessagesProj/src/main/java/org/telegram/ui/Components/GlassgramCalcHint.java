package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.ui.ActionBar.Theme;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * glassGram's calculator in the message field: when the text before the cursor ends with a sum
 * and "=", like "12*7=", a pill above the field shows the result, and a tap writes it after the
 * "=". It takes + − × ÷ ^, brackets and percents (200*15%), with "." or "," for decimals.
 */
@SuppressLint("ViewConstructor")
public class GlassgramCalcHint extends TextView {

    private final View container;
    private final ChatActivityEnterView enterView;
    private final Theme.ResourcesProvider resourcesProvider;
    private final int[] location = new int[2];
    private String result;
    private boolean shown;

    private final ViewTreeObserver.OnPreDrawListener follower = () -> {
        place();
        return true;
    };

    public GlassgramCalcHint(Context context, View container, ChatActivityEnterView enterView, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.container = container;
        this.enterView = enterView;
        this.resourcesProvider = resourcesProvider;
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        setTypeface(AndroidUtilities.bold());
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setMaxWidth(dp(260));
        setPadding(dp(14), dp(7), dp(14), dp(7));
        setElevation(dp(3));
        updateColors();
        setVisibility(GONE);
        setOnClickListener(v -> insert());
    }

    public void updateColors() {
        setTextColor(Theme.getColor(Theme.key_chat_messagePanelText, resourcesProvider));
        setBackground(Theme.createRoundRectDrawable(dp(18), Theme.getColor(Theme.key_chat_stickersHintPanel, resourcesProvider)));
    }

    /** Called when the field's text or cursor changed. */
    public void update(CharSequence text, int cursor) {
        final String value = GlassgramConfig.inputCalculator ? resultFor(text, cursor) : null;
        if (value == null) {
            hide();
            return;
        }
        result = value;
        setText("= " + value);
        if (!shown) {
            shown = true;
            setVisibility(VISIBLE);
            getViewTreeObserver().addOnPreDrawListener(follower);
            place();
            setAlpha(0f);
            setScaleX(0.8f);
            setScaleY(0.8f);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
    }

    public void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        getViewTreeObserver().removeOnPreDrawListener(follower);
        animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT)
            .withEndAction(() -> {
                if (!shown) {
                    setVisibility(GONE);
                }
            }).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        // Leaving with the hint shown (closing the chat) would leave the follower in the window's
        // observer, keeping this hint and, through the field, the whole chat
        if (shown) {
            shown = false;
            getViewTreeObserver().removeOnPreDrawListener(follower);
            animate().cancel();
            setVisibility(GONE);
        }
        super.onDetachedFromWindow();
    }

    // Above the field's left edge, wherever the field is now
    private void place() {
        final EditText field = enterView.getEditField();
        if (field == null || !field.isAttachedToWindow() || getMeasuredHeight() <= 0) {
            return;
        }
        field.getLocationInWindow(location);
        final int fieldX = location[0], fieldY = location[1];
        container.getLocationInWindow(location);
        setPivotX(0);
        setPivotY(getMeasuredHeight());
        setTranslationX(fieldX - location[0] + dp(4) - getLeft());
        setTranslationY(fieldY - location[1] - getMeasuredHeight() - dp(10) - getTop());
    }

    private void insert() {
        final EditText field = enterView.getEditField();
        if (result == null || field == null) {
            return;
        }
        final int cursor = Math.max(0, field.getSelectionEnd());
        final Editable editable = field.getText();
        if (editable == null || cursor > editable.length()) {
            return;
        }
        editable.insert(cursor, result);
        try {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        } catch (Exception ignore) {}
        hide();
    }

    /** The result of the sum right before the cursor ending with "=", or null when there is none. */
    public static String resultFor(CharSequence text, int cursor) {
        if (text == null || cursor <= 1 || cursor > text.length() || text.charAt(cursor - 1) != '=') {
            return null;
        }
        int start = cursor - 1;
        while (start > 0 && isMathChar(text.charAt(start - 1))) {
            start--;
        }
        // the sum starts at a number, a bracket or a sign: "box 2x3=" is 2x3
        while (start < cursor - 1 && !startsSum(text.charAt(start))) {
            start++;
        }
        final String expression = text.subSequence(start, cursor - 1).toString().trim();
        if (expression.length() < 3 || expression.length() > 200) {
            return null;
        }
        final Parser parser = new Parser(expression);
        final double value;
        try {
            value = parser.parse();
        } catch (Exception e) {
            return null;
        }
        // a plain number, or a phone number with a leading +, is not a sum
        if (!parser.hasOperation || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return format(value, expression.indexOf(',') >= 0 && expression.indexOf('.') < 0);
    }

    private static boolean isMathChar(char c) {
        return c >= '0' && c <= '9' || c == '.' || c == ',' || c == ' ' || c == '+' || c == '-' || c == '−'
            || c == '*' || c == '×' || c == 'x' || c == '/' || c == '÷' || c == '^' || c == '%' || c == '(' || c == ')';
    }

    private static boolean startsSum(char c) {
        return c >= '0' && c <= '9' || c == '(' || c == '-' || c == '−' || c == '.';
    }

    private static String format(double value, boolean comma) {
        String s;
        if (Math.abs(value) >= 1e18 || value != 0 && Math.abs(value) < 1e-9) {
            s = new BigDecimal(value).round(new MathContext(10)).stripTrailingZeros().toString().replace("E+", "e").replace("E", "e");
        } else {
            s = new BigDecimal(value).round(new MathContext(15)).stripTrailingZeros().toPlainString();
        }
        if ("-0".equals(s)) {
            s = "0";
        }
        return comma ? s.replace('.', ',') : s;
    }

    // expression := term (('+' | '-') term)*; term := factor (('*' | '/') factor)*;
    // factor := unary ('^' factor)?; unary := '-' unary | postfix; postfix := primary '%'*
    private static final class Parser {
        private final String s;
        private int pos;
        boolean hasOperation;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            final double v = expression();
            skip();
            if (pos != s.length()) {
                throw new IllegalArgumentException();
            }
            return v;
        }

        private void skip() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        private boolean eat(char... options) {
            skip();
            if (pos < s.length()) {
                final char c = s.charAt(pos);
                for (char o : options) {
                    if (c == o) {
                        pos++;
                        return true;
                    }
                }
            }
            return false;
        }

        private double expression() {
            double v = term();
            while (true) {
                if (eat('+')) {
                    v += term();
                    hasOperation = true;
                } else if (eat('-', '−')) {
                    v -= term();
                    hasOperation = true;
                } else {
                    return v;
                }
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                if (eat('*', '×', 'x')) {
                    v *= factor();
                    hasOperation = true;
                } else if (eat('/', '÷')) {
                    final double d = factor();
                    if (d == 0) {
                        throw new ArithmeticException();
                    }
                    v /= d;
                    hasOperation = true;
                } else {
                    return v;
                }
            }
        }

        private double factor() {
            final double v = unary();
            if (eat('^')) {
                hasOperation = true;
                return Math.pow(v, factor());
            }
            return v;
        }

        private double unary() {
            if (eat('-', '−')) {
                return -unary();
            }
            if (eat('+')) {
                return unary();
            }
            double v = primary();
            while (eat('%')) {
                v /= 100;
                hasOperation = true;
            }
            return v;
        }

        private double primary() {
            if (eat('(')) {
                final double v = expression();
                if (!eat(')')) {
                    throw new IllegalArgumentException();
                }
                return v;
            }
            skip();
            final int start = pos;
            boolean separator = false;
            while (pos < s.length()) {
                final char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if ((c == '.' || c == ',') && !separator) {
                    separator = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw new IllegalArgumentException();
            }
            return Double.parseDouble(s.substring(start, pos).replace(',', '.'));
        }
    }
}

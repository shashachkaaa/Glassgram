package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.GlassgramFonts;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

/** Lightweight, theme-aware schematic previews. Never reads chats, accounts or wallpapers. */
public final class GlassgramAppearancePreview extends View implements Theme.Colorable {
    public static final int HEADER = 1, TABS = 2, COMPOSER = 3, FONT = 4;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SparseArray<Drawable> icons = new SparseArray<>();
    private int kind, flags;
    private CharSequence title = "", subtitle = "";
    private Object data;
    private Typeface font;
    private int surface, background, foreground, accent, divider, secondary;

    public GlassgramAppearancePreview(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateColors();
    }

    public static final class Factory extends UItem.UItemFactory<GlassgramAppearancePreview> {
        static { setup(new Factory()); }

        @Override
        public GlassgramAppearancePreview createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new GlassgramAppearancePreview(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((GlassgramAppearancePreview) view).bind(item);
        }

        @Override public boolean isClickable() { return false; }
    }

    public static UItem of(int kind, int flags, CharSequence title, CharSequence subtitle, Object data) {
        UItem item = UItem.ofFactory(Factory.class);
        item.id = 7000 + kind;
        item.intValue = kind;
        item.longValue = flags;
        item.text = title;
        item.subtext = subtitle;
        item.object = data;
        return item;
    }

    private void bind(UItem item) {
        boolean sizeChanged = kind != item.intValue;
        kind = item.intValue;
        flags = (int) item.longValue;
        title = item.text == null ? "" : item.text;
        subtitle = item.subtext == null ? "" : item.subtext;
        data = item.object;
        font = kind == FONT && data instanceof String ? GlassgramFonts.preview((String) data) : null;
        setContentDescription(subtitle + ": " + title);
        updateColors();
        if (sizeChanged) requestLayout();
    }

    @Override
    public void updateColors() {
        surface = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        background = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
        foreground = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        secondary = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        accent = Theme.getColor(Theme.key_windowBackgroundWhiteValueText, resourcesProvider);
        divider = Theme.getColor(Theme.key_divider, resourcesProvider);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = kind == HEADER ? 158 : kind == COMPOSER ? 134 : kind == FONT ? 102 : 96;
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(height));
    }

    private boolean flag(int bit) { return (flags & bit) != 0; }

    private void round(Canvas c, float l, float t, float r, float b, float radius, int color) {
        if (r <= l || b <= t) return;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, radius, radius, fill);
    }

    private void frame(Canvas c, float l, float t, float r, float b, float radius, int color) {
        if (r <= l || b <= t) return;
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Math.max(1, AndroidUtilities.dpf2(0.65f)));
        fill.setColor(color);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    private void pill(Canvas c, float l, float t, float r, float b, boolean glass) {
        float radius = Math.min(dp(18), (b-t)/2f);
        round(c, l, t, r, b, radius, glass ? ColorUtils.blendARGB(surface, background, 0.35f) : surface);
        if (glass) {
            frame(c, l, t, r, b, radius, divider);
            round(c, l+dp(7), t+dp(2), r-dp(7), t+dp(3), dp(1), Theme.multAlpha(foreground, 0.12f));
        }
    }

    private void label(Canvas c, CharSequence value, float x, float centerY, float maxWidth, int size, int color, Typeface typeface, boolean centered) {
        if (maxWidth <= 0) return;
        text.setTypeface(typeface);
        text.setTextSize(dp(size));
        text.setColor(color);
        text.setTextAlign(centered ? Paint.Align.CENTER : Paint.Align.LEFT);
        CharSequence fitted = TextUtils.ellipsize(value, text, maxWidth, TextUtils.TruncateAt.END);
        c.drawText(fitted.toString(), x, centerY - (text.ascent() + text.descent()) / 2f, text);
    }

    private void icon(Canvas c, int res, float x, float y, int size, int color) {
        Drawable d = icons.get(res);
        if (d == null) {
            d = getContext().getDrawable(res);
            if (d == null) return;
            d = d.mutate();
            icons.put(res, d);
        }
        int h = dp(size);
        d.setTint(color);
        d.setBounds(Math.round(x-h/2f), Math.round(y-h/2f), Math.round(x+h/2f), Math.round(y+h/2f));
        d.draw(c);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float l = dp(12), t = dp(12), r = getWidth()-dp(12), b = getHeight()-dp(12);
        if (r-l < dp(100)) return;
        round(c,l,t,r,b,dp(15),background);
        frame(c,l,t,r,b,dp(15),divider);
        c.save();
        c.clipRect(l+dp(2),t+dp(2),r-dp(2),b-dp(2));
        switch (kind) {
            case HEADER: drawHeader(c,l,t,r,b); break;
            case TABS: drawTabs(c,l,t,r,b); break;
            case COMPOSER: drawComposer(c,l,t,r,b); break;
            case FONT:
                label(c, title, (l+r)/2f, t+dp(24), r-l-dp(22), 21, foreground, font, true);
                label(c, subtitle, (l+r)/2f, t+dp(52), r-l-dp(22), 13, secondary, null, true);
                break;
        }
        c.restore();
    }

    private void drawHeader(Canvas c, float l,float t,float r,float b) {
        final boolean glass = flag(1), ios = glass && flag(2);
        float cx = (l+r)/2f;
        if (glass) {
            pill(c,l+dp(8),t+dp(7),l+dp(38),t+dp(37),true);
            pill(c,r-dp(38),t+dp(7),r-dp(8),t+dp(37),true);
        } else {
            round(c,l,t,r,t+dp(41),dp(8),surface);
        }
        icon(c,R.drawable.msg_settings,l+dp(23),t+dp(22),18,foreground);
        icon(c,R.drawable.msg_search,r-dp(23),t+dp(22),18,foreground);
        float tx = ios ? cx : l+dp(46);
        float max = r-l-dp(105);
        if (flag(8)) max -= dp(12);
        label(c,title,tx,t+dp(22),max,15,foreground,AndroidUtilities.bold(),ios);
        if (flag(8)) {
            text.setTypeface(AndroidUtilities.bold());
            text.setTextSize(dp(15));
            float tw = Math.min(max,text.measureText(title.toString()));
            float dotX = ios ? tx+tw/2f+dp(7) : tx+tw+dp(7);
            fill.setColor(accent);
            c.drawCircle(Math.min(dotX,r-dp(46)),t+dp(22),dp(3),fill);
        }
        float y = t+dp(48);
        if (!flag(4)) {
            for (int i=0;i<4;i++) {
                float x = l+dp(22)+i*(r-l-dp(44))/3f;
                fill.setColor(Theme.multAlpha(accent,0.65f));
                c.drawCircle(x,y+dp(7),dp(10),fill);
                fill.setColor(surface);
                c.drawCircle(x,y+dp(7),dp(8),fill);
            }
            y += dp(25);
        }
        if (!flag(16)) {
            round(c,r-dp(48),y,r-dp(12),y+dp(4),dp(2),Theme.multAlpha(accent,0.55f));
        }
        for (int row=0; row<2; row++) {
            float yy=y+dp(13+row*26);
            if (yy+dp(11)>b) break;
            fill.setColor(Theme.multAlpha(secondary,0.45f));
            c.drawCircle(l+dp(22),yy,dp(10),fill);
            round(c,l+dp(40),yy-dp(6),l+(r-l)*0.55f,yy-dp(2),dp(2),secondary);
            round(c,l+dp(40),yy+dp(3),l+(r-l)*0.72f,yy+dp(7),dp(2),Theme.multAlpha(secondary,0.5f));
        }
        if (!flag(32)) {
            pill(c,r-dp(39),b-dp(34),r-dp(7),b-dp(2),glass);
            icon(c,R.drawable.msg_edit,r-dp(23),b-dp(18),18,accent);
        }
    }

    private void drawTabs(Canvas c,float l,float t,float r,float b) {
        if (!(data instanceof List)) return;
        List<?> tabs=(List<?>)data;
        int count=tabs.size();
        if (count==0) return;
        float left=l+dp(8), right=r-dp(8), width=(right-left)/count;
        pill(c,left,t+dp(6),right,b-dp(6),true);
        for (int i=0;i<count;i++) {
            Object obj=tabs.get(LocaleController.isRTL ? count-1-i : i);
            if (!(obj instanceof Integer)) continue;
            int tab=(Integer)obj, drawable, name;
            switch(tab) {
                case GlassgramConfig.TAB_CHATS: drawable=R.drawable.msg_discussion; name=R.string.MainTabsChats; break;
                case GlassgramConfig.TAB_CONTACTS: drawable=R.drawable.msg_contacts; name=R.string.MainTabsContacts; break;
                case GlassgramConfig.TAB_CALLS: drawable=R.drawable.msg_calls; name=R.string.MainTabsCalls; break;
                case GlassgramConfig.TAB_SETTINGS: drawable=R.drawable.msg_settings; name=R.string.Settings; break;
                default: drawable=R.drawable.settings_account; name=R.string.MainTabsProfile; break;
            }
            float x=left+width*(i+0.5f);
            boolean selected=tab==GlassgramConfig.TAB_CHATS;
            if (selected) round(c,x-width/2+dp(3),t+dp(10),x+width/2-dp(3),b-dp(10),dp(15),Theme.multAlpha(accent,0.13f));
            icon(c,drawable,x,t+dp(25),20,selected?accent:foreground);
            label(c,LocaleController.getString(name),x,b-dp(21),width-dp(6),10,selected?accent:secondary,null,true);
        }
    }

    private void drawComposer(Canvas c,float l,float t,float r,float b) {
        float span=r-l;
        round(c,l+dp(11),t+dp(12),l+span*0.62f,t+dp(34),dp(11),Theme.getColor(Theme.key_chat_inBubble,resourcesProvider));
        round(c,r-span*0.48f,t+dp(39),r-dp(11),t+dp(60),dp(11),Theme.getColor(Theme.key_chat_outBubble,resourcesProvider));
        round(c,l+dp(21),t+dp(21),l+span*0.48f,t+dp(25),dp(2),Theme.multAlpha(secondary,0.55f));
        round(c,r-span*0.39f,t+dp(48),r-dp(21),t+dp(52),dp(2),Theme.multAlpha(secondary,0.55f));
        float y=b-dp(37), bottom=b-dp(7);
        if (flag(1)) {
            pill(c,l+dp(8),y,l+dp(38),bottom,true);
            pill(c,l+dp(43),y,r-dp(43),bottom,true);
            pill(c,r-dp(38),y,r-dp(8),bottom,true);
        } else {
            pill(c,l+dp(8),y,r-dp(8),bottom,false);
        }
        icon(c,R.drawable.msg_sendfile,l+dp(23),y+dp(15),17,foreground);
        label(c,title,l+dp(51),y+dp(15),span-dp(103),12,secondary,null,false);
        icon(c,R.drawable.msg_send,r-dp(23),y+dp(15),18,accent);
        if (flag(2)) {
            pill(c,r-dp(51),t+dp(5),r-dp(8),t+dp(24),true);
            fill.setColor(foreground);
            for(int i=0;i<3;i++) c.drawCircle(r-dp(36-i*7),t+dp(14),dp(1.5f),fill);
        }
    }
}

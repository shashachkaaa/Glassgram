package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Objects;

final class GlassgramPluginCard extends FrameLayout implements Theme.Colorable {
    private final int account;
    private final Theme.ResourcesProvider colors;
    private final LinearLayout card;
    private final BackupImageView icon;
    private final LinearLayout labels;
    private final TextView title, version, author, description, error;
    private final View divider;
    private final Switch toggle;
    private final ImageView share, source, pin, delete;
    private PluginInfo plugin;
    private boolean pinned;

    private GlassgramPluginCard(Context context, int account, Theme.ResourcesProvider colors) {
        super(context);
        this.account = account;
        this.colors = colors;
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(5));
        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.TOP, 14, 4, 14, 5));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        icon = new BackupImageView(context);
        header.addView(icon, LayoutHelper.createLinear(44, 44));
        labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPaddingRelative(0, 0, dp(8), 0);
        header.addView(labels, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        title = text(17);
        title.setTypeface(AndroidUtilities.bold());
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        version = text(13);
        labels.addView(version);
        author = text(13);
        author.setMaxLines(2);
        author.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(author);
        toggle = new Switch(context, colors);
        header.addView(toggle, LayoutHelper.createLinear(68, 48));

        description = text(14);
        description.setMaxLines(4);
        description.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.TOP, 0, 12, 0, 0));
        error = text(13);
        card.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.TOP, 0, 8, 0, 0));
        divider = new View(context);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = dp(8);
        card.addView(divider, dividerParams);
        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        share = action(actions, R.drawable.msg_shareout);
        source = action(actions, R.drawable.msg_openin);
        pin = action(actions, R.drawable.msg_pin);
        actions.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
        delete = action(actions, R.drawable.msg_delete);
        updateColors();
    }

    private TextView text(int size) {
        TextView text = new TextView(getContext());
        text.setTextSize(size);
        text.setGravity(Gravity.START);
        return text;
    }

    private ImageView action(LinearLayout bar, int drawable) {
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(drawable);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        bar.addView(icon, LayoutHelper.createLinear(48, 48));
        return icon;
    }

    private int color(int key) {
        return Theme.getColor(key, colors);
    }

    @Override
    public void updateColors() {
        final int primary = color(Theme.key_windowBackgroundWhiteBlackText);
        final int secondary = color(Theme.key_windowBackgroundWhiteGrayText2);
        final int ripple = color(Theme.key_listSelector);
        card.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(24), color(Theme.key_windowBackgroundWhite), ripple));
        title.setTextColor(primary);
        version.setTextColor(secondary);
        author.setTextColor(author.hasOnClickListeners() && author.isClickable()
            ? color(Theme.key_windowBackgroundWhiteLinkText) : secondary);
        description.setTextColor(primary);
        error.setTextColor(color(Theme.key_text_RedBold));
        divider.setBackgroundColor(color(Theme.key_divider));
        for (ImageView button : new ImageView[]{share, source, pin, delete}) {
            button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(24), 0, ripple));
            button.setColorFilter(button == delete ? color(Theme.key_text_RedBold)
                : button == pin && pinned ? color(Theme.key_windowBackgroundWhiteBlueText) : color(Theme.key_windowBackgroundWhiteGrayIcon));
        }
        toggle.updateColors();
    }

    private void bind(GlassgramPluginsActivity screen, PluginInfo plugin, boolean pinned, boolean enabled) {
        final boolean samePlugin = this.plugin != null && this.plugin.id.equals(plugin.id);
        if (!samePlugin || !this.plugin.icon.equals(plugin.icon)) {
            labels.setPaddingRelative(0, 0, dp(8), 0);
            GlassgramPluginInstaller.bindPluginIcon(icon, account, plugin.icon,
                () -> labels.setPaddingRelative(dp(12), 0, dp(8), 0));
        }
        this.plugin = plugin;
        this.pinned = pinned;
        title.setText(plugin.name);
        version.setText(formatString(R.string.GlassgramPluginVersion, plugin.version));
        author.setText(plugin.author);
        author.setVisibility(plugin.author.isEmpty() ? GONE : VISIBLE);
        final String handle = plugin.author.trim();
        final boolean linkedAuthor = handle.matches("@[A-Za-z0-9_]{1,32}");
        author.setOnClickListener(linkedAuthor ? v -> Browser.openUrl(getContext(), "https://t.me/" + handle.substring(1)) : null);
        author.setClickable(linkedAuthor);
        description.setText(plugin.description);
        description.setVisibility(plugin.description.isEmpty() ? GONE : VISIBLE);
        error.setText(getString(R.string.GlassgramPluginLoadError));
        error.setVisibility(plugin.error == null ? GONE : VISIBLE);
        card.setOnClickListener(v -> screen.openPlugin(plugin));
        // Do not reset LiquidToggle's animation on a rebind of the same state.
        if (!samePlugin || toggle.isChecked() != enabled) {
            toggle.setChecked(enabled, samePlugin);
        }
        toggle.setContentDescription(formatString(R.string.GlassgramPluginToggleName, plugin.name));
        toggle.setOnClickListener(v -> screen.togglePlugin(plugin, checked -> toggle.setChecked(checked, true)));

        share.setContentDescription(getString(R.string.ShareFile));
        share.setOnClickListener(v -> screen.sharePlugin(plugin));
        source.setContentDescription(getString(R.string.GlassgramPluginSource));
        source.setOnClickListener(v -> screen.openSource(plugin));
        pin.setImageResource(pinned ? R.drawable.msg_unpin : R.drawable.msg_pin);
        pin.setContentDescription(getString(pinned ? R.string.GlassgramPluginUnpin : R.string.GlassgramPluginPin));
        pin.setOnClickListener(v -> screen.togglePin(plugin));
        delete.setContentDescription(getString(R.string.GlassgramPluginDelete));
        delete.setOnClickListener(v -> screen.deletePlugin(plugin));
        updateColors();
    }

    static final class Factory extends UItem.UItemFactory<GlassgramPluginCard> {
        static { setup(new Factory()); }

        static UItem of(GlassgramPluginsActivity screen, PluginInfo plugin, boolean pinned) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = plugin;
            item.object2 = screen;
            item.checked = pinned;
            item.intValue = screen.displayedEnabled(plugin) ? 1 : 0;
            return item;
        }

        @Override
        public GlassgramPluginCard createView(Context context, RecyclerListView list, int account, int guid, Theme.ResourcesProvider colors) {
            return new GlassgramPluginCard(context, account, colors);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView list) {
            ((GlassgramPluginCard) view).bind((GlassgramPluginsActivity) item.object2, (PluginInfo) item.object, item.checked, item.intValue != 0);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return ((PluginInfo) a.object).id.equals(((PluginInfo) b.object).id);
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            PluginInfo before = (PluginInfo) a.object;
            PluginInfo after = (PluginInfo) b.object;
            return a.checked == b.checked && a.intValue == b.intValue
                && Objects.equals(before.id, after.id)
                && Objects.equals(before.name, after.name)
                && Objects.equals(before.version, after.version)
                && Objects.equals(before.author, after.author)
                && Objects.equals(before.description, after.description)
                && Objects.equals(before.icon, after.icon)
                && Objects.equals(before.error, after.error)
                && Objects.equals(before.path, after.path)
                && Objects.equals(before.engine, after.engine)
                && before.hasSettings == after.hasSettings;
        }
    }
}

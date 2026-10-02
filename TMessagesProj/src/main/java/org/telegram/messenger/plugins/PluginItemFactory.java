package org.telegram.messenger.plugins;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * Settings rows made by a plugin's SimpleSettingFactory (ui.settings).
 *
 * Telegram decides clickability and shadows per factory class, so there is one class for each
 * combination; the row's own Python callbacks travel in UItem.object as a {@link Delegate}.
 */
public class PluginItemFactory extends UItem.UItemFactory<PluginItemFactory.Container> {

    /** The Python factory of a custom row, made by ui.settings.SimpleSettingFactory. */
    public static class Delegate {
        public final PyObject py;
        public final boolean clickable;
        public final boolean shadow;
        final boolean hasEquals;
        final boolean hasContentEquals;

        public Delegate(PyObject py, boolean clickable, boolean shadow, boolean hasEquals, boolean hasContentEquals) {
            this.py = py;
            this.clickable = clickable;
            this.shadow = shadow;
            this.hasEquals = hasEquals;
            this.hasContentEquals = hasContentEquals;
        }
    }

    public static class Container extends FrameLayout {
        final int currentAccount;
        final int classGuid;
        final Theme.ResourcesProvider resourcesProvider;
        final RecyclerListView listView;
        Delegate delegate;

        Container(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.listView = listView;
            this.currentAccount = currentAccount;
            this.classGuid = classGuid;
            this.resourcesProvider = resourcesProvider;
        }

        public View getContent() {
            return getChildCount() > 0 ? getChildAt(0) : null;
        }
    }

    public static class Plain extends PluginItemFactory {
    }

    public static class Clickable extends PluginItemFactory {
        @Override
        public boolean isClickable() {
            return true;
        }
    }

    public static class Shadow extends PluginItemFactory {
        @Override
        public boolean isShadow() {
            return true;
        }
    }

    public static class ClickableShadow extends PluginItemFactory {
        @Override
        public boolean isClickable() {
            return true;
        }

        @Override
        public boolean isShadow() {
            return true;
        }
    }

    private static boolean setUp;

    private static void setupAll() {
        if (setUp) {
            return;
        }
        setUp = true;
        UItem.UItemFactory.setup(new Plain());
        UItem.UItemFactory.setup(new Clickable());
        UItem.UItemFactory.setup(new Shadow());
        UItem.UItemFactory.setup(new ClickableShadow());
    }

    public static UItem asItem(Delegate delegate, Object args) {
        setupAll();
        final UItem item;
        if (delegate.clickable && delegate.shadow) {
            item = UItem.ofFactory(ClickableShadow.class);
        } else if (delegate.clickable) {
            item = UItem.ofFactory(Clickable.class);
        } else if (delegate.shadow) {
            item = UItem.ofFactory(Shadow.class);
        } else {
            item = UItem.ofFactory(Plain.class);
        }
        item.object = delegate;
        item.object2 = args;
        return item;
    }

    @Override
    public boolean isClickable() {
        return false;
    }

    @Override
    public Container createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
        Container container = new Container(context, listView, currentAccount, classGuid, resourcesProvider);
        container.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    @Override
    public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
        if (!(view instanceof Container) || !(item.object instanceof Delegate)) {
            return;
        }
        Container container = (Container) view;
        Delegate delegate = (Delegate) item.object;
        try {
            if (container.delegate != delegate || container.getChildCount() == 0) {
                container.removeAllViews();
                container.delegate = delegate;
                PyObject created = delegate.py.callAttr("create_view", container.getContext(), container.listView, container.currentAccount, container.classGuid, container.resourcesProvider);
                View content = created == null ? null : created.toJava(View.class);
                if (content != null) {
                    if (content.getParent() instanceof ViewGroup) {
                        ((ViewGroup) content.getParent()).removeView(content);
                    }
                    container.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
            }
            View content = container.getContent();
            if (content != null) {
                delegate.py.callAttr("bind_view", content, item, divider, adapter, listView);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void attachedView(RecyclerListView listView, View view, UItem item) {
        if (!(view instanceof Container) || !(item.object instanceof Delegate)) {
            return;
        }
        View content = ((Container) view).getContent();
        if (content == null) {
            return;
        }
        try {
            ((Delegate) item.object).py.callAttr("attached_view", listView, content, item);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean equals(UItem a, UItem b) {
        if (a.object instanceof Delegate && a.object == b.object && ((Delegate) a.object).hasEquals) {
            try {
                return ((Delegate) a.object).py.callAttr("equals", a, b).toBoolean();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return a.object == b.object && a.object2 == b.object2;
    }

    @Override
    public boolean contentsEquals(UItem a, UItem b) {
        if (a.object instanceof Delegate && a.object == b.object && ((Delegate) a.object).hasContentEquals) {
            try {
                return ((Delegate) a.object).py.callAttr("content_equals", a, b).toBoolean();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return super.contentsEquals(a, b);
    }

    /** Click on a custom row; true when the plugin handled it. */
    public static void onClick(PluginInfo plugin, UItem item, View view) {
        if (!(item.object instanceof Delegate)) {
            return;
        }
        View target = view instanceof Container && ((Container) view).getContent() != null ? ((Container) view).getContent() : view;
        try {
            ((Delegate) item.object).py.callAttr("on_click", plugin, item, target);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean onLongClick(PluginInfo plugin, UItem item, View view) {
        if (!(item.object instanceof Delegate)) {
            return false;
        }
        View target = view instanceof Container && ((Container) view).getContent() != null ? ((Container) view).getContent() : view;
        try {
            return ((Delegate) item.object).py.callAttr("on_long_click", plugin, item, target).toBoolean();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }
}

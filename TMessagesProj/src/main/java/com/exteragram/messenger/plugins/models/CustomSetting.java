package com.exteragram.messenger.plugins.models;

import android.view.View;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.Plugin;

import org.telegram.ui.Components.UItem;

/**
 * glassGram: exteraGram's custom settings row, for plugins made for exteraGram that make their
 * rows with a {@link Factory} of their own (ui.settings.Custom(factory=...)): glassGram's plugin
 * settings screen asks the factory for the row's item and passes it the row's clicks.
 */
public class CustomSetting {

    public UItem item;
    public Factory<?> factory;
    public PyObject factoryArgs;

    public CustomSetting(UItem item) {
        this.item = item;
    }

    public CustomSetting(Factory<?> factory, PyObject factoryArgs) {
        this.factory = factory;
        this.factoryArgs = factoryArgs;
    }

    public CustomSetting(View view) {
        this(UItem.asCustom(view));
    }

    public UItem getItem() {
        return item;
    }

    public void setItem(UItem item) {
        this.item = item;
    }

    public Factory<?> getFactory() {
        return factory;
    }

    public void setFactory(Factory<?> factory) {
        this.factory = factory;
    }

    public PyObject getFactoryArgs() {
        return factoryArgs;
    }

    public void setFactoryArgs(PyObject factoryArgs) {
        this.factoryArgs = factoryArgs;
    }

    /** Makes a plugin's custom rows: their item, and the item's view (a {@link UItem.UItemFactory}). */
    public static abstract class Factory<V extends View> extends UItem.UItemFactory<V> {
        public boolean isShadowValue;
        public boolean isClickableValue = true;

        public boolean isShadowValue() {
            return isShadowValue;
        }

        public void setShadowValue(boolean shadowValue) {
            isShadowValue = shadowValue;
        }

        public boolean isClickableValue() {
            return isClickableValue;
        }

        public void setClickableValue(boolean clickableValue) {
            isClickableValue = clickableValue;
        }

        public boolean getIsShadowValue() {
            return isShadowValue;
        }

        public boolean getIsClickableValue() {
            return isClickableValue;
        }

        /** The row's item, null for none. */
        public UItem create(Plugin plugin, CustomSetting customSetting, PyObject factoryArgs) {
            return null;
        }

        public void onClick(Plugin plugin, UItem item, View view) {
        }

        public void onLongClick(Plugin plugin, UItem item, View view) {
        }

        @Override
        public boolean isShadow() {
            return isShadowValue;
        }

        @Override
        public boolean isClickable() {
            return isClickableValue;
        }
    }
}

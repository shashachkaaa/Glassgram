package com.exteragram.messenger.plugins;

import android.text.TextUtils;

import org.telegram.messenger.plugins.PluginInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * glassGram: an installed plugin as exteraGram's plugin engine shows it to plugins, made from
 * what glassGram's engine says of it ({@link PluginInfo}) and kept up to date with it.
 */
public class Plugin {

    private final String id;
    private String name;
    private String description = "";
    private String author = "";
    private String version = "1.0";
    private String icon;
    private String pack;
    private int index = -1;
    private volatile boolean enabled;
    private volatile Throwable error;
    private String path;
    private List<String> requirements = new ArrayList<>();

    public Plugin(String id, String name) {
        this.id = id;
        this.name = name;
    }

    Plugin(PluginInfo info) {
        this(info.id, info.name);
        update(info);
    }

    void update(PluginInfo info) {
        name = info.name;
        description = info.description;
        author = info.author;
        version = TextUtils.isEmpty(info.version) ? "1.0" : info.version;
        setIcon(info.icon);
        enabled = info.enabled;
        // exteraGram keeps the exception; plugins read its message
        error = info.error == null ? null : new RuntimeException(info.error);
        path = info.path;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /** The plugin's engine: glassGram's runs Python plugins only. */
    public String getEngine() {
        return PluginsConstants.PYTHON;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    public boolean hasError() {
        return error != null;
    }

    public String getIcon() {
        return icon;
    }

    /** "pack/index": the sticker of a set the plugin shows as its icon, as in exteraGram. */
    public void setIcon(String icon) {
        this.icon = TextUtils.isEmpty(icon) ? null : icon;
        pack = null;
        index = -1;
        if (this.icon == null) {
            return;
        }
        final int slash = this.icon.lastIndexOf('/');
        if (slash <= 0 || slash == this.icon.length() - 1) {
            return;
        }
        try {
            index = Integer.parseInt(this.icon.substring(slash + 1));
            pack = this.icon.substring(0, slash);
        } catch (NumberFormatException ignore) {
            index = -1;
        }
    }

    public String getPack() {
        return pack;
    }

    public int getIndex() {
        return index;
    }

    public String getPath() {
        return path;
    }

    public List<String> getRequirements() {
        return requirements;
    }

    public void setRequirements(List<String> requirements) {
        this.requirements = requirements == null ? new ArrayList<>() : requirements;
    }
}

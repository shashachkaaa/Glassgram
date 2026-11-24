package org.telegram.messenger.plugins;

/** What the plugin list shows about an installed plugin. */
public final class PluginInfo {

    public final String id;
    public final String name;
    public final String description;
    public final String author;
    public final String version;
    public final String icon;
    public final boolean enabled;
    public final String error;
    public final boolean hasSettings;
    /** Its file, for a .plugin; null for one that is a folder (an .elyx plugin). */
    public final String path;
    /** The engine that runs it: "python" for a .plugin, "elyx-py" for an .elyx or .eaf one. */
    public final String engine;

    PluginInfo(String id, String name, String description, String author, String version, String icon, boolean enabled, String error, boolean hasSettings, String path, String engine) {
        this.id = id;
        this.name = name;
        this.description = description == null ? "" : description;
        this.author = author == null ? "" : author;
        this.version = version == null ? "" : version;
        this.icon = icon == null ? "" : icon;
        this.enabled = enabled;
        this.error = error;
        this.hasSettings = hasSettings;
        this.path = path;
        this.engine = engine;
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

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getError() {
        return error;
    }
}

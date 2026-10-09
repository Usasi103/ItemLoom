package dev.itemloom.paper.compat.script;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original item section and provenance; constructing this value never registers a generator. */
public final class LegacyItemConfig {
    private final String id;
    private final File file;
    private final ConfigurationSection configSection;

    public LegacyItemConfig(String id, File file) {
        this(id, file, read(file));
    }

    /** The old constructor accepts the containing configuration, not the item's child section. */
    public LegacyItemConfig(String id, File file, ConfigurationSection config) {
        this.id = Objects.requireNonNull(id, "id");
        this.file = file;
        configSection = Objects.requireNonNull(config, "config").getConfigurationSection(id);
    }

    public String getId() {
        return id;
    }

    public File getFile() {
        return file;
    }

    public ConfigurationSection getConfigSection() {
        return configSection;
    }

    private static ConfigurationSection read(File file) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(Objects.requireNonNull(file, "file"));
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("Cannot read item configuration: " + file, error);
        }
        return config;
    }
}

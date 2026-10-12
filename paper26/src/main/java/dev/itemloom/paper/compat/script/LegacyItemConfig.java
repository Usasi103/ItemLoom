package dev.itemloom.paper.compat.script;

import java.io.*;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class LegacyItemConfig {
    private final String id;
    private final File file;
    private final ConfigurationSection section;

    public LegacyItemConfig(String id, File file) {
        this(id, file, load(file));
    }

    public LegacyItemConfig(String id, File file, ConfigurationSection config) {
        this.id = Objects.requireNonNull(id);
        this.file = file;
        section = Objects.requireNonNull(config).getConfigurationSection(id);
    }

    private static ConfigurationSection load(File file) {
        Objects.requireNonNull(file);
        YamlConfiguration result = new YamlConfiguration();
        try {
            result.load(file);
            return result;
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalArgumentException("Cannot read item configuration: " + file, error);
        }
    }

    public String getId() {
        return id;
    }

    public File getFile() {
        return file;
    }

    public ConfigurationSection getConfigSection() {
        return section;
    }
}

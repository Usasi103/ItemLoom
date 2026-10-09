package dev.itemloom.paper.compat.script;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import dev.itemloom.compat.ni.NiSourceDirectory;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.integration.PapiBridge;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** A standalone source directory for old scripts, independent of the active generator registry. */
public final class LegacyItemConfigManager {
    private final NiItemOperations owner;
    private final JavaPlugin plugin;
    private final String dir;
    private final Path directory;
    public final ArrayList<File> files = new ArrayList<>();
    public final ConcurrentHashMap<String, LegacyItemConfig> itemConfigs =
            new ConcurrentHashMap<>();

    public LegacyItemConfigManager(
            NiItemOperations owner, JavaPlugin plugin, Path root, String dir) {
        this.owner = Objects.requireNonNull(owner);
        this.plugin = Objects.requireNonNull(plugin);
        this.dir = Objects.requireNonNull(dir);
        directory =
                new File(Objects.requireNonNull(root).toFile(), dir)
                        .toPath()
                        .toAbsolutePath()
                        .normalize();
        reloadItemConfigs();
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public String getDir() {
        return dir;
    }

    public ArrayList<File> getFiles() {
        return files;
    }

    public ConcurrentHashMap<String, LegacyItemConfig> getItemConfigs() {
        return itemConfigs;
    }

    public ArrayList<String> getItemIdsRaw() {
        return new ArrayList<>(itemConfigs.keySet());
    }

    public ArrayList<String> getItemIds() {
        ArrayList<String> ids = getItemIdsRaw();
        Collections.sort(ids);
        return ids;
    }

    /** Prepare every file before replacing these two stable views; never rewrite user YAML. */
    public void reloadItemConfigs() {
        owner.ensureActive();
        ArrayList<File> nextFiles = new ArrayList<>();
        Map<String, LegacyItemConfig> nextConfigs = new LinkedHashMap<>();
        try {
            // The standalone NI directory lists every file, loads .yml only, and keeps last ID
            // wins.
            NiSourceDirectory loaded = NiSourceDirectory.read(directory, false);
            loaded.files().forEach(path -> nextFiles.add(path.toFile()));
            for (var entry : loaded.sources().entrySet()) {
                File file = entry.getKey().toFile();
                String source = NiSourceDirectory.withoutBom(entry.getValue());
                if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                    source = PapiBridge.itemSections(source);
                YamlConfiguration config = new YamlConfiguration();
                config.loadFromString(source);
                for (String id : config.getKeys(false))
                    nextConfigs.put(id, new LegacyItemConfig(id, file, config));
            }
            loaded.verifyUnchanged();
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalArgumentException(
                    "Cannot load item configuration directory: " + directory, error);
        }
        owner.ensureActive();
        files.clear();
        files.addAll(nextFiles);
        itemConfigs.clear();
        itemConfigs.putAll(nextConfigs);
    }
}

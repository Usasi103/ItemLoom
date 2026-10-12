package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.NiSourceDirectory;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.integration.PapiBridge;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class LegacyItemConfigManager {
    public final ArrayList<File> files = new ArrayList<>();
    public final ConcurrentHashMap<String, LegacyItemConfig> itemConfigs =
            new ConcurrentHashMap<>();
    private final NiItemOperations owner;
    private final JavaPlugin plugin;
    private final String dir;
    private final Path directory;

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
        ids.sort(String::compareTo);
        return ids;
    }

    public void reloadItemConfigs() {
        owner.ensureActive();
        try {
            NiSourceDirectory snapshot = NiSourceDirectory.read(directory, false);
            Map<String, LegacyItemConfig> prepared = new LinkedHashMap<>();
            boolean papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
            for (var entry : snapshot.sources().entrySet()) {
                String text = NiSourceDirectory.withoutBom(entry.getValue());
                YamlConfiguration root = new YamlConfiguration();
                root.loadFromString(papi ? PapiBridge.itemSections(text) : text);
                for (String id : root.getKeys(false))
                    prepared.put(id, new LegacyItemConfig(id, entry.getKey().toFile(), root));
            }
            snapshot.verifyUnchanged();
            owner.ensureActive();
            files.clear();
            for (Path path : snapshot.files()) files.add(path.toFile());
            itemConfigs.clear();
            itemConfigs.putAll(prepared);
        } catch (IOException | InvalidConfigurationException error) {
            throw new IllegalArgumentException(
                    "Cannot load item configuration directory: " + directory, error);
        }
    }
}

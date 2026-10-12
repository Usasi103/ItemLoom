package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.*;
import dev.itemloom.core.*;
import dev.itemloom.paper.compat.script.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class NiItemRegistry {
    private record Published(
            ItemEngine.Catalog<ItemStack> catalog,
            Map<String, Integer> hashes,
            Map<String, NiDisplayTemplate> displays,
            Object token) {}

    private final long revision;
    private final NiItemOperations operations;
    private final RevisionMap<LegacyItemConfig> configs;
    private final RevisionMap<LegacyItemGenerator> generators;
    private final ArrayList<File> files = new ArrayList<>();
    private volatile Published published;

    NiItemRegistry(
            long revision, NiRepository.Input input, Path root, NiItemOperations operations) {
        this.revision = revision;
        this.operations = operations;
        published = publication(Map.of());
        configs = new RevisionMap<>(operations::ensureActive, (key, value) -> {}, () -> {});
        generators =
                new RevisionMap<>(
                        operations::ensureActive,
                        (key, value) -> {
                            if (key.isBlank())
                                throw new IllegalArgumentException("Item registry key is required");
                            if (!value.ownedBy(operations))
                                throw new IllegalArgumentException(
                                        "Generator belongs to another revision");
                        },
                        () -> published = publication(generatorsSnapshot()));
        configs.seed(originals(input.items(), root));
    }

    private Map<String, LegacyItemGenerator> generatorsSnapshot() {
        return generators.snapshot();
    }

    static Map<String, LegacyItemConfig> originals(
            Map<String, NiRepository.Definition> definitions, Path root) {
        Map<String, Map<String, Object>> sourceMaps = new LinkedHashMap<>();
        definitions
                .values()
                .forEach(
                        definition ->
                                sourceMaps
                                        .computeIfAbsent(
                                                definition.source(),
                                                ignored -> new LinkedHashMap<>())
                                        .put(definition.id(), definition.config().values()));
        Map<String, ConfigurationSection> roots = new HashMap<>();
        sourceMaps.forEach(
                (source, values) -> roots.put(source, NiConfigViews.section(new NiConfig(values))));
        Map<String, LegacyItemConfig> result = new LinkedHashMap<>();
        definitions.forEach(
                (id, definition) ->
                        result.put(
                                id,
                                new LegacyItemConfig(
                                        id,
                                        root.resolve(definition.source()).toFile(),
                                        roots.get(definition.source()))));
        return result;
    }

    public Map<String, LegacyItemConfig> configs() {
        operations.ensureActive();
        return configs;
    }

    public Map<String, LegacyItemGenerator> generators() {
        operations.ensureActive();
        return generators;
    }

    public ArrayList<File> files() {
        operations.ensureActive();
        return files;
    }

    public ItemEngine.Catalog<ItemStack> catalog() {
        return published.catalog;
    }

    public Object publicationToken() {
        return published.token;
    }

    public Integer updateHash(String id) {
        return published.hashes.get(id);
    }

    public Map<String, NiDisplayTemplate> displays() {
        return published.displays;
    }

    void initialize(Map<String, LegacyItemGenerator> values) {
        generators.seed(values);
        published = publication(values);
    }

    void initializeFiles(Path root) {
        try {
            for (Path file : NiSourceDirectory.list(root.resolve("Items")))
                files.add(file.toFile());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    Runnable prepareReload(
            Map<String, LegacyItemConfig> originals,
            List<Path> paths,
            Map<String, LegacyItemGenerator> compiled) {
        Runnable replaceConfigs = configs.prepareReplacement(originals);
        Runnable replaceGenerators =
                compiled == null ? null : generators.prepareReplacement(compiled);
        Published publication = compiled == null ? published : publication(compiled);
        List<File> preparedFiles = paths.stream().map(Path::toFile).toList();
        return () -> {
            operations.ensureActive();
            replaceConfigs.run();
            if (replaceGenerators != null) replaceGenerators.run();
            files.clear();
            files.addAll(preparedFiles);
            published = publication;
        };
    }

    private Published publication(Map<String, LegacyItemGenerator> values) {
        Map<String, ItemRecipe<ItemStack>> recipes = new LinkedHashMap<>();
        Map<String, Integer> hashes = new LinkedHashMap<>();
        Map<String, NiDisplayTemplate> displays = new LinkedHashMap<>();
        values.forEach(
                (key, generator) -> {
                    recipes.put(key, generator.compiledRecipe());
                    if (generator.getUpdate()) hashes.put(key, generator.getHashCode());
                    if (generator.compiledRecipe().display() != null)
                        displays.put(key, generator.compiledRecipe().display());
                });
        return new Published(
                new ItemEngine.Catalog<>(revision, recipes),
                Collections.unmodifiableMap(hashes),
                Collections.unmodifiableMap(displays),
                new Object());
    }
}

package dev.itemloom.paper.compat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiSourceDirectory;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.ItemEngine;
import dev.itemloom.core.ItemRecipe;
import dev.itemloom.paper.compat.script.LegacyItemConfig;
import dev.itemloom.paper.compat.script.LegacyItemGenerator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** Two independent legacy maps; one immutable publication supplies runtime recipes and hashes. */
public final class NiItemRegistry {
    private record Published(
            ItemEngine.Catalog<ItemStack> catalog,
            Map<String, Integer> updateHashes,
            Map<String, NiDisplayTemplate> displays,
            Object token) {}

    private final long revision;
    private final NiItemOperations operations;
    private final RevisionMap<LegacyItemConfig> configs;
    private final RevisionMap<LegacyItemGenerator> generators;
    private final ArrayList<File> files = new ArrayList<>();
    private volatile Published published;

    NiItemRegistry(
            long revision, NiRepository.Input input, Path inputRoot, NiItemOperations operations) {
        this.revision = revision;
        this.operations = operations;
        configs = new RevisionMap<>(operations::ensureActive, (key, value) -> {}, () -> {});
        generators =
                new RevisionMap<>(
                        operations::ensureActive,
                        (key, value) -> {
                            if (key.isBlank())
                                throw new IllegalArgumentException("Blank item registry key");
                            if (!value.ownedBy(operations))
                                throw new IllegalArgumentException(
                                        "Generator belongs to another item catalog revision");
                        },
                        this::publish);
        configs.seed(originals(input.items(), inputRoot));
        published =
                new Published(
                        new ItemEngine.Catalog<>(revision, Map.of()),
                        Map.of(),
                        Map.of(),
                        new Object());
    }

    static Map<String, LegacyItemConfig> originals(
            Map<String, NiRepository.Definition> definitions, Path inputRoot) {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        definitions.forEach(
                (id, definition) ->
                        grouped.computeIfAbsent(
                                        definition.source(), ignored -> new LinkedHashMap<>())
                                .put(id, definition.config().values()));
        grouped.forEach(
                (source, values) -> files.put(source, NiYaml.toSection(new NiConfig(values))));
        Map<String, LegacyItemConfig> initial = new LinkedHashMap<>();
        definitions.forEach(
                (id, definition) ->
                        initial.put(
                                id,
                                new LegacyItemConfig(
                                        id,
                                        inputRoot.resolve(definition.source()).normalize().toFile(),
                                        files.get(definition.source()))));
        return initial;
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
        return published.catalog();
    }

    /** Cache invalidation must not keep a closed catalog and its script engines reachable. */
    public Object publicationToken() {
        return published.token();
    }

    public Integer updateHash(String id) {
        return published.updateHashes().get(id);
    }

    public Map<String, NiDisplayTemplate> displays() {
        return published.displays();
    }

    void initialize(Map<String, LegacyItemGenerator> initial) {
        generators.seed(initial);
        publish();
    }

    void initializeFiles(Path inputRoot) {
        try {
            NiSourceDirectory.list(inputRoot.resolve("Items"))
                    .forEach(path -> files.add(path.toFile()));
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot list item source files", error);
        }
    }

    /** A null generator map means source-only reload, with the exact previous publication retained. */
    Runnable prepareReload(
            Map<String, LegacyItemConfig> nextConfigs,
            List<Path> nextFiles,
            Map<String, LegacyItemGenerator> nextGenerators) {
        operations.ensureActive();
        Runnable replaceConfigs = configs.prepareReplacement(nextConfigs);
        Runnable replaceGenerators =
                nextGenerators == null ? null : generators.prepareReplacement(nextGenerators);
        Published next = nextGenerators == null ? null : publication(nextGenerators);
        ArrayList<File> copiedFiles = new ArrayList<>();
        nextFiles.forEach(path -> copiedFiles.add(path.toFile()));
        return () -> {
            operations.ensureActive();
            replaceConfigs.run();
            if (replaceGenerators != null) replaceGenerators.run();
            files.clear();
            files.addAll(copiedFiles);
            if (next != null) published = next;
        };
    }

    private void publish() {
        published = publication(generators.snapshot());
    }

    private Published publication(Map<String, LegacyItemGenerator> values) {
        Map<String, ItemRecipe<ItemStack>> recipes = new LinkedHashMap<>();
        Map<String, Integer> hashes = new LinkedHashMap<>();
        Map<String, NiDisplayTemplate> displays = new LinkedHashMap<>();
        values.forEach(
                (key, generator) -> {
                    NiPaperRecipe recipe = generator.compiledRecipe();
                    recipes.put(key, recipe);
                    if (generator.getUpdate()) hashes.put(key, recipe.definitionHash());
                    if (recipe.display() != null) displays.put(key, recipe.display());
                });
        return new Published(
                new ItemEngine.Catalog<>(revision, recipes),
                Map.copyOf(hashes),
                Map.copyOf(displays),
                new Object());
    }
}

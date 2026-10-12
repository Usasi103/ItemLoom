package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.*;
import dev.itemloom.paper.compat.script.LegacyItemPack;
import java.io.*;
import java.util.*;

public final class NiPackRegistry {
    public final NiPackRegistry INSTANCE = this;
    public final Map<String, LegacyItemPack> itemPacks;
    private final NiItemOperations owner;
    private final RevisionMap<LegacyItemPack> values;

    public NiPackRegistry(NiItemOperations owner) {
        this.owner = owner;
        values =
                new RevisionMap<>(
                        owner::ensureActive,
                        (key, pack) -> {
                            if (!pack.ownedBy(owner))
                                throw new IllegalArgumentException(
                                        "Pack belongs to another revision");
                        },
                        () -> {});
        itemPacks = values;
    }

    void initialize(Map<String, Object> definitions) {
        values.seed(compile(definitions));
    }

    private Map<String, LegacyItemPack> compile(Map<String, Object> definitions) {
        Map<String, LegacyItemPack> result = new LinkedHashMap<>();
        definitions.forEach(
                (id, value) -> {
                    if (value instanceof Map<?, ?> map) {
                        Map<String, Object> section = new LinkedHashMap<>();
                        map.forEach((key, child) -> section.put(String.valueOf(key), child));
                        result.put(
                                id,
                                new LegacyItemPack(
                                        owner, id, NiConfigViews.section(new NiConfig(section))));
                    }
                });
        return result;
    }

    public Map<String, LegacyItemPack> getItemPacks() {
        owner.ensureActive();
        return itemPacks;
    }

    public List<String> getItemPackIdsRaw() {
        return new ArrayList<>(values.keySet());
    }

    public List<String> getItemPackIds() {
        return values.keySet().stream().sorted().toList();
    }

    public LegacyItemPack getItemPack(String id) {
        return values.get(id);
    }

    public void reload() {
        owner.ensureActive();
        try {
            NiSourceDirectory source =
                    NiSourceDirectory.read(owner.catalog().inputRoot().resolve("ItemPacks"), true);
            Map<String, Object> definitions = new LinkedHashMap<>();
            source.sources()
                    .forEach(
                            (path, text) ->
                                    definitions.putAll(
                                            NiYaml.read(
                                                            NiSourceDirectory.withoutBom(text),
                                                            path.toString())
                                                    .values()));
            Runnable replace = values.prepareReplacement(compile(definitions));
            source.verifyUnchanged();
            owner.ensureActive();
            replace.run();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}

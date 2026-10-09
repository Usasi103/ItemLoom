package dev.itemloom.paper.compat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiSourceDirectory;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.compat.script.LegacyItemPack;

/** Stable script-visible pack map with candidate-only reload, owned by one catalog revision. */
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
                        (id, pack) -> {
                            if (!pack.ownedBy(owner))
                                throw new IllegalArgumentException(
                                        "Item pack belongs to a different catalog revision");
                        },
                        () -> {});
        itemPacks = values;
    }

    void initialize(Map<String, Object> definitions) {
        values.seed(compile(definitions));
    }

    private Map<String, LegacyItemPack> compile(Map<String, Object> definitions) {
        var containing = NiYaml.toSection(new NiConfig(definitions));
        Map<String, LegacyItemPack> packs = new LinkedHashMap<>();
        for (String id : containing.getKeys(false)) {
            var config = containing.getConfigurationSection(id);
            if (config != null) packs.put(id, new LegacyItemPack(owner, id, config));
        }
        return packs;
    }

    public Map<String, LegacyItemPack> getItemPacks() {
        owner.ensureActive();
        return itemPacks;
    }

    public List<String> getItemPackIdsRaw() {
        return new ArrayList<>(getItemPacks().keySet());
    }

    public List<String> getItemPackIds() {
        var ids = getItemPackIdsRaw();
        ids.sort(String::compareTo);
        return ids;
    }

    public LegacyItemPack getItemPack(String id) {
        return getItemPacks().get(id);
    }

    public void reload() {
        owner.ensureActive();
        try {
            var source =
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
            Runnable publish = values.prepareReplacement(compile(definitions));
            source.verifyUnchanged();
            publish.run();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot reload item packs", failure);
        }
    }
}

package dev.itemloom.compat.ni.script;

import dev.itemloom.compat.ni.*;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

public final class LegacySection {
    private final ConfigurationSection data;
    private final String id;

    public LegacySection(ConfigurationSection data) {
        this(data, data.getName());
    }

    public LegacySection(ConfigurationSection data, String id) {
        this.data = data;
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return data.getString("type");
    }

    public ConfigurationSection getData() {
        return data;
    }

    public String get(Map<String, String> cache, Object player, ConfigurationSection sections) {
        NiEvaluation evaluation =
                NiEvaluation.current()
                        .legacyContext(
                                cache,
                                player,
                                sections == null ? null : NiYaml.fromSection(sections));
        try {
            return evaluation.configured(NiYaml.fromSection(data));
        } finally {
            if (cache != null && cache != evaluation.generation().rolls()) {
                cache.clear();
                cache.putAll(evaluation.generation().rolls());
            }
        }
    }

    public String load(Map<String, String> cache, Object player, ConfigurationSection sections) {
        String result = get(cache, player, sections);
        if (cache != null && result != null) cache.put(id, result);
        return result;
    }

    public String get() {
        return get(null, null, null);
    }

    public String load() {
        return load(null, null, null);
    }
}

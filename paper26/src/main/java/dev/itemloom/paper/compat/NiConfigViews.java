package dev.itemloom.paper.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.itemloom.compat.ni.NiConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Mutable script views of immutable frontend data, without reparsing YAML or evaluating nodes. */
public final class NiConfigViews {
    private NiConfigViews() {}

    public static Map<String, Object> map(NiConfig source) {
        return copyMap(source.values());
    }

    public static ConfigurationSection section(NiConfig source) {
        ConfigurationSection target = new YamlConfiguration();
        fill(target, source.values());
        return target;
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, child) -> copy.put(key.toString(), mutable(child)));
        return copy;
    }

    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> map) return copyMap(map);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(child -> copy.add(mutable(child)));
            return copy;
        }
        return value;
    }

    private static void fill(ConfigurationSection target, Map<?, ?> source) {
        source.forEach(
                (key, value) -> {
                    if (value instanceof Map<?, ?> map)
                        fill(target.createSection(key.toString()), map);
                    else target.set(key.toString(), mutable(value));
                });
    }
}

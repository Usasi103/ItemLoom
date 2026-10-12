package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.NiConfig;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class NiConfigViews {
    private NiConfigViews() {}

    public static Map<String, Object> map(NiConfig config) {
        return copy(config.values());
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), mutable(value)));
        return result;
    }

    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> map) return copy(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(child -> result.add(mutable(child)));
            return result;
        }
        return value;
    }

    public static ConfigurationSection section(NiConfig config) {
        YamlConfiguration result = new YamlConfiguration();
        fill(result, config.values());
        return result;
    }

    private static void fill(ConfigurationSection target, Map<?, ?> source) {
        source.forEach(
                (key, value) -> {
                    String name = String.valueOf(key);
                    if (value instanceof Map<?, ?> map) fill(target.createSection(name), map);
                    else target.set(name, mutable(value));
                });
    }
}

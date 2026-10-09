package dev.itemloom.compat.sx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** Detached SX input. Dotted YAML paths are expanded by Bukkit before this snapshot is made. */
public record SxConfig(Map<String, Object> values) {
    public SxConfig {
        values = freeze(values);
    }

    public static SxConfig from(ConfigurationSection section) {
        return new SxConfig(section.getValues(false));
    }

    public Object get(String key) {
        return values.get(key);
    }

    public String text(String key, String fallback) {
        Object value = get(key);
        return value == null ? fallback : value.toString();
    }

    public boolean bool(String key, boolean fallback) {
        return get(key) instanceof Boolean value ? value : fallback;
    }

    public List<String> strings(String key) {
        if (!(get(key) instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(v -> v instanceof String || v instanceof Number || v instanceof Boolean)
                .map(Object::toString)
                .toList();
    }

    public SxConfig section(String key) {
        return get(key) instanceof Map<?, ?> map
                ? new SxConfig(stringKeys(map))
                : new SxConfig(Map.of());
    }

    public static Map<String, Object> stringKeys(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static Map<String, Object> freeze(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, detach(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object detach(Object value) {
        if (value instanceof ConfigurationSection section) return freeze(section.getValues(false));
        if (value instanceof Map<?, ?> map) return freeze(stringKeys(map));
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(v -> result.add(detach(v)));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof ItemStack item) return item.clone();
        return value;
    }
}

package dev.itemloom.compat.sx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** An owned, ordered configuration tree. */
public record SxConfig(Map<String, Object> values) {
    public SxConfig {
        values = Collections.unmodifiableMap(stringKeys(values));
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
        List<String> result = new ArrayList<>();
        if (get(key) instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof String
                        || value instanceof Number
                        || value instanceof Boolean) {
                    result.add(value.toString());
                }
            }
        }
        return result;
    }

    public SxConfig section(String key) {
        return new SxConfig(get(key) instanceof Map<?, ?> map ? stringKeys(map) : Map.of());
    }

    public static Map<String, Object> stringKeys(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), snapshot(value)));
        return result;
    }

    private static Object snapshot(Object value) {
        if (value instanceof ConfigurationSection section) {
            return Collections.unmodifiableMap(stringKeys(section.getValues(false)));
        }
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(stringKeys(map));
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(entry -> result.add(snapshot(entry)));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof ItemStack item) {
            return item.clone();
        }
        if (value instanceof byte[] array) {
            return array.clone();
        }
        if (value instanceof int[] array) {
            return array.clone();
        }
        if (value instanceof long[] array) {
            return array.clone();
        }
        return value;
    }
}

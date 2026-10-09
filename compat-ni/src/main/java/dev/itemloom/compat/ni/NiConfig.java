package dev.itemloom.compat.ni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detached, recursively immutable input configuration. Does not own files or server state. */
public final class NiConfig {
    private final Map<String, Object> values;
    private final boolean generated;

    public NiConfig(Map<String, ?> source) {
        this(source, false);
    }

    public static NiConfig mapReader(Map<String, ?> source) {
        return new NiConfig(source, true);
    }

    NiConfig(Map<String, ?> source, boolean generated) {
        if (generated) {
            // NI's expanded MapConfigReader copies each accessed section into a default
            // HashMap. Its iteration order affects overlapping aliases/options (for example
            // durability and maxdurability). Preserve that behavior at this boundary only.
            Map<String, Object> orderedLikeNi = new java.util.HashMap<>();
            source.forEach(orderedLikeNi::put);
            source = orderedLikeNi;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    if (generated || value != null) copy.put(key, freeze(value));
                });
        values = Collections.unmodifiableMap(copy);
        this.generated = generated;
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(String.valueOf(key), freeze(child)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(child -> copy.add(freeze(child)));
            return Collections.unmodifiableList(copy);
        }
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Unsupported YAML value: " + value.getClass().getName());
    }

    public Map<String, Object> values() {
        return values;
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public Object get(String path) {
        Object current = values;
        for (String segment : segments(path)) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(segment);
        }
        return current;
    }

    private List<String> segments(String path) {
        if (!generated || path.indexOf('\\') < 0) return List.of(path.split("\\.", -1));
        List<String> result = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean escaped = false;
        for (char c : path.toCharArray()) {
            if (escaped) {
                part.append(c);
                escaped = false;
            } else if (c == '\\') escaped = true;
            else if (c == '.') {
                result.add(part.toString());
                part.setLength(0);
            } else part.append(c);
        }
        result.add(part.toString());
        return result;
    }

    public boolean contains(String path) {
        return get(path) != null;
    }

    public boolean hasKey(String path) {
        Object current = values;
        for (String part : segments(path)) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) return false;
            current = map.get(part);
        }
        return true;
    }

    public String string(String path) {
        Object value = get(path);
        return value == null ? null : String.valueOf(value);
    }

    public String string(String path, String fallback) {
        String value = string(path);
        return value == null ? fallback : value;
    }

    public List<String> strings(String path) {
        Object value = get(path);
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object element : list) {
            if (element != null
                    && (generated
                            || element instanceof String
                            || element instanceof Number
                            || element instanceof Boolean
                            || element instanceof Character)) {
                result.add(String.valueOf(element));
            }
        }
        return result;
    }

    public boolean bool(String path, boolean fallback) {
        Object value = get(path);
        return value instanceof Boolean bool
                ? bool
                : generated && value != null ? Boolean.parseBoolean(value.toString()) : fallback;
    }

    public int integer(String path, int fallback) {
        Object value = get(path);
        return value instanceof Number number
                ? number.intValue()
                : generated && value != null ? Integer.parseInt(value.toString()) : fallback;
    }

    public long longValue(String path, long fallback) {
        Object value = get(path);
        return value instanceof Number number
                ? number.longValue()
                : generated && value != null ? Long.parseLong(value.toString()) : fallback;
    }

    public double decimal(String path, double fallback) {
        Object value = get(path);
        return value instanceof Number number
                ? number.doubleValue()
                : generated && value != null ? Double.parseDouble(value.toString()) : fallback;
    }

    public NiConfig section(String path) {
        Object value = get(path);
        if (!(value instanceof Map<?, ?> map)) return null;
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, child) -> copy.put(String.valueOf(key), child));
        return new NiConfig(copy, generated);
    }

    public NiConfig without(String... paths) {
        Map<String, Object> result = mutableCopy(values);
        for (String path : paths) put(result, path, null);
        return new NiConfig(result, generated);
    }

    public NiConfig overlay(NiConfig override) {
        Map<String, Object> result = mutableCopy(values);
        merge(result, override.values);
        return new NiConfig(result, generated);
    }

    public NiConfig with(String path, Object value) {
        Map<String, Object> result = mutableCopy(values);
        put(result, path, value);
        return new NiConfig(result, generated);
    }

    private static Map<String, Object> mutableCopy(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach(
                (key, value) ->
                        result.put(
                                String.valueOf(key),
                                value instanceof Map<?, ?> map ? mutableCopy(map) : value));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> source) {
        source.forEach(
                (key, value) -> {
                    if (target.get(key) instanceof Map<?, ?> previous
                            && value instanceof Map<?, ?> next) {
                        Map<String, Object> merged = mutableCopy(previous);
                        merge(merged, (Map<String, Object>) next);
                        target.put(key, merged);
                    } else
                        target.put(key, value instanceof Map<?, ?> map ? mutableCopy(map) : value);
                });
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String path, Object value) {
        String[] segments = path.split("\\.", -1);
        Map<String, Object> parent = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Object child = parent.get(segments[i]);
            if (!(child instanceof Map<?, ?>)) {
                if (value == null) return;
                child = new LinkedHashMap<String, Object>();
                parent.put(segments[i], child);
            }
            parent = (Map<String, Object>) child;
        }
        if (value == null) parent.remove(segments[segments.length - 1]);
        else parent.put(segments[segments.length - 1], value);
    }
}

package dev.itemloom.compat.ni;

import java.util.*;

public final class NiConfig {
    private final Map<String, Object> content;
    private final boolean generated;

    public NiConfig(Map<String, ?> source) {
        this(source, false);
    }

    NiConfig(Map<String, ?> source, boolean generated) {
        this.generated = generated;
        Map<String, Object> copy = generated ? new HashMap<>() : new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    if (generated || value != null) copy.put(key, freeze(value));
                });
        content = Collections.unmodifiableMap(copy);
    }

    public static NiConfig mapReader(Map<String, ?> source) {
        return new NiConfig(source, true);
    }

    private static Object freeze(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), freeze(child)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(child -> result.add(freeze(child)));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException(
                "Unsupported configuration value: " + value.getClass().getName());
    }

    public Map<String, Object> values() {
        return content;
    }

    public Set<String> keys() {
        return content.keySet();
    }

    private List<String> path(String path) {
        if (!generated) return Arrays.asList(path.split("\\.", -1));
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean escape = false;
        for (char c : path.toCharArray()) {
            if (escape) {
                part.append(c);
                escape = false;
            } else if (c == '\\') escape = true;
            else if (c == '.') {
                parts.add(part.toString());
                part.setLength(0);
            } else part.append(c);
        }
        parts.add(part.toString());
        return parts;
    }

    public Object get(String path) {
        Object current = content;
        for (String part : path(path)) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    public boolean contains(String path) {
        return get(path) != null;
    }

    public boolean hasKey(String path) {
        List<String> parts = path(path);
        Object current = content;
        for (int i = 0; i < parts.size(); i++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts.get(i))) return false;
            current = map.get(parts.get(i));
        }
        return true;
    }

    public String string(String path) {
        return string(path, null);
    }

    public String string(String path, String fallback) {
        Object value = get(path);
        return value == null ? fallback : value.toString();
    }

    public List<String> strings(String path) {
        List<String> result = new ArrayList<>();
        if (get(path) instanceof List<?> list)
            for (Object value : list) {
                if (value != null
                        && (generated
                                || value instanceof String
                                || value instanceof Number
                                || value instanceof Boolean)) result.add(value.toString());
            }
        return result;
    }

    public boolean bool(String path, boolean fallback) {
        Object value = get(path);
        if (value instanceof Boolean bool) return bool;
        return generated && value != null ? Boolean.parseBoolean(value.toString()) : fallback;
    }

    public int integer(String path, int fallback) {
        Object value = get(path);
        if (value instanceof Number number) return number.intValue();
        return generated && value != null ? Integer.parseInt(value.toString()) : fallback;
    }

    public long longValue(String path, long fallback) {
        Object value = get(path);
        if (value instanceof Number number) return number.longValue();
        return generated && value != null ? Long.parseLong(value.toString()) : fallback;
    }

    public double decimal(String path, double fallback) {
        Object value = get(path);
        if (value instanceof Number number) return number.doubleValue();
        return generated && value != null ? Double.parseDouble(value.toString()) : fallback;
    }

    @SuppressWarnings("unchecked")
    public NiConfig section(String path) {
        return get(path) instanceof Map<?, ?> map
                ? new NiConfig((Map<String, Object>) map, generated)
                : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutable(Map<String, Object> original) {
        Map<String, Object> result = new LinkedHashMap<>();
        original.forEach(
                (key, value) ->
                        result.put(
                                key,
                                value instanceof Map<?, ?> map
                                        ? mutable((Map<String, Object>) map)
                                        : value));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void assign(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.", -1);
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = cursor.get(parts[i]);
            if (!(child instanceof Map)) {
                if (value == null) return;
                child = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], child);
            }
            cursor = (Map<String, Object>) child;
        }
        if (value == null) cursor.remove(parts[parts.length - 1]);
        else cursor.put(parts[parts.length - 1], value);
    }

    public NiConfig without(String... paths) {
        Map<String, Object> result = mutable(content);
        for (String path : paths) assign(result, path, null);
        return new NiConfig(result, generated);
    }

    public NiConfig with(String path, Object value) {
        Map<String, Object> result = mutable(content);
        assign(result, path, value);
        return new NiConfig(result, generated);
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> override) {
        override.forEach(
                (key, value) -> {
                    if (target.get(key) instanceof Map<?, ?> left
                            && value instanceof Map<?, ?> right)
                        merge((Map<String, Object>) left, (Map<String, Object>) right);
                    else
                        target.put(
                                key,
                                value instanceof Map<?, ?> map
                                        ? mutable((Map<String, Object>) map)
                                        : value);
                });
    }

    public NiConfig overlay(NiConfig override) {
        Map<String, Object> result = mutable(content);
        merge(result, override.content);
        return new NiConfig(result, generated);
    }
}

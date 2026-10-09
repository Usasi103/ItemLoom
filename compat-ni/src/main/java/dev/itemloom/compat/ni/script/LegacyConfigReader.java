package dev.itemloom.compat.ni.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiYaml;
import org.bukkit.configuration.ConfigurationSection;

/** Script reader adapters retain live handles; compiled engine configurations remain detached. */
public abstract class LegacyConfigReader {
    public abstract Object getHandle();

    public abstract NiConfig view();

    public static LegacyConfigReader parse(Object input) {
        if (input instanceof LegacyConfigReader reader) return reader;
        if (input instanceof ConfigurationSection section) return new BukkitReader(section);
        if (input instanceof Map<?, ?> map) return new MapReader(map);
        if (input instanceof String text)
            return new MapReader(NiYaml.readGenerated(text, "script configuration").values());
        return null;
    }

    public int size() {
        return keySet().size();
    }

    public Set<String> keySet() {
        return view().keys();
    }

    public boolean containsKey(String key) {
        return view().contains(key);
    }

    public Object get(String key) {
        return view().get(key);
    }

    public Object getOrDefault(String key, Object fallback) {
        Object value = get(key);
        return value == null ? fallback : value;
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String fallback) {
        Object value = get(key);
        return value == null ? fallback : value.toString();
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int fallback) {
        Object value = get(key);
        return value instanceof Number n
                ? n.intValue()
                : this instanceof MapReader && value != null
                        ? Integer.parseInt(value.toString())
                        : fallback;
    }

    public long getLong(String key) {
        return getLong(key, 0);
    }

    public long getLong(String key, long fallback) {
        Object value = get(key);
        return value instanceof Number n
                ? n.longValue()
                : this instanceof MapReader && value != null
                        ? Long.parseLong(value.toString())
                        : fallback;
    }

    public double getDouble(String key) {
        return getDouble(key, 0);
    }

    public double getDouble(String key, double fallback) {
        Object value = get(key);
        return value instanceof Number n
                ? n.doubleValue()
                : this instanceof MapReader && value != null
                        ? Double.parseDouble(value.toString())
                        : fallback;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = get(key);
        return value instanceof Boolean b
                ? b
                : this instanceof MapReader && value != null
                        ? Boolean.parseBoolean(value.toString())
                        : fallback;
    }

    public List<String> getStringList(String key) {
        List<String> result = new ArrayList<>();
        if (get(key) instanceof List<?> values)
            for (Object value : values) {
                if (value != null
                        && (this instanceof MapReader
                                || value instanceof String
                                || value instanceof Number
                                || value instanceof Boolean
                                || value instanceof Character)) result.add(value.toString());
            }
        return result;
    }

    public List<Map<?, ?>> getMapList(String key) {
        List<Map<?, ?>> result = new ArrayList<>();
        if (get(key) instanceof List<?> values)
            for (Object value : values) if (value instanceof Map<?, ?> map) result.add(map);
        return result;
    }

    public LegacyConfigReader getConfig(String key) {
        return parse(get(key));
    }

    public static final class BukkitReader extends LegacyConfigReader {
        private final ConfigurationSection handle;

        public BukkitReader(ConfigurationSection handle) {
            this.handle = java.util.Objects.requireNonNull(handle);
        }

        @Override
        public ConfigurationSection getHandle() {
            return handle;
        }

        @Override
        public NiConfig view() {
            return NiYaml.fromSection(handle);
        }

        @Override
        public Set<String> keySet() {
            return handle.getKeys(false);
        }

        @Override
        public Object get(String key) {
            return handle.get(key);
        }

        @Override
        public boolean containsKey(String key) {
            return handle.contains(key);
        }

        @Override
        public LegacyConfigReader getConfig(String key) {
            ConfigurationSection child = handle.getConfigurationSection(key);
            return child == null ? null : new BukkitReader(child);
        }
    }

    public static final class MapReader extends LegacyConfigReader {
        private final Map<String, Object> handle = new HashMap<>();

        public MapReader(Map<?, ?> handle) {
            handle.forEach((key, value) -> this.handle.put(key.toString(), value));
        }

        @Override
        public Map<String, Object> getHandle() {
            return handle;
        }

        @Override
        public NiConfig view() {
            return NiConfig.mapReader(handle);
        }

        @Override
        public Set<String> keySet() {
            return handle.keySet();
        }

        @Override
        public boolean containsKey(String key) {
            return handle.containsKey(key);
        }

        @Override
        public Object get(String path) {
            if (path.indexOf('.') < 0 && path.indexOf('\\') < 0) return handle.get(path);
            Object value = handle;
            for (String key : dev.itemloom.compat.ni.NiTemplate.split(path, '.', 0)) {
                if (!(value instanceof Map<?, ?> map)) return null;
                value = map.get(key);
            }
            return value;
        }

        @Override
        public LegacyConfigReader getConfig(String key) {
            Object value = get(key);
            return value instanceof Map<?, ?> map ? new MapReader(map) : null;
        }
    }
}

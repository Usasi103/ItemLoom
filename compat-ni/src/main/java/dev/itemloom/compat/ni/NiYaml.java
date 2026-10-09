package dev.itemloom.compat.ni;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** NI generation performs serialize -> expand text -> parse YAML; keep this at the language boundary. */
public final class NiYaml {
    private NiYaml() {}

    public static NiConfig read(String text, String source) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException error) {
            throw new IllegalArgumentException(source + ": " + error.getMessage(), error);
        }
        return new NiConfig(detach(yaml));
    }

    public static String write(NiConfig config) {
        return toSection(config).saveToString();
    }

    /** Expanded generation YAML uses NI's map reader, whose scalar coercion differs from Bukkit. */
    public static NiConfig readGenerated(String text, String source) {
        try {
            Object parsed =
                    new org.yaml.snakeyaml.Yaml(
                                    new org.yaml.snakeyaml.constructor.SafeConstructor(
                                            new org.yaml.snakeyaml.LoaderOptions()))
                            .load(text);
            if (parsed == null) return new NiConfig(Map.of(), true);
            if (!(parsed instanceof Map<?, ?> map))
                throw new IllegalArgumentException("Expected a YAML object");
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return new NiConfig(result, true);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(source + ": " + error.getMessage(), error);
        }
    }

    public static YamlConfiguration toSection(NiConfig config) {
        YamlConfiguration yaml = new YamlConfiguration();
        fill(yaml, config.values());
        return yaml;
    }

    public static NiConfig fromSection(ConfigurationSection config) {
        return new NiConfig(detach(config));
    }

    private static Map<String, Object> detach(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) result.put(key, detachValue(section.get(key)));
        return result;
    }

    private static Object detachValue(Object value) {
        if (value instanceof ConfigurationSection section) return detach(section);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(child -> result.add(detachValue(child)));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), detachValue(child)));
            return result;
        }
        return value;
    }

    private static void fill(ConfigurationSection target, Map<?, ?> values) {
        values.forEach(
                (key, value) -> {
                    if (value instanceof Map<?, ?> map)
                        fill(target.createSection(String.valueOf(key)), map);
                    else target.set(String.valueOf(key), value);
                });
    }
}

package dev.itemloom.compat.ni;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class NiYaml {
    private NiYaml() {}

    public static NiConfig read(String text, String source) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            return fromSection(yaml);
        } catch (Exception error) {
            throw new IllegalArgumentException(source + ": " + error.getMessage(), error);
        }
    }

    public static String write(NiConfig config) {
        return toSection(config).saveToString();
    }

    public static NiConfig readGenerated(String text, String source) {
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            if (parsed == null) return NiConfig.mapReader(Map.of());
            if (!(parsed instanceof Map<?, ?> map))
                throw new IllegalArgumentException("Expected a YAML object");
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            return NiConfig.mapReader(values);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(source + ": " + error.getMessage(), error);
        }
    }

    public static YamlConfiguration toSection(NiConfig config) {
        YamlConfiguration result = new YamlConfiguration();
        writeMap(result, config.values());
        return result;
    }

    private static void writeMap(ConfigurationSection section, Map<?, ?> values) {
        values.forEach(
                (key, value) -> {
                    String name = String.valueOf(key);
                    if (value instanceof Map<?, ?> map) writeMap(section.createSection(name), map);
                    else section.set(name, value);
                });
    }

    public static NiConfig fromSection(ConfigurationSection config) {
        return new NiConfig(readMap(config));
    }

    private static Map<String, Object> readMap(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        section.getValues(false).forEach((key, value) -> result.put(key, detach(value)));
        return result;
    }

    private static Object detach(Object value) {
        if (value instanceof ConfigurationSection section) return readMap(section);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), detach(child)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(NiYaml::detach).toList();
        return value;
    }
}

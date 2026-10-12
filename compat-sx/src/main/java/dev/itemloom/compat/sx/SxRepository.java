package dev.itemloom.compat.sx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Read-only source collection and validation. */
public final class SxRepository {
    public record Definition(String id, String source, SxConfig config, String alias) {}

    public record Input(
            SxConfig settings,
            Map<String, Definition> items,
            Map<String, SxRandom> random,
            Map<String, String> scripts,
            Map<String, String> sources) {
        public Input {
            items = ordered(items);
            random = ordered(random);
            scripts = ordered(scripts);
            sources = ordered(sources);
        }

        private static <T> Map<String, T> ordered(Map<String, T> values) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    public Input read(Path root) throws IOException {
        Map<String, String> sources = texts(root);
        SxConfig settings = new SxConfig(Map.of());
        Map<String, Definition> items = new LinkedHashMap<>();
        Map<String, SxRandom> random = new LinkedHashMap<>();
        Map<String, String> scripts = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String name = source.getKey();
            if (name.startsWith("Scripts/")) {
                scripts.put(name.substring(8), source.getValue());
                continue;
            }
            SxConfig config = parse(source.getValue(), name);
            if (name.equals("Config.yml")) {
                settings = config;
            } else if (name.startsWith("RandomString/")) {
                for (Map.Entry<String, SxRandom> entry :
                        SxRandom.compile(config.values()).entrySet()) {
                    if (random.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate random key " + entry.getKey() + " in " + name);
                    }
                }
            } else {
                for (Map.Entry<String, Object> entry : config.values().entrySet()) {
                    String id = entry.getKey();
                    if (id.startsWith("NoLoad")) {
                        continue;
                    }
                    if (id.isBlank()) {
                        throw new IllegalArgumentException("Blank item ID in " + name);
                    }
                    Object value = entry.getValue();
                    Definition definition;
                    if (value instanceof String alias) {
                        definition = new Definition(id, name, null, alias);
                    } else if (value instanceof Map<?, ?> map) {
                        definition =
                                new Definition(
                                        id, name, new SxConfig(SxConfig.stringKeys(map)), null);
                    } else {
                        throw new IllegalArgumentException("Invalid item " + id + " in " + name);
                    }
                    Definition previous = items.putIfAbsent(id, definition);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "Duplicate item "
                                        + id
                                        + " in "
                                        + previous.source()
                                        + " and "
                                        + name);
                    }
                }
            }
        }
        return new Input(settings, items, random, scripts, sources);
    }

    public void verifyUnchanged(Path root, Input input) throws IOException {
        if (!texts(root).equals(input.sources())) {
            throw new IOException("SX sources changed during preparation; retry reload");
        }
    }

    public static SxConfig parse(String text, String source) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(withoutBom(text));
            return SxConfig.from(yaml);
        } catch (InvalidConfigurationException | RuntimeException error) {
            throw new IllegalArgumentException("Invalid SX YAML in " + source, error);
        }
    }

    private static Map<String, String> texts(Path root) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.exists(root)) {
            return result;
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("SX source root is not a directory: " + root);
        }
        try (var paths = Files.walk(root)) {
            for (Path path :
                    paths.filter(Files::isRegularFile)
                            .sorted((a, b) -> relative(root, a).compareTo(relative(root, b)))
                            .toList()) {
                String name = relative(root, path);
                if (recognized(name)) {
                    result.put(name, withoutBom(Files.readString(path, StandardCharsets.UTF_8)));
                }
            }
        }
        return result;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static boolean recognized(String name) {
        for (String segment : name.split("/")) {
            if (segment.startsWith("NoLoad")) {
                return false;
            }
        }
        return name.equals("Config.yml")
                || name.startsWith("Scripts/") && name.endsWith(".js")
                || (name.startsWith("Item/") || name.startsWith("RandomString/"))
                        && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private static String withoutBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}

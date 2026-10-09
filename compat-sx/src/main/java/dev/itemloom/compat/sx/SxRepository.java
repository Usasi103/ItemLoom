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

/** Optional SX-Item subtree. Ordinary reads never install, migrate, or rewrite source files. */
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
    }

    private static <T> Map<String, T> ordered(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public Input read(Path root) throws IOException {
        Map<String, String> sources = sources(root);
        Map<String, Definition> items = new LinkedHashMap<>();
        Map<String, SxRandom> random = new LinkedHashMap<>();
        Map<String, String> scripts = new LinkedHashMap<>();
        SxConfig settings = new SxConfig(Map.of());
        for (var file : sources.entrySet()) {
            String path = file.getKey();
            if (path.startsWith("Scripts/")) {
                scripts.put(path.substring(8), file.getValue());
                continue;
            }
            SxConfig config = parse(file.getValue(), path);
            if (path.equals("Config.yml")) {
                settings = config;
                continue;
            }
            if (path.startsWith("Item/")) {
                for (var entry : config.values().entrySet()) {
                    String id = entry.getKey();
                    if (id.startsWith("NoLoad")) continue;
                    if (id.isBlank())
                        throw new IllegalArgumentException(path + ": empty SX item ID");
                    Object value = entry.getValue();
                    Definition definition;
                    if (value instanceof String target)
                        definition = new Definition(id, path, null, target);
                    else if (value instanceof Map<?, ?> map)
                        definition =
                                new Definition(
                                        id, path, new SxConfig(SxConfig.stringKeys(map)), null);
                    else
                        throw new IllegalArgumentException(
                                path + ": " + id + " must be an item object or alias");
                    Definition previous = items.putIfAbsent(id, definition);
                    if (previous != null)
                        throw new IllegalArgumentException(
                                path + ": SX item " + id + " duplicates " + previous.source());
                }
            } else if (path.startsWith("RandomString/")) {
                for (var entry : SxRandom.compile(config.values()).entrySet()) {
                    if (random.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                        throw new IllegalArgumentException(
                                path + ": duplicate SX random key " + entry.getKey());
                }
            }
        }
        return new Input(settings, items, random, scripts, sources);
    }

    public void verifyUnchanged(Path root, Input input) throws IOException {
        if (!input.sources().equals(sources(root)))
            throw new IOException("SX files changed during preparation; retry reload");
    }

    public static SxConfig parse(String text, String source) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException | RuntimeException error) {
            throw new IllegalArgumentException(source + ": " + error.getMessage(), error);
        }
        return SxConfig.from(yaml);
    }

    private Map<String, String> sources(Path root) throws IOException {
        if (!Files.exists(root)) return Map.of();
        if (!Files.isDirectory(root)) throw new IOException("SX root is not a directory: " + root);
        Map<String, String> result = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = root.relativize(file);
                boolean disabled = false;
                for (Path part : relative)
                    if (part.toString().startsWith("NoLoad")) {
                        disabled = true;
                        break;
                    }
                if (disabled) continue;
                String name = relative.toString().replace('\\', '/');
                boolean yaml = name.endsWith(".yml") || name.endsWith(".yaml");
                if (!(name.equals("Config.yml")
                        || yaml && (name.startsWith("Item/") || name.startsWith("RandomString/"))
                        || name.startsWith("Scripts/") && name.endsWith(".js"))) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                result.put(name, text.startsWith("\uFEFF") ? text.substring(1) : text);
            }
        }
        return result;
    }
}

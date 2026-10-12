package dev.itemloom.compat.ni;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;

public final class NiRepository {
    public record Definition(String id, String source, NiConfig config) {}

    public record ItemInput(NiSourceDirectory source, Map<String, Definition> items) {
        public ItemInput {
            items = ordered(items);
        }
    }

    public record Input(
            NiConfig settings,
            Map<String, Definition> items,
            Map<String, NiConfig> globalFiles,
            Map<String, Object> globalValues,
            Map<String, Object> packs,
            Map<String, Object> actions,
            Map<String, Object> functions,
            Map<String, String> scripts,
            Map<String, String> expansions,
            Map<String, String> sources) {
        public Input {
            items = ordered(items);
            globalFiles = ordered(globalFiles);
            globalValues = ordered(globalValues);
            packs = ordered(packs);
            actions = ordered(actions);
            functions = ordered(functions);
            scripts = ordered(scripts);
            expansions = ordered(expansions);
            sources = ordered(sources);
        }
    }

    private static <T> Map<String, T> ordered(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public Input read(Path root) throws IOException {
        return read(root, UnaryOperator.identity());
    }

    public Input read(Path root, UnaryOperator<String> itemTransform) throws IOException {
        Map<String, String> sources = snapshot(root);
        Map<String, Definition> items = new LinkedHashMap<>();
        Map<String, NiConfig> globals = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>(),
                packs = new LinkedHashMap<>(),
                actions = new LinkedHashMap<>(),
                functions = new LinkedHashMap<>();
        Map<String, String> scripts = new LinkedHashMap<>(), expansions = new LinkedHashMap<>();
        NiConfig settings = new NiConfig(Map.of());
        List<String> errors = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            String name = entry.getKey();
            int slash = name.indexOf('/');
            String category = slash < 0 ? "" : name.substring(0, slash);
            String relative = slash < 0 ? name : name.substring(slash + 1);
            if (name.endsWith(".js")) {
                if (category.equals("Scripts")) scripts.put(relative, entry.getValue());
                if (category.equals("Expansions")) expansions.put(relative, entry.getValue());
                continue;
            }
            if (!Set.of("", "Items", "GlobalSections", "ItemPacks", "ItemActions", "Functions")
                    .contains(category)) continue;
            try {
                NiConfig config =
                        NiYaml.read(
                                category.equals("Items")
                                        ? itemTransform.apply(entry.getValue())
                                        : entry.getValue(),
                                name);
                switch (category) {
                    case "" -> {
                        if (name.equals("config.yml")) settings = config;
                    }
                    case "Items" -> addItems(items, config, name);
                    case "GlobalSections" -> {
                        globals.put(relative, config);
                        values.putAll(config.values());
                    }
                    case "ItemPacks" -> packs.putAll(config.values());
                    case "ItemActions" -> actions.putAll(config.values());
                    case "Functions" -> functions.putAll(config.values());
                    default -> {}
                }
            } catch (IllegalArgumentException error) {
                errors.add(error.getMessage());
            }
        }
        fail(errors);
        return new Input(
                settings,
                items,
                globals,
                values,
                packs,
                actions,
                functions,
                scripts,
                expansions,
                sources);
    }

    private static void fail(List<String> errors) {
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
    }

    private static void addItems(Map<String, Definition> items, NiConfig config, String source) {
        List<String> errors = new ArrayList<>();
        for (String id : config.keys()) {
            NiConfig section = config.section(id);
            if (section == null) continue;
            Definition previous = items.putIfAbsent(id, new Definition(id, source, section));
            if (previous != null)
                errors.add("Duplicate item " + id + ": " + previous.source() + " and " + source);
        }
        fail(errors);
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Not a directory: " + root);
        Map<String, String> result = new LinkedHashMap<>();
        for (Path file : NiSourceDirectory.list(root)) {
            String name = root.relativize(file).toString().replace('\\', '/');
            if (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".js"))
                result.put(
                        name,
                        NiSourceDirectory.withoutBom(
                                Files.readString(file, StandardCharsets.UTF_8)));
        }
        return result;
    }

    public ItemInput readItems(Path root, UnaryOperator<String> itemTransform) throws IOException {
        NiSourceDirectory source = NiSourceDirectory.read(root.resolve("Items"), true);
        Map<String, Definition> items = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (var entry : source.sources().entrySet()) {
            String name =
                    "Items/"
                            + source.directory()
                                    .relativize(entry.getKey())
                                    .toString()
                                    .replace('\\', '/');
            try {
                addItems(
                        items,
                        NiYaml.read(
                                itemTransform.apply(NiSourceDirectory.withoutBom(entry.getValue())),
                                name),
                        name);
            } catch (IllegalArgumentException error) {
                errors.add(error.getMessage());
            }
        }
        fail(errors);
        return new ItemInput(source, items);
    }

    public void verifyUnchanged(Path root, Input input) throws IOException {
        if (!snapshot(root).equals(input.sources()))
            throw new IOException("Configuration files changed during preparation; retry reload");
    }
}

package dev.itemloom.compat.ni;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads an input tree without installing defaults, rewriting scripts, or mutating disk state. */
public final class NiRepository {
    public record Definition(String id, String source, NiConfig config) {}

    public record ItemInput(NiSourceDirectory source, Map<String, Definition> items) {
        public ItemInput {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
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

        private static <T> Map<String, T> ordered(Map<String, T> values) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    public Input read(Path root) throws IOException {
        return read(root, java.util.function.UnaryOperator.identity());
    }

    /** The optional item transform changes parsed input only; source verification retains raw text. */
    public Input read(Path root, java.util.function.UnaryOperator<String> itemTransform)
            throws IOException {
        Map<String, String> sources = readSources(root);
        List<String> problems = new ArrayList<>();
        return parse(sources, problems, itemTransform);
    }

    /** Local ItemManager reload: parse only Items, retaining this repository's strict duplicate policy. */
    public ItemInput readItems(Path root, java.util.function.UnaryOperator<String> itemTransform)
            throws IOException {
        NiSourceDirectory source = NiSourceDirectory.read(root.resolve("Items"), true);
        Map<String, String> text = new LinkedHashMap<>();
        source.sources()
                .forEach(
                        (path, contents) ->
                                text.put(
                                        "Items/"
                                                + source.directory()
                                                        .relativize(path)
                                                        .toString()
                                                        .replace('\\', '/'),
                                        NiSourceDirectory.withoutBom(contents)));
        Input parsed = parse(text, new ArrayList<>(), itemTransform);
        return new ItemInput(source, parsed.items());
    }

    /** YAML and script files are rechecked before a prepared runtime can replace the active one. */
    public void verifyUnchanged(Path root, Input input) throws IOException {
        if (!input.sources().equals(readSources(root)))
            throw new IOException("Configuration files changed during preparation; retry reload");
    }

    private Map<String, String> readSources(Path root) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        if (!Files.isDirectory(root))
            throw new IOException("Configuration directory does not exist: " + root);
        try (var files = Files.walk(root)) {
            // Stable traversal is useful for diagnostics; duplicate item ids are diagnosed below.
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!(relative.endsWith(".yml")
                        || relative.endsWith(".yaml")
                        || relative.endsWith(".js"))) continue;
                String contents = Files.readString(path, StandardCharsets.UTF_8);
                sources.put(
                        relative, contents.startsWith("\uFEFF") ? contents.substring(1) : contents);
            }
        }
        return sources;
    }

    private Input parse(
            Map<String, String> sources,
            List<String> problems,
            java.util.function.UnaryOperator<String> itemTransform) {
        Map<String, Definition> items = new LinkedHashMap<>();
        Map<String, NiConfig> globals = new LinkedHashMap<>();
        Map<String, Object> globalValues = new LinkedHashMap<>();
        Map<String, Object> packs = new LinkedHashMap<>();
        Map<String, Object> actions = new LinkedHashMap<>();
        Map<String, Object> functions = new LinkedHashMap<>();
        Map<String, String> scripts = new LinkedHashMap<>();
        Map<String, String> expansions = new LinkedHashMap<>();
        NiConfig settings = new NiConfig(Map.of());
        for (Map.Entry<String, String> file : sources.entrySet()) {
            String name = file.getKey();
            int slash = name.indexOf('/');
            String category = slash < 0 ? "" : name.substring(0, slash);
            String relative = slash < 0 ? name : name.substring(slash + 1);
            if (name.endsWith(".js")) {
                if (category.equals("Scripts")) scripts.put(relative, file.getValue());
                if (category.equals("Expansions")) expansions.put(relative, file.getValue());
                continue;
            }
            if (!List.of("", "Items", "GlobalSections", "ItemPacks", "ItemActions", "Functions")
                    .contains(category)) continue;
            NiConfig config;
            try {
                config =
                        NiYaml.read(
                                category.equals("Items")
                                        ? itemTransform.apply(file.getValue())
                                        : file.getValue(),
                                name);
            } catch (IllegalArgumentException error) {
                problems.add(error.getMessage());
                continue;
            }
            switch (category) {
                case "" -> {
                    if (name.equals("config.yml")) settings = config;
                }
                case "Items" -> {
                    for (String id : config.keys()) {
                        NiConfig item = config.section(id);
                        if (item == null) continue;
                        Definition previous = items.putIfAbsent(id, new Definition(id, name, item));
                        if (previous != null)
                            problems.add(name + ": " + id + " duplicates " + previous.source());
                    }
                }
                case "GlobalSections" -> {
                    globals.put(relative, config);
                    globalValues.putAll(config.values());
                }
                case "ItemPacks" -> packs.putAll(config.values());
                case "ItemActions" -> actions.putAll(config.values());
                case "Functions" -> functions.putAll(config.values());
                default -> throw new AssertionError(category);
            }
        }
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("\n", problems));
        return new Input(
                settings,
                items,
                globals,
                globalValues,
                packs,
                actions,
                functions,
                scripts,
                expansions,
                sources);
    }
}

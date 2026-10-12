package dev.itemloom.paper.compat;

import com.google.gson.Gson;
import dev.itemloom.compat.ni.*;
import dev.itemloom.paper.compat.script.*;
import dev.itemloom.paper.compat.script.LegacyItemManager.SaveResult;
import dev.keystone.storage.StorageWriter;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Explicit saves retain checked source bytes for a best-effort multi-file rollback. */
public final class NiItemFiles {
    private static final Gson JSON = new Gson();

    private record Source(Path path, byte[] bytes, NiConfig config) {}

    private record Write(Source original, String text, byte[] bytes) {}

    private NiItemFiles() {}

    public static SaveResult save(
            NiItemOperations items, ItemStack item, String id, String path, boolean cover) {
        items.ensureActive();
        if (item == null || item.isEmpty()) return SaveResult.AIR;
        if (!cover && items.catalog().registry().generators().containsKey(id))
            return SaveResult.CONFLICT;
        Path root = items.catalog().inputRoot().resolve("Items").toAbsolutePath().normalize();
        Path target = root.resolve(Objects.requireNonNull(path, "Item path")).normalize();
        if (target.equals(root) || !target.startsWith(root))
            throw new IllegalArgumentException("Item path must be inside " + root);
        return savePrepared(items, item, id, target, null, cover);
    }

    public static SaveResult save(
            NiItemOperations items,
            ItemStack item,
            String id,
            File file,
            YamlConfiguration config,
            boolean cover) {
        items.ensureActive();
        if (item == null || item.isEmpty()) return SaveResult.AIR;
        if (!cover && items.catalog().registry().generators().containsKey(id))
            return SaveResult.CONFLICT;
        Objects.requireNonNull(config, "Item configuration");
        return savePrepared(
                items,
                item,
                id,
                Objects.requireNonNull(file).toPath().toAbsolutePath().normalize(),
                config,
                cover);
    }

    private static SaveResult savePrepared(
            NiItemOperations items,
            ItemStack item,
            String id,
            Path target,
            YamlConfiguration caller,
            boolean cover) {
        if (id == null || id.isBlank() || id.indexOf('.') >= 0)
            throw new IllegalArgumentException("Item ID must be nonblank and contain no dot");
        try {
            NiItemRegistry registry = items.catalog().registry();
            Path directory = items.catalog().inputRoot().resolve("Items");
            List<Path> listed = listSources(directory);
            Set<Path> paths = new LinkedHashSet<>();
            for (Path path : listed) if (yaml(path)) paths.add(path.toAbsolutePath().normalize());
            for (LegacyItemConfig config : registry.configs().values()) {
                if (config.getFile() != null)
                    paths.add(config.getFile().toPath().toAbsolutePath().normalize());
            }
            for (LegacyItemGenerator generator : registry.generators().values()) {
                if (generator.getFile() != null)
                    paths.add(generator.getFile().toPath().toAbsolutePath().normalize());
            }
            paths.add(target);
            Map<Path, Source> sources = new LinkedHashMap<>();
            for (Path path : paths) sources.put(path, read(path));
            NiConfig supplied = caller == null ? null : NiYaml.fromSection(caller);
            if (!cover
                    && ((supplied != null && supplied.hasKey(id))
                            || sources.values().stream()
                                    .anyMatch(source -> source.config.hasKey(id))))
                return SaveResult.CONFLICT;
            Source destination = sources.get(target);
            Map<String, Object> combined = new LinkedHashMap<>(destination.config.values());
            if (supplied != null)
                for (var entry : supplied.values().entrySet()) {
                    if (!entry.getKey().equals(id)
                            && combined.containsKey(entry.getKey())
                            && !Objects.equals(combined.get(entry.getKey()), entry.getValue()))
                        throw new IllegalArgumentException(
                                "Caller and disk disagree on unrelated definition "
                                        + entry.getKey());
                    combined.put(entry.getKey(), entry.getValue());
                }
            NiConfig captured = NiItemSnapshot.capture(item);
            combined.put(id, captured.values());
            NiConfig candidate = new NiConfig(combined);
            YamlConfiguration candidateSection = NiYaml.toSection(candidate);
            LegacyItemConfig detachedOrigin =
                    new LegacyItemConfig(id, target.toFile(), candidateSection);
            LegacyItemGenerator prepared = items.catalog().compile(detachedOrigin);
            List<Write> writes = new ArrayList<>();
            for (Source source : sources.values()) {
                if (source.path.equals(target)) continue;
                if (source.config.hasKey(id))
                    writes.add(write(source, NiYaml.write(source.config.without(id))));
            }
            String ordinary = NiYaml.write(candidate.without(id));
            // An empty YAML root is a complete flow document, so omit it before the new block key.
            if (candidate.keys().size() == 1) ordinary = "";
            String literal = JSON.toJson(captured.values()).replace("%", "\\u0025");
            String text =
                    ordinary
                            + (ordinary.isEmpty() || ordinary.endsWith("\n") ? "" : "\n")
                            + JSON.toJson(id).replace("%", "\\u0025")
                            + ": "
                            + literal
                            + "\n";
            if (!NiYaml.read(text, target.toString()).values().equals(candidate.values()))
                throw new IllegalArgumentException(
                        "Saved item does not roundtrip through YAML: " + id);
            writes.add(write(destination, text));
            items.ensureActive();
            if (!listSources(directory).equals(listed))
                throw new ConcurrentModificationException(
                        "Items directory changed while preparing save");
            for (Source source : sources.values()) verify(source);
            perform(writes);
            YamlConfiguration publishedSection = candidateSection;
            if (caller != null) {
                caller.set(id, null);
                caller.createSection(id, NiConfigViews.map(captured));
                publishedSection = caller;
            }
            LegacyItemConfig origin = new LegacyItemConfig(id, target.toFile(), publishedSection);
            LegacyItemGenerator generator =
                    new LegacyItemGenerator(
                            items, origin, prepared.compiledRecipe(), prepared.postGenerate());
            registry.configs().put(id, origin);
            registry.generators().put(id, generator);
            if (!registry.files().contains(target.toFile())) registry.files().add(target.toFile());
            return SaveResult.SUCCESS;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot save item " + id + " to " + target, error);
        }
    }

    private static List<Path> listSources(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Item directory is not a directory: " + directory);
        }
        try (var walk = Files.walk(directory)) {
            List<Path> entries = walk.toList();
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("Symbolic item path is not permitted: " + entry);
                }
            }
            return entries.stream()
                    .filter(entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
    }

    private static boolean yaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static Source read(Path path) throws IOException {
        safe(path);
        sidecarsAbsent(path);
        byte[] bytes =
                Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(path) : null;
        String text =
                bytes == null
                        ? ""
                        : StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString();
        return new Source(
                path, bytes, NiYaml.read(NiSourceDirectory.withoutBom(text), path.toString()));
    }

    private static void safe(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current))
                throw new IOException("Symbolic save path is not permitted: " + current);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.equals(path)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Save ancestor is not a directory: " + current);
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Item source is not a regular file: " + path);
    }

    private static Path sidecar(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private static void sidecarsAbsent(Path path) throws IOException {
        for (String suffix : List.of(".tmp", ".previous"))
            if (Files.exists(sidecar(path, suffix), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Recovery sidecar already exists: " + sidecar(path, suffix));
    }

    private static Write write(Source source, String text) {
        return new Write(source, text, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void verify(Source source) throws IOException {
        safe(source.path);
        sidecarsAbsent(source.path);
        byte[] now =
                Files.exists(source.path, LinkOption.NOFOLLOW_LINKS)
                        ? Files.readAllBytes(source.path)
                        : null;
        if (!Arrays.equals(source.bytes, now))
            throw new ConcurrentModificationException("Item source changed: " + source.path);
    }

    private static void perform(List<Write> writes) throws IOException {
        List<Write> attempted = new ArrayList<>();
        List<Path> createdDirectories = new ArrayList<>();
        try {
            for (Write write : writes) {
                List<Path> missing = new ArrayList<>();
                for (Path parent = write.original.path.getParent();
                        parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS);
                        parent = parent.getParent()) missing.add(parent);
                Collections.reverse(missing);
                for (Path path : missing) {
                    Files.createDirectory(path);
                    createdDirectories.add(path);
                }
                attempted.add(write);
                StorageWriter.writeAtomic(write.original.path.toFile(), write.text);
                if (!Arrays.equals(write.bytes, Files.readAllBytes(write.original.path)))
                    throw new IOException("Written item bytes differ: " + write.original.path);
            }
        } catch (IOException | RuntimeException failure) {
            Collections.reverse(attempted);
            for (Write write : attempted) rollback(write.original, failure);
            Collections.reverse(createdDirectories);
            for (Path path : createdDirectories) {
                try {
                    Files.delete(path);
                } catch (DirectoryNotEmptyException ignored) {
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static void rollback(Source source, Exception failure) {
        try {
            safe(source.path);
            if (source.bytes == null) Files.deleteIfExists(source.path);
            else {
                Files.write(source.path, source.bytes);
                if (!Arrays.equals(source.bytes, Files.readAllBytes(source.path)))
                    throw new IOException("Restored bytes differ: " + source.path);
            }
            for (String suffix : List.of(".tmp", ".previous"))
                Files.deleteIfExists(sidecar(source.path, suffix));
        } catch (IOException | RuntimeException restore) {
            failure.addSuppressed(restore);
            if (source.bytes != null) {
                try {
                    Path recovery =
                            source.path.resolveSibling(
                                    source.path.getFileName()
                                            + ".itemloom-recovery-"
                                            + UUID.randomUUID());
                    Files.write(
                            recovery,
                            source.bytes,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    if (!Arrays.equals(source.bytes, Files.readAllBytes(recovery)))
                        throw new IOException("Recovery bytes differ: " + recovery);
                    failure.addSuppressed(
                            new IOException("Original bytes preserved at " + recovery));
                } catch (IOException | RuntimeException recovery) {
                    failure.addSuppressed(recovery);
                }
            }
        }
    }
}

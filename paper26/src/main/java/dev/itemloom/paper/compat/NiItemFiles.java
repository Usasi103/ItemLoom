package dev.itemloom.paper.compat;

import dev.keystone.storage.StorageWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.compat.script.LegacyItemConfig;
import dev.itemloom.paper.compat.script.LegacyItemGenerator;
import dev.itemloom.paper.compat.script.LegacyItemManager.SaveResult;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Synchronous legacy save operations. Candidate compilation precedes disk and registry changes.
 * StorageWriter commits each file separately; cross-file covers roll back caught failures but
 * have a process-crash window. This class never recovers or deletes pre-existing sidecars.
 */
public final class NiItemFiles {
    private NiItemFiles() {}

    public static SaveResult save(
            NiItemOperations owner, ItemStack item, String id, String path, boolean cover) {
        SaveResult early = early(owner, item, id, cover);
        if (early != null) return early;
        Path directory = owner.catalog().inputRoot().resolve("Items");
        Path target =
                directory
                        .resolve(Objects.requireNonNull(path, "path"))
                        .toAbsolutePath()
                        .normalize();
        if (!target.startsWith(directory) || target.equals(directory))
            throw new IllegalArgumentException("Item path must be inside " + directory);
        return savePrepared(owner, item, id, target, null, cover);
    }

    public static SaveResult save(
            NiItemOperations owner,
            ItemStack item,
            String id,
            File file,
            YamlConfiguration config,
            boolean cover) {
        SaveResult early = early(owner, item, id, cover);
        if (early != null) return early;
        return savePrepared(
                owner,
                item,
                id,
                Objects.requireNonNull(file, "file").toPath().toAbsolutePath().normalize(),
                Objects.requireNonNull(config, "config"),
                cover);
    }

    private static SaveResult early(
            NiItemOperations owner, ItemStack item, String id, boolean cover) {
        owner.ensureActive();
        if (item == null || item.isEmpty()) return SaveResult.AIR;
        if (!cover && owner.catalog().registry().generators().containsKey(id))
            return SaveResult.CONFLICT;
        if (id == null || id.isBlank() || id.indexOf('.') >= 0)
            throw new IllegalArgumentException(
                    "Item ID must be a nonempty top-level YAML key without '.'");
        return null;
    }

    private static SaveResult savePrepared(
            NiItemOperations owner,
            ItemStack item,
            String id,
            Path target,
            YamlConfiguration caller,
            boolean cover) {
        try {
            // An existing unregistered definition is still a conflict: do not erase it silently.
            Map<Path, Source> sources = new LinkedHashMap<>();
            Source destination = read(target);
            sources.put(target, destination);
            if (!cover && destination.yaml.contains(id)) return SaveResult.CONFLICT;
            if (!cover && caller != null && caller.contains(id)) return SaveResult.CONFLICT;
            var registry = owner.catalog().registry();
            LinkedHashSet<Path> candidates = new LinkedHashSet<>();
            for (Path path : paths(owner.catalog().inputRoot().resolve("Items"))) {
                String name = path.getFileName().toString();
                if (name.endsWith(".yml") || name.endsWith(".yaml")) candidates.add(path);
            }
            registry.configs()
                    .values()
                    .forEach(
                            config -> {
                                if (config.getFile() != null)
                                    candidates.add(
                                            config.getFile().toPath().toAbsolutePath().normalize());
                            });
            registry.generators()
                    .values()
                    .forEach(
                            generator -> {
                                if (generator.getFile() != null)
                                    candidates.add(
                                            generator
                                                    .getFile()
                                                    .toPath()
                                                    .toAbsolutePath()
                                                    .normalize());
                            });
            for (Path path : candidates) {
                if (path.equals(target)) continue;
                Source source = read(path);
                if (!source.yaml.contains(id)) continue;
                if (!cover) return SaveResult.CONFLICT;
                sources.put(path, source);
            }

            YamlConfiguration merged = caller == null ? copy(destination.yaml) : copy(caller);
            if (caller != null) {
                Map<String, Object> diskValues = NiYaml.fromSection(destination.yaml).values();
                Map<String, Object> callerValues = NiYaml.fromSection(caller).values();
                for (String key : destination.yaml.getKeys(false)) {
                    if (key.equals(id)) continue;
                    if (callerValues.containsKey(key)
                            && !Objects.equals(callerValues.get(key), diskValues.get(key))) {
                        throw new IllegalArgumentException(
                                "Unrelated item configuration differs from disk: "
                                        + target
                                        + " / "
                                        + key);
                    }
                    if (!callerValues.containsKey(key)) merged.set(key, destination.yaml.get(key));
                }
            }
            var snapshot = NiItemSnapshot.capture(item);
            merged.createSection(id, snapshot.values());
            // Bind a detached configuration, so caller mutations cannot redirect this candidate.
            LegacyItemConfig origin = new LegacyItemConfig(id, target.toFile(), merged);
            var generator = owner.catalog().compile(origin);
            List<Write> writes = new ArrayList<>();
            writes.add(new Write(destination, snapshotYaml(merged, id, snapshot.values())));
            for (Source source : sources.values()) {
                if (source == destination) continue;
                YamlConfiguration replacement = copy(source.yaml);
                replacement.set(id, null);
                writes.add(new Write(source, replacement.saveToString()));
            }
            // No script callbacks or scheduler waits occur after this fence, on the main thread.
            owner.ensureActive();
            commit(writes);
            if (caller != null) {
                caller.createSection(id, snapshot.values());
                origin = new LegacyItemConfig(id, target.toFile(), caller);
                generator =
                        new LegacyItemGenerator(
                                owner,
                                origin,
                                generator.compiledRecipe(),
                                generator.postGenerate());
            }
            registry.configs().put(id, origin);
            registry.generators().put(id, generator);
            return SaveResult.SUCCESS;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot save item " + id + " to " + target, error);
        }
    }

    private static List<Path> paths(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Item directory is not a directory: " + root);
        try (var walk = Files.walk(root)) {
            List<Path> result = new ArrayList<>();
            for (Path path : walk.sorted().toList()) {
                if (Files.isSymbolicLink(path))
                    throw new IOException(
                            "Symbolic item paths are not supported for saving: " + path);
                if (Files.isRegularFile(path)) result.add(path);
            }
            return result;
        }
    }

    private record Source(Path path, byte[] original, String text, YamlConfiguration yaml) {}

    private record Write(Source source, String text) {}

    private static Source read(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part))
                throw new IOException("Symbolic item paths are not supported for saving: " + part);
        }
        sidecarsAbsent(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return new Source(path, null, null, new YamlConfiguration());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Item path is not a regular file: " + path);
        byte[] bytes = Files.readAllBytes(path);
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        YamlConfiguration yaml =
                parse(text.startsWith("\uFEFF") ? text.substring(1) : text, path.toString());
        return new Source(path, bytes, text, yaml);
    }

    private static YamlConfiguration parse(String text, String description) throws IOException {
        YamlConfiguration result = new YamlConfiguration();
        try {
            result.loadFromString(text);
        } catch (InvalidConfigurationException error) {
            throw new IOException("Cannot parse item configuration " + description, error);
        }
        return result;
    }

    private static YamlConfiguration copy(YamlConfiguration source) throws IOException {
        return parse(source.saveToString(), "save candidate");
    }

    private static String snapshotYaml(
            YamlConfiguration containing, String id, Map<String, Object> snapshot)
            throws IOException {
        YamlConfiguration neighbors = copy(containing);
        neighbors.set(id, null);
        // NI scans raw YAML for registered %placeholders% before YAML decoding. JSON is a
        // valid YAML flow mapping, and escaping percent here preserves literal snapshot data
        // while leaving the other definitions' placeholder behavior intact.
        var json =
                new com.google.gson.GsonBuilder()
                        .disableHtmlEscaping()
                        .serializeNulls()
                        .setPrettyPrinting()
                        .create();
        String saved =
                (json.toJson(id) + ": " + json.toJson(snapshot)).replace("%", "\\u0025") + "\n";
        String text = (neighbors.getKeys(false).isEmpty() ? "" : neighbors.saveToString()) + saved;
        YamlConfiguration readback = parse(text, "literal item snapshot");
        if (!NiYaml.fromSection(containing).values().equals(NiYaml.fromSection(readback).values()))
            throw new IOException("Saved definition failed YAML verification");
        return text;
    }

    private static Path sidecar(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private static void sidecarsAbsent(Path path) throws IOException {
        for (String suffix : List.of(".tmp", ".previous")) {
            Path sidecar = sidecar(path, suffix);
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Pending recovery material: "
                                + sidecar
                                + "; inspect and explicitly recover it before saving (no automatic recovery performed)");
            }
        }
    }

    private static boolean unchanged(Source source) throws IOException {
        if (source.original == null) return !Files.exists(source.path, LinkOption.NOFOLLOW_LINKS);
        return Files.isRegularFile(source.path, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(source.original, Files.readAllBytes(source.path));
    }

    private static void commit(List<Write> writes) throws IOException {
        LinkedHashSet<Path> missingDirectories = new LinkedHashSet<>();
        for (Write write : writes) {
            Source source = write.source;
            sidecarsAbsent(source.path);
            if (!unchanged(source))
                throw new IOException("Item file changed during save preparation: " + source.path);
            for (Path parent = source.path.getParent();
                    parent != null && !Files.exists(parent);
                    parent = parent.getParent()) {
                missingDirectories.add(parent);
            }
        }
        List<Write> attempted = new ArrayList<>();
        try {
            for (Write write : writes) {
                if (!unchanged(write.source))
                    throw new IOException("Item file changed before commit: " + write.source.path);
                attempted.add(write);
                StorageWriter.writeAtomic(write.source.path.toFile(), write.text);
                if (!Arrays.equals(
                        write.text.getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(write.source.path))) {
                    throw new IOException(
                            "Item file failed write verification: " + write.source.path);
                }
            }
        } catch (IOException | RuntimeException failure) {
            IOException error =
                    new IOException(
                            "Item save failed; cross-file save is not crash-atomic", failure);
            Collections.reverse(attempted);
            for (Write write : attempted) {
                try {
                    rollback(write.source);
                } catch (IOException | RuntimeException restore) {
                    error.addSuppressed(restore);
                    preserveOriginal(write.source, error);
                }
            }
            List<Path> directories = new ArrayList<>(missingDirectories);
            directories.sort(java.util.Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path directory : directories) {
                try {
                    Files.deleteIfExists(directory);
                } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                    /* Keep failed recovery material. */
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        }
    }

    private static void rollback(Source source) throws IOException {
        if (!unchanged(source)) {
            if (source.original == null) Files.deleteIfExists(source.path);
            else StorageWriter.writeAtomic(source.path.toFile(), source.text);
        }
        if (!unchanged(source)) throw new IOException("Cannot verify rollback of " + source.path);
        // Preflight proved these names absent; only this attempt can own StorageWriter's sidecars.
        Files.deleteIfExists(sidecar(source.path, ".tmp"));
        Files.deleteIfExists(sidecar(source.path, ".previous"));
    }

    private static void preserveOriginal(Source source, IOException error) {
        if (source.original == null) {
            error.addSuppressed(
                    new IOException(
                            "Recovery required: remove newly created file only after inspection: "
                                    + source.path));
            return;
        }
        try {
            Path backup =
                    Files.createTempFile(
                            source.path.getParent(),
                            source.path.getFileName() + ".save-rollback-",
                            ".bin");
            Files.write(backup, source.original);
            if (!Arrays.equals(source.original, Files.readAllBytes(backup)))
                throw new IOException("Recovery copy verification failed: " + backup);
            error.addSuppressed(
                    new IOException(
                            "Manual recovery required for "
                                    + source.path
                                    + "; original bytes: "
                                    + backup
                                    + "; inspect .tmp/.previous before any future recovery"));
        } catch (IOException backup) {
            error.addSuppressed(backup);
        }
    }
}

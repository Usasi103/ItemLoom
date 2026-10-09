package dev.itemloom.compat.ni;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only item-directory snapshot, including the complete file list and exact source text. */
public record NiSourceDirectory(
        Path directory, boolean includeYaml, List<Path> files, Map<Path, String> sources) {
    public NiSourceDirectory {
        directory = directory.toAbsolutePath().normalize();
        files = List.copyOf(files);
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    public static List<Path> list(Path directory) throws IOException {
        BasicFileAttributes root;
        try {
            root = Files.readAttributes(directory, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            return List.of();
        }
        if (!root.isDirectory())
            throw new IOException("Not an item configuration directory: " + directory);
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.readAttributes(path, BasicFileAttributes.class).isRegularFile())
                    files.add(path);
            }
        }
        return List.copyOf(files);
    }

    public static NiSourceDirectory read(Path directory, boolean includeYaml) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        List<Path> files = list(absolute);
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(".yml") || includeYaml && name.endsWith(".yaml")) {
                sources.put(file, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return new NiSourceDirectory(absolute, includeYaml, files, sources);
    }

    public void verifyUnchanged() throws IOException {
        NiSourceDirectory current = read(directory, includeYaml);
        if (!files.equals(current.files) || !sources.equals(current.sources)) {
            throw new IOException(
                    "Item configuration files changed during preparation; retry reload: "
                            + directory);
        }
    }

    public static String withoutBom(String source) {
        return source.startsWith("\uFEFF") ? source.substring(1) : source;
    }
}

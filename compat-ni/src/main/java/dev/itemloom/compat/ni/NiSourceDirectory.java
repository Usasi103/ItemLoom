package dev.itemloom.compat.ni;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public record NiSourceDirectory(
        Path directory, boolean includeYaml, List<Path> files, Map<Path, String> sources) {
    public NiSourceDirectory {
        directory = directory.toAbsolutePath().normalize();
        files = List.copyOf(files);
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    public static List<Path> list(Path directory) throws IOException {
        if (!Files.exists(directory)) return List.of();
        if (!Files.isDirectory(directory)) throw new IOException("Not a directory: " + directory);
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    public static NiSourceDirectory read(Path directory, boolean includeYaml) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        List<Path> files = list(root);
        Map<Path, String> text = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.toString();
            if (name.endsWith(".yml") || includeYaml && name.endsWith(".yaml"))
                text.put(file, Files.readString(file, StandardCharsets.UTF_8));
        }
        return new NiSourceDirectory(root, includeYaml, files, text);
    }

    public void verifyUnchanged() throws IOException {
        NiSourceDirectory now = read(directory, includeYaml);
        if (!files.equals(now.files) || !sources.equals(now.sources))
            throw new IOException("Configuration files changed during preparation; retry reload");
    }

    public static String withoutBom(String source) {
        return source.startsWith("\uFEFF") ? source.substring(1) : source;
    }
}

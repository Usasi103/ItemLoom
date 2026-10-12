package dev.itemloom.paper.sx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

/** Validated lookup data observed on the supported Paper release; no runtime legacy conversion. */
final class LegacyMaterialTable {
    private final Map<Integer, Material[]> ids = new HashMap<>();
    private final Map<String, Material[]> names = new HashMap<>();

    static LegacyMaterialTable load() {
        String resource = "/compat-sx/legacy-materials-26.2.tsv";
        try (InputStream stream = LegacyMaterialTable.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing " + resource);
            }
            LegacyMaterialTable table = read(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (table.ids.size() != 463) {
                throw new IOException("Expected 463 legacy keys, found " + table.ids.size());
            }
            return table;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load legacy material observations", error);
        }
    }

    static LegacyMaterialTable read(Reader source) throws IOException {
        LegacyMaterialTable table = new LegacyMaterialTable();
        BufferedReader reader =
                source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        String line;
        int row = 0;
        while ((line = reader.readLine()) != null) {
            row++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                String[] columns = line.split("\t", -1);
                if (columns.length < 3
                        || !columns[0].matches("[0-9]+")
                        || !columns[1].matches("[A-Z0-9_]+")) {
                    throw new IllegalArgumentException("Invalid row header");
                }
                int id = Integer.parseInt(columns[0]);
                Material[] data = new Material[256];
                int previous = -1;
                Material material = null;
                for (int index = 2; index < columns.length; index++) {
                    String[] boundary = columns[index].split("=", -1);
                    if (boundary.length != 2 || !boundary[0].matches("[0-9]+")) {
                        throw new IllegalArgumentException("Invalid data boundary");
                    }
                    int start = Integer.parseInt(boundary[0]);
                    if (start > 255 || start <= previous || previous == -1 && start != 0) {
                        throw new IllegalArgumentException("Unordered data boundary");
                    }
                    if (previous >= 0) {
                        Arrays.fill(data, previous, start, material);
                    }
                    material = boundary[1].equals("-") ? null : Material.getMaterial(boundary[1]);
                    if (!boundary[1].equals("-")
                            && (material == null
                                    || material.isLegacy()
                                    || material.isAir()
                                    || !material.isItem())) {
                        throw new IllegalArgumentException("Invalid modern item " + boundary[1]);
                    }
                    previous = start;
                }
                Arrays.fill(data, previous, 256, material);
                if (table.ids.putIfAbsent(id, data) != null
                        || table.names.putIfAbsent(columns[1], data) != null) {
                    throw new IllegalArgumentException("Duplicate legacy material");
                }
            } catch (RuntimeException error) {
                throw new IOException("Invalid legacy material table row " + row, error);
            }
        }
        return table;
    }

    Material byId(int id, int data) {
        return at(ids.get(id), data);
    }

    Material byName(String name, int data) {
        return at(names.get(name.toUpperCase(Locale.ROOT)), data);
    }

    private static Material at(Material[] values, int data) {
        return values == null || data < 0 || data > 255 ? null : values[data];
    }
}

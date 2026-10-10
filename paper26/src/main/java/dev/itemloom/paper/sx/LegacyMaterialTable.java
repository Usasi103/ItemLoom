package dev.itemloom.paper.sx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

/** Fixed Paper 26.2 observations; no CraftLegacy/DFU call is made while generating an item. */
final class LegacyMaterialTable {
    private final Map<Integer, Variants> ids;
    private final Map<String, Variants> names;

    private LegacyMaterialTable(Map<Integer, Variants> ids, Map<String, Variants> names) {
        this.ids = Map.copyOf(ids);
        this.names = Map.copyOf(names);
    }

    static LegacyMaterialTable load() {
        var stream =
                LegacyMaterialTable.class.getResourceAsStream(
                        "/compat-sx/legacy-materials-26.2.tsv");
        if (stream == null)
            throw new IllegalStateException("Missing Paper legacy material observations");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            LegacyMaterialTable table = read(reader);
            if (table.ids.size() != 463)
                throw new IOException("Incomplete Paper legacy material observations");
            return table;
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Invalid Paper legacy material observations", error);
        }
    }

    static LegacyMaterialTable read(Reader source) throws IOException {
        Map<Integer, Variants> ids = new HashMap<>();
        Map<String, Variants> names = new HashMap<>();
        var reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length < 3) throw new IOException("Incomplete material row");
            int id = Integer.parseInt(fields[0]);
            if (id < 0 || !fields[1].matches("[A-Z][A-Z0-9_]*"))
                throw new IOException("Invalid legacy key");
            int[] starts = new int[fields.length - 2];
            Material[] materials = new Material[starts.length];
            for (int i = 0; i < starts.length; i++) {
                String[] entry = fields[i + 2].split("=", -1);
                if (entry.length != 2) throw new IOException("Invalid variant entry");
                int start = Integer.parseInt(entry[0]);
                if ((i == 0 && start != 0) || (i > 0 && start <= starts[i - 1]) || start > 255)
                    throw new IOException("Invalid variant range");
                starts[i] = start;
                Material material = entry[1].equals("-") ? null : Material.valueOf(entry[1]);
                if (material != null
                        && (material.isLegacy() || material.isAir() || !material.isItem()))
                    throw new IOException("Invalid modern item material");
                materials[i] = material;
            }
            Variants variants = new Variants(starts, materials);
            if (ids.putIfAbsent(id, variants) != null
                    || names.putIfAbsent(fields[1], variants) != null)
                throw new IOException("Duplicate legacy key");
        }
        return new LegacyMaterialTable(ids, names);
    }

    Material byId(int id, int data) {
        return resolve(ids.get(id), data);
    }

    Material byName(String name, int data) {
        return resolve(names.get(name.toUpperCase(Locale.ROOT)), data);
    }

    private static Material resolve(Variants variants, int data) {
        if (variants == null || data < 0 || data > 255) return null;
        int index = Arrays.binarySearch(variants.starts, data);
        return variants.materials[index >= 0 ? index : -index - 2];
    }

    private record Variants(int[] starts, Material[] materials) {}
}

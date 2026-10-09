package dev.itemloom.paper.sx;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.material.MaterialData;

/** Legacy identifiers are configuration data; only modern Paper materials leave this boundary. */
@SuppressWarnings({"deprecation", "removal"})
final class SxMaterials {
    record Resolved(Material material, String damage) {}

    private static final Map<String, Material> CACHE = new HashMap<>();

    private SxMaterials() {}

    static Resolved resolve(String text) {
        Material exact = modern(text);
        if (exact != null) return new Resolved(exact, null);
        int colon = text.lastIndexOf(':');
        String base = text, suffix = null;
        if (colon > 0) {
            String candidate = text.substring(colon + 1);
            if (candidate.matches("(?:<[+-]?\\d+|[+-]?\\d+|[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)%)")) {
                base = text.substring(0, colon);
                suffix = candidate;
            }
        }
        Material material = modern(base);
        if (material == null) {
            int data = suffix != null && suffix.matches("\\d+") ? Integer.parseInt(suffix) : 0;
            String key = base + ':' + data;
            material = CACHE.get(key);
            if (material == null) {
                Material legacy = null;
                if (base.matches("\\d+")) {
                    int id = Integer.parseInt(base);
                    legacy = LegacyIds.BY_ID.get(id);
                } else legacy = LegacyIds.BY_NAME.get("LEGACY_" + base.toUpperCase(Locale.ROOT));
                if (legacy != null && data <= 255)
                    material =
                            Bukkit.getUnsafe()
                                    .fromLegacy(new MaterialData(legacy, (byte) data), true);
                if (material != null && !material.isAir()) CACHE.put(key, material);
            }
        }
        if (material == null || material.isAir() || !material.isItem())
            throw new IllegalArgumentException("Unknown SX item material: " + text);
        return new Resolved(material, suffix);
    }

    private static final class LegacyIds {
        static final Map<Integer, Material> BY_ID = new HashMap<>();
        static final Map<String, Material> BY_NAME = new HashMap<>();

        static {
            // Paper rewrites Material.values() in modern plugins to omit legacy constants.
            // Enum metadata retains the input IDs needed for this configuration adapter.
            for (Material material : Material.class.getEnumConstants())
                if (material.isLegacy()) {
                    BY_ID.put(material.getId(), material);
                    BY_NAME.put(material.name(), material);
                }
        }
    }

    private static Material modern(String key) {
        Material material = Material.matchMaterial(key);
        return material != null && !material.isLegacy() && material.isItem() && !material.isAir()
                ? material
                : null;
    }
}

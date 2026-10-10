package dev.itemloom.paper.sx;

import java.util.regex.Pattern;
import org.bukkit.Material;

/** Legacy identifiers are configuration data; only modern Paper materials leave this boundary. */
final class SxMaterials {
    record Resolved(Material material, String damage) {}

    private static final Pattern SUFFIX =
            Pattern.compile("(?:<[+-]?\\d+|[+-]?\\d+|[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)%)");
    private static final Pattern UNSIGNED = Pattern.compile("\\d+");

    private SxMaterials() {}

    static Resolved resolve(String text) {
        Material exact = modern(text);
        if (exact != null) return new Resolved(exact, null);
        int colon = text.lastIndexOf(':');
        String base = text, suffix = null;
        if (colon > 0) {
            String candidate = text.substring(colon + 1);
            if (SUFFIX.matcher(candidate).matches()) {
                base = text.substring(0, colon);
                suffix = candidate;
            }
        }
        Material material = modern(base);
        if (material == null) {
            int data =
                    suffix != null && UNSIGNED.matcher(suffix).matches()
                            ? Integer.parseInt(suffix)
                            : 0;
            if (data <= 255) {
                material =
                        UNSIGNED.matcher(base).matches()
                                ? LegacyTableHolder.TABLE.byId(Integer.parseInt(base), data)
                                : LegacyTableHolder.TABLE.byName(base, data);
            }
        }
        if (material == null || material.isAir() || !material.isItem())
            throw new IllegalArgumentException("Unknown SX item material: " + text);
        return new Resolved(material, suffix);
    }

    private static final class LegacyTableHolder {
        static final LegacyMaterialTable TABLE = LegacyMaterialTable.load();
    }

    private static Material modern(String key) {
        // Material names cannot begin with an ASCII digit. Avoid Paper's normalization
        // and regex allocation for the common numeric configuration path.
        if (key.isEmpty() || (key.charAt(0) >= '0' && key.charAt(0) <= '9')) return null;
        Material material = Material.matchMaterial(key);
        return material != null && !material.isLegacy() && material.isItem() && !material.isAir()
                ? material
                : null;
    }
}

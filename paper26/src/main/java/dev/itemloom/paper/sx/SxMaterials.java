package dev.itemloom.paper.sx;

import org.bukkit.Material;

/** Material grammar keeps modern names ahead of legacy variant data. */
final class SxMaterials {
    record Resolved(Material material, String damage) {}

    private static final class Observations {
        private static final LegacyMaterialTable TABLE = LegacyMaterialTable.load();
    }

    static Resolved resolve(String text) {
        Material modern = modern(text);
        if (modern != null) {
            return new Resolved(modern, null);
        }
        String base = text;
        String suffix = null;
        int colon = text.lastIndexOf(':');
        if (colon >= 0) {
            String tail = text.substring(colon + 1);
            if (tail.matches("[+-]?[0-9]+")
                    || tail.matches("<[+-]?[0-9]+")
                    || tail.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)%")) {
                base = text.substring(0, colon);
                suffix = tail;
            }
        }
        modern = modern(base);
        if (modern != null) {
            return new Resolved(modern, suffix);
        }
        int data = suffix != null && suffix.matches("[0-9]+") ? Integer.parseInt(suffix) : 0;
        Material material =
                base.matches("[0-9]+")
                        ? Observations.TABLE.byId(Integer.parseInt(base), data)
                        : Observations.TABLE.byName(base, data);
        if (material == null) {
            throw new IllegalArgumentException("Unknown SX material: " + text);
        }
        return new Resolved(material, suffix);
    }

    private static Material modern(String text) {
        Material material = Material.matchMaterial(text);
        return material == null || material.isLegacy() || material.isAir() || !material.isItem()
                ? null
                : material;
    }
}

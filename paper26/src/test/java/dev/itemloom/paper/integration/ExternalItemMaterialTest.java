package dev.itemloom.paper.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ExternalItemMaterialTest {
    @Test
    void keepsTheWholeNamespacedItemId() {
        ExternalItemMaterial source =
                ExternalItemMaterial.parse(" itembridge:CraftEngine:pack:item:variant ");
        assertEquals("craftengine", source.provider());
        assertEquals("pack:item:variant", source.id());
        assertTrue(ExternalItemMaterial.isExternal("ITEMBRIDGE:craftengine:pack:item"));
        assertFalse(ExternalItemMaterial.isExternal("minecraft:stone"));
        assertFalse(ExternalItemMaterial.isExternal(null));
    }

    @Test
    void rejectsMalformedAndRecursiveSources() {
        for (String value :
                List.of(
                        "STONE",
                        "itembridge:",
                        "itembridge:craftengine",
                        "itembridge::id",
                        "itembridge:craftengine:"))
            assertThrows(IllegalArgumentException.class, () -> ExternalItemMaterial.parse(value));
        for (String source : List.of("ni", "NeigeItems", "sx", "SXItem", "SX-Item", "ItemLoom"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ExternalItemMaterial.parse("itembridge:" + source + ":example"));
    }
}

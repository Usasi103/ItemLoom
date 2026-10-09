package dev.itemloom.paper.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.junit.jupiter.api.Test;

class NiItemMigrationTest {
    @Test
    void oldJsonRollsAndUnrelatedDataSurviveWithoutRegeneration() {
        CompoundTag input = new CompoundTag(),
                legacy = new CompoundTag(),
                foreign = new CompoundTag();
        legacy.putString("id", "sword");
        legacy.putString(
                "data", "{\"quality\":\"legendary\",\"damage\":\"17.5\",\"missing\":null}");
        legacy.putInt("durability", 7);
        legacy.putInt("maxDurability", 100);
        legacy.putString("owner", "old-owner");
        legacy.putLong("itemTime", 123456789L);
        foreign.putString("enchant-record", "preserve");
        input.put("external-plugin", foreign);
        input.put("NeigeItems", legacy);
        CompoundTag before = input.copy();
        CompoundTag result = new NiItemMigration().convert(input);
        ItemIdentity identity = new ItemStateCodec().read(result).orElseThrow();
        assertEquals("sword", identity.id());
        assertEquals("17.5", identity.rolls().get("damage"));
        assertTrue(identity.rolls().containsKey("missing"));
        assertNull(identity.rolls().get("missing"));
        assertEquals(before, input);
        assertEquals(foreign, result.get("external-plugin"));
        assertFalse(result.contains("NeigeItems"));
        CompoundTag properties =
                result.getCompoundOrEmpty(ItemStateCodec.KEY).getCompoundOrEmpty("properties");
        assertEquals(7, properties.getInt("durability").orElseThrow());
        assertEquals("old-owner", properties.getString("owner").orElseThrow());
        assertEquals(123456789L, properties.getLong("itemTime").orElseThrow());
    }

    @Test
    void nestedLegacyRollKeysRemainAddressableAfterMigration() {
        CompoundTag input = new CompoundTag(),
                legacy = new CompoundTag(),
                data = new CompoundTag(),
                nested = new CompoundTag();
        nested.putString("damage", "8.5");
        data.put("stats", nested);
        legacy.putString("id", "weapon");
        legacy.put("data", data);
        input.put("NeigeItems", legacy);
        assertEquals(
                Map.of("stats.damage", "8.5"),
                new ItemStateCodec()
                        .read(new NiItemMigration().convert(input))
                        .orElseThrow()
                        .rolls());
    }

    @Test
    void malformedDataAndConflictingIdentityCannotDestroyOriginal() {
        CompoundTag input = new CompoundTag(), legacy = new CompoundTag();
        legacy.putString("id", "old");
        legacy.putString("data", "{");
        input.put("NeigeItems", legacy);
        CompoundTag before = input.copy();
        assertThrows(RuntimeException.class, () -> new NiItemMigration().convert(input));
        assertEquals(before, input);
        legacy.putString("data", "{}");
        input.put(
                ItemStateCodec.KEY,
                new ItemStateCodec()
                        .encode(new ItemIdentity("different", Map.of()), new CompoundTag()));
        assertThrows(IllegalArgumentException.class, () -> new NiItemMigration().convert(input));
        assertEquals("old", input.getCompoundOrEmpty("NeigeItems").getString("id").orElseThrow());
    }

    @Test
    void migrationIsIdempotentAndFutureSchemaIsNotOverwritten() {
        CompoundTag input = new CompoundTag(), legacy = new CompoundTag();
        legacy.putString("id", "old");
        input.put("NeigeItems", legacy);
        var migration = new NiItemMigration();
        CompoundTag once = migration.convert(input);
        assertEquals(once, migration.convert(once));
        once.getCompoundOrEmpty(ItemStateCodec.KEY).putInt("schema", 999);
        assertThrows(IllegalArgumentException.class, () -> migration.convert(once));
    }
}

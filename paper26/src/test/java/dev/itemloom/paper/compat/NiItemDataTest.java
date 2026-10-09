package dev.itemloom.paper.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.itemloom.compat.ni.NiConfig;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.junit.jupiter.api.Test;

class NiItemDataTest {
    private final NiItemData data =
            new NiItemData(Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC));

    @Test
    void generatedOptionsAndNbtOverridesBecomeIndependentState() {
        var config =
                NiYaml.readGenerated(
                        """
                options: {charge: '2', max-charge: '8', durability: 10, item-time: '60', hide: 'true'}
                nbt:
                  other: {value: '(Short) 17'}
                  NeigeItems: {charge: 1}
                """,
                        "item");
        CompoundTag source = new CompoundTag();
        source.putString("external", "keep");
        CompoundTag result =
                new NiItemMigration()
                        .convert(
                                data.apply(
                                        source,
                                        config,
                                        new ItemIdentity("sword", Map.of("roll", "7")),
                                        42));
        assertEquals("keep", result.getString("external").orElseThrow());
        assertFalse(source.contains("NeigeItems"));
        assertFalse(result.contains("NeigeItems"));
        assertEquals("7", new ItemStateCodec().read(result).orElseThrow().rolls().get("roll"));
        CompoundTag properties =
                result.getCompoundOrEmpty(ItemStateCodec.KEY).getCompoundOrEmpty("properties");
        assertEquals(1, properties.getInt("charge").orElseThrow());
        // The legacy map reader applies max-charge before charge for these keys.
        assertEquals(2, properties.getInt("maxCharge").orElseThrow());
        assertEquals(10, properties.getInt("maxDurability").orElseThrow());
        assertEquals(70_000L, properties.getLong("itemTime").orElseThrow());
        assertTrue(properties.getBoolean("hide").orElseThrow());
        assertEquals(
                (short) 17, result.getCompoundOrEmpty("other").getShort("value").orElseThrow());
    }

    @Test
    void removingGeneratedMetadataRetainsExplicitExternalNbt() {
        var config =
                NiYaml.readGenerated(
                        "options: {removeNBT: true, charge: 99}\nnbt: {foreign: keep}", "plain");
        CompoundTag result =
                data.apply(new CompoundTag(), config, new ItemIdentity("plain", Map.of()), 1);
        assertFalse(result.contains("NeigeItems"));
        assertEquals("keep", result.getString("foreign").orElseThrow());
    }

    @Test
    void directGenerationMatchesTranslationAcrossOptionsAndFallbacks() {
        Map<String, String> rolls = new LinkedHashMap<>();
        rolls.put("nullable", null);
        rolls.put("Unicode 雪", "\"\\\n\r<>&雪\u2028");
        rolls.put("empty", "");
        ItemIdentity identity = new ItemIdentity("测试:blade", rolls);
        var codec = new ItemStateCodec();
        var migration = new NiItemMigration();
        var configs =
                List.of(
                        "",
                        "options: {}",
                        "nbt: {}",
                        "options: {removeNBT: true}",
                        "options: {remove-nbt: 'true', charge: 9}",
                        "nbt: {NeigeItems: {data: '{broken'}}",
                        "nbt: {NeigeItems: {id: 'overlay', charge: 3}}",
                        "nbt: {foreign: '(Short) 17'}",
                        """
                options: {charge: '2', max-charge: '8', maxCharge: 9, durability: 10, max-durability: 11,
                  itemBreak: false, item-break: true, hide: 'true', owner: '雪', color: green,
                  dropSkill: one, drop-skill: two, itemTime: 20, item-time: '60', unknown: keep}
                """,
                        "options: {charge: invalid}",
                        "options: {color: unknown, item-time: 9223372036854775807}");
        List<CompoundTag> sources = new ArrayList<>();
        sources.add(new CompoundTag());
        CompoundTag external = new CompoundTag();
        external.putIntArray("external", new int[] {1, 2, 3});
        sources.add(external);
        CompoundTag legacy = new CompoundTag(), previous = new CompoundTag();
        previous.putString("id", "previous");
        previous.putString("data", "{}");
        previous.putInt("unknown", 17);
        legacy.put("NeigeItems", previous);
        sources.add(legacy);
        CompoundTag modern = new CompoundTag();
        modern.put(ItemStateCodec.KEY, codec.encode(identity, previous));
        sources.add(modern);
        CompoundTag corrupt = new CompoundTag();
        corrupt.putString(ItemStateCodec.KEY, "broken");
        sources.add(corrupt);
        for (String yaml : configs) {
            NiConfig config = NiYaml.readGenerated(yaml, "differential");
            for (CompoundTag source : sources) {
                CompoundTag before = source.copy();
                assertEquals(
                        outcome(() -> migration.convert(data.apply(source, config, identity, -42))),
                        outcome(() -> data.applyGenerated(source, config, identity, -42)),
                        yaml + " / " + source);
                assertEquals(before, source, "generation cannot mutate a borrowed source");
            }
        }
        CompoundTag generated =
                data.applyGenerated(
                        external,
                        NiYaml.readGenerated("options: {durability: 8}", "copy"),
                        identity,
                        42);
        generated.getIntArray("external").orElseThrow()[0] = 99;
        assertEquals(1, external.getIntArray("external").orElseThrow()[0]);
        assertEquals(identity, codec.read(generated).orElseThrow());
    }

    @Test
    void nullableIdentityKeepsTheExistingTranslationContract() {
        NiConfig config = NiYaml.readGenerated("options: {charge: 8}", "nullable");
        CompoundTag source = new CompoundTag();
        assertEquals(
                new NiItemMigration().convert(data.apply(source, config, null, 1)),
                data.applyGenerated(source, config, null, 1));
    }

    private static Object outcome(java.util.function.Supplier<CompoundTag> operation) {
        try {
            return operation.get();
        } catch (RuntimeException error) {
            return List.of(error.getClass(), String.valueOf(error.getMessage()));
        }
    }
}

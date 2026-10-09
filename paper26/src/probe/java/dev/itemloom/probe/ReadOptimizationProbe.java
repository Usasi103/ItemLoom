package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Differential scalar reads against the unchanged writable legacy view on actual Paper stacks. */
final class ReadOptimizationProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final NiItemMigration MIGRATION = new NiItemMigration();

    static Map<String, Object> run() {
        var checks = new ArrayList<String>();
        var failures = new LinkedHashMap<String, String>();
        Map<String, CompoundTag> samples = new LinkedHashMap<>();
        samples.put("absent", null);
        samples.put("empty", new CompoundTag());
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("unrelated", "keep");
        samples.put("unrelated", unrelated);
        CompoundTag wrong = new CompoundTag();
        wrong.putString("NeigeItems", "wrong type");
        samples.put("wrong-legacy", wrong);
        List<Tag> numbers =
                List.of(
                        ByteTag.valueOf((byte) -1),
                        ShortTag.valueOf((short) 32000),
                        IntTag.valueOf(Integer.MIN_VALUE),
                        LongTag.valueOf(Long.MAX_VALUE),
                        FloatTag.valueOf(-0.5f),
                        DoubleTag.valueOf(256.9),
                        DoubleTag.valueOf(Double.NaN),
                        StringTag.valueOf("17"),
                        new CompoundTag());
        for (int i = 0; i < numbers.size(); i++) {
            CompoundTag properties = new CompoundTag();
            for (String field :
                    List.of("charge", "maxCharge", "durability", "maxDurability", "itemBreak"))
                properties.put(field, numbers.get(i));
            CompoundTag legacy = new CompoundTag();
            legacy.put("NeigeItems", properties.copy());
            // Scalar legacy reads deliberately tolerate a missing id and malformed saved data.
            legacy.getCompoundOrEmpty("NeigeItems").putString("data", "{broken");
            samples.put("legacy-" + i, legacy);
            CompoundTag modern = new CompoundTag();
            modern.put(
                    ItemStateCodec.KEY,
                    CODEC.encode(new ItemIdentity("fixture", Map.of("roll", "saved")), properties));
            samples.put("modern-" + i, modern);
        }
        CompoundTag modern = samples.get("modern-0");
        CompoundTag both = modern.copy(), legacy = new CompoundTag();
        legacy.putString("id", "fixture");
        legacy.putString("data", "{\"roll\":\"saved\"}");
        both.put("NeigeItems", legacy);
        samples.put("matching-dual", both);
        CompoundTag conflict = both.copy();
        conflict.getCompoundOrEmpty("NeigeItems").putString("id", "other");
        samples.put("conflicting-dual", conflict);
        CompoundTag badLegacy = both.copy();
        badLegacy.getCompoundOrEmpty("NeigeItems").putString("data", "{broken");
        samples.put("malformed-dual", badLegacy);
        CompoundTag nonCompound = new CompoundTag();
        nonCompound.putString(ItemStateCodec.KEY, "broken");
        samples.put("wrong-envelope", nonCompound);
        for (String key : List.of("schema", "id", "rolls")) {
            CompoundTag bad = modern.copy();
            bad.getCompoundOrEmpty(ItemStateCodec.KEY).remove(key);
            samples.put("missing-" + key, bad);
        }
        CompoundTag bad = modern.copy();
        bad.getCompoundOrEmpty(ItemStateCodec.KEY).putInt("schema", 999);
        samples.put("unknown-schema", bad);
        bad = modern.copy();
        bad.getCompoundOrEmpty(ItemStateCodec.KEY).getCompoundOrEmpty("rolls").putInt("roll", 1);
        samples.put("nonstring-roll", bad);
        bad = modern.copy();
        bad.getCompoundOrEmpty(ItemStateCodec.KEY).putString("null_rolls", "wrong");
        samples.put("wrong-null-list", bad);
        bad = modern.copy();
        ListTag nulls = new ListTag();
        nulls.add(StringTag.valueOf("roll"));
        bad.getCompoundOrEmpty(ItemStateCodec.KEY).put("null_rolls", nulls);
        samples.put("conflicting-roll", bad);
        bad = modern.copy();
        bad.getCompoundOrEmpty(ItemStateCodec.KEY).putString("properties", "wrong");
        samples.put("wrong-properties", bad);

        for (boolean craft : List.of(false, true))
            for (var sample : samples.entrySet()) {
                String label = (craft ? "craft/" : "bukkit/") + sample.getKey();
                var handle = CraftItemStack.asNMSCopy(new ItemStack(Material.STONE, 2));
                if (sample.getValue() != null)
                    handle.set(DataComponents.CUSTOM_DATA, CustomData.of(sample.getValue().copy()));
                ItemStack item =
                        craft
                                ? CraftItemStack.asCraftMirror(handle)
                                : CraftItemStack.asBukkitCopy(handle);
                CompoundTag before = NmsItems.customData(item);
                for (String key : List.of("charge", "maxCharge", "durability", "maxDurability")) {
                    same(
                            checks,
                            failures,
                            label + "/" + key,
                            () -> {
                                var root = new LegacyNbtItemStack(item).getTag();
                                return root == null
                                        ? null
                                        : root.getDeepIntOrNull("NeigeItems." + key);
                            },
                            () -> NiItemNodes.legacyInteger(item, key));
                }
                for (boolean fallback : List.of(false, true))
                    same(
                            checks,
                            failures,
                            label + "/bool/" + fallback,
                            () -> {
                                var root = new LegacyNbtItemStack(item).getTag();
                                return root == null
                                        ? null
                                        : root.getDeepBoolean("NeigeItems.itemBreak", fallback);
                            },
                            () -> NiItemNodes.legacyBoolean(item, "itemBreak", fallback));
                same(
                        checks,
                        failures,
                        label + "/identity",
                        () -> CODEC.read(before),
                        () -> CODEC.read(item));
                same(
                        checks,
                        failures,
                        label + "/legacy-identity",
                        () -> MIGRATION.read(before),
                        () -> MIGRATION.identify(item));
                CompoundTag properties = CODEC.properties(item);
                properties.putString("poison", "never shared");
                verify(
                        checks,
                        failures,
                        label + "/source-isolation",
                        before.equals(NmsItems.customData(item)));
            }
        verify(
                checks,
                failures,
                "null identity read",
                CODEC.read((ItemStack) null).isEmpty() && MIGRATION.identify(null).isEmpty());
        same(
                checks,
                failures,
                "null scalar rejects with original exception",
                () -> new LegacyNbtItemStack(null).getTag(),
                () -> NiItemNodes.legacyInteger(null, "charge"));
        return Map.of(
                "passed",
                failures.isEmpty(),
                "checks",
                checks.size() + failures.size(),
                "verified",
                checks,
                "failures",
                failures,
                "boundary",
                "Actual Paper NMS/Bukkit/Craft reads; scalar error/value parity with writable legacy projection, no client or TPS measurement");
    }

    private static Object outcome(Supplier<?> operation) {
        try {
            return operation.get();
        } catch (RuntimeException error) {
            return List.of(error.getClass().getName(), String.valueOf(error.getMessage()));
        }
    }

    private static void same(
            List<String> checks,
            Map<String, String> failures,
            String name,
            Supplier<?> before,
            Supplier<?> after) {
        Object expected = outcome(before), actual = outcome(after);
        if (Objects.equals(expected, actual)) checks.add(name);
        else failures.put(name, "expected=" + expected + ", actual=" + actual);
    }

    private static void verify(
            List<String> checks, Map<String, String> failures, String name, boolean valid) {
        if (valid) checks.add(name);
        else failures.put(name, "failed");
    }
}

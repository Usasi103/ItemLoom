package dev.itemloom.probe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.nbt.LegacyNbtUtils;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Independent assertions against actual Paper/NMS stacks; never reads player inventories. */
final class NbtProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String OLD_KEY = "NeigeItems";

    static Map<String, Object> run(JavaPlugin probe) {
        Checks checks = new Checks();
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("NBT probe must run on the server thread");
        checks.group("empty-custom-data", () -> emptyData(checks));
        checks.group("empty-serialization", () -> emptySerialization(checks));
        for (boolean craft : List.of(false, true)) {
            String kind = craft ? "craft" : "bukkit";
            checks.group(kind + "/projection", () -> projection(checks, fixture(craft)));
            checks.group(kind + "/rollback", () -> rollback(checks, fixture(craft)));
            checks.group(kind + "/multiple-views", () -> multipleViews(checks, fixture(craft)));
            checks.group(kind + "/children", () -> children(checks, fixture(craft)));
            checks.group(kind + "/arrays", () -> arrays(checks, fixture(craft)));
            checks.group(kind + "/copies", () -> copies(checks, fixture(craft)));
            checks.group(kind + "/compound-rolls", () -> compoundRolls(checks, craft));
            checks.group(kind + "/serialization", () -> serialization(checks, fixture(craft)));
        }
        return Map.of(
                "passed",
                checks.failures.isEmpty(),
                "checks",
                checks.attempted,
                "passedChecks",
                checks.verified.size(),
                "verified",
                List.copyOf(checks.verified),
                "failures",
                Map.copyOf(checks.failures),
                "referenceRequired",
                false,
                "server",
                probe.getServer().getMinecraftVersion(),
                "fixtures",
                "new Bukkit/Craft stacks and in-memory streams only");
    }

    private static void emptyData(Checks check) {
        ItemStack item = new ItemStack(Material.STONE, 2);
        LegacyNbtItemStack view = new LegacyNbtItemStack(item);
        check.that(view.getTag() == null, "getTag preserves absence of custom_data");
        LegacyNbt.Compound tag = view.getOrCreateTag();
        check.that(
                tag != null
                        && CraftItemStack.asNMSCopy(item).get(DataComponents.CUSTOM_DATA) != null,
                "getOrCreateTag attaches custom_data to the original Bukkit stack");
        tag.putString("probe", "attached");
        check.that(
                "attached".equals(NmsItems.customData(item).getStringOr("probe", ""))
                        && item.getAmount() == 2,
                "created tag writes through without changing stack amount");
        CraftItemStack.unwrap(item).remove(DataComponents.CUSTOM_DATA);
        tag.saveTo(item);
        check.that(
                CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA) == null,
                "saving a stale live view does not resurrect custom_data removed by another writer");
        ItemStack empty = new ItemStack(Material.AIR);
        check.reject(
                RuntimeException.class,
                () -> new LegacyNbtItemStack(empty).getOrCreateTag(),
                "empty items reject custom_data writes");
    }

    private static void emptySerialization(Checks check) {
        ItemStack empty = new ItemStack(Material.AIR);
        check.that(
                LegacyNbtUtils.of(null).isEmpty(), "null serialized input restores an empty stack");
        // NI uses the non-optional ItemStack CODEC on modern servers as well. Its
        // rejection of empty stacks is distinct from of(null)'s documented sentinel.
        check.reject(
                RuntimeException.class,
                () -> LegacyNbtUtils.save(empty),
                "non-optional item serialization rejects AIR");
        check.reject(
                RuntimeException.class,
                () -> LegacyNbtUtils.save(new ItemStack(Material.STONE, 0)),
                "non-optional item serialization rejects zero-count stacks");
        check.reject(
                RuntimeException.class,
                () -> LegacyNbtUtils.of(new LegacyNbt.Compound()),
                "an empty compound is not a valid non-optional item envelope");
        check.that(
                new NiItemMigration().convert(empty).isEmpty()
                        && CraftItemStack.unwrap(empty).get(DataComponents.CUSTOM_DATA) == null,
                "empty item migration does not attach custom_data");
    }

    private static void projection(Checks check, Fixture fixture) {
        ItemStack item = fixture.item;
        LegacyNbtItemStack wrapper = new LegacyNbtItemStack(item);
        Tag before = full(item);
        LegacyNbt.Compound root = wrapper.getTag();
        check.that(
                wrapper.asItemStack() == item && wrapper.isCraftItemStack() == fixture.craft,
                "wrapper retains the exact supplied stack object");
        check.that(
                root.containsKey(OLD_KEY) && !root.containsKey(ItemStateCodec.KEY),
                "legacy view exposes NeigeItems without exposing the modern envelope");
        check.that(
                full(item).equals(before),
                "reading a legacy projection does not modify actual custom_data");
        LegacyNbt.Compound old = root.getCompound(OLD_KEY);
        old.putString("id", "changed-id");
        old.putString("data", "{\"power\":\"9\",\"new\":\"added\",\"nullable\":null}");
        old.putInt("charge", 12);
        old.getOrCreateCompound("options").putBoolean("enabled", true);
        ItemIdentity identity = CODEC.read(item).orElseThrow();
        check.that(
                identity.id().equals("changed-id")
                        && "9".equals(identity.rolls().get("power"))
                        && "added".equals(identity.rolls().get("new"))
                        && identity.rolls().containsKey("nullable")
                        && identity.rolls().get("nullable") == null,
                "id and JSON data mutations update independent identity, saved values, and explicit nulls");
        CompoundTag custom = NmsItems.customData(item),
                state = custom.getCompoundOrEmpty(ItemStateCodec.KEY);
        check.that(
                !custom.contains(OLD_KEY)
                        && state.getCompoundOrEmpty("properties").getIntOr("charge", -1) == 12
                        && state.getCompoundOrEmpty("properties")
                                .getCompoundOrEmpty("options")
                                .getBooleanOr("enabled", false),
                "properties write only through itemloom:items on the real stack");
        check.that(
                !state.getCompoundOrEmpty("properties").contains("id")
                        && !state.getCompoundOrEmpty("properties").contains("data"),
                "legacy identity fields do not leak into properties");
        preserved(check, fixture);
        if (fixture.craft)
            check.that(
                    fixture.handle.get(DataComponents.CUSTOM_DATA).copyTag().equals(custom),
                    "Craft mirror mutates its original NMS handle");
        old.putString("data", "{\"only\":\"replacement\"}");
        check.that(
                CODEC.read(item).orElseThrow().rolls().equals(Map.of("only", "replacement"))
                        && !NmsItems.customData(item)
                                .getCompoundOrEmpty(ItemStateCodec.KEY)
                                .contains("null_rolls"),
                "replacing JSON data removes obsolete saved values and null metadata");
    }

    private static void rollback(Checks check, Fixture fixture) {
        ItemStack item = fixture.item;
        LegacyNbtItemStack wrapper = new LegacyNbtItemStack(item);
        LegacyNbt.Compound root = wrapper.getTag(), old = root.getCompound(OLD_KEY);
        rejectUnchanged(
                check,
                item,
                root,
                () -> old.putString("data", "{"),
                RuntimeException.class,
                "malformed JSON mutation rolls back both item and view");
        rejectUnchanged(
                check,
                item,
                root,
                () -> old.putString("data", "[]"),
                RuntimeException.class,
                "non-object JSON data is rejected atomically");
        rejectUnchanged(
                check,
                item,
                root,
                () -> old.putString("data", "{\"bad\":{\"nested\":1}}"),
                RuntimeException.class,
                "nested JSON saved values are rejected atomically");
        rejectUnchanged(
                check,
                item,
                root,
                () -> old.putString("id", "  "),
                RuntimeException.class,
                "blank identity is rejected atomically");
        rejectUnchanged(
                check,
                item,
                root,
                () -> old.putInt("id", 42),
                RuntimeException.class,
                "non-string identity is rejected atomically");
        rejectUnchanged(
                check,
                item,
                root,
                () -> old.remove("id"),
                RuntimeException.class,
                "removing id from an otherwise retained legacy record is rejected");
        LegacyNbt.Compound invalid = root.clone();
        invalid.getCompound(OLD_KEY).putString("data", "{");
        invalid.putString("must-not-appear", "candidate-only");
        Tag before = full(item);
        check.reject(
                RuntimeException.class,
                () -> wrapper.setTag(invalid),
                "invalid setTag candidate is rejected");
        check.that(
                full(item).equals(before),
                "failed setTag changes neither foreign keys nor item components");
        old.putInt("charge", 77);
        check.that(
                CODEC.properties(item).getIntOr("charge", -1) == 77,
                "the same view remains writable after rejected mutations");
        preserved(check, fixture);
    }

    private static void multipleViews(Checks check, Fixture fixture) {
        ItemStack item = fixture.item;
        LegacyNbt.Compound first = new LegacyNbtItemStack(item).getTag(),
                second = new LegacyNbtItemStack(item).getTag();
        first.getCompound(OLD_KEY).putString("id", "first-id");
        second.getCompound(OLD_KEY).putInt("charge", 21);
        check.that(
                CODEC.read(item).orElseThrow().id().equals("first-id")
                        && CODEC.properties(item).getIntOr("charge", -1) == 21,
                "stale views merge independent fields inside the same legacy compound");
        first.putString("first-field", "one");
        second.putString("second-field", "two");
        check.that(
                "one".equals(NmsItems.customData(item).getStringOr("first-field", ""))
                        && "two".equals(NmsItems.customData(item).getStringOr("second-field", "")),
                "stale root views merge independent foreign keys");
        rejectUnchanged(
                check,
                item,
                second,
                () -> second.getCompound(OLD_KEY).putString("id", "conflicting-id"),
                ConcurrentModificationException.class,
                "conflicting writes to the same identity field fail without data loss");
        second.getCompound(OLD_KEY).putString("id", "first-id");
        check.that(
                CODEC.read(item).orElseThrow().id().equals("first-id"),
                "a stale handle may converge to the already-current value");
        first.getCompound(OLD_KEY).putString("data", "{\"power\":\"merged\"}");
        check.that(
                CODEC.properties(item).getIntOr("charge", -1) == 21
                        && "merged".equals(CODEC.read(item).orElseThrow().rolls().get("power")),
                "later mutations through an older view preserve the other view's properties");
        Tag beforeSave = full(item);
        first.saveTo(item);
        second.saveTo(item);
        first.saveTo(CraftItemStack.asCraftMirror(CraftItemStack.unwrap(item)));
        check.that(
                full(item).equals(beforeSave),
                "saving stale live roots to the same stack or handle preserves every merged edit");
        ItemStack replaced = item.clone();
        first.clone().saveTo(replaced);
        check.that(
                !NmsItems.customData(replaced).contains("second-field")
                        && CODEC.properties(replaced).getIntOr("charge", -1) == 10,
                "saving a detached snapshot still explicitly replaces custom_data");
        ItemStack other = item.clone();
        first.saveTo(other);
        check.that(
                full(other).equals(full(replaced)),
                "saving a live root to another stack retains explicit replacement semantics");
        preserved(check, fixture);
    }

    private static void children(Checks check, Fixture fixture) {
        ItemStack item = fixture.item;
        LegacyNbt.Compound root = new LegacyNbtItemStack(item).getTag();
        LegacyNbt.Compound child = new LegacyNbt.Compound();
        root.put("attached", child);
        child.putInt("value", 3);
        check.that(
                NmsItems.customData(item).getCompoundOrEmpty("attached").getIntOr("value", -1) == 3,
                "a newly inserted child remains writable through its original variable");
        LegacyNbt.ListValue list = new LegacyNbt.ListValue();
        child.put("rows", list);
        LegacyNbt.Compound first = new LegacyNbt.Compound(), second = new LegacyNbt.Compound();
        first.putString("name", "first");
        second.putString("name", "second");
        list.addAll(List.of(first, second));
        second.putInt("value", 7);
        check.that(
                rawRows(item).getCompoundOrEmpty(1).getIntOr("value", -1) == 7,
                "new list and inserted compound variables remain linked to the stack");
        LegacyNbt.Compound held = list.getCompound(1);
        list.remove(0);
        held.putInt("value", 8);
        check.that(
                rawRows(item).size() == 1
                        && rawRows(item).getCompoundOrEmpty(0).getIntOr("value", -1) == 8,
                "a retained list child follows its object after preceding entries are removed");
        Tag afterRemoval = full(item);
        first.putString("removed", "detached");
        check.that(
                full(item).equals(afterRemoval),
                "a removed child's original variable no longer modifies the stack");
        LegacyNbt.ListValue detached = list.clone();
        detached.getCompound(0).putInt("value", 99);
        check.that(
                rawRows(item).getCompoundOrEmpty(0).getIntOr("value", -1) == 8,
                "list cloning detaches nested children");
        root.put("copied-child", child);
        child.putInt("value", 4);
        check.that(
                NmsItems.customData(item).getCompoundOrEmpty("attached").getIntOr("value", -1) == 4
                        && NmsItems.customData(item)
                                        .getCompoundOrEmpty("copied-child")
                                        .getIntOr("value", -1)
                                == 3,
                "reusing an already-linked subtree inserts an independent copy");
        root.remove("attached");
        Tag detachedSnapshot = full(item);
        held.putInt("value", 100);
        list.addEmptyCompound().putString("after", "removal");
        child.putBoolean("removed", true);
        check.that(
                full(item).equals(detachedSnapshot),
                "removing an ancestor detaches its child and list handles");
        root.putDeepInt("escaped.key\\.with\\.dots.value", 5);
        check.that(
                root.getDeepInt("escaped.key\\.with\\.dots.value") == 5
                        && NmsItems.customData(item)
                                        .getCompoundOrEmpty("escaped")
                                        .getCompoundOrEmpty("key.with.dots")
                                        .getIntOr("value", -1)
                                == 5,
                "escaped deep compound paths preserve literal dots during live writes");
        preserved(check, fixture);
    }

    private static net.minecraft.nbt.ListTag rawRows(ItemStack item) {
        return NmsItems.customData(item).getCompoundOrEmpty("attached").getListOrEmpty("rows");
    }

    private static void arrays(Checks check, Fixture fixture) {
        ItemStack item = fixture.item;
        LegacyNbt.Compound root = new LegacyNbtItemStack(item).getTag();
        byte[] bytes = {Byte.MIN_VALUE, 0, Byte.MAX_VALUE};
        int[] ints = {Integer.MIN_VALUE, 0, Integer.MAX_VALUE};
        long[] longs = {Long.MIN_VALUE, 0, Long.MAX_VALUE};
        root.putByteArray("bytes", bytes);
        root.putIntArray("ints", ints);
        root.putLongArray("longs", longs);
        bytes[0] = 9;
        ints[0] = 9;
        longs[0] = 9;
        check.that(
                root.getTagType("bytes") == Tag.TAG_BYTE_ARRAY
                        && root.getTagType("ints") == Tag.TAG_INT_ARRAY
                        && root.getTagType("longs") == Tag.TAG_LONG_ARRAY,
                "byte/int/long arrays retain their distinct NBT types");
        check.that(
                root.getByteArray("bytes")[0] == Byte.MIN_VALUE
                        && root.getIntArray("ints")[0] == Integer.MIN_VALUE
                        && root.getLongArray("longs")[0] == Long.MIN_VALUE,
                "array constructors detach caller-owned primitive buffers");
        byte[] readBytes = root.getByteArray("bytes");
        int[] readInts = root.getIntArray("ints");
        long[] readLongs = root.getLongArray("longs");
        readBytes[1] = 9;
        readInts[1] = 9;
        readLongs[1] = 9;
        check.that(
                root.getByteArray("bytes")[1] == 0
                        && root.getIntArray("ints")[1] == 0
                        && root.getLongArray("longs")[1] == 0,
                "array getters return isolated primitive buffers");
        Tag before = full(item);
        LegacyNbt.ByteArray byteView = (LegacyNbt.ByteArray) root.get("bytes");
        LegacyNbt.IntArray intView = (LegacyNbt.IntArray) root.get("ints");
        LegacyNbt.LongArray longView = (LegacyNbt.LongArray) root.get("longs");
        check.reject(
                UnsupportedOperationException.class,
                () -> byteView.set(0, (byte) 1),
                "byte array element mutation is explicitly unsupported");
        check.reject(
                UnsupportedOperationException.class,
                () -> intView.add(4),
                "int array insertion is explicitly unsupported");
        check.reject(
                UnsupportedOperationException.class,
                () -> longView.remove(0),
                "long array removal is explicitly unsupported");
        check.that(
                full(item).equals(before),
                "rejected primitive-array operations leave the stack unchanged");
        root.putLongArray("longs", new long[] {4, 5});
        check.that(
                Arrays.equals(
                        NmsItems.customData(item).getLongArray("longs").orElseThrow(),
                        new long[] {4, 5}),
                "replacing a typed array writes the complete replacement");
        preserved(check, fixture);
    }

    private static void copies(Checks check, Fixture fixture) {
        ItemStack item = fixture.item;
        LegacyNbtItemStack wrapper = new LegacyNbtItemStack(item);
        LegacyNbt.Compound root = wrapper.getTag();
        Tag before = full(item);
        root.clone().getCompound(OLD_KEY).putString("id", "detached-id");
        root.copyTag().putString("copy-only", "detached");
        ((CompoundTag) root.getHandle()).putString("handle-only", "detached");
        ((CompoundTag) LegacyNbt.Unsafe.getDelegate(root)).putString("unsafe-only", "detached");
        check.that(
                full(item).equals(before),
                "clone, copyTag, getHandle and Unsafe delegate snapshots cannot bypass validated commits");
        LegacyNbtItemStack cloned = wrapper.clone();
        cloned.getTag().getCompound(OLD_KEY).putString("id", "cloned-stack");
        check.that(
                full(item).equals(before)
                        && CODEC.read(cloned.asItemStack())
                                .orElseThrow()
                                .id()
                                .equals("cloned-stack"),
                "item wrapper cloning isolates both the stack and its modern identity");
        for (ItemStack copy :
                List.of(wrapper.asCopy(), wrapper.asBukkitCopy(), wrapper.asCraftCopy()))
            new LegacyNbtItemStack(copy).getTag().putString("copy-write", "isolated");
        check.that(
                full(item).equals(before),
                "all item-copy adapters isolate later custom-data mutations");
        CompoundTag source = new CompoundTag();
        source.putInt("value", 1);
        LegacyNbt.Compound wrapped = (LegacyNbt.Compound) LegacyNbt.wrap(source);
        source.putInt("value", 2);
        wrapped.putInt("second", 3);
        check.that(
                wrapped.getInt("value") == 1 && !source.contains("second"),
                "wrapping an NMS compound does not retain a raw mutable alias");
    }

    private static void compoundRolls(Checks check, boolean craft) {
        CompoundTag data = new CompoundTag(), nested = new CompoundTag();
        nested.putShort("rank", (short) 4);
        nested.putLong("seed", 1234567890123L);
        data.put("nested", nested);
        data.putString("literal.dot", "literal-value");
        data.putByteArray("flags", new byte[] {1, -1});
        data.putIntArray("samples", new int[] {4, 8});
        CompoundTag old = new CompoundTag(), custom = new CompoundTag();
        old.putString("id", "old-compound");
        old.put("data", data.copy());
        old.putInt("charge", 6);
        custom.put(OLD_KEY, old);
        custom.putString("foreign:old", "retained");
        ItemStack item = stack(craft, custom).item;
        Tag components = withoutCustomData(item), before = full(item);
        LegacyNbt.Compound root = new LegacyNbtItemStack(item).getTag();
        check.that(
                full(item).equals(before),
                "reading a legacy compound item does not migrate it implicitly");
        root.getCompound(OLD_KEY)
                .getCompound("data")
                .getCompound("nested")
                .putShort("rank", (short) 5);
        data.getCompoundOrEmpty("nested").putShort("rank", (short) 5);
        CompoundTag state = NmsItems.customData(item).getCompoundOrEmpty(ItemStateCodec.KEY);
        check.that(
                !NmsItems.customData(item).contains(OLD_KEY)
                        && data.equals(state.get("compat_ni_rolls")),
                "compound data migration keeps exact keys and primitive types in compat_ni_rolls");
        check.that(
                "5".equals(CODEC.read(item).orElseThrow().rolls().get("nested.rank"))
                        && "literal-value"
                                .equals(CODEC.read(item).orElseThrow().rolls().get("literal.dot")),
                "compound writes also update the independent flattened saved values");
        root.getCompound(OLD_KEY).putString("id", "old-renamed");
        root.getCompound(OLD_KEY).putInt("charge", 10);
        check.that(
                data.equals(
                                NmsItems.customData(item)
                                        .getCompoundOrEmpty(ItemStateCodec.KEY)
                                        .get("compat_ni_rolls"))
                        && data.equals(
                                new LegacyNbtItemStack(item)
                                        .getTag()
                                        .getCompound(OLD_KEY)
                                        .getCompound("data")
                                        .copyTag()),
                "subsequent identity and property writes retain the exact compound projection");
        root.getCompound(OLD_KEY).putString("data", "{\"json\":\"now\"}");
        check.that(
                !NmsItems.customData(item)
                                .getCompoundOrEmpty(ItemStateCodec.KEY)
                                .contains("compat_ni_rolls")
                        && CODEC.read(item).orElseThrow().rolls().equals(Map.of("json", "now")),
                "switching compound data to JSON removes stale compat_ni_rolls");
        check.that(
                components.equals(withoutCustomData(item))
                        && "retained"
                                .equals(NmsItems.customData(item).getStringOr("foreign:old", "")),
                "legacy migration retains foreign custom-data keys and every other component");
    }

    private static void serialization(Checks check, Fixture fixture) throws Exception {
        LegacyNbt.Compound data =
                LegacyNbtUtils.parse(
                        "{text:'中文 NBT',byte:-4b,short:32000s,int:42,long:1234567890123L,float:1.5f,double:2.25d,bytes:[B;1b,-2b],ints:[I;3,4],longs:[L;5L,6L],nested:{key:'value'},list:[{n:1},{n:2}]}");
        check.that(
                LegacyNbtUtils.parse(data.toString()).copyTag().equals(data.copyTag()),
                "SNBT parse/print preserves compound structure and numeric/array types");
        check.reject(
                IllegalArgumentException.class,
                () -> LegacyNbtUtils.parse("{incomplete:"),
                "invalid SNBT is rejected");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(raw)) {
            LegacyNbtUtils.write(data, output);
        }
        try (DataInputStream input =
                new DataInputStream(new ByteArrayInputStream(raw.toByteArray()))) {
            check.that(
                    LegacyNbtUtils.read(input).copyTag().equals(data.copyTag()),
                    "uncompressed binary NBT roundtrip preserves all values");
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LegacyNbtUtils.writeCompressed(data, compressed);
        check.that(
                LegacyNbtUtils.readCompressed(new ByteArrayInputStream(compressed.toByteArray()))
                        .copyTag()
                        .equals(data.copyTag()),
                "compressed binary NBT roundtrip preserves all values");
        LegacyNbt.Compound saved = LegacyNbtUtils.save(fixture.item);
        check.that(
                saved.copyTag().equals(full(fixture.item)),
                "full item save serializes the actual modern envelope and components");
        ItemStack restored = LegacyNbtUtils.of(saved);
        check.that(
                full(restored).equals(full(fixture.item)),
                "full modern item save/of roundtrip is lossless");
        ItemStack vanilla =
                fixture.craft
                        ? CraftItemStack.asCraftCopy(new ItemStack(Material.STONE, 2))
                        : new ItemStack(Material.STONE, 2);
        ItemStack vanillaRestored = LegacyNbtUtils.of(LegacyNbtUtils.save(vanilla));
        check.that(
                full(vanillaRestored).equals(full(vanilla))
                        && CraftItemStack.unwrap(vanillaRestored).get(DataComponents.CUSTOM_DATA)
                                == null,
                "vanilla item save/of preserves the complete envelope without adding empty custom_data");
        new LegacyNbtItemStack(vanilla).getOrCreateTag();
        ItemStack emptyDataRestored = LegacyNbtUtils.of(LegacyNbtUtils.save(vanilla));
        check.that(
                full(emptyDataRestored).equals(full(vanilla))
                        && CraftItemStack.unwrap(emptyDataRestored).get(DataComponents.CUSTOM_DATA)
                                != null,
                "an explicitly present empty custom_data component also survives save/of unchanged");
        CompoundTag old = new CompoundTag(), oldCustom = new CompoundTag();
        old.putString("id", "serialized-old");
        old.putString("data", "{\"saved\":\"value\"}");
        oldCustom.put(OLD_KEY, old);
        oldCustom.putString("foreign:serialized", "keep");
        ItemStack legacy = stack(fixture.craft, oldCustom).item;
        ItemStack migrated = LegacyNbtUtils.of(LegacyNbtUtils.save(legacy));
        check.that(
                CODEC.read(migrated)
                                .orElseThrow()
                                .equals(new NiItemMigration().identify(legacy).orElseThrow())
                        && !NmsItems.customData(migrated).contains(OLD_KEY)
                        && withoutCustomData(migrated).equals(withoutCustomData(legacy)),
                "deserializing a legacy item migrates identity while preserving other components");
        check.that(
                "keep".equals(NmsItems.customData(migrated).getStringOr("foreign:serialized", "")),
                "legacy item deserialization retains foreign custom-data keys");
        check.that(
                full(LegacyNbtUtils.of(LegacyNbtUtils.save(migrated))).equals(full(migrated)),
                "a migrated legacy item has a lossless subsequent save/of roundtrip");
        LegacyNbt.Compound liveLegacy = new LegacyNbtItemStack(legacy).getTag();
        liveLegacy.saveTo(legacy);
        check.that(
                full(legacy).equals(full(migrated)),
                "saving an untouched live legacy view still performs validated migration");
    }

    private static Fixture fixture(boolean craft) {
        CompoundTag properties = new CompoundTag(),
                future = new CompoundTag(),
                foreign = new CompoundTag();
        properties.putInt("charge", 10);
        future.putInt("next-schema-field", 88);
        future.putByteArray("opaque", new byte[] {3, 1, 4});
        foreign.putString("token", "unchanged");
        foreign.putLongArray("opaque", new long[] {9, 7, 5});
        Map<String, String> rolls = new LinkedHashMap<>();
        rolls.put("power", "7");
        rolls.put("nullable", null);
        CompoundTag state = CODEC.encode(new ItemIdentity("original-id", rolls), properties);
        state.put("future:extension", future.copy());
        CompoundTag custom = new CompoundTag();
        custom.put(ItemStateCodec.KEY, state);
        custom.put("foreign:keep", foreign.copy());
        Stack stack = stack(craft, custom);
        return new Fixture(
                craft, stack.item, stack.handle, withoutCustomData(stack.item), foreign, future);
    }

    private static Stack stack(boolean craft, CompoundTag custom) {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        if (craft) item = CraftItemStack.asCraftCopy(item);
        var handle = CraftItemStack.unwrap(item);
        handle.set(DataComponents.CUSTOM_DATA, CustomData.of(custom.copy()));
        handle.set(
                DataComponents.CUSTOM_NAME,
                Component.literal("NBT 探针").withStyle(ChatFormatting.AQUA));
        handle.set(DataComponents.DAMAGE, 7);
        handle.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return new Stack(item, handle);
    }

    private static void preserved(Checks check, Fixture fixture) {
        CompoundTag custom = NmsItems.customData(fixture.item);
        check.that(
                fixture.future.equals(
                        custom.getCompoundOrEmpty(ItemStateCodec.KEY).get("future:extension")),
                "unknown future envelope fields survive compatibility writes");
        check.that(
                fixture.foreign.equals(custom.get("foreign:keep")),
                "unrelated custom-data compound remains byte-type equivalent");
        check.that(
                fixture.components.equals(withoutCustomData(fixture.item)),
                "material, amount, name style, damage and all non-custom-data components remain unchanged");
    }

    private static Tag full(ItemStack item) {
        return encode(CraftItemStack.asNMSCopy(item));
    }

    private static Tag withoutCustomData(ItemStack item) {
        var copy = CraftItemStack.asNMSCopy(item);
        copy.remove(DataComponents.CUSTOM_DATA);
        return encode(copy);
    }

    private static Tag encode(net.minecraft.world.item.ItemStack item) {
        return net.minecraft.world.item.ItemStack.CODEC
                .encodeStart(
                        CraftRegistry.getMinecraftRegistry()
                                .createSerializationContext(NbtOps.INSTANCE),
                        item)
                .getOrThrow();
    }

    private static void rejectUnchanged(
            Checks check,
            ItemStack item,
            LegacyNbt.Compound view,
            ThrowingRunnable operation,
            Class<? extends Throwable> type,
            String name) {
        Tag original = full(item);
        CompoundTag snapshot = view.copyTag();
        check.reject(type, operation, name);
        check.that(
                full(item).equals(original) && view.copyTag().equals(snapshot),
                name + " / original stack and handle unchanged");
    }

    private record Stack(ItemStack item, net.minecraft.world.item.ItemStack handle) {}

    private record Fixture(
            boolean craft,
            ItemStack item,
            net.minecraft.world.item.ItemStack handle,
            Tag components,
            CompoundTag foreign,
            CompoundTag future) {}

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class Checks {
        private final List<String> verified = new ArrayList<>();
        private final Map<String, String> failures = new LinkedHashMap<>();
        private int attempted;
        private String group;

        void group(String name, ThrowingRunnable operation) {
            group = name;
            try {
                operation.run();
            } catch (Throwable error) {
                failures.put(name, error.toString());
            }
        }

        void that(boolean value, String name) {
            attempted++;
            if (!value) throw new AssertionError(name);
            verified.add(group + " / " + name);
        }

        void reject(Class<? extends Throwable> type, ThrowingRunnable operation, String name) {
            attempted++;
            try {
                operation.run();
            } catch (Throwable error) {
                if (!type.isInstance(error))
                    throw new AssertionError(
                            name + ": expected " + type.getSimpleName() + ", received " + error,
                            error);
                verified.add(group + " / " + name);
                return;
            }
            throw new AssertionError(name + ": operation succeeded");
        }
    }
}

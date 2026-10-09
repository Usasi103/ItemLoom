package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.compat.script.LegacyItemUtils;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** True NMS/plain-Bukkit stacks and the actual take-ni-item action; no source configuration changes. */
final class ItemIdentityProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final LegacyItemUtils UTILS = new LegacyItemUtils(item -> item.getType().name());

    static Map<String, Object> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Identity probe requires the server thread");
        Checks checks = new Checks();
        try (Fixture fixture = new Fixture(plugin)) {
            for (boolean plain : new boolean[] {false, true}) {
                String format = plain ? "Bukkit" : "Craft";
                checks.group(format + "-empty", () -> empty(checks, fixture, plain));
                checks.group(format + "-scalar", () -> scalar(checks, fixture, plain));
                checks.group(format + "-headers", () -> headers(checks, fixture, plain));
                checks.group(format + "-complete-identity", () -> completeIdentity(checks, plain));
                checks.group(format + "-take", () -> take(checks, fixture, plain));
            }
            checks.group("script-aliases", () -> scripts(checks, fixture));
            checks.group(
                    "closed-manager",
                    () -> {
                        fixture.catalog.close();
                        checks.reject(
                                () -> fixture.manager.getItemId(stack(modern("target"), false)),
                                "revision-owned manager still rejects calls after close");
                    });
        } catch (Throwable error) {
            checks.failures.put("fixture", error.toString());
        }
        return Map.of(
                "passed",
                checks.failures.isEmpty(),
                "assertions",
                checks.count,
                "checks",
                checks.passed,
                "failures",
                checks.failures,
                "realClient",
                false,
                "referenceRequired",
                false);
    }

    private static void empty(Checks c, Fixture f, boolean plain) {
        query(c, f, null, null, "null item");
        query(c, f, convert(new ItemStack(Material.AIR), plain), null, "air");
        ItemStack ordinary = convert(new ItemStack(Material.STONE), plain);
        query(c, f, ordinary, null, "ordinary item");
        c.that(
                CraftItemStack.unwrap(ordinary).get(DataComponents.CUSTOM_DATA) == null,
                "query never adds an absent custom_data component");
        ItemStack empty = stack(modern("target"), plain);
        empty.setAmount(0);
        query(c, f, empty, null, "zero-count stack");
        CompoundTag wrongLegacy = new CompoundTag();
        wrongLegacy.putString("NeigeItems", "not-a-compound");
        query(c, f, stack(wrongLegacy, plain), null, "legacy non-compound");
        CompoundTag missing = legacy(null, "broken-json");
        query(c, f, stack(missing, plain), null, "legacy missing id");
        missing.getCompoundOrEmpty("NeigeItems").putInt("id", 8);
        query(c, f, stack(missing, plain), null, "legacy non-string id");
    }

    private static void scalar(Checks c, Fixture f, boolean plain) {
        query(c, f, stack(modern("target"), plain), "target", "healthy modern record");
        query(
                c,
                f,
                stack(legacy("legacy", "{\"roll\":\"saved\"}"), plain),
                "legacy",
                "healthy legacy record");
        query(
                c,
                f,
                stack(legacy("", "bad-json"), plain),
                "",
                "legacy query retains its historical empty-string id");
        for (String bad : List.of("not-json", "[]", "{\"roll\":{\"nested\":true}}"))
            query(
                    c,
                    f,
                    stack(legacy("target", bad), plain),
                    "target",
                    "legacy malformed data " + bad);
        CompoundTag numeric = legacy("target", "{}");
        numeric.getCompoundOrEmpty("NeigeItems").putInt("data", 5);
        query(c, f, stack(numeric, plain), "target", "legacy numeric data");
        for (var fixture : brokenRolls().entrySet()) {
            CompoundTag root = modern("target");
            fixture.getValue().accept(root.getCompoundOrEmpty(ItemStateCodec.KEY));
            query(c, f, stack(root, plain), "target", fixture.getKey());
        }
        CompoundTag dual = modern("modern-wins");
        dual.put("NeigeItems", legacy("legacy-loses", "bad-json").getCompoundOrEmpty("NeigeItems"));
        dual.getCompoundOrEmpty(ItemStateCodec.KEY).putString("rolls", "malformed");
        query(
                c,
                f,
                stack(dual, plain),
                "modern-wins",
                "modern id has precedence even when both saved-data fields are malformed");
        CompoundTag headerOnly = modern("header-only");
        headerOnly.getCompoundOrEmpty(ItemStateCodec.KEY).remove("properties");
        headerOnly.getCompoundOrEmpty(ItemStateCodec.KEY).remove("rolls");
        query(
                c,
                f,
                stack(headerOnly, plain),
                "header-only",
                "scalar contract reads only schema and id");
    }

    private static void headers(Checks c, Fixture f, boolean plain) {
        Map<String, Consumer<CompoundTag>> cases = new LinkedHashMap<>();
        cases.put("missing schema", state -> state.remove("schema"));
        cases.put("string schema", state -> state.putString("schema", "1"));
        cases.put("unsupported schema", state -> state.putInt("schema", 900));
        cases.put("missing id", state -> state.remove("id"));
        cases.put("non-string id", state -> state.putInt("id", 12));
        cases.put("blank id", state -> state.putString("id", "   "));
        for (var entry : cases.entrySet()) {
            CompoundTag root = modern("target");
            root.put(
                    "NeigeItems",
                    legacy("must-not-fallback", "{}").getCompoundOrEmpty("NeigeItems"));
            entry.getValue().accept(root.getCompoundOrEmpty(ItemStateCodec.KEY));
            invalid(c, f, stack(root, plain), entry.getKey());
        }
        CompoundTag root = legacy("must-not-fallback", "{}");
        root.putString(ItemStateCodec.KEY, "broken-envelope");
        invalid(c, f, stack(root, plain), "non-compound modern envelope");
    }

    private static void completeIdentity(Checks c, boolean plain) {
        for (var fixture : brokenRolls().entrySet()) {
            CompoundTag root = modern("target");
            fixture.getValue().accept(root.getCompoundOrEmpty(ItemStateCodec.KEY));
            ItemStack item = stack(root, plain);
            c.reject(
                    () -> CODEC.read(item),
                    "complete modern read retains validation: " + fixture.getKey());
            c.reject(
                    () -> NiItemNodes.identity(item),
                    "complete node identity retains validation: " + fixture.getKey());
        }
        c.reject(
                () -> NiItemNodes.identity(stack(legacy("target", "[]"), plain)),
                "complete legacy identity still rejects non-object saved data");
        ItemStack valid = stack(modern("target"), plain);
        ItemIdentity identity = NiItemNodes.identity(valid);
        c.that(
                identity.id().equals("target") && identity.rolls().get("roll").equals("saved"),
                "complete identity still returns saved rolls");
    }

    private static void take(Checks c, Fixture f, boolean plain) {
        f.clear();
        CompoundTag broken = modern("target");
        broken.getCompoundOrEmpty(ItemStateCodec.KEY).putString("rolls", "broken");
        ItemStack first = stack(broken, plain), second = stack(legacy("target", "bad-json"), plain);
        first.setAmount(2);
        second.setAmount(4);
        ItemStack unrelated = stack(legacy("other", "bad-json"), plain);
        unrelated.setAmount(6);
        byte[] unrelatedBefore = unrelated.serializeAsBytes();
        var secondData = CraftItemStack.unwrap(second).get(DataComponents.CUSTOM_DATA);
        var secondPatch = CraftItemStack.unwrap(second).getComponentsPatch();
        f.player.getInventory().setItem(0, first);
        f.player.getInventory().setItem(1, unrelated);
        f.player.getInventory().setItem(40, second);
        c.that(
                f.function("take") == Result.CONTINUE,
                "take-ni-item accepts matching ids with unreadable saved rolls");
        c.that(
                f.player.getInventory().getItem(0).isEmpty()
                        && f.player.getInventory().getItem(40).getAmount() == 3,
                "take-ni-item removes exactly three across main storage and offhand");
        c.that(
                Arrays.equals(unrelatedBefore, unrelated.serializeAsBytes()),
                "take-ni-item does not change unrelated ids");
        c.that(
                CraftItemStack.unwrap(second).get(DataComponents.CUSTOM_DATA) == secondData
                        && CraftItemStack.unwrap(second).getComponentsPatch().equals(secondPatch),
                "partial take changes only count, preserving custom_data and every component");
        byte[] remainder = second.serializeAsBytes();
        c.that(
                f.function("negative") == Result.STOP
                        && Arrays.equals(remainder, second.serializeAsBytes()),
                "negative take remains rejected without minting items");
        c.that(
                f.function("take-alias") == Result.CONTINUE
                        && f.player.getInventory().getItem(40).getAmount() == 2,
                "takeniitem action alias uses the same scalar lookup");
        c.that(
                f.function("take-all") == Result.CONTINUE
                        && f.player.getInventory().getItem(40).isEmpty(),
                "request beyond stock retains legacy consume-all-matches behavior");
        c.that(
                Arrays.equals(unrelatedBefore, unrelated.serializeAsBytes()),
                "consume-all still leaves other ids untouched");

        f.clear();
        CompoundTag dual = modern("other");
        dual.put("NeigeItems", legacy("target", "bad-json").getCompoundOrEmpty("NeigeItems"));
        ItemStack modernOther = stack(dual, plain);
        f.player.getInventory().setItem(0, modernOther);
        c.that(
                f.function("take") == Result.CONTINUE && modernOther.getAmount() == 3,
                "take cannot consume a conflicting legacy id when a modern id exists");
        dual.getCompoundOrEmpty(ItemStateCodec.KEY).remove("id");
        ItemStack invalid = stack(dual, plain);
        byte[] invalidBefore = invalid.serializeAsBytes();
        f.player.getInventory().setItem(0, invalid);
        c.that(
                f.function("take") == Result.STOP
                        && Arrays.equals(invalidBefore, invalid.serializeAsBytes()),
                "take rejects a damaged modern header instead of consuming the fallback legacy id");
    }

    private static void scripts(Checks c, Fixture f) {
        ItemStack item = stack(legacy("target", "bad-json"), false);
        var context = f.catalog.actionContext(f.player, Map.of("queried", item));
        c.that(
                Boolean.TRUE.equals(
                        context.evaluate(
                                "Java.type('pers.neige.neigeitems.utils.ItemUtils').getItemId(queried) == 'target'"
                                        + " && Packages.pers.neige.neigeitems.manager.ItemManager.INSTANCE.getItemId(queried) == 'target'"
                                        + " && ItemManager.getItemId(queried) == 'target'")),
                "Java.type, Packages, and bare manager use the scalar compatibility API");
        f.clear();
        item.setAmount(4);
        f.player.getInventory().setItem(0, item);
        c.that(
                ((Number)
                                        f.catalog
                                                .actionContext(f.player, null)
                                                .evaluate("niItemAmount('target')"))
                                .intValue()
                        == 4,
                "action-library count helper tolerates malformed legacy data");
    }

    private static void query(Checks c, Fixture f, ItemStack item, String expected, String name) {
        boolean serializable = item != null && !item.isEmpty();
        ItemStack beforeStack = item == null ? null : item.clone();
        byte[] before = serializable ? item.serializeAsBytes() : null;
        var custom =
                item == null || item.isEmpty()
                        ? null
                        : CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        c.that(
                Objects.equals(CODEC.readId(item), expected)
                        && Objects.equals(NiItemNodes.itemId(item), expected)
                        && Objects.equals(UTILS.getItemId(item), expected)
                        && Objects.equals(f.manager.getItemId(item), expected),
                name + ": all id entry points agree");
        var context = f.catalog.actionContext(f.player, null);
        context.set(NiContextKeys.ITEM_STACK, item);
        c.that(
                Objects.equals(
                        NiItemNodes.value(
                                "item_id",
                                "",
                                context,
                                ignored -> {
                                    throw new AssertionError("name accessed");
                                }),
                        expected),
                name + ": item_id node needs no full item view");
        c.that(
                item == null
                        || (serializable
                                ? Arrays.equals(before, item.serializeAsBytes())
                                : item.equals(beforeStack)),
                name + ": stack unchanged after id reads");
        if (item != null && !item.isEmpty())
            c.that(
                    CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA) == custom,
                    name + ": custom-data component identity is retained");
    }

    private static void invalid(Checks c, Fixture f, ItemStack item, String name) {
        byte[] before = item.serializeAsBytes();
        c.reject(() -> CODEC.readId(item), name + ": scalar read rejects corrupt modern header");
        c.reject(() -> UTILS.getItemId(item), name + ": script utils cannot fall back to old id");
        c.reject(
                () -> f.manager.getItemId(item),
                name + ": script manager cannot fall back to old id");
        c.reject(() -> NiItemNodes.identity(item), name + ": full identity remains strict");
        c.that(
                Arrays.equals(before, item.serializeAsBytes()),
                name + ": failed reads never mutate the stack");
    }

    private static Map<String, Consumer<CompoundTag>> brokenRolls() {
        Map<String, Consumer<CompoundTag>> cases = new LinkedHashMap<>();
        cases.put("missing rolls", state -> state.remove("rolls"));
        cases.put("non-compound rolls", state -> state.putString("rolls", "broken"));
        cases.put("non-string roll", state -> state.getCompoundOrEmpty("rolls").putInt("bad", 7));
        cases.put("bad null-roll list", state -> state.putString("null_rolls", "broken"));
        cases.put(
                "non-string null-roll key",
                state -> {
                    ListTag nulls = new ListTag();
                    nulls.add(IntTag.valueOf(4));
                    state.put("null_rolls", nulls);
                });
        cases.put(
                "conflicting null roll",
                state -> {
                    ListTag nulls = new ListTag();
                    nulls.add(StringTag.valueOf("roll"));
                    state.put("null_rolls", nulls);
                });
        return cases;
    }

    private static CompoundTag modern(String id) {
        CompoundTag root = new CompoundTag();
        root.put(
                ItemStateCodec.KEY,
                CODEC.encode(new ItemIdentity(id, Map.of("roll", "saved")), new CompoundTag()));
        root.putString("foreign:unknown", "unchanged");
        return root;
    }

    private static CompoundTag legacy(String id, String data) {
        CompoundTag root = new CompoundTag(), legacy = new CompoundTag();
        if (id != null) legacy.putString("id", id);
        legacy.putString("data", data);
        root.put("NeigeItems", legacy);
        root.putString("foreign:unknown", "unchanged");
        return root;
    }

    private static ItemStack stack(CompoundTag data, boolean plain) {
        ItemStack result = NmsItems.withCustomData(new ItemStack(Material.STONE, 3), data);
        var handle = CraftItemStack.unwrap(result);
        handle.set(DataComponents.CUSTOM_NAME, Component.literal("Unchanged probe name"));
        handle.set(DataComponents.REPAIR_COST, 17);
        handle.set(DataComponents.MAX_STACK_SIZE, 16);
        return convert(result, plain);
    }

    private static ItemStack convert(ItemStack item, boolean plain) {
        ItemStack result = plain ? new ItemStack(item) : CraftItemStack.asCraftCopy(item);
        if ((result instanceof CraftItemStack) == plain)
            throw new AssertionError("Wrong item implementation");
        return result;
    }

    private static final class Fixture implements AutoCloseable {
        final Player player = ProbePlayer.create("ItemIdentityProbe");
        final PlayerActionState players = new PlayerActionState();
        final NiCatalog catalog;
        final LegacyItemManager manager;

        Fixture(JavaPlugin plugin) {
            players.join(player.getUniqueId());
            NiRepository.Input input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(
                                    "take",
                                    "take-ni-item: target 3",
                                    "take-alias",
                                    "takeniitem: target 1",
                                    "take-all",
                                    "take-ni-item: target 999",
                                    "negative",
                                    "take-ni-item: target -1"),
                            Map.of(),
                            Map.of(),
                            Map.of());
            try {
                catalog = new NiCatalog(1, input, plugin, (viewer, text) -> null, players);
            } catch (Throwable error) {
                players.close();
                throw error;
            }
            manager = new LegacyItemManager(catalog.items());
        }

        Result function(String name) {
            var future = catalog.runFunction(name, player, null).toCompletableFuture();
            if (!future.isDone())
                throw new AssertionError("Synchronous take action unexpectedly deferred");
            return future.join();
        }

        void clear() {
            for (int slot = 0; slot < 41; slot++) player.getInventory().setItem(slot, null);
        }

        @Override
        public void close() {
            try {
                catalog.close();
            } finally {
                players.close();
            }
        }
    }

    private static final class Checks {
        int count;
        String group;
        final List<String> passed = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        void group(String name, Runnable test) {
            group = name;
            try {
                test.run();
            } catch (Throwable error) {
                failures.put(name, error.toString());
            }
        }

        void that(boolean condition, String message) {
            count++;
            if (!condition) throw new AssertionError(message);
            passed.add(group + ": " + message);
        }

        void reject(Runnable action, String message) {
            boolean rejected = false;
            try {
                action.run();
            } catch (RuntimeException expected) {
                rejected = true;
            }
            that(rejected, message);
        }
    }
}

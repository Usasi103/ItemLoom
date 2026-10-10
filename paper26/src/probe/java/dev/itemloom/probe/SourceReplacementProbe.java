package dev.itemloom.probe;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.sx.SxConfig;
import dev.itemloom.compat.sx.SxExpressions;
import dev.itemloom.compat.sx.SxRandom;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.LegacyItemEditorManager;
import dev.itemloom.paper.integration.OptionalItemSources;
import dev.itemloom.paper.nms.NmsItems;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Deterministic observations for running the same probe against two released implementations. */
@SuppressWarnings("deprecation")
final class SourceReplacementProbe {
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    @FunctionalInterface
    private interface Observation {
        Object get() throws Exception;
    }

    static Map<String, Object> run(JavaPlugin plugin) {
        SourceReplacementProbe probe = new SourceReplacementProbe();
        probe.group("state", probe::state);
        probe.group("comparisons", probe::comparisons);
        probe.group("text", probe::text);
        probe.group("sx", () -> probe.sx(plugin));
        return Map.of(
                "passed",
                probe.failures.isEmpty(),
                "failures",
                probe.failures,
                "observations",
                probe.observations,
                "cases",
                probe.observations.size(),
                "boundary",
                "Deterministic baseline/candidate observations on Paper; no real client or load test");
    }

    private void group(String name, Observation action) {
        try {
            action.get();
        } catch (Throwable error) {
            failures.add(name + ": " + root(error));
        }
    }

    private void observe(String name, Observation action) {
        if (observations.containsKey(name)) throw new AssertionError("Duplicate case " + name);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object value = action.get();
            // Preserve isolated UTF-16 surrogates produced by substring boundary cases.
            // A raw isolated surrogate cannot be written by the UTF-8 report encoder.
            result.put(
                    "value", value instanceof String text ? text.chars().boxed().toList() : value);
            result.put("type", value == null ? "null" : value.getClass().getName());
        } catch (Throwable error) {
            result.put("error", root(error).getClass().getName());
        }
        observations.put(name, result);
    }

    private static Throwable root(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null)
            error = error.getCause();
        return error;
    }

    private Object state() throws Exception {
        int[] values = {
            Integer.MIN_VALUE,
            -32769,
            -32768,
            -2,
            -1,
            0,
            1,
            2,
            59,
            60,
            100,
            32767,
            32768,
            Integer.MAX_VALUE
        };
        for (Material material :
                List.of(
                        Material.PAPER,
                        Material.WOODEN_SWORD,
                        Material.DIAMOND_SWORD,
                        Material.ELYTRA,
                        Material.BOW)) {
            ItemStack item = new ItemStack(material);
            for (int remaining : values)
                for (int maximum : values)
                    observe(
                            "damage/" + material + '/' + remaining + '/' + maximum,
                            () ->
                                    LegacyItemEditorManager.checkDurability(
                                            item, remaining, maximum));
        }
        Method preserve =
                NiItemOperations.class.getDeclaredMethod(
                        "preserveState", LegacyNbt.Compound.class, LegacyNbt.Compound.class);
        preserve.setAccessible(true);
        for (int fields = 0; fields < 4; fields++) {
            LegacyNbt.Compound previous = new LegacyNbt.Compound();
            if ((fields & 1) != 0) previous.putInt("charge", 0);
            if ((fields & 2) != 0) previous.putInt("durability", -17);
            previous.putString("foreign", "old");
            for (boolean withDestination : List.of(false, true)) {
                LegacyNbt.Compound target = new LegacyNbt.Compound();
                LegacyNbt.Compound inner = new LegacyNbt.Compound();
                inner.putInt("charge", 4);
                inner.putInt("durability", 80);
                inner.putString("foreign", "new");
                if (withDestination) target.put("NeigeItems", inner);
                observe(
                        "carry/" + fields + '/' + withDestination,
                        () -> {
                            preserve.invoke(null, previous, target);
                            var current = target.getCompound("NeigeItems");
                            if (current == null) return Map.of("present", false);
                            return Map.of(
                                    "present",
                                    true,
                                    "charge",
                                    current.getInt("charge"),
                                    "durability",
                                    current.getInt("durability"),
                                    "foreign",
                                    current.getString("foreign"));
                        });
            }
        }
        return null;
    }

    private Object comparisons() {
        List<Object> data =
                List.of(
                        Map.of(),
                        Map.of("a", 1),
                        Map.of("a", 99),
                        Map.of("z", 1),
                        ordered("a", 1, "z", 2),
                        ordered("z", 2, "a", 1),
                        Map.of("nest", Map.of("q", List.of(1, 2))),
                        List.of(),
                        List.of(1),
                        List.of(4),
                        List.of(1, 2),
                        List.of(1, 5),
                        List.of("a"),
                        List.of("z"),
                        1,
                        "one");
        List<LegacyNbt> values = data.stream().map(LegacyNbt::of).toList();
        for (int a = 0; a < values.size(); a++)
            for (int b = 0; b < values.size(); b++) {
                LegacyNbt left = values.get(a), right = values.get(b);
                observe("nbt/" + a + '/' + b, () -> left.compareTo(right));
            }
        List<LegacyNbtItemStack> items = new ArrayList<>();
        for (Material material : List.of(Material.DIAMOND_SWORD, Material.BOW))
            for (int count : List.of(1, 3))
                for (int damage : List.of(0, 19))
                    for (boolean tagged : List.of(false, true)) {
                        ItemStack item = new ItemStack(material, count);
                        item.setDurability((short) damage);
                        if (tagged) {
                            CompoundTag custom = new CompoundTag();
                            custom.putString("key", "value");
                            item = NmsItems.withCustomData(item, custom);
                        }
                        items.add(new LegacyNbtItemStack(item));
                    }
        for (int a = 0; a < items.size(); a++)
            for (int b = 0; b < items.size(); b++) {
                var left = items.get(a);
                var right = items.get(b);
                observe("item-order/" + a + '/' + b, () -> left.compareTo(right));
            }
        return null;
    }

    private Object text() throws Exception {
        Class<?> type = Class.forName("dev.itemloom.compat.ni.NiTextNodes");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object nodes = constructor.newInstance();
        Method configured =
                type.getDeclaredMethod(
                        "configured", String.class, NiConfig.class, NiEvaluation.class);
        Method inline = type.getDeclaredMethod("inline", String.class, String.class);
        configured.setAccessible(true);
        inline.setAccessible(true);
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation evaluation =
                    new NiEvaluation(
                            new GenerationContext(Map.of(), new Random(1)),
                            new NiConfig(Map.of()),
                            null,
                            NiEvaluation.Mode.ACTION,
                            new NiNodes(),
                            scripts,
                            new NiEvaluation.Host() {
                                public String placeholder(Object player, String parameters) {
                                    return null;
                                }

                                public String itemValue(String key, String parameters) {
                                    return null;
                                }

                                public void check(
                                        Object actions, NiEvaluation ignored, String value) {}
                            });
            int index = 0;
            for (String mode :
                    List.of(
                            "lower",
                            "upper",
                            "trim",
                            "length",
                            "contains",
                            "starts",
                            "ends",
                            "replace",
                            "substring",
                            "unknown"))
                for (String value : List.of(" Abc \t", "", "A\uD83D\uDE00Z")) {
                    NiConfig config =
                            new NiConfig(
                                    ordered(
                                            "mode",
                                            mode,
                                            "value",
                                            value,
                                            "arg",
                                            "A",
                                            "start",
                                            "1",
                                            "end",
                                            "2",
                                            "default",
                                            "fallback",
                                            "target",
                                            "A",
                                            "replacement",
                                            "$1\\"));
                    observe(
                            "string/" + index++,
                            () -> configured.invoke(nodes, "string", config, evaluation));
                }
            for (String mode :
                    List.of("matches", "find", "count", "group", "replace-first", "replace-all"))
                for (String pattern : List.of("(?<named>a)(b)?", "", "["))
                    for (String flag : List.of("", "i", "U", "!")) {
                        NiConfig config =
                                new NiConfig(
                                        ordered(
                                                "mode",
                                                mode,
                                                "value",
                                                "a ab A",
                                                "pattern",
                                                pattern,
                                                "group",
                                                "named",
                                                "default",
                                                "none",
                                                "replacement",
                                                "<$1>",
                                                "flags",
                                                flag,
                                                "literal-replacement",
                                                "false"));
                        observe(
                                "regex/" + index++,
                                () -> configured.invoke(nodes, "regex", config, evaluation));
                    }
            for (String group : List.of("", "0", "1", "2", "-1", "123", "named", "missing"))
                for (String replacement : List.of("$2", "$9", "$", "\\", "text")) {
                    NiConfig config =
                            new NiConfig(
                                    ordered(
                                            "mode",
                                            "replace-all",
                                            "value",
                                            "a ab",
                                            "pattern",
                                            "(?<named>a)(b)?",
                                            "group",
                                            group,
                                            "default",
                                            "none",
                                            "replacement",
                                            replacement));
                    observe(
                            "replacement/" + index++,
                            () -> configured.invoke(nodes, "regex", config, evaluation));
                    NiConfig grouped =
                            new NiConfig(
                                    ordered(
                                            "mode",
                                            "group",
                                            "value",
                                            "a ab",
                                            "pattern",
                                            "(?<named>a)(b)?",
                                            "group",
                                            group,
                                            "default",
                                            "none"));
                    observe(
                            "group/" + index++,
                            () -> configured.invoke(nodes, "regex", grouped, evaluation));
                }
            for (String source :
                    List.of(
                            "lower_ABC",
                            "contains_a_abc",
                            "replace_a_$1_aba",
                            "substring_1_3",
                            "substring_3_1_fallback_abcd",
                            "upper",
                            "unknown_x"))
                observe("inline/string/" + index++, () -> inline.invoke(nodes, "string", source));
            for (String source :
                    List.of(
                            "count_aaa_a",
                            "group_abc_(b)_1_missing",
                            "replace-first_aba_a_$1_i_true",
                            "find_ABC_a_i",
                            "matches_a"))
                observe("inline/regex/" + index++, () -> inline.invoke(nodes, "regex", source));
        }
        return null;
    }

    private Object sx(JavaPlugin plugin) throws Exception {
        List<String> scalar =
                Arrays.asList(
                        null,
                        "plain",
                        "[int]12",
                        "[byte]-128",
                        "[byte]128",
                        "[short]32768",
                        "[long]9223372036854775807",
                        "[double]NaN",
                        "[float]-Infinity",
                        "[INT]1",
                        "[int",
                        "[]1",
                        "[int] 1",
                        "[int]",
                        "[int]1]2");
        for (int i = 0; i < scalar.size(); i++) {
            String source = scalar.get(i);
            observe(
                    "sx-scalar/" + i,
                    () -> expressions(new ArrayList<>()).replace((Object) source));
        }
        for (String expression :
                List.of(
                        "<l:key>",
                        "<l:key#A:B:>",
                        "<l:key#>",
                        "<l:key#<s:one:two>>",
                        "<l:key#A:B>-<l:key#C:D>"))
            observe(
                    "sx-lock/" + expression,
                    () -> {
                        SxExpressions handler = expressions(new ArrayList<>());
                        String first = handler.replace(expression);
                        String second = handler.replace(expression);
                        return ordered(
                                "first", first, "second", second, "locks", handler.getLockMap());
                    });

        ItemStack prototype = new ItemStack(Material.DIAMOND_SWORD, 7);
        prototype.editMeta(
                meta -> {
                    meta.setDisplayName("Provider name");
                    meta.setLore(List.of("Provider lore"));
                    meta.setUnbreakable(true);
                    meta.getPersistentDataContainer()
                            .set(
                                    new NamespacedKey("fixture", "marker"),
                                    PersistentDataType.STRING,
                                    "retained");
                });
        CompoundTag providerData = NmsItems.customData(prototype);
        CompoundTag nested = new CompoundTag();
        nested.putString("left", "keep");
        providerData.put("nested", nested);
        prototype = NmsItems.withCustomData(prototype, providerData);
        ItemStack shared = prototype;
        ItemStack before = shared.clone();
        OptionalItemSources sources =
                ItemBridgeProbe.sources(plugin, "replacement", (id, player, params) -> shared);
        Class<?> recipeClass = Class.forName("dev.itemloom.paper.sx.SxPaperRecipe");
        var constructor =
                recipeClass.getDeclaredConstructor(
                        SxRepository.Definition.class, SxConfig.class, OptionalItemSources.class);
        constructor.setAccessible(true);
        Method create = recipeClass.getDeclaredMethod("create", SxExpressions.class);
        create.setAccessible(true);
        List<Map<String, Object>> configurations = new ArrayList<>();
        configurations.add(ordered("ID", "DIAMOND_SWORD"));
        for (String durability : List.of("25%", "<7", "7", "", "-1", "99999", "NaN%", "Infinity%"))
            configurations.add(ordered("ID", "DIAMOND_SWORD", "Durability", durability));
        configurations.add(
                ordered(
                        "ID",
                        "BOW",
                        "Amount",
                        "2",
                        "Name",
                        "&aName",
                        "Lore",
                        List.of("&bFirst", "Second"),
                        "EnchantList",
                        List.of("UNBREAKING:2"),
                        "ItemFlagList",
                        List.of("HIDE_ENCHANTS"),
                        "Unbreakable",
                        true,
                        "CustomModelData",
                        "123"));
        configurations.add(ordered("ID", "LEATHER_CHESTPLATE", "Color", "ff00a0"));
        configurations.add(ordered("ID", "POTION", "Potion", "healing"));
        configurations.add(
                ordered(
                        "ID",
                        "POTION",
                        "Potion",
                        ordered(
                                "SPEED",
                                        ordered(
                                                "duration",
                                                "80",
                                                "amplifier",
                                                "2",
                                                "particles",
                                                false),
                                "UNKNOWN_EFFECT", ordered("duration", "invalid"))));
        configurations.add(
                ordered(
                        "ID",
                        "DIAMOND_SWORD",
                        "Attributes",
                        List.of(
                                "generic_attack_damage:4.5:0:hand",
                                "generic_attack_speed:0:0",
                                "generic_attack_damage:2:1:off_hand")));
        configurations.add(
                ordered(
                        "ID",
                        "DIAMOND_SWORD",
                        "ClearAttribute",
                        true,
                        "Attributes",
                        List.of("invalid ignored")));
        configurations.add(
                ordered(
                        "ID",
                        "PLAYER_HEAD",
                        "SkullName",
                        "SampleName",
                        "ItemFlagList",
                        List.of("HIDE_PROFILE")));
        configurations.add(
                ordered("ID", "PLAYER_HEAD", "SkullName", "12345678-1234-1234-1234-123456789abc"));
        configurations.add(
                ordered(
                        "ID",
                        "PAPER",
                        "NBT",
                        ordered(
                                "AttributeModifiers",
                                List.of(ordered("UUID", List.of(1, 2, 3, 4), "Name", "test")))));
        configurations.add(ordered("ID", "itembridge:replacement:fixture:blade"));
        configurations.add(
                ordered(
                        "ID",
                        "itembridge:replacement:fixture:blade",
                        "Amount",
                        "2",
                        "Name",
                        "Overridden",
                        "Lore",
                        List.of(),
                        "Unbreakable",
                        false,
                        "NBT",
                        ordered("nested", ordered("right", "added"))));
        configurations.add(
                ordered(
                        "ID",
                        "itembridge:replacement:fixture:blade",
                        "Components",
                        ordered("minecraft:custom_name", "Explicit component")));
        configurations.add(ordered("ID", "DIAMOND_SWORD", "Amount", "0"));
        configurations.add(ordered("ID", "DIAMOND_SWORD", "EnchantList", List.of("UNKNOWN:1")));
        configurations.add(ordered("ID", "PAPER", "ItemFlagList", List.of("INVALID_FLAG")));
        configurations.add(
                ordered(
                        "ID",
                        "PAPER",
                        "NBT",
                        ordered("AttributeModifiers", List.of(ordered("UUID", List.of("bad"))))));
        for (int i = 0; i < configurations.size(); i++) {
            SxConfig config = new SxConfig(configurations.get(i));
            int number = i;
            observe(
                    "sx-item/" + i,
                    () -> {
                        List<String> expanded = new ArrayList<>();
                        var definition =
                                new SxRepository.Definition(
                                        "Replacement" + number, "synthetic", config, null);
                        Object recipe =
                                constructor.newInstance(
                                        definition, new SxConfig(Map.of()), sources);
                        ItemStack item = (ItemStack) create.invoke(recipe, expressions(expanded));
                        return ordered("item", encoded(item), "expansions", expanded);
                    });
        }
        if (!shared.equals(before)) throw new AssertionError("External provider prototype mutated");
        return null;
    }

    private static SxExpressions expressions(List<String> trace) {
        return new SxExpressions(
                null,
                SxRandom.compile(Map.of("key", "pool")),
                Map.of(),
                Map.of(),
                new Random(7),
                text -> {
                    trace.add(text);
                    return text;
                },
                (file, function, handler, arguments) -> "script",
                new SxConfig(Map.of()),
                CLOCK);
    }

    private static JsonElement encoded(ItemStack item) {
        return net.minecraft.world.item.ItemStack.CODEC
                .encodeStart(
                        CraftRegistry.getMinecraftRegistry()
                                .createSerializationContext(JsonOps.INSTANCE),
                        CraftItemStack.asNMSCopy(item))
                .getOrThrow();
    }

    private static Map<String, Object> ordered(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
}

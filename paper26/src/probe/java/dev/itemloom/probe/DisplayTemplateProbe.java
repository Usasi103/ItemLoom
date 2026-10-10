package dev.itemloom.probe;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.paper.compat.NiDisplayTemplate;
import dev.itemloom.paper.compat.NiItemAppearance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.plugin.java.JavaPlugin;

/** Runtime assertions for display recipes against the configured native codecs. */
public final class DisplayTemplateProbe {
    private DisplayTemplateProbe() {}

    public static Map<String, Object> run(JavaPlugin owner) {
        Checks checks = new Checks();
        construction(checks);
        staticApplication(checks);
        orderedResolution(checks);
        rollback(checks);
        loreAndIsolation(checks);
        return Map.of(
                "passed",
                true,
                "checks",
                checks.verified.size(),
                "verified",
                List.copyOf(checks.verified));
    }

    private static void construction(Checks checks) {
        checks.reject(Map.of("Name", "bad"), "top-level keys are exact");
        checks.reject(Map.of("components", "bad"), "components require a map");
        checks.reject(Map.of("name", 7), "name requires string");
        checks.reject(Map.of("lore", List.of("ok", 7)), "lore requires string elements");
        checks.reject(Map.of("name", "a", "mini-name", "b"), "aliases claim canonical identity");
        checks.reject(
                Map.of("name", "a", "components", Map.of("minecraft:custom_name", "b")),
                "explicit spelling and alias conflict");
        checks.reject(
                Map.of("components", Map.of("custom_name", "a", "minecraft:custom_name", "b")),
                "qualified and unqualified keys conflict");
        for (String key :
                List.of("custom_data", "damage", "other:custom_name", "unknown", "!custom_name")) {
            checks.reject(
                    Map.of("components", Map.of(key, Map.of("x", 1))),
                    "disallowed component " + key);
        }
        Map<String, Object> nullComponent = new LinkedHashMap<>();
        nullComponent.put("custom_name", null);
        checks.reject(Map.of("components", nullComponent), "null explicit value rejected");
        IllegalArgumentException error =
                checks.reject(
                        Map.of("components", Map.of("rarity", "impossible")),
                        "constant codecs validated at construction");
        checks.that(
                error.getMessage().contains("rarity") && error.getCause() != null,
                "explicit codec error identifies key and cause");
        NiDisplayTemplate keyOnly =
                template(
                        Map.of(
                                "components",
                                Map.of(
                                        "custom_model_data",
                                        Map.of(
                                                "strings",
                                                List.of("literal"),
                                                "<ignored>",
                                                "literal"))));
        checks.that(!keyOnly.dynamic(), "map keys do not create dynamic values");
    }

    private static void staticApplication(Checks checks) {
        NiDisplayTemplate empty = template(Map.of());
        ItemStack target = new ItemStack(Items.DIAMOND, 7);
        target.set(DataComponents.DAMAGE, 3);
        ItemStack original = target.copy();
        empty.apply(
                target,
                value -> {
                    throw new AssertionError("static resolver invoked");
                });
        checks.that(
                !empty.dynamic() && ItemStack.matches(original, target),
                "empty template changes nothing");
        NiDisplayTemplate fixed = template(Map.of("name", "fixed"));
        fixed.apply(
                target,
                value -> {
                    throw new AssertionError("static resolver invoked");
                });
        checks.that(
                !fixed.dynamic()
                        && target.get(DataComponents.CUSTOM_NAME).getString().equals("fixed"),
                "constant name writes with zero callbacks");
        checks.that(
                target.getItem() == Items.DIAMOND
                        && target.getCount() == 7
                        && target.get(DataComponents.DAMAGE) == 3,
                "type amount and unselected fields retained");
        NiDisplayTemplate model = template(Map.of("custom-model-data", 19));
        model.apply(
                target,
                value -> {
                    throw new AssertionError("static resolver invoked");
                });
        checks.that(
                target.get(DataComponents.CUSTOM_MODEL_DATA).floats().equals(List.of(19F)),
                "model alias follows appearance conversion");
        NiDisplayTemplate itemName = template(Map.of("item-name", "item title"));
        itemName.apply(
                target,
                value -> {
                    throw new AssertionError("static resolver invoked");
                });
        checks.that(
                target.get(DataComponents.ITEM_NAME).getString().equals("item title")
                        && target.get(DataComponents.CUSTOM_NAME).getString().equals("fixed"),
                "item name distinct from custom name");
        NiDisplayTemplate nested =
                template(
                        Map.of(
                                "components",
                                Map.of(
                                        "custom_name",
                                        Map.of(
                                                "text",
                                                "root",
                                                "extra",
                                                List.of(Map.of("text", "child"))))));
        ItemStack first = new ItemStack(Items.PAPER);
        nested.apply(
                first,
                value -> {
                    throw new AssertionError("static resolver invoked");
                });
        Component originalName = first.get(DataComponents.CUSTOM_NAME);
        ((MutableComponent) originalName.getSiblings().getFirst()).append(" changed");
        ItemStack second = new ItemStack(Items.PAPER);
        nested.apply(
                second,
                value -> {
                    throw new AssertionError("static resolver invoked");
                });
        checks.that(
                second.get(DataComponents.CUSTOM_NAME).getString().equals("rootchild"),
                "cached explicit component detaches mutable sibling text");
    }

    private static void orderedResolution(Checks checks) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("strings", List.of("literal", "%model%"));
        model.put("floats", List.of("(Float) 2.5"));
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("custom_model_data", model);
        components.put("tooltip_style", "<style>");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("lore", List.of("fixed", "%lore%"));
        source.put("components", components);
        source.put("name", "%name%");
        NiDisplayTemplate template = template(source);
        source.put("name", "mutated input");
        List<String> calls = new ArrayList<>();
        ItemStack target = new ItemStack(Items.PAPER);
        template.apply(
                target,
                value -> {
                    calls.add(value);
                    return switch (value) {
                        case "%model%" -> "resolved";
                        case "<style>" -> "minecraft:stone";
                        case "%name%" -> "title %literal%";
                        case "%lore%" -> "line one\nline two";
                        default -> value;
                    };
                });
        checks.that(template.dynamic(), "nested strings establish dynamic template");
        checks.that(
                calls.equals(
                        List.of(
                                "literal",
                                "%model%",
                                "(Float) 2.5",
                                "<style>",
                                "%name%",
                                "%lore%")),
                "explicit string siblings and deferred lore callbacks preserve encounter order");
        checks.that(
                target.get(DataComponents.CUSTOM_MODEL_DATA)
                        .strings()
                        .equals(List.of("literal", "resolved")),
                "resolved nested component accepted by native codec");
        checks.that(
                target.get(DataComponents.CUSTOM_NAME).getString().equals("title %literal%"),
                "resolver output is not reparsed");
        checks.that(
                target.get(DataComponents.LORE).lines().stream()
                        .map(Component::getString)
                        .toList()
                        .equals(List.of("fixed", "line one", "line two")),
                "resolved lore expands into native lines");
    }

    private static void rollback(Checks checks) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "%first%");
        source.put("components", Map.of("rarity", "%last%"));
        NiDisplayTemplate template = template(source);
        ItemStack target = new ItemStack(Items.DIAMOND, 4);
        target.set(DataComponents.CUSTOM_NAME, Component.literal("original"));
        target.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("old lore"))));
        ItemStack original = target.copy();
        try {
            template.apply(
                    target, value -> value.equals("%first%") ? "new name" : "invalid rarity");
            throw new AssertionError("dynamic invalid rarity accepted");
        } catch (IllegalArgumentException expected) {
            checks.that(
                    expected.getCause() != null && expected.getMessage().contains("rarity"),
                    "resolved codec failure retains context and cause");
        }
        checks.that(
                ItemStack.matches(original, target), "late codec failure commits no components");
        RuntimeException expected = new IllegalStateException("resolver failure");
        try {
            template.apply(
                    target,
                    value -> {
                        if (value.equals("%last%")) throw expected;
                        return "new name";
                    });
            throw new AssertionError("resolver exception swallowed");
        } catch (IllegalStateException observed) {
            checks.that(observed == expected, "resolver exception identity preserved");
        }
        checks.that(
                ItemStack.matches(original, target), "late resolver failure commits no components");
    }

    private static void loreAndIsolation(Checks checks) {
        List<String> source = List.of("&aGreen", "%line%", "&lBold");
        NiDisplayTemplate template = template(Map.of("lore", source));
        ItemStack first = new ItemStack(Items.PAPER);
        template.apply(first, value -> "&bBlue\n&cRed");
        ItemLore expected =
                new NiItemAppearance(
                                NiConfig.mapReader(
                                        Map.of(
                                                "lore",
                                                List.of("&aGreen", "&bBlue\n&cRed", "&lBold"))),
                                warning -> {})
                        .apply(new ItemStack(Items.PAPER))
                        .get(DataComponents.LORE);
        checks.that(
                first.get(DataComponents.LORE).equals(expected),
                "raw and styled lore match appearance codec");
        ((MutableComponent) first.get(DataComponents.LORE).lines().getFirst()).append(" mutated");
        ItemStack second = new ItemStack(Items.PAPER);
        template.apply(second, value -> "&bBlue\n&cRed");
        checks.that(
                second.get(DataComponents.LORE).equals(expected),
                "target text mutations do not leak into template");
        ItemStack third = new ItemStack(Items.PAPER);
        template.apply(third, value -> "different");
        checks.that(
                third.get(DataComponents.LORE).lines().stream()
                        .map(Component::getString)
                        .toList()
                        .equals(List.of("Green", "different", "Bold")),
                "later resolver output is independent");
        NiDisplayTemplate emptyStatic = template(Map.of("lore", List.of()));
        emptyStatic.apply(
                second,
                value -> {
                    throw new AssertionError("static resolver invoked");
                });
        var emptyAppearance =
                new NiItemAppearance(NiConfig.mapReader(Map.of("lore", List.of())), warning -> {})
                        .apply(new ItemStack(Items.PAPER))
                        .get(DataComponents.LORE);
        checks.that(
                java.util.Objects.equals(second.get(DataComponents.LORE), emptyAppearance),
                "static empty lore uses the native appearance default");
        template(Map.of("lore", List.of("%empty%"))).apply(third, value -> "\n");
        checks.that(
                ItemLore.EMPTY.equals(third.get(DataComponents.LORE)),
                "dynamic empty lore selects explicit empty component");
        NiDisplayTemplate mini = template(Map.of("mini-name", "<red>red</red>"));
        mini.apply(third, value -> value);
        Component miniExpected =
                new NiItemAppearance(
                                NiConfig.mapReader(Map.of("mini-name", "<red>red</red>")),
                                warning -> {})
                        .apply(new ItemStack(Items.PAPER))
                        .get(DataComponents.CUSTOM_NAME);
        checks.that(
                third.get(DataComponents.CUSTOM_NAME).equals(miniExpected),
                "mini alias retains appearance formatting");
    }

    private static NiDisplayTemplate template(Map<String, ?> source) {
        return new NiDisplayTemplate(new NiConfig(source));
    }

    private static final class Checks {
        private final List<String> verified = new ArrayList<>();

        void that(boolean condition, String description) {
            if (!condition) throw new AssertionError(description);
            verified.add(description);
        }

        IllegalArgumentException reject(Map<String, ?> source, String description) {
            try {
                template(source);
            } catch (IllegalArgumentException error) {
                that(error.getMessage().startsWith("client_bound_data:"), description);
                return error;
            }
            throw new AssertionError(description);
        }
    }
}

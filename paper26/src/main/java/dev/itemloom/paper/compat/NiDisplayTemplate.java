package dev.itemloom.paper.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiTagValues;
import dev.itemloom.paper.nms.NmsItems;

/** NI's opt-in client_bound_data frontend. Templates only describe display components. */
public final class NiDisplayTemplate {
    private static final Set<String> ALLOWED =
            Set.of(
                    "custom_name",
                    "item_name",
                    "lore",
                    "item_model",
                    "custom_model_data",
                    "tooltip_style",
                    "tooltip_display",
                    "enchantment_glint_override",
                    "rarity",
                    "dyed_color");
    private final Map<DataComponentType<?>, Object> constants;
    private final Map<String, Object> variables;
    private final String loreAlias;
    private final List<Line> lore;

    private record Line(String text, ItemLore constant) {}

    public NiDisplayTemplate(NiConfig config) {
        Set<DataComponentType<?>> selected = new LinkedHashSet<>();
        Map<String, Object> fixed = new LinkedHashMap<>(), dynamic = new LinkedHashMap<>();
        List<Line> lines = null;
        String alias = null;
        for (var field : config.values().entrySet()) {
            String key = field.getKey();
            Object value = field.getValue();
            if (key.equals("components")) {
                if (!(value instanceof Map<?, ?> components))
                    throw invalid("components must be a map");
                var fixedComponents = new LinkedHashMap<String, Object>();
                var dynamicComponents = new LinkedHashMap<String, Object>();
                for (var component : components.entrySet()) {
                    String name = String.valueOf(component.getKey());
                    select(selected, name);
                    if (component.getValue() == null)
                        throw invalid("null component value: " + name);
                    (dynamic(component.getValue()) ? dynamicComponents : fixedComponents)
                            .put(name, component.getValue());
                }
                if (!fixedComponents.isEmpty()) fixed.put(key, fixedComponents);
                if (!dynamicComponents.isEmpty()) dynamic.put(key, dynamicComponents);
            } else {
                String name = alias(key);
                if (name == null) throw invalid("unsupported display field: " + key);
                select(selected, name);
                if (key.endsWith("name") && !(value instanceof String))
                    throw invalid(key + " must be a string");
                if (key.endsWith("lore")) {
                    if (!(value instanceof List<?> list)
                            || list.stream().anyMatch(line -> !(line instanceof String)))
                        throw invalid(key + " must be a string list");
                    if (dynamic(value)) {
                        alias = key;
                        lines = new ArrayList<>();
                        for (Object line : list) {
                            String text = (String) line;
                            lines.add(
                                    new Line(text, dynamic(text) ? null : compileLine(key, text)));
                        }
                        continue;
                    }
                }
                (dynamic(value) ? dynamic : fixed).put(key, value);
            }
        }
        constants = compile(fixed);
        variables = java.util.Collections.unmodifiableMap(dynamic);
        loreAlias = alias;
        lore = lines == null ? null : List.copyOf(lines);
    }

    public boolean dynamic() {
        return !variables.isEmpty() || lore != null;
    }

    public void apply(ItemStack copy, UnaryOperator<String> resolve) {
        Map<DataComponentType<?>, Object> values = constants;
        if (dynamic()) {
            values = new LinkedHashMap<>(constants);
            values.putAll(compile(resolveMap(variables, resolve)));
            if (lore != null) {
                List<net.minecraft.network.chat.Component> raw = new ArrayList<>(),
                        styled = new ArrayList<>();
                for (Line line : lore) {
                    ItemLore rendered =
                            line.constant() == null
                                    ? compileLine(loreAlias, resolve.apply(line.text()))
                                    : line.constant();
                    raw.addAll(rendered.lines());
                    styled.addAll(rendered.styledLines());
                }
                values.put(
                        DataComponents.LORE, new ItemLore(List.copyOf(raw), List.copyOf(styled)));
            }
        }
        values.forEach((type, value) -> set(copy, type, value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void set(ItemStack copy, DataComponentType type, Object value) {
        copy.set(type, value);
    }

    private static Map<DataComponentType<?>, Object> compile(Map<String, Object> source) {
        if (source.isEmpty()) return Map.of();
        Map<String, Object> aliases = new LinkedHashMap<>(source);
        Object components = aliases.remove("components");
        ItemStack item =
                new NiItemAppearance(
                                NiConfig.mapReader(aliases),
                                message -> {
                                    throw invalid(message);
                                })
                        .apply(new ItemStack(Items.PAPER));
        Map<DataComponentType<?>, Object> result = new LinkedHashMap<>();
        for (String key : aliases.keySet()) {
            DataComponentType<?> type = type(alias(key));
            result.put(type, item.get(type));
        }
        if (components instanceof Map<?, ?> map)
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                try {
                    NmsItems.applyComponents(
                            item, Map.of(key, NiTagValues.decode(entry.getValue())));
                } catch (IllegalArgumentException | IllegalStateException error) {
                    throw new IllegalArgumentException(
                            "client_bound_data: invalid component value for " + key, error);
                }
                DataComponentType<?> type = type(key);
                result.put(type, item.get(type));
            }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static ItemLore compileLine(String alias, String value) {
        ItemLore result =
                (ItemLore) compile(Map.of(alias, List.of(value))).get(DataComponents.LORE);
        return result == null ? ItemLore.EMPTY : result;
    }

    private static String alias(String field) {
        return switch (field) {
            case "name", "mini-name" -> "custom_name";
            case "item-name", "mini-item-name" -> "item_name";
            case "lore", "mini-lore" -> "lore";
            case "custom-model-data" -> "custom_model_data";
            default -> null;
        };
    }

    private static DataComponentType<?> type(String name) {
        Identifier id = Identifier.tryParse(name);
        if (id == null || !id.getNamespace().equals("minecraft") || !ALLOWED.contains(id.getPath()))
            throw invalid("component is not display-only: " + name);
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
        if (type == null) throw invalid("unknown component: " + name);
        return type;
    }

    private static void select(Set<DataComponentType<?>> selected, String name) {
        if (!selected.add(type(name))) throw invalid("duplicate display component: " + name);
    }

    private static boolean dynamic(Object value) {
        if (value instanceof String text) return text.indexOf('<') >= 0 || text.indexOf('%') >= 0;
        if (value instanceof Map<?, ?> map)
            return map.values().stream().anyMatch(NiDisplayTemplate::dynamic);
        if (value instanceof List<?> list)
            return list.stream().anyMatch(NiDisplayTemplate::dynamic);
        return false;
    }

    private static Map<String, Object> resolveMap(
            Map<String, Object> source, UnaryOperator<String> resolver) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, resolve(value, resolver)));
        return result;
    }

    private static Object resolve(Object value, UnaryOperator<String> resolver) {
        if (value instanceof String text) return resolver.apply(text);
        if (value instanceof List<?> list)
            return list.stream().map(child -> resolve(child, resolver)).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), resolve(child, resolver)));
            return result;
        }
        return value;
    }

    private static IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException("client_bound_data: " + text);
    }
}

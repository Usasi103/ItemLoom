package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiTagValues;
import dev.itemloom.paper.display.ProofItemCopies;
import dev.itemloom.paper.nms.NmsItems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Validated display recipes with detached constant values and transactional dynamic rendering. */
public final class NiDisplayTemplate {
    private static final Map<String, DataComponentType<?>> DISPLAY_COMPONENTS =
            Map.ofEntries(
                    Map.entry("custom_name", DataComponents.CUSTOM_NAME),
                    Map.entry("item_name", DataComponents.ITEM_NAME),
                    Map.entry("lore", DataComponents.LORE),
                    Map.entry("item_model", DataComponents.ITEM_MODEL),
                    Map.entry("custom_model_data", DataComponents.CUSTOM_MODEL_DATA),
                    Map.entry("tooltip_style", DataComponents.TOOLTIP_STYLE),
                    Map.entry("tooltip_display", DataComponents.TOOLTIP_DISPLAY),
                    Map.entry(
                            "enchantment_glint_override",
                            DataComponents.ENCHANTMENT_GLINT_OVERRIDE),
                    Map.entry("rarity", DataComponents.RARITY),
                    Map.entry("dyed_color", DataComponents.DYED_COLOR));

    private record Field(
            String key,
            DataComponentType<?> type,
            Object source,
            boolean explicit,
            boolean dynamic) {}

    private record LoreLine(String source, ItemStack constant) {}

    private record Prepared(DataComponentType<?> type, Object value) {
        void commit(ItemStack target) {
            if (value == null) target.remove(type);
            else target.set(TypedDataComponent.createUnchecked(type, value));
        }
    }

    private final List<Field> fields;
    private final ItemStack constants;
    private final List<DataComponentType<?>> constantTypes;
    private final Field lore;
    private final List<LoreLine> loreLines;
    private final boolean dynamic;

    public NiDisplayTemplate(NiConfig config) {
        List<Field> accepted = new ArrayList<>();
        ItemStack compiled = new ItemStack(Items.PAPER);
        List<DataComponentType<?>> compiledTypes = new ArrayList<>();
        Set<DataComponentType<?>> selected = new HashSet<>();
        Field loreField = null;
        List<LoreLine> compiledLore = new ArrayList<>();
        for (Map.Entry<String, Object> entry : config.values().entrySet()) {
            String key = entry.getKey();
            Object source = entry.getValue();
            if (key.equals("components")) {
                if (!(source instanceof Map<?, ?> components))
                    throw invalid("components must be a map");
                for (Map.Entry<?, ?> component : components.entrySet()) {
                    String componentKey = String.valueOf(component.getKey());
                    DataComponentType<?> type = explicitType(componentKey);
                    if (component.getValue() == null)
                        throw invalid("component " + componentKey + " must not be null");
                    claim(selected, type, componentKey);
                    Field field =
                            new Field(
                                    componentKey,
                                    type,
                                    component.getValue(),
                                    true,
                                    needsResolution(component.getValue()));
                    if (field.dynamic()) accepted.add(field);
                    else cache(field, compiled, compiledTypes);
                }
                continue;
            }
            DataComponentType<?> type =
                    switch (key) {
                        case "name", "mini-name" -> DataComponents.CUSTOM_NAME;
                        case "item-name", "mini-item-name" -> DataComponents.ITEM_NAME;
                        case "lore", "mini-lore" -> DataComponents.LORE;
                        case "custom-model-data" -> DataComponents.CUSTOM_MODEL_DATA;
                        default -> throw invalid("unsupported field " + key);
                    };
            claim(selected, type, key);
            if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) {
                if (!(source instanceof String)) throw invalid(key + " must be a string");
            } else if (type == DataComponents.LORE) {
                if (!(source instanceof List<?> lines)
                        || lines.stream().anyMatch(line -> !(line instanceof String))) {
                    throw invalid(key + " must be a list of strings");
                }
            }
            Field field = new Field(key, type, source, false, needsResolution(source));
            if (type == DataComponents.LORE) {
                if (field.dynamic()) {
                    loreField = field;
                    for (Object sourceLine : (List<?>) source) {
                        String line = (String) sourceLine;
                        ItemStack constant = null;
                        if (!needsResolution(line)) {
                            constant = new ItemStack(Items.PAPER);
                            new Prepared(DataComponents.LORE, render(field, List.of(line)))
                                    .commit(constant);
                        }
                        compiledLore.add(new LoreLine(line, constant));
                    }
                } else cache(field, compiled, compiledTypes);
            } else {
                if (field.dynamic()) accepted.add(field);
                else cache(field, compiled, compiledTypes);
            }
        }
        fields = List.copyOf(accepted);
        constants = compiled;
        constantTypes = List.copyOf(compiledTypes);
        lore = loreField;
        loreLines = List.copyOf(compiledLore);
        dynamic = !fields.isEmpty() || lore != null;
    }

    public boolean dynamic() {
        return dynamic;
    }

    public void apply(ItemStack copy, UnaryOperator<String> resolve) {
        List<Prepared> prepared = new ArrayList<>(constantTypes.size() + fields.size() + 1);
        if (!constantTypes.isEmpty()) {
            ItemStack detached = ProofItemCopies.copy(constants);
            for (DataComponentType<?> type : constantTypes)
                prepared.add(new Prepared(type, detached.get(type)));
        }
        for (Field field : fields) {
            Object value = resolveTree(field.source(), resolve);
            prepared.add(new Prepared(field.type(), render(field, value)));
        }
        if (lore != null) {
            List<Component> lines = new ArrayList<>();
            List<Component> styled = new ArrayList<>();
            for (LoreLine line : loreLines) {
                ItemLore fragment;
                if (line.constant() != null) {
                    fragment = ProofItemCopies.copy(line.constant()).get(DataComponents.LORE);
                } else {
                    List<String> resolved = new ArrayList<>(1);
                    resolved.add(resolve.apply(line.source()));
                    fragment = (ItemLore) render(lore, resolved);
                }
                if (fragment != null) {
                    lines.addAll(fragment.lines());
                    styled.addAll(fragment.styledLines());
                }
            }
            prepared.add(
                    new Prepared(
                            DataComponents.LORE,
                            new ItemLore(List.copyOf(lines), List.copyOf(styled))));
        }
        for (Prepared value : prepared) value.commit(copy);
    }

    private static void claim(
            Set<DataComponentType<?>> selected, DataComponentType<?> type, String key) {
        if (!selected.add(type)) throw invalid("duplicate component selected by " + key);
    }

    private static DataComponentType<?> explicitType(String key) {
        String name = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        DataComponentType<?> type = DISPLAY_COMPONENTS.get(name);
        if (type == null)
            throw invalid("component " + key + " is not an allowed display component");
        return type;
    }

    private static void cache(Field field, ItemStack compiled, List<DataComponentType<?>> types) {
        new Prepared(field.type(), render(field, field.source())).commit(compiled);
        types.add(field.type());
    }

    private static Object render(Field field, Object source) {
        try {
            ItemStack scratch = new ItemStack(Items.PAPER);
            if (field.explicit()) {
                NmsItems.applyComponents(scratch, Map.of(field.key(), NiTagValues.decode(source)));
            } else {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put(field.key(), source);
                scratch =
                        new NiItemAppearance(NiConfig.mapReader(values), warning -> {})
                                .apply(scratch);
            }
            return scratch.get(field.type());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "client_bound_data: "
                            + (field.explicit() ? "component " : "field ")
                            + field.key()
                            + " could not be rendered",
                    error);
        }
    }

    private static boolean needsResolution(Object value) {
        if (value instanceof String text) return text.indexOf('<') >= 0 || text.indexOf('%') >= 0;
        if (value instanceof List<?> values)
            return values.stream().anyMatch(NiDisplayTemplate::needsResolution);
        if (value instanceof Map<?, ?> values)
            return values.values().stream().anyMatch(NiDisplayTemplate::needsResolution);
        return false;
    }

    private static Object resolveTree(Object value, UnaryOperator<String> resolve) {
        if (value instanceof String text) return resolve.apply(text);
        if (value instanceof List<?> values) {
            List<Object> result = new ArrayList<>(values.size());
            for (Object child : values) result.add(resolveTree(child, resolve));
            return result;
        }
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach(
                    (key, child) -> result.put(String.valueOf(key), resolveTree(child, resolve)));
            return result;
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("client_bound_data: " + message);
    }
}

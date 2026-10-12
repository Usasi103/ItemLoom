package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.*;
import dev.itemloom.paper.nms.NmsItems;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.craftbukkit.CraftRegistry;

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

    private record Field(
            String name,
            DataComponentType<?> type,
            Object source,
            boolean explicit,
            boolean dynamic,
            Tag constant) {}

    private record Prepared(DataComponentType<?> type, Object value) {}

    private final List<Field> fields;
    private final boolean dynamic;

    public NiDisplayTemplate(NiConfig config) {
        List<Field> compiled = new ArrayList<>();
        Set<DataComponentType<?>> claimed = new HashSet<>();
        for (var entry : config.values().entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (name.equals("components")) {
                if (!(value instanceof Map<?, ?> map)) throw invalid(name);
                for (var component : map.entrySet()) {
                    String id = String.valueOf(component.getKey());
                    String plain = id.startsWith("minecraft:") ? id.substring(10) : id;
                    if (!ALLOWED.contains(plain) || component.getValue() == null) throw invalid(id);
                    add(compiled, claimed, id, component(plain), component.getValue(), true);
                }
                continue;
            }
            DataComponentType<?> type =
                    switch (name) {
                        case "name", "mini-name" -> DataComponents.CUSTOM_NAME;
                        case "item-name", "mini-item-name" -> DataComponents.ITEM_NAME;
                        case "lore", "mini-lore" -> DataComponents.LORE;
                        case "custom-model-data" -> DataComponents.CUSTOM_MODEL_DATA;
                        default -> throw invalid(name);
                    };
            if ((name.equals("lore") || name.equals("mini-lore"))) {
                if (!(value instanceof List<?> list)
                        || list.stream().anyMatch(line -> !(line instanceof String)))
                    throw invalid(name);
            } else if (!name.equals("custom-model-data") && !(value instanceof String))
                throw invalid(name);
            add(compiled, claimed, name, type, value, false);
        }
        // Convenience lore callbacks run after the other fields, in their original line order.
        compiled.sort(
                Comparator.comparing(
                        field -> !field.explicit && field.type == DataComponents.LORE));
        fields = List.copyOf(compiled);
        dynamic = fields.stream().anyMatch(Field::dynamic);
    }

    private static DataComponentType<?> component(String id) {
        DataComponentType<?> type =
                BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse("minecraft:" + id));
        if (type == null) throw invalid(id);
        return type;
    }

    private static void add(
            List<Field> fields,
            Set<DataComponentType<?>> claimed,
            String name,
            DataComponentType<?> type,
            Object value,
            boolean explicit) {
        if (!claimed.add(type)) throw invalid("duplicate component " + name);
        boolean dynamic = dynamic(value);
        Field field = new Field(name, type, value, explicit, dynamic, null);
        if (!dynamic) {
            try {
                field =
                        new Field(
                                name,
                                type,
                                value,
                                explicit,
                                false,
                                encode(type, render(field, value)));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "client_bound_data: " + name + ": " + error.getMessage(), error);
            }
        }
        fields.add(field);
    }

    private static boolean dynamic(Object value) {
        if (value instanceof String text) return text.indexOf('<') >= 0 || text.indexOf('%') >= 0;
        if (value instanceof List<?> list)
            return list.stream().anyMatch(NiDisplayTemplate::dynamic);
        if (value instanceof Map<?, ?> map)
            return map.values().stream().anyMatch(NiDisplayTemplate::dynamic);
        return false;
    }

    public boolean dynamic() {
        return dynamic;
    }

    public void apply(ItemStack target, UnaryOperator<String> resolve) {
        List<Prepared> prepared = new ArrayList<>();
        for (Field field : fields) {
            // Resolver failures belong to the caller; only codec failures gain field context.
            Object resolved = field.dynamic ? resolveField(field, resolve) : null;
            try {
                Tag encoded =
                        field.dynamic
                                ? encode(field.type, render(field, resolved))
                                : field.constant;
                prepared.add(new Prepared(field.type, decode(field.type, encoded)));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "client_bound_data: " + field.name + ": " + error.getMessage(), error);
            }
        }
        for (Prepared value : prepared) set(target, value.type, value.value);
    }

    private static Object resolveField(Field field, UnaryOperator<String> parse) {
        if (!field.explicit && field.type == DataComponents.LORE) {
            return ((List<?>) field.source)
                    .stream()
                            .map(line -> dynamic(line) ? parse.apply((String) line) : line)
                            .toList();
        }
        return resolve(field.source, parse);
    }

    private static Object resolve(Object value, UnaryOperator<String> parse) {
        if (value instanceof String text) return parse.apply(text);
        if (value instanceof List<?> list)
            return list.stream().map(child -> resolve(child, parse)).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), resolve(child, parse)));
            return result;
        }
        return value;
    }

    private static Object render(Field field, Object value) {
        if (value == null) return null;
        ItemStack carrier = new ItemStack(Items.STONE);
        if (field.explicit)
            NmsItems.applyComponents(
                    carrier, Collections.singletonMap(field.name, NiTagValues.decode(value)));
        else
            carrier =
                    new NiItemAppearance(
                                    NiConfig.mapReader(Collections.singletonMap(field.name, value)),
                                    message -> {
                                        throw invalid(message);
                                    })
                            .apply(carrier);
        return carrier.get(field.type);
    }

    @SuppressWarnings("unchecked")
    private static <T> Tag encode(DataComponentType<T> type, Object value) {
        return value == null
                ? null
                : type.codecOrThrow()
                        .encodeStart(
                                CraftRegistry.getMinecraftRegistry()
                                        .createSerializationContext(NbtOps.INSTANCE),
                                (T) value)
                        .getOrThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T decode(DataComponentType<T> type, Tag value) {
        if (value == null) return null;
        T decoded =
                type.codecOrThrow()
                        .parse(
                                CraftRegistry.getMinecraftRegistry()
                                        .createSerializationContext(NbtOps.INSTANCE),
                                value.copy())
                        .getOrThrow();
        // Codec decoding detaches nested payloads; fresh roots also retain mutable text operations.
        if (decoded instanceof Component text) return (T) text.copy();
        if (decoded instanceof ItemLore lore)
            return (T)
                    new ItemLore(
                            lore.lines().stream().map(line -> (Component) line.copy()).toList(),
                            lore.styledLines().stream()
                                    .map(line -> (Component) line.copy())
                                    .toList());
        return decoded;
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(ItemStack target, DataComponentType<T> type, Object value) {
        if (value == null) target.remove(type);
        else target.set(type, (T) value);
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("client_bound_data: " + field);
    }
}

package dev.itemloom.paper.compat;

import com.mojang.serialization.DynamicOps;
import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiTagValues;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/**
 * A detached NI definition for one item's present state, without its quantity or generator rules.
 * Literal values live in NI's static section: saving text must not execute embedded item nodes.
 * This class does not write files or register definitions. The caller owns safe access to the source.
 */
public final class NiItemSnapshot {
    private NiItemSnapshot() {}

    /** Returns null for an empty item. An unrepresentable persistent component fails explicitly. */
    public static NiConfig capture(ItemStack source) {
        if (source == null || source.isEmpty()) return null;
        var item = CraftItemStack.asNMSCopy(source);
        DynamicOps<Tag> ops =
                CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        Map<String, Object> definition = new LinkedHashMap<>();
        Map<String, Object> fixed = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();
        definition.put("material", source.getType().name());
        // Apply removals against this item's defaults. A STONE prototype would discard a
        // removal such as max_damage before the root material changes it into a sword.
        fixed.put("material", source.getType().name());
        // The static prototype must not manufacture an empty legacy identity before final
        // generation.
        fixed.put("options", Map.of("remove-nbt", true));

        var custom = item.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            CompoundTag data = custom.copyTag();
            var modern = new ItemStateCodec().read(data);
            var legacy = new NiItemMigration().read(data);
            if (modern.isPresent() && legacy.isPresent()) {
                throw new IllegalArgumentException(
                        "Cannot snapshot two item identities; migrate or resolve the source first");
            }
            // NI's saved nbt overrides its newly generated identity. Keep that observable contract,
            // while allowing the normal recipe migration to remove an old NeigeItems envelope.
            if (modern.isPresent() || legacy.isPresent())
                definition.put("options", Map.of("remove-nbt", true));
            Object typed = typedCandidate(data);
            if (typed != null) {
                if (!data.isEmpty()) fixed.put("nbt", typed);
                else components.put("minecraft:custom_data", Map.of());
            } else {
                // The NI typed-list convention conflates numeric lists and arrays, and cannot
                // express empty arrays. 26.2's actual custom_data codec also accepts native SNBT.
                components.put(
                        "minecraft:custom_data",
                        componentValue(DataComponents.CUSTOM_DATA, custom, ops));
            }
        }

        for (var entry : item.getComponentsPatch().entrySet()) {
            DataComponentType<?> type = entry.getKey();
            var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (key == null)
                throw new IllegalArgumentException(
                        "Cannot snapshot an unregistered item component");
            String id = key.toString();
            if (entry.getValue().isEmpty()) {
                // Final NI generation owns custom_data and attaches the resulting item identity.
                if (type != DataComponents.CUSTOM_DATA) components.put("!" + id, Map.of());
                continue;
            }
            if (type == DataComponents.CUSTOM_DATA) continue;
            if (type.isTransient())
                throw new IllegalArgumentException("Cannot persist transient component " + id);
            Object value = entry.getValue().orElseThrow();
            if (type == DataComponents.CUSTOM_NAME) {
                var name = item.get(DataComponents.CUSTOM_NAME);
                String text = MiniMessage.miniMessage().serialize(PaperAdventure.asAdventure(name));
                fixed.put("mini-name", text);
                var restored =
                        PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(text));
                if (encode(type, value, ops).equals(encode(type, restored, ops))) continue;
            } else if (type == DataComponents.LORE) {
                ItemLore lore = item.get(DataComponents.LORE);
                List<String> text =
                        lore.lines().stream()
                                .map(
                                        line ->
                                                MiniMessage.miniMessage()
                                                        .serialize(
                                                                PaperAdventure.asAdventure(line)))
                                .toList();
                fixed.put("mini-lore", text);
                List<net.minecraft.network.chat.Component> restored = new ArrayList<>();
                for (String line : text)
                    for (String part : line.split("\n")) {
                        restored.add(
                                PaperAdventure.asVanilla(
                                        MiniMessage.miniMessage().deserialize(part)));
                    }
                // NI ignores empty mini-lore; preserve an explicitly present empty component too.
                if (!restored.isEmpty()
                        && encode(type, value, ops)
                                .equals(encode(type, new ItemLore(restored), ops))) continue;
            }
            components.put(id, componentValue(type, value, ops));
        }
        if (!components.isEmpty()) fixed.put("components", components);
        definition.put("static", fixed);
        return new NiConfig(definition);
    }

    private static Object componentValue(
            DataComponentType<?> type, Object value, DynamicOps<Tag> ops) {
        Tag original = encode(type, value, ops);
        try {
            Object candidate = yamlValue(typed(LegacyNbt.toValue(original)));
            if (equivalent(type, original, NmsItems.tag(NiTagValues.decode(candidate)), ops))
                return candidate;
        } catch (RuntimeException ignored) {
            // Typed YAML cannot encode every native NBT list/array shape. Try the actual codec's
            // SNBT route.
        }
        String snbt = original.toString();
        if (equivalent(type, original, StringTag.valueOf(snbt), ops)) return snbt;
        throw new IllegalArgumentException(
                "NI configuration cannot represent component losslessly: "
                        + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
    }

    private static <T> boolean equivalent(
            DataComponentType<T> type, Tag original, Tag candidate, DynamicOps<Tag> ops) {
        var decoded = type.codecOrThrow().parse(ops, candidate).result();
        return decoded.isPresent()
                && original.equals(
                        type.codecOrThrow().encodeStart(ops, decoded.get()).getOrThrow());
    }

    @SuppressWarnings("unchecked")
    private static <T> Tag encode(DataComponentType<T> type, Object value, DynamicOps<Tag> ops) {
        return type.codecOrThrow().encodeStart(ops, (T) value).getOrThrow();
    }

    /** Reuse the existing NBT-to-value conversion; only NI's typed YAML notation belongs here. */
    private static Object typed(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), typed(child)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(NiItemSnapshot::typed).toList();
        if (value instanceof Number number) {
            String type = number instanceof Integer ? "Int" : number.getClass().getSimpleName();
            return "(" + type + ") " + number;
        }
        if (value instanceof String text) {
            try {
                if (!text.equals(NiTagValues.decode(text))) return "(String) " + text;
            } catch (RuntimeException ignored) {
                return "(String) " + text;
            }
        }
        return value;
    }

    private static Object typedCandidate(Tag original) {
        try {
            Object candidate = yamlValue(typed(LegacyNbt.toValue(original)));
            return original.equals(NmsItems.tag(NiTagValues.decode(candidate))) ? candidate : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** A save result must survive the same Bukkit YAML boundary used to load saved definitions. */
    private static Object yamlValue(Object value) {
        return NiYaml.read(NiYaml.write(new NiConfig(Map.of("value", value))), "item snapshot")
                .get("value");
    }
}

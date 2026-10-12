package dev.itemloom.paper.compat;

import com.mojang.serialization.DynamicOps;
import dev.itemloom.compat.ni.*;
import dev.itemloom.paper.nms.*;
import java.util.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

public final class NiItemSnapshot {
    private NiItemSnapshot() {}

    public static NiConfig capture(ItemStack source) {
        if (source == null || source.isEmpty()) return null;
        var item = CraftItemStack.asNMSCopy(source);
        var custom = item.get(DataComponents.CUSTOM_DATA);
        CompoundTag data = custom == null ? new CompoundTag() : custom.copyTag();
        var modern = new ItemStateCodec().read(data);
        var legacy = new NiItemMigration().read(data);
        if (modern.isPresent() && legacy.isPresent())
            throw new IllegalArgumentException("Cannot save simultaneous native and NI identities");
        Map<String, Object> root = new LinkedHashMap<>(),
                fixed = new LinkedHashMap<>(),
                components = new LinkedHashMap<>();
        root.put("material", source.getType().name());
        root.put("static", fixed);
        fixed.put("material", source.getType().name());
        fixed.put("options", Map.of("remove-nbt", true));
        if (modern.isPresent() || legacy.isPresent())
            root.put("options", Map.of("remove-nbt", true));
        DynamicOps<Tag> ops =
                CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        for (var entry : item.getComponentsPatch().entrySet()) {
            DataComponentType<?> type = entry.getKey();
            var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (key == null || type.isTransient())
                throw new IllegalArgumentException(
                        "Cannot save unregistered or transient component: " + type);
            String id = key.toString();
            if (entry.getValue().isEmpty()) {
                if (type != DataComponents.CUSTOM_DATA) components.put("!" + id, Map.of());
                continue;
            }
            Tag encoded = encode(type, entry.getValue().get(), ops);
            if (type == DataComponents.CUSTOM_DATA && !data.isEmpty()) {
                Object candidate = typed(data);
                if (data.equals(yamlTag(candidate))) {
                    fixed.put("nbt", candidate);
                    continue;
                }
            }
            components.put(id, componentValue(type, encoded, ops));
        }
        if (!components.isEmpty()) fixed.put("components", components);
        return new NiConfig(root);
    }

    private static Object componentValue(
            DataComponentType<?> type, Tag encoded, DynamicOps<Tag> ops) {
        Object candidate = typed(encoded);
        if (equivalent(type, encoded, yamlTag(candidate), ops)) return candidate;
        String snbt = encoded.toString();
        if (equivalent(type, encoded, yamlTag(snbt), ops)) return snbt;
        throw new IllegalArgumentException(
                "Cannot losslessly save component: "
                        + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
    }

    private static Tag yamlTag(Object candidate) {
        try {
            NiConfig wrapper = new NiConfig(Collections.singletonMap("value", candidate));
            Object loaded = NiYaml.read(NiYaml.write(wrapper), "saved item roundtrip").get("value");
            return NmsItems.tag(NiTagValues.decode(loaded));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static <T> boolean equivalent(
            DataComponentType<T> type, Tag expected, Tag candidate, DynamicOps<Tag> ops) {
        if (candidate == null) return false;
        try {
            T decoded = type.codecOrThrow().parse(ops, candidate).getOrThrow();
            return expected.equals(type.codecOrThrow().encodeStart(ops, decoded).getOrThrow());
        } catch (RuntimeException error) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Tag encode(DataComponentType<T> type, Object value, DynamicOps<Tag> ops) {
        return type.codecOrThrow().encodeStart(ops, (T) value).getOrThrow();
    }

    private static Object typed(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            Map<String, Object> result = new LinkedHashMap<>();
            compound.forEach((key, value) -> result.put(key, typed(value)));
            return result;
        }
        if (tag instanceof StringTag text) return "(String) " + text.value();
        if (tag instanceof NumericTag number) {
            String name =
                    switch (tag.getId()) {
                        case Tag.TAG_BYTE -> "Byte";
                        case Tag.TAG_SHORT -> "Short";
                        case Tag.TAG_INT -> "Int";
                        case Tag.TAG_LONG -> "Long";
                        case Tag.TAG_FLOAT -> "Float";
                        case Tag.TAG_DOUBLE -> "Double";
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported numeric NBT: " + tag);
                    };
            return "(" + name + ") " + number.box();
        }
        List<Object> result = new ArrayList<>();
        if (tag instanceof ListTag list) {
            list.forEach(value -> result.add(typed(value)));
            return result;
        }
        if (tag instanceof ByteArrayTag bytes) {
            for (byte value : bytes.getAsByteArray()) result.add("(Byte) " + value);
            return result;
        }
        if (tag instanceof IntArrayTag ints) {
            for (int value : ints.getAsIntArray()) result.add("(Int) " + value);
            return result;
        }
        if (tag instanceof LongArrayTag longs) {
            for (long value : longs.getAsLongArray()) result.add("(Long) " + value);
            return result;
        }
        throw new IllegalArgumentException("Cannot save NBT value: " + tag);
    }
}

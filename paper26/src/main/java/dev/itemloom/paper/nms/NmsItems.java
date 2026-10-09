package dev.itemloom.paper.nms;

import com.mojang.serialization.DynamicOps;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Direct Paper 26.2 item access. No old-version dispatch, generated bridges, or source-format logic. */
public final class NmsItems {
    private NmsItems() {}

    /** Commit an already validated owned candidate, including component removals. */
    public static void replace(ItemStack target, ItemStack prepared) {
        var source = CraftItemStack.asNMSCopy(prepared);
        // A Craft mirror aliases the inventory's NMS stack. setType(AIR) detaches that
        // mirror, so clear the original count before detaching it.
        if (source.isEmpty()) {
            target.setAmount(0);
            target.setType(prepared.getType());
            return;
        }
        target.setType(prepared.getType());
        var handle = CraftItemStack.unwrap(target);
        handle.restorePatch(source.getComponentsPatch());
        handle.setCount(source.getCount());
    }

    public static CompoundTag customData(ItemStack item) {
        CustomData data = CraftItemStack.asNMSCopy(item).get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** Returns a new stack, retaining all components absent from the requested change. */
    public static ItemStack withCustomData(ItemStack item, CompoundTag data) {
        var copy = CraftItemStack.asNMSCopy(item);
        copy.set(DataComponents.CUSTOM_DATA, CustomData.of(data.copy()));
        return CraftItemStack.asCraftMirror(copy);
    }

    public static ItemStack withComponents(ItemStack item, Map<String, ?> components) {
        var copy = CraftItemStack.asNMSCopy(item);
        applyComponents(copy, components);
        return CraftItemStack.asCraftMirror(copy);
    }

    /** Mutates an owned construction copy; callers must never pass a live inventory handle. */
    public static void applyComponents(
            net.minecraft.world.item.ItemStack copy, Map<String, ?> components) {
        DynamicOps<Tag> ops =
                CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        for (Map.Entry<String, ?> entry : components.entrySet()) {
            String name = entry.getKey();
            boolean remove = name.startsWith("!");
            Identifier key = Identifier.tryParse(remove ? name.substring(1) : name);
            DataComponentType<?> type =
                    key == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(key);
            if (type == null) throw new IllegalArgumentException("Unknown item component: " + name);
            if (remove || entry.getValue() == null) copy.remove(type);
            else decode(copy, type, tag(entry.getValue()), ops);
        }
    }

    private static <T> void decode(
            net.minecraft.world.item.ItemStack item,
            DataComponentType<T> type,
            Tag value,
            DynamicOps<Tag> ops) {
        T decoded = type.codecOrThrow().parse(ops, value).getOrThrow();
        item.set(type, decoded);
    }

    /** Ordinary Java values only; input-specific numeric markers are decoded by the frontend. */
    public static Tag tag(Object value) {
        if (value instanceof Tag tag) return tag.copy();
        if (value instanceof Boolean bool) return ByteTag.valueOf(bool);
        if (value instanceof Byte number) return ByteTag.valueOf(number);
        if (value instanceof Short number) return ShortTag.valueOf(number);
        if (value instanceof Integer number) return IntTag.valueOf(number);
        if (value instanceof Long number) return LongTag.valueOf(number);
        if (value instanceof Float number) return FloatTag.valueOf(number);
        if (value instanceof Double number) return DoubleTag.valueOf(number);
        if (value instanceof byte[] array) return new ByteArrayTag(array.clone());
        if (value instanceof int[] array) return new IntArrayTag(array.clone());
        if (value instanceof long[] array) return new LongArrayTag(array.clone());
        if (value instanceof String text) return StringTag.valueOf(text);
        if (value instanceof Map<?, ?> map) {
            CompoundTag result = new CompoundTag();
            map.forEach(
                    (key, child) -> {
                        if (child != null) result.put(String.valueOf(key), tag(child));
                    });
            return result;
        }
        if (value instanceof List<?> list) {
            ListTag result = new ListTag();
            for (Object child : list) if (child != null) result.add(tag(child));
            return result;
        }
        throw new IllegalArgumentException(
                "Unsupported item value: " + (value == null ? "null" : value.getClass().getName()));
    }
}

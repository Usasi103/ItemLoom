package dev.itemloom.paper.nms;

import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Component-preserving conversions at the Bukkit/NMS boundary. */
public final class NmsItems {
    private NmsItems() {}

    public static void replace(ItemStack target, ItemStack prepared) {
        var replacement = CraftItemStack.asNMSCopy(prepared);
        if (replacement.isEmpty()) {
            target.setAmount(0);
            target.setType(prepared.getType());
            return;
        }
        target.setType(prepared.getType());
        var handle = CraftItemStack.unwrap(target);
        handle.restorePatch(replacement.getComponentsPatch());
        target.setAmount(replacement.getCount());
    }

    public static CompoundTag customData(ItemStack item) {
        if (item == null || item.isEmpty()) return new CompoundTag();
        var data = CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

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

    public static void applyComponents(
            net.minecraft.world.item.ItemStack item, Map<String, ?> values) {
        for (var entry : values.entrySet()) {
            String name = entry.getKey();
            boolean remove = name.startsWith("!");
            String key = remove ? name.substring(1) : name;
            Identifier id = Identifier.tryParse(key);
            DataComponentType<?> type =
                    id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
            if (type == null) throw new IllegalArgumentException("未知物品组件: " + key);
            if (remove || entry.getValue() == null) item.remove(type);
            else decode(item, type, tag(entry.getValue()));
        }
    }

    private static <T> void decode(
            net.minecraft.world.item.ItemStack item, DataComponentType<T> type, Tag value) {
        var codec = type.codec();
        if (codec == null) throw new IllegalArgumentException("组件不支持持久化: " + type);
        var ops = CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        T decoded = codec.parse(ops, value).getOrThrow(IllegalArgumentException::new);
        item.set(type, decoded);
    }

    public static Tag tag(Object value) {
        return switch (value) {
            case Tag existing -> existing.copy();
            case Byte n -> ByteTag.valueOf(n);
            case Short n -> ShortTag.valueOf(n);
            case Integer n -> IntTag.valueOf(n);
            case Long n -> LongTag.valueOf(n);
            case Float n -> FloatTag.valueOf(n);
            case Double n -> DoubleTag.valueOf(n);
            case Boolean flag -> ByteTag.valueOf(flag);
            case String text -> StringTag.valueOf(text);
            case byte[] bytes -> new ByteArrayTag(bytes.clone());
            case int[] integers -> new IntArrayTag(integers.clone());
            case long[] longs -> new LongArrayTag(longs.clone());
            case Map<?, ?> map -> {
                CompoundTag compound = new CompoundTag();
                map.forEach(
                        (key, child) -> {
                            if (child != null) compound.put(String.valueOf(key), tag(child));
                        });
                yield compound;
            }
            case List<?> values -> {
                ListTag list = new ListTag();
                for (Object child : values) if (child != null) list.add(tag(child));
                yield list;
            }
            case null -> throw new IllegalArgumentException("NBT 值不能为 null");
            default ->
                    throw new IllegalArgumentException(
                            "不支持的 NBT 值类型: " + value.getClass().getName());
        };
    }
}

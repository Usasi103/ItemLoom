package dev.itemloom.paper.runtime;

import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Only scalar state escapes the storage boundary. No borrowed mutable NBT is published. */
public final class ScalarItemState {
    public record State(
            String id,
            Integer updateHash,
            long expiresAt,
            Integer durability,
            Integer maximumDurability,
            boolean breaks) {}

    private static final ItemStateCodec CODEC = new ItemStateCodec();

    private ScalarItemState() {}

    public static State read(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        var data = CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag root = data.getUnsafe();
        Tag nativeState = root.get(ItemStateCodec.KEY);
        String id = CODEC.readId(item);
        if (id == null) {
            return null;
        }
        CompoundTag properties =
                nativeState == null
                        ? root.getCompoundOrEmpty("NeigeItems")
                        : ((CompoundTag) nativeState).getCompoundOrEmpty("properties");
        return new State(
                id,
                properties.getInt("hashCode").orElse(null),
                properties.getLong("itemTime").orElse(Long.MAX_VALUE),
                properties.getInt("durability").orElse(null),
                properties.getInt("maxDurability").orElse(null),
                properties.getBoolean("itemBreak").orElse(true));
    }

    public static ItemStack durability(ItemStack source, int remaining) {
        CompoundTag root = NmsItems.customData(source).copy();
        CompoundTag state = root.getCompound(ItemStateCodec.KEY).orElse(null);
        if (state != null) {
            CODEC.readId(source);
            CompoundTag replacement = state.copy();
            CompoundTag properties = replacement.getCompoundOrEmpty("properties").copy();
            properties.putInt("durability", remaining);
            replacement.put("properties", properties);
            root.put(ItemStateCodec.KEY, replacement);
        } else {
            CompoundTag properties = root.getCompoundOrEmpty("NeigeItems").copy();
            properties.putInt("durability", remaining);
            root.put("NeigeItems", properties);
        }
        return NmsItems.withCustomData(source, root);
    }
}

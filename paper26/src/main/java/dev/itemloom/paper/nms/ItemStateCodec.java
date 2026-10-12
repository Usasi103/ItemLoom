package dev.itemloom.paper.nms;

import dev.itemloom.core.ItemIdentity;
import java.util.LinkedHashMap;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Reads and writes the stable schema-1 ItemLoom envelope. */
public final class ItemStateCodec {
    public static final String KEY = "itemloom:items";

    public String readId(ItemStack item) {
        return item == null || item.isEmpty() ? null : readId(CraftItemStack.unwrap(item));
    }

    public String readId(net.minecraft.world.item.ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        var data = item.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag root = data.copyTag();
        if (root.contains(KEY)) return nativeId(envelope(root));
        if (root.get("NeigeItems") instanceof CompoundTag legacy
                && legacy.get("id") instanceof StringTag id) return id.value();
        return null;
    }

    public Optional<ItemIdentity> read(ItemStack item) {
        return read(NmsItems.customData(item));
    }

    public Optional<ItemIdentity> read(CompoundTag root) {
        if (!root.contains(KEY)) return Optional.empty();
        CompoundTag state = envelope(root);
        String id = nativeId(state);
        if (!(state.get("rolls") instanceof CompoundTag rolls)) throw invalid("rolls 必须为 compound");
        var values = new LinkedHashMap<String, String>();
        for (var entry : rolls.entrySet()) {
            if (!(entry.getValue() instanceof StringTag text)) throw invalid("rolls 值必须为 string");
            values.put(entry.getKey(), text.value());
        }
        if (state.contains("null_rolls")) {
            if (!(state.get("null_rolls") instanceof ListTag nulls))
                throw invalid("null_rolls 必须为 list");
            for (Tag value : nulls) {
                if (!(value instanceof StringTag text)) throw invalid("null_rolls 值必须为 string");
                if (values.containsKey(text.value())) throw invalid("重复的 roll: " + text.value());
                values.put(text.value(), null);
            }
        }
        return Optional.of(new ItemIdentity(id, values));
    }

    public CompoundTag encode(ItemIdentity identity, CompoundTag properties) {
        CompoundTag state = new CompoundTag();
        state.putInt("schema", 1);
        state.putString("id", identity.id());
        CompoundTag rolls = new CompoundTag();
        ListTag nulls = new ListTag();
        identity.rolls()
                .forEach(
                        (key, value) -> {
                            if (value == null) nulls.add(StringTag.valueOf(key));
                            else rolls.putString(key, value);
                        });
        state.put("rolls", rolls);
        if (!nulls.isEmpty()) state.put("null_rolls", nulls);
        state.put("properties", properties.copy());
        return state;
    }

    public ItemStack write(ItemStack item, ItemIdentity identity, CompoundTag properties) {
        CompoundTag data = NmsItems.customData(item);
        data.put(KEY, encode(identity, properties));
        return NmsItems.withCustomData(item, data);
    }

    public CompoundTag properties(ItemStack item) {
        CompoundTag root = NmsItems.customData(item);
        if (root.get(KEY) instanceof CompoundTag state
                && state.get("properties") instanceof CompoundTag values) return values.copy();
        return new CompoundTag();
    }

    private static CompoundTag envelope(CompoundTag root) {
        if (root.get(KEY) instanceof CompoundTag state) return state;
        throw invalid("标识必须为 compound");
    }

    private static String nativeId(CompoundTag state) {
        if (!(state.get("schema") instanceof IntTag schema) || schema.value() != 1)
            throw invalid("不支持的 schema");
        if (!(state.get("id") instanceof StringTag id) || id.value().isBlank())
            throw invalid("id 必须为非空 string");
        return id.value();
    }

    private static IllegalArgumentException invalid(String problem) {
        return new IllegalArgumentException("损坏的 ItemLoom 物品标识: " + problem);
    }
}

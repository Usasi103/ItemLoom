package dev.itemloom.paper.compat.script;

import java.util.HashMap;
import java.util.Objects;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import org.bukkit.inventory.ItemStack;

/** Per-invocation legacy view; the underlying stack keeps ItemLoom' storage protocol. */
public final class LegacyItemInfo
        implements dev.itemloom.compat.ni.script.LegacySectionUtils.ItemView {
    private final ItemStack item;
    private final LegacyNbtItemStack nbtItem;
    private final LegacyNbt.Compound tag, properties;
    private final LegacyNbt dataTag;
    private final String id;
    private HashMap<String, String> data;

    public LegacyItemInfo(
            ItemStack item,
            LegacyNbtItemStack nbtItem,
            LegacyNbt.Compound tag,
            LegacyNbt.Compound properties,
            String id,
            HashMap<String, String> data) {
        this.item = Objects.requireNonNull(item);
        this.nbtItem = Objects.requireNonNull(nbtItem);
        this.tag = Objects.requireNonNull(tag);
        this.properties = Objects.requireNonNull(properties);
        this.id = Objects.requireNonNull(id);
        this.data = data;
        dataTag = properties.get("data");
    }

    public static LegacyItemInfo inspect(ItemStack item) {
        var identity = NiItemNodes.identity(item);
        if (identity == null) return null;
        var wrapper = new LegacyNbtItemStack(item);
        var tag = wrapper.getOrCreateTag();
        return new LegacyItemInfo(
                item,
                wrapper,
                tag,
                tag.getCompound("NeigeItems"),
                identity.id(),
                new HashMap<>(identity.rolls()));
    }

    public ItemStack getItemStack() {
        return item;
    }

    public LegacyNbtItemStack getNbtItemStack() {
        return nbtItem;
    }

    public LegacyNbt.Compound getItemTag() {
        return tag;
    }

    public LegacyNbt.Compound getNeigeItems() {
        return properties;
    }

    public String getId() {
        return id;
    }

    public synchronized HashMap<String, String> getData() {
        if (data == null) {
            data = new HashMap<>();
            if (dataTag instanceof LegacyNbt.Compound compound) flatten(compound, "", data);
            else if (dataTag != null) {
                com.google.gson.JsonParser.parseString(dataTag.getAsString())
                        .getAsJsonObject()
                        .entrySet()
                        .forEach(
                                entry ->
                                        data.put(
                                                entry.getKey(),
                                                entry.getValue().isJsonNull()
                                                        ? null
                                                        : entry.getValue().getAsString()));
            }
        }
        return data;
    }

    private static void flatten(
            LegacyNbt.Compound tag, String prefix, HashMap<String, String> values) {
        tag.forEach(
                (key, value) -> {
                    if (value instanceof LegacyNbt.Compound child)
                        flatten(child, prefix + key + '.', values);
                    else values.put(prefix + key, value.getAsString());
                });
    }

    public String getDataValue(String key) {
        return getDataValue(key, null);
    }

    public String getDataValue(String key, String fallback) {
        return dataTag instanceof LegacyNbt.Compound compound
                ? compound.getDeepString(key, fallback)
                : getData().getOrDefault(key, fallback);
    }
}

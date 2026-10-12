package dev.itemloom.paper.compat.script;

import com.google.gson.*;
import dev.itemloom.compat.ni.script.LegacySectionUtils;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.nbt.*;
import java.util.*;
import org.bukkit.inventory.ItemStack;

public final class LegacyItemInfo implements LegacySectionUtils.ItemView {
    private final ItemStack item;
    private final LegacyNbtItemStack nbt;
    private final LegacyNbt.Compound tag, properties;
    private final String id;
    private final LegacyNbt dataTag;
    private HashMap<String, String> data;

    public LegacyItemInfo(
            ItemStack item,
            LegacyNbtItemStack nbtItem,
            LegacyNbt.Compound tag,
            LegacyNbt.Compound properties,
            String id,
            HashMap<String, String> data) {
        this.item = Objects.requireNonNull(item);
        nbt = Objects.requireNonNull(nbtItem);
        this.tag = Objects.requireNonNull(tag);
        this.properties = Objects.requireNonNull(properties);
        this.id = Objects.requireNonNull(id);
        this.data = data;
        dataTag = properties.get("data");
    }

    public static LegacyItemInfo inspect(ItemStack item) {
        ItemIdentity identity = NiItemNodes.identity(item);
        if (identity == null) return null;
        LegacyNbtItemStack nbt = new LegacyNbtItemStack(item);
        LegacyNbt.Compound tag = nbt.getOrCreateTag();
        return new LegacyItemInfo(
                item,
                nbt,
                tag,
                tag.getCompound("NeigeItems"),
                identity.id(),
                new HashMap<>(identity.rolls()));
    }

    public ItemStack getItemStack() {
        return item;
    }

    public LegacyNbtItemStack getNbtItemStack() {
        return nbt;
    }

    @Override
    public LegacyNbt.Compound getItemTag() {
        return tag;
    }

    public LegacyNbt.Compound getNeigeItems() {
        return properties;
    }

    public String getId() {
        return id;
    }

    @Override
    public synchronized HashMap<String, String> getData() {
        if (data == null) {
            HashMap<String, String> parsed = new HashMap<>();
            if (dataTag instanceof LegacyNbt.Compound compound) flatten(compound, "", parsed);
            else if (dataTag != null)
                JsonParser.parseString(dataTag.getAsString())
                        .getAsJsonObject()
                        .entrySet()
                        .forEach(
                                entry ->
                                        parsed.put(
                                                entry.getKey(),
                                                entry.getValue().isJsonNull()
                                                        ? null
                                                        : entry.getValue().getAsString()));
            data = parsed;
        }
        return data;
    }

    private static void flatten(
            LegacyNbt.Compound compound, String prefix, Map<String, String> result) {
        compound.forEach(
                (key, value) -> {
                    String path = prefix + key;
                    if (value instanceof LegacyNbt.Compound child)
                        flatten(child, path + ".", result);
                    else result.put(path, value.getAsString());
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

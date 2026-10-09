package dev.itemloom.paper.nms;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import dev.itemloom.core.ItemIdentity;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Own versioned custom-data namespace; only this class interprets the storage envelope. */
public final class ItemStateCodec {
    public static final String KEY = "itemloom:items";
    private static final int SCHEMA = 1;

    /**
     * Scalar identity query for either stored format. Saved rolls are deliberately outside
     * this contract; use read for operations that need a validated complete identity.
     * Only the String escapes: the borrowed custom-data tree is never copied or mutated.
     */
    public String readId(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        return readId(CraftItemStack.unwrap(item));
    }

    /** Read-only network eligibility query; it never consults Bukkit or a live inventory. */
    public String readId(net.minecraft.world.item.ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        var data = item.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag custom = data.getUnsafe();
        Tag raw = custom.get(KEY);
        if (raw != null) return stateId(raw);
        // A legacy ID query has always tolerated absent or malformed saved data.
        CompoundTag legacy = custom.getCompound("NeigeItems").orElse(null);
        return legacy == null ? null : legacy.getString("id").orElse(null);
    }

    public Optional<ItemIdentity> read(ItemStack item) {
        return read(customDataView(item));
    }

    public Optional<ItemIdentity> read(CompoundTag customData) {
        Tag raw = customData.get(KEY);
        if (raw == null) return Optional.empty();
        String id = stateId(raw);
        CompoundTag state = (CompoundTag) raw;
        CompoundTag rolls =
                state.getCompound("rolls")
                        .orElseThrow(() -> new IllegalArgumentException("Item state has no rolls"));
        Map<String, String> values = new LinkedHashMap<>();
        for (var entry : rolls.entrySet()) {
            String value =
                    entry.getValue()
                            .asString()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Non-string roll: " + entry.getKey()));
            values.put(entry.getKey(), value);
        }
        Tag nulls = state.get("null_rolls");
        if (nulls != null) {
            if (!(nulls instanceof ListTag list))
                throw new IllegalArgumentException("Malformed null roll list");
            for (Tag value : list) {
                String key =
                        value.asString()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Non-string null roll key"));
                if (values.containsKey(key))
                    throw new IllegalArgumentException("Conflicting roll: " + key);
                values.put(key, null);
            }
        }
        return Optional.of(new ItemIdentity(id, values));
    }

    private static String stateId(Tag raw) {
        if (!(raw instanceof CompoundTag state))
            throw new IllegalArgumentException("Malformed item state envelope");
        int schema =
                state.getInt("schema")
                        .orElseThrow(
                                () -> new IllegalArgumentException("Item state has no schema"));
        if (schema != SCHEMA)
            throw new IllegalArgumentException("Unsupported item state schema: " + schema);
        String id =
                state.getString("id")
                        .orElseThrow(() -> new IllegalArgumentException("Item state has no id"));
        if (id.isBlank()) throw new IllegalArgumentException("Item id is required");
        return id;
    }

    public CompoundTag encode(ItemIdentity identity, CompoundTag properties) {
        CompoundTag state = new CompoundTag(), rolls = new CompoundTag();
        ListTag nulls = new ListTag();
        identity.rolls()
                .forEach(
                        (key, value) -> {
                            if (value == null) nulls.add(StringTag.valueOf(key));
                            else rolls.putString(key, value);
                        });
        state.putInt("schema", SCHEMA);
        state.putString("id", identity.id());
        state.put("rolls", rolls);
        if (!nulls.isEmpty()) state.put("null_rolls", nulls);
        state.put("properties", properties.copy());
        return state;
    }

    public ItemStack write(ItemStack source, ItemIdentity identity, CompoundTag properties) {
        CompoundTag custom = NmsItems.customData(source);
        custom.put(KEY, encode(identity, properties));
        return NmsItems.withCustomData(source, custom);
    }

    public CompoundTag properties(ItemStack source) {
        return customDataView(source)
                .getCompoundOrEmpty(KEY)
                .getCompoundOrEmpty("properties")
                .copy();
    }

    /** The borrowed tree stays internal; reads return independent identities or a copied subtree. */
    private static CompoundTag customDataView(ItemStack item) {
        if (item == null) return new CompoundTag();
        var data = CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.getUnsafe();
    }
}

package dev.itemloom.paper.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.inventory.ItemStack;

/** Removable old-data reader. Migration never asks an item generator to rebuild the stack. */
public final class NiItemMigration {
    private static final String OLD_KEY = "NeigeItems";
    private final ItemStateCodec codec = new ItemStateCodec();

    public Optional<ItemIdentity> identify(ItemStack item) {
        if (item == null) return Optional.empty();
        var data =
                org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(item)
                        .get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data == null ? Optional.empty() : read(data.getUnsafe());
    }

    public Optional<ItemIdentity> read(CompoundTag custom) {
        Tag raw = custom.get(OLD_KEY);
        if (raw == null) return Optional.empty();
        if (!(raw instanceof CompoundTag old))
            throw new IllegalArgumentException("Malformed legacy item metadata");
        String id = old.getString("id").orElse(null);
        if (id == null) return Optional.empty();
        Map<String, String> values = new LinkedHashMap<>();
        Tag data = old.get("data");
        if (data instanceof CompoundTag compound) flatten(compound, "", values);
        else if (data instanceof StringTag string) {
            JsonElement json = JsonParser.parseString(string.value());
            if (!json.isJsonObject())
                throw new IllegalArgumentException("Legacy item data is not an object");
            json.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry -> {
                                JsonElement value = entry.getValue();
                                if (value.isJsonNull()) values.put(entry.getKey(), null);
                                else if (value.isJsonPrimitive())
                                    values.put(entry.getKey(), value.getAsString());
                                else
                                    throw new IllegalArgumentException(
                                            "Legacy roll is not scalar: " + entry.getKey());
                            });
        } else if (data != null)
            throw new IllegalArgumentException("Unsupported legacy item data type");
        return Optional.of(new ItemIdentity(id, values));
    }

    private static void flatten(CompoundTag compound, String path, Map<String, String> values) {
        for (var entry : compound.entrySet()) {
            String key = path + entry.getKey();
            Tag value = entry.getValue();
            if (value instanceof CompoundTag child) flatten(child, key + ".", values);
            else
                values.put(
                        key,
                        value.asString()
                                .orElseGet(
                                        () ->
                                                value.asNumber()
                                                        .map(Object::toString)
                                                        .orElseGet(value::toString)));
        }
    }

    public CompoundTag convert(CompoundTag custom) {
        Optional<ItemIdentity> existing = codec.read(custom);
        Optional<ItemIdentity> old = read(custom);
        if (old.isEmpty()) return custom.copy();
        if (existing.isPresent()) {
            // Never discard an old record when two independently written identities coexist.
            if (!existing.get().equals(old.get()))
                throw new IllegalArgumentException("Conflicting modern and legacy item identity");
            return custom.copy();
        }
        CompoundTag previous = custom.getCompoundOrEmpty(OLD_KEY);
        CompoundTag properties = previous.copy();
        properties.remove("id");
        properties.remove("data");
        CompoundTag result = custom.copy();
        CompoundTag state = codec.encode(old.get(), properties);
        // Rare older saves use a compound for data. Keep its exact key shape for scripts
        // reading NeigeItems.data.*; the independent identity still uses flat saved rolls.
        if (previous.get("data") instanceof CompoundTag compound)
            state.put("compat_ni_rolls", compound.copy());
        result.put(ItemStateCodec.KEY, state);
        result.remove(OLD_KEY);
        return result;
    }

    public ItemStack convert(ItemStack item) {
        CompoundTag original = NmsItems.customData(item);
        CompoundTag converted = convert(original);
        // An unchanged item must retain absent custom_data as absent: adding an empty
        // component changes the stack's serialized form and stacking identity.
        return converted.equals(original) ? item.clone() : NmsItems.withCustomData(item, converted);
    }
}

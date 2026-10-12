package dev.itemloom.paper.compat;

import com.google.gson.*;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.bukkit.inventory.ItemStack;

public final class NiItemMigration {
    private final ItemStateCodec codec = new ItemStateCodec();

    public Optional<ItemIdentity> identify(ItemStack item) {
        return read(NmsItems.customData(item));
    }

    public Optional<ItemIdentity> read(CompoundTag custom) {
        Tag raw = custom.get("NeigeItems");
        if (raw == null) return Optional.empty();
        if (!(raw instanceof CompoundTag legacy))
            throw new IllegalArgumentException("Malformed NeigeItems envelope");
        String id = legacy.getString("id").orElse(null);
        if (id == null) return Optional.empty();
        Map<String, String> rolls = new LinkedHashMap<>();
        Tag data = legacy.get("data");
        if (data instanceof CompoundTag compound) flatten(compound, "", rolls);
        else if (data instanceof StringTag text) {
            JsonElement parsed = JsonParser.parseString(text.asString().orElseThrow());
            if (!parsed.isJsonObject())
                throw new IllegalArgumentException("NI data must be a JSON object");
            parsed.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry -> {
                                JsonElement value = entry.getValue();
                                if (!value.isJsonNull() && !value.isJsonPrimitive())
                                    throw new IllegalArgumentException(
                                            "Non-scalar NI roll: " + entry.getKey());
                                rolls.put(
                                        entry.getKey(),
                                        value.isJsonNull() ? null : value.getAsString());
                            });
        } else if (data != null) throw new IllegalArgumentException("Malformed NI data");
        return Optional.of(new ItemIdentity(id, rolls));
    }

    private static void flatten(CompoundTag source, String prefix, Map<String, String> result) {
        for (var entry : source.entrySet()) {
            String path = prefix + entry.getKey();
            Tag value = entry.getValue();
            if (value instanceof CompoundTag nested) flatten(nested, path + ".", result);
            else
                result.put(
                        path,
                        value instanceof NumericTag numeric
                                ? numeric.box().toString()
                                : value.asString().orElseGet(value::toString));
        }
    }

    public CompoundTag convert(CompoundTag source) {
        Optional<ItemIdentity> modern = codec.read(source);
        Optional<ItemIdentity> legacy = read(source);
        CompoundTag result = source.copy();
        if (legacy.isEmpty()) return result;
        if (modern.isPresent()) {
            if (!modern.get().equals(legacy.get()))
                throw new IllegalArgumentException("Conflicting native and NI item identities");
            return result;
        }
        CompoundTag properties = source.getCompoundOrEmpty("NeigeItems").copy();
        Tag data = properties.remove("data");
        properties.remove("id");
        result.remove("NeigeItems");
        CompoundTag state = codec.encode(legacy.get(), properties);
        if (data instanceof CompoundTag) state.put("compat_ni_rolls", data.copy());
        result.put(ItemStateCodec.KEY, state);
        return result;
    }

    public ItemStack convert(ItemStack item) {
        CompoundTag original = NmsItems.customData(item);
        CompoundTag converted = convert(original);
        return converted.equals(original) ? item.clone() : NmsItems.withCustomData(item, converted);
    }
}

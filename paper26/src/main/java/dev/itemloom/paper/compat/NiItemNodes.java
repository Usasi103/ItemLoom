package dev.itemloom.paper.compat;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.compat.ni.action.NiValues;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/** Projects independent item state into the old node vocabulary without modifying the stack. */
public final class NiItemNodes {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final NiItemMigration MIGRATION = new NiItemMigration();

    private NiItemNodes() {}

    @SuppressWarnings("deprecation")
    public static String value(
            String type,
            String parameters,
            NiActionContext context,
            Function<ItemStack, String> name) {
        if (context == null) return null;
        ItemStack item = context.getItemStack();
        // A scalar node must not decode saved rolls or materialize ItemMeta/lore.
        if (type.equals("item_id")) return itemId(item);
        List<String> args = NiTemplate.arguments(parameters, 2);
        String fallback = args.size() > 1 ? args.get(1) : "";
        if (type.equals("data")) {
            Map<String, String> data;
            if (context.has(NiContextKeys.DATA)) data = context.getData();
            else {
                ItemIdentity identity = identity(item);
                data = identity == null ? null : identity.rolls();
            }
            return data == null
                    ? null
                    : data.getOrDefault(args.getFirst(), args.size() > 1 ? fallback : null);
        }
        if (type.equals("nbt")) {
            if (context.has(NiContextKeys.NBT)) {
                Object nbt = context.getNbt();
                if (nbt == null) return fallback;
                if (nbt instanceof CompoundTag tag)
                    return deepString(tag, args.getFirst(), fallback);
                if (nbt instanceof dev.itemloom.paper.compat.nbt.LegacyNbt.Compound tag)
                    return deepString((CompoundTag) tag.copyTag(), args.getFirst(), fallback);
                throw new IllegalArgumentException(
                        "Unsupported script NBT view: " + nbt.getClass().getName());
            }
            return item == null ? null : deepString(legacyData(item), args.getFirst(), fallback);
        }
        if (item == null)
            return type.equals("whole_lore") ? "" : type.equals("lore") ? fallback : null;
        if (type.equals("amount")) return Integer.toString(item.getAmount());
        if (type.equals("type")) return item.getType().toString();
        if (type.equals("name")) return name.apply(item);
        var meta = item.getItemMeta();
        if (type.equals("damage"))
            return Integer.toString(meta instanceof Damageable damage ? damage.getDamage() : 0);
        List<String> lore = meta == null || !meta.hasLore() ? List.of() : meta.getLore();
        return switch (type) {
            case "lore_size" -> Integer.toString(lore.size());
            case "whole_lore" -> String.join("\n", lore);
            case "lore" -> {
                Integer index = NiValues.strictInteger(args.getFirst());
                if (index == null) yield null;
                yield index < 0 || index >= lore.size() ? fallback : lore.get(index);
            }
            default -> null;
        };
    }

    public static String itemId(ItemStack item) {
        return CODEC.readId(item);
    }

    public static ItemIdentity identity(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        return CODEC.read(item).or(() -> MIGRATION.identify(item)).orElse(null);
    }

    /** Same validation and scalar conversion as the legacy live view, without constructing it. */
    public static Integer legacyInteger(ItemStack item, String key) {
        CompoundTag properties = propertiesForReading(item);
        Tag value = properties == null ? null : properties.get(key);
        return value instanceof NumericTag number ? number.intValue() : null;
    }

    /** null means absent custom_data; an absent/wrongly typed field uses the legacy fallback. */
    public static Boolean legacyBoolean(ItemStack item, String key, boolean fallback) {
        CompoundTag properties = propertiesForReading(item);
        if (properties == null) return null;
        Tag value = properties.get(key);
        return value instanceof NumericTag number ? number.byteValue() != 0 : fallback;
    }

    private static CompoundTag propertiesForReading(ItemStack item) {
        var data =
                org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(
                                java.util.Objects.requireNonNull(item, "item"))
                        .get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag custom = data.getUnsafe();
        var identity =
                CODEC.read(custom); // Corrupt rolls/schema still reject a scalar placeholder.
        if (identity.isPresent()) {
            if (custom.contains("NeigeItems")) {
                var old = MIGRATION.read(custom);
                if (old.isPresent() && !identity.get().equals(old.get()))
                    throw new IllegalArgumentException(
                            "Conflicting modern and legacy item identity");
            }
            return custom.getCompoundOrEmpty(ItemStateCodec.KEY).getCompoundOrEmpty("properties");
        }
        return custom.getCompoundOrEmpty("NeigeItems");
    }

    public static CompoundTag legacyData(ItemStack item) {
        CompoundTag custom = NmsItems.customData(item);
        var identity = CODEC.read(custom);
        if (identity.isEmpty()) return custom;
        CompoundTag state = custom.getCompoundOrEmpty(ItemStateCodec.KEY);
        CompoundTag old = state.getCompoundOrEmpty("properties").copy();
        old.putString("id", identity.get().id());
        JsonObject rolls = new JsonObject();
        identity.get()
                .rolls()
                .forEach(
                        (key, value) -> {
                            if (value == null) rolls.add(key, JsonNull.INSTANCE);
                            else rolls.addProperty(key, value);
                        });
        if (state.get("compat_ni_rolls") instanceof CompoundTag compound)
            old.put("data", compound.copy());
        else old.putString("data", rolls.toString());
        custom.remove(ItemStateCodec.KEY);
        custom.put("NeigeItems", old);
        return custom;
    }

    public static String deepString(CompoundTag root, String path, String fallback) {
        Tag value = root;
        for (String key : NiTemplate.split(path, '.', 0)) {
            if (!(value instanceof CompoundTag compound)) return fallback;
            value = compound.get(key);
        }
        if (value == null) return fallback;
        String text = value.asString().orElse(null);
        return text == null ? tagText(value) : text;
    }

    private static String tagText(Tag value) {
        return value.asNumber().map(Object::toString).orElseGet(value::toString);
    }
}

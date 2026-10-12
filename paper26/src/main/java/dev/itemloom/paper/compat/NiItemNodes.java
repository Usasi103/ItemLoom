package dev.itemloom.paper.compat;

import com.google.gson.JsonObject;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.compat.ni.action.*;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.nms.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

public final class NiItemNodes {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final NiItemMigration MIGRATION = new NiItemMigration();

    private NiItemNodes() {}

    public static String value(
            String type,
            String parameters,
            NiActionContext context,
            Function<ItemStack, String> name) {
        if (context == null) return null;
        ItemStack item = context.getItemStack();
        if (type.equals("item_id")) return CODEC.readId(item);
        List<String> args = NiTemplate.arguments(parameters, 2);
        String key = args.getFirst(), fallback = args.size() > 1 ? args.get(1) : "";
        if (type.equals("data")) {
            Map<String, String> data;
            if (context.has(NiContextKeys.DATA)) data = context.getData();
            else {
                ItemIdentity identity = identity(item);
                data = identity == null ? null : identity.rolls();
            }
            return data == null ? null : data.getOrDefault(key, args.size() > 1 ? fallback : null);
        }
        if (type.equals("nbt")) {
            if (!context.has(NiContextKeys.NBT) && item == null) return null;
            Object selected =
                    context.has(NiContextKeys.NBT)
                            ? context.getNbt()
                            : item == null ? null : legacyData(item);
            if (selected == null) return fallback;
            CompoundTag nbt;
            if (selected instanceof CompoundTag compound) nbt = compound;
            else if (selected instanceof LegacyNbt.Compound compound)
                nbt = (CompoundTag) compound.getHandle();
            else throw new IllegalArgumentException("NBT context must be a compound");
            return deepString(nbt, key, fallback);
        }
        if (item == null)
            return type.equals("whole_lore") ? "" : type.equals("lore") ? fallback : null;
        return switch (type) {
            case "amount" -> String.valueOf(item.getAmount());
            case "type" -> item.getType().name();
            case "name" -> name.apply(item);
            case "damage" ->
                    String.valueOf(
                            item.getItemMeta() instanceof Damageable damaged
                                    ? damaged.getDamage()
                                    : 0);
            case "lore_size" -> String.valueOf(lore(item).size());
            case "whole_lore" -> String.join("\n", lore(item));
            case "lore" -> {
                Integer index = NiValues.strictInteger(key);
                if (index == null) yield null;
                List<String> lore = lore(item);
                yield index < 0 || index >= lore.size() ? fallback : lore.get(index);
            }
            default -> null;
        };
    }

    private static List<String> lore(ItemStack item) {
        var meta = item.getItemMeta();
        List<String> value = meta == null ? null : meta.getLore();
        return value == null ? List.of() : value;
    }

    public static String itemId(ItemStack item) {
        return CODEC.readId(item);
    }

    public static ItemIdentity identity(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        return CODEC.read(item).or(() -> MIGRATION.identify(item)).orElse(null);
    }

    private static CompoundTag properties(ItemStack item) {
        var custom =
                CraftItemStack.unwrap(Objects.requireNonNull(item, "item"))
                        .get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag data = custom.copyTag();
        if (CODEC.read(data).isPresent()) {
            MIGRATION.convert(data);
            return data.getCompoundOrEmpty(ItemStateCodec.KEY).getCompoundOrEmpty("properties");
        }
        return data.getCompoundOrEmpty("NeigeItems");
    }

    public static Integer legacyInteger(ItemStack item, String key) {
        CompoundTag values = properties(item);
        Tag value = values == null ? null : values.get(key);
        return value instanceof NumericTag number ? number.intValue() : null;
    }

    public static Boolean legacyBoolean(ItemStack item, String key, boolean fallback) {
        CompoundTag values = properties(item);
        if (values == null) return null;
        return values.get(key) instanceof NumericTag number ? number.byteValue() != 0 : fallback;
    }

    public static CompoundTag legacyData(ItemStack item) {
        CompoundTag data = NmsItems.customData(item);
        Optional<ItemIdentity> modern = CODEC.read(data);
        if (modern.isEmpty()) return data;
        MIGRATION.convert(data);
        ItemIdentity identity = modern.get();
        CompoundTag state = data.getCompoundOrEmpty(ItemStateCodec.KEY);
        CompoundTag legacy = state.getCompoundOrEmpty("properties").copy();
        Tag originalRolls = state.get("compat_ni_rolls");
        legacy.putString("id", identity.id());
        if (originalRolls instanceof CompoundTag) legacy.put("data", originalRolls.copy());
        else {
            JsonObject rolls = new JsonObject();
            identity.rolls().forEach(rolls::addProperty);
            legacy.putString("data", rolls.toString());
        }
        data.remove(ItemStateCodec.KEY);
        data.put("NeigeItems", legacy);
        return data;
    }

    public static String deepString(CompoundTag data, String path, String fallback) {
        Tag value = data;
        for (String key : NiTemplate.split(path, '.', 0)) {
            if (!(value instanceof CompoundTag compound)) return fallback;
            value = compound.get(key);
            if (value == null) return fallback;
        }
        return value instanceof NumericTag number
                ? number.box().toString()
                : value.asString().orElseGet(value::toString);
    }
}

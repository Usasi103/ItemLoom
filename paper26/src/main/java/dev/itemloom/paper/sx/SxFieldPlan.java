package dev.itemloom.paper.sx;

import dev.itemloom.compat.sx.SxConfig;
import dev.itemloom.compat.sx.SxExpressions;
import dev.itemloom.paper.nms.NmsItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Executes documented field precedence against an owned item, never a provider or inventory object. */
@SuppressWarnings({"deprecation", "removal"})
final class SxFieldPlan {
    private final SxConfig config;

    SxFieldPlan(SxConfig config) {
        this.config = config;
    }

    ItemStack apply(
            ItemStack item,
            SxExpressions handler,
            boolean external,
            String inlineDamage,
            String id) {
        if (!external || present("Amount")) {
            int amount = Integer.parseInt(handler.replace(config.text("Amount", "1")));
            if (amount < 1) {
                throw new IllegalArgumentException("SX amount must be positive: " + id);
            }
            item.setAmount(amount);
        }
        ItemMeta meta = item.getItemMeta();
        String durability = inlineDamage == null ? config.text("Durability", null) : inlineDamage;
        if (meta instanceof Damageable damageable && durability != null) {
            String expanded = handler.replace(durability);
            if (!expanded.isEmpty()) {
                damageable.setDamage(damage(expanded, item.getType().getMaxDurability()));
            }
        }
        if (config.get("Name") != null) {
            meta.setDisplayName(colors(handler.replace(config.text("Name", null))));
        }
        if (!external || present("Lore")) {
            meta.setLore(
                    handler.replace(config.strings("Lore")).stream()
                            .map(SxFieldPlan::colors)
                            .toList());
        }
        for (String line : handler.replace(config.strings("EnchantList"))) {
            EnchantmentInput input = EnchantmentInput.parse(line);
            Enchantment enchantment = Enchantment.getByName(input.key().toUpperCase(Locale.ROOT));
            if (enchantment == null) {
                NamespacedKey key = NamespacedKey.fromString(input.key().toLowerCase(Locale.ROOT));
                enchantment = key == null ? null : Registry.ENCHANTMENT.get(key);
            }
            if (enchantment == null) {
                throw new IllegalArgumentException("Unknown SX enchantment: " + input.key());
            }
            if (input.level() != 0) {
                meta.addEnchant(enchantment, input.level(), true);
            }
        }
        boolean hideProfile = false;
        for (String flag : config.strings("ItemFlagList")) {
            if (flag.equals("HIDE_PROFILE")) {
                hideProfile = true;
            } else {
                meta.addItemFlags(
                        ItemFlag.valueOf(
                                flag.equals("HIDE_POTION_EFFECTS")
                                        ? "HIDE_ADDITIONAL_TOOLTIP"
                                        : flag));
            }
        }
        if (!external || present("Unbreakable")) {
            meta.setUnbreakable(config.bool("Unbreakable", false));
        }
        if (meta instanceof LeatherArmorMeta leather && present("Color")) {
            leather.setColor(
                    Color.fromRGB(
                            Integer.parseInt(handler.replace(config.text("Color", "FFFFFF")), 16)));
        }
        if (present("CustomModelData")) {
            meta.setCustomModelData(
                    Integer.valueOf(handler.replace(config.text("CustomModelData", null))));
        }
        if (meta instanceof PotionMeta potion) {
            potion(potion, handler);
        }
        boolean clearAttributes = config.bool("ClearAttribute", false);
        if (!clearAttributes) {
            attributes(meta, handler);
        }
        item.setItemMeta(meta);
        if (clearAttributes) {
            var owned = CraftItemStack.asNMSCopy(item);
            owned.remove(DataComponents.ATTRIBUTE_MODIFIERS);
            item = CraftItemStack.asCraftMirror(owned);
        }
        if (meta instanceof SkullMeta && config.get("SkullName") != null) {
            item =
                    NmsItems.withComponents(
                            item,
                            Map.of(
                                    "minecraft:profile",
                                    profileValue(handler.replace(config.text("SkullName", null)))));
        }
        if (present("Components")) {
            item =
                    NmsItems.withComponents(
                            item,
                            objectMap(
                                    handler.replace(
                                            (Object) config.section("Components").values())));
        }
        if (hideProfile) {
            var owned = CraftItemStack.asNMSCopy(item);
            TooltipDisplay previous = owned.get(DataComponents.TOOLTIP_DISPLAY);
            owned.set(
                    DataComponents.TOOLTIP_DISPLAY,
                    (previous == null ? TooltipDisplay.DEFAULT : previous)
                            .withHidden(DataComponents.PROFILE, true));
            item = CraftItemStack.asCraftMirror(owned);
        }
        if (present("NBT")) {
            CompoundTag supplied =
                    (CompoundTag)
                            NmsItems.tag(
                                    nbtValues(
                                            objectMap(
                                                    handler.replace(
                                                            (Object)
                                                                    config.section("NBT")
                                                                            .values()))));
            CompoundTag custom = NmsItems.customData(item);
            if (external) {
                custom.merge(supplied);
            } else {
                supplied.entrySet()
                        .forEach(entry -> custom.put(entry.getKey(), entry.getValue().copy()));
            }
            item = NmsItems.withCustomData(item, custom);
        }
        return item;
    }

    private boolean present(String key) {
        return config.values().containsKey(key);
    }

    private void potion(PotionMeta meta, SxExpressions handler) {
        Object value = config.get("Potion");
        if (value instanceof String text) {
            meta.setBasePotionType(
                    PotionType.valueOf(handler.replace(text).toUpperCase(Locale.ROOT)));
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String name = handler.replace(String.valueOf(entry.getKey()));
                PotionEffectType type = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
                if (type == null) {
                    continue;
                }
                SxConfig effect =
                        new SxConfig(
                                entry.getValue() instanceof Map<?, ?> fields
                                        ? SxConfig.stringKeys(fields)
                                        : Map.of());
                int duration = Integer.parseInt(handler.replace(effect.text("duration", "1")));
                int amplifier = Integer.parseInt(handler.replace(effect.text("amplifier", "1")));
                meta.addCustomEffect(
                        new PotionEffect(
                                type,
                                duration,
                                amplifier,
                                effect.bool("ambient", true),
                                effect.bool("particles", true),
                                effect.bool("icon", true)),
                        true);
            }
        }
    }

    private void attributes(ItemMeta meta, SxExpressions handler) {
        int sequence = 0;
        for (String line : handler.replace(config.strings("Attributes"))) {
            AttributeInput input = AttributeInput.parse(line);
            NamespacedKey key = NamespacedKey.fromString(input.name());
            Attribute attribute = key == null ? null : Registry.ATTRIBUTE.get(key);
            EquipmentSlotGroup slot = EquipmentSlotGroup.getByName(input.slot());
            if (attribute == null || slot == null) {
                throw new IllegalArgumentException("Unknown SX attribute or slot: " + line);
            }
            if (input.amount() == 0) {
                meta.removeAttributeModifier(attribute);
            } else {
                meta.addAttributeModifier(
                        attribute,
                        new AttributeModifier(
                                new NamespacedKey("itemloom", "sx_" + sequence++),
                                input.amount(),
                                AttributeModifier.Operation.values()[input.operation()],
                                slot));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    static int damage(String value, int maximum) {
        short narrowed;
        if (value.startsWith("<")) {
            narrowed = (short) (maximum - Short.parseShort(value.substring(1)));
        } else if (value.endsWith("%")) {
            narrowed =
                    (short)
                            (maximum
                                    * (1
                                            - Double.parseDouble(
                                                            value.substring(0, value.length() - 1))
                                                    / 100));
        } else {
            narrowed = Short.parseShort(value);
        }
        return Math.max(0, narrowed);
    }

    static String colors(String value) {
        return value.replace('&', '\u00a7');
    }

    static Map<String, Object> profileValue(String value) {
        if (value.length() <= 16) {
            return Map.of("name", value);
        }
        UUID uuid = UUID.fromString(value);
        long high = uuid.getMostSignificantBits();
        long low = uuid.getLeastSignificantBits();
        return Map.of(
                "id", new int[] {(int) (high >>> 32), (int) high, (int) (low >>> 32), (int) low});
    }

    static Map<String, Object> nbtValues(Map<String, Object> values) {
        if (!(values.get("AttributeModifiers") instanceof List<?> modifiers)) {
            return values;
        }
        Map<String, Object> result = new LinkedHashMap<>(values);
        List<Object> converted = new ArrayList<>(modifiers.size());
        for (Object modifier : modifiers) {
            if (modifier instanceof Map<?, ?> fields
                    && fields.get("UUID") instanceof List<?> parts) {
                Map<Object, Object> copy = new LinkedHashMap<>(fields);
                int[] uuid = new int[parts.size()];
                for (int index = 0; index < parts.size(); index++) {
                    uuid[index] = Integer.parseInt(String.valueOf(parts.get(index)));
                }
                copy.put("UUID", uuid);
                converted.add(copy);
            } else {
                converted.add(modifier);
            }
        }
        result.put("AttributeModifiers", converted);
        return result;
    }

    record EnchantmentInput(String key, int level) {
        static EnchantmentInput parse(String value) {
            int split = value.lastIndexOf(':');
            if (split < 0) {
                throw new IllegalArgumentException("Expected enchantment:level: " + value);
            }
            return new EnchantmentInput(
                    value.substring(0, split), Integer.parseInt(value.substring(split + 1)));
        }
    }

    record AttributeInput(String name, double amount, int operation, String slot) {
        static AttributeInput parse(String value) {
            String[] parts = value.split(":");
            if (parts.length < 3 || parts.length > 4) {
                throw new IllegalArgumentException(
                        "Expected attribute:amount:operation[:slot]: " + value);
            }
            String name = parts[0].toLowerCase(Locale.ROOT);
            for (String prefix : List.of("generic_", "horse_", "zombie_")) {
                if (name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                    break;
                }
            }
            double amount = Double.parseDouble(parts[1]);
            int operation = Integer.parseInt(parts[2]);
            if (!Double.isFinite(amount) || operation < 0 || operation > 2) {
                throw new IllegalArgumentException(
                        "Invalid attribute amount or operation: " + value);
            }
            String slot = parts.length == 3 ? "any" : parts[3].toLowerCase(Locale.ROOT);
            slot =
                    switch (slot) {
                        case "hand" -> "mainhand";
                        case "off_hand" -> "offhand";
                        default -> slot;
                    };
            return new AttributeInput(name, amount, operation, slot);
        }
    }
}

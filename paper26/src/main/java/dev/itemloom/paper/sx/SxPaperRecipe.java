package dev.itemloom.paper.sx;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.sx.SxConfig;
import dev.itemloom.compat.sx.SxExpressions;
import dev.itemloom.compat.sx.SxRandom;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
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
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import dev.itemloom.paper.integration.ExternalItemMaterial;
import dev.itemloom.paper.integration.OptionalItemSources;
import org.bukkit.entity.Player;

/** SX convenience-field order, followed by explicit 26.2 components and custom NBT. */
@SuppressWarnings("deprecation")
final class SxPaperRecipe {
    private static final ItemStateCodec STATE = new ItemStateCodec();
    private static final Set<String> FIELDS =
            Set.of(
                    "Type",
                    "Item",
                    "Path",
                    "Name",
                    "Lore",
                    "Amount",
                    "Durability",
                    "EnchantList",
                    "ItemFlagList",
                    "Unbreakable",
                    "SkullName",
                    "Color",
                    "Potion",
                    "CustomModelData",
                    "ClearAttribute",
                    "Attributes",
                    "Components",
                    "NBT",
                    "Random",
                    "Update",
                    "ProtectNBT");
    private final String id;
    private final SxConfig config;
    private final ItemStack imported;
    private final OptionalItemSources itemSources;
    private ItemStack prototype;
    private final List<String> materials;
    private final SxConfig components, nbt, potions;
    private final List<String> flags, lore, enchants, attributes;
    final Map<String, SxRandom> random;
    final Set<String> protectedPaths;
    final boolean update;
    final int hash;

    SxPaperRecipe(SxRepository.Definition definition, SxConfig settings) {
        this(definition, settings, new OptionalItemSources());
    }

    SxPaperRecipe(
            SxRepository.Definition definition,
            SxConfig settings,
            OptionalItemSources itemSources) {
        this.itemSources = itemSources;
        id = definition.id();
        config = definition.config();
        hash = config.values().hashCode();
        for (String field : config.values().keySet())
            if (!field.equalsIgnoreCase("ID") && !FIELDS.contains(field))
                throw new IllegalArgumentException(
                        definition.source() + ": unsupported SX item field " + id + '.' + field);
        String type = config.text("Type", "Default");
        if (!type.equals("Default") && !type.equals("Import"))
            throw new IllegalArgumentException(
                    definition.source() + ": unsupported SX Type " + type + " for " + id);
        if (type.equals("Import")) {
            if (!(config.get("Item") instanceof ItemStack item) || item.isEmpty())
                throw new IllegalArgumentException(
                        definition.source()
                                + ": Import.Item must be a serialized Bukkit ItemStack: "
                                + id);
            imported = item.clone();
        } else imported = null;
        update = imported == null && config.bool("Update", false);
        String materialKey =
                config.values().containsKey("ID")
                        ? "ID"
                        : config.values().keySet().stream()
                                .filter(k -> k.equalsIgnoreCase("ID"))
                                .findFirst()
                                .orElse("ID");
        materials =
                config.get(materialKey) instanceof List
                        ? config.strings(materialKey)
                        : List.of(config.text(materialKey, "APPLE"));
        if (materials.isEmpty()) throw new IllegalArgumentException("SX ID list is empty: " + id);
        if (imported == null)
            for (String material : materials)
                if (!material.contains("<") && !material.contains("%"))
                    if (ExternalItemMaterial.isExternal(material))
                        ExternalItemMaterial.parse(material);
                    else SxMaterials.resolve(material);
        random = SxRandom.compile(config.section("Random").values());
        components = config.section("Components");
        nbt = config.section("NBT");
        potions = config.section("Potion");
        flags = config.strings("ItemFlagList");
        lore = config.strings("Lore");
        enchants = config.strings("EnchantList");
        attributes = config.strings("Attributes");
        Set<String> protect = new LinkedHashSet<>(settings.strings("ProtectNBT"));
        for (String path : config.strings("ProtectNBT")) {
            if (path.startsWith("!")) protect.remove(path.substring(1));
            else protect.add(path);
        }
        // Format identity is always owned by ItemLoom, never overwritten by protected user data.
        for (String path : protect) {
            if (path.isBlank()
                    || java.util.Arrays.stream(path.split("\\.", -1)).anyMatch(String::isEmpty))
                throw new IllegalArgumentException("Invalid SX ProtectNBT path: " + path);
            if (path.startsWith(ItemStateCodec.KEY))
                throw new IllegalArgumentException(
                        "ProtectNBT cannot protect the ItemLoom identity envelope: " + path);
            if (path.startsWith("components.")) {
                String key = path.substring(11).split("\\.", 2)[0];
                var identifier = net.minecraft.resources.Identifier.tryParse(key);
                var component =
                        identifier == null
                                ? null
                                : net.minecraft.core.registries.BuiltInRegistries
                                        .DATA_COMPONENT_TYPE
                                        .getValue(identifier);
                if (component == null)
                    throw new IllegalArgumentException("Unknown SX protected component: " + key);
                if (component == DataComponents.CUSTOM_DATA)
                    throw new IllegalArgumentException(
                            "ProtectNBT cannot replace the ItemLoom identity envelope: " + path);
            }
        }
        protectedPaths = Set.copyOf(protect);
    }

    ItemStack create(SxExpressions handler) {
        ItemStack item =
                imported != null
                        ? imported.clone()
                        : prototype != null ? prototype.clone() : createDefault(handler);
        CompoundTag properties = new CompoundTag();
        properties.putString("source", "sx");
        properties.putInt("sx_hash", hash);
        return STATE.write(item, new ItemIdentity(id, handler.getLockMap()), properties);
    }

    void prepare(SxExpressions handler) {
        // Fixed definitions are validated before publication and reuse only an owned prototype.
        // Dynamic definitions still evaluate every request; random/scripts are never previewed
        // here.
        if (imported == null
                && materials.size() == 1
                && !ExternalItemMaterial.isExternal(materials.getFirst())
                && !dynamic(config.values())) prototype = createDefault(handler);
    }

    private static boolean dynamic(Object value) {
        if (value instanceof String text) return text.indexOf('<') >= 0 || text.indexOf('%') >= 0;
        if (value instanceof Map<?, ?> map)
            return map.entrySet().stream()
                    .anyMatch(e -> dynamic(e.getKey()) || dynamic(e.getValue()));
        if (value instanceof List<?> list) return list.stream().anyMatch(SxPaperRecipe::dynamic);
        return false;
    }

    private ItemStack createDefault(SxExpressions handler) {
        String material =
                handler.replace(
                        materials.get(
                                java.util.concurrent.ThreadLocalRandom.current()
                                        .nextInt(materials.size())));
        boolean external = ExternalItemMaterial.isExternal(material);
        SxMaterials.Resolved resolved = external ? null : SxMaterials.resolve(material);
        ItemStack item =
                external
                        ? itemSources.material(
                                material,
                                handler.getPlayer() instanceof Player player ? player : null,
                                Map.of())
                        : new ItemStack(resolved.material());
        if (!external || config.get("Amount") != null) {
            int amount = Integer.parseInt(handler.replace(config.text("Amount", "1")));
            if (amount < 1) throw new IllegalArgumentException("SX Amount must be positive: " + id);
            item.setAmount(amount);
        }
        var meta = item.getItemMeta();
        String durability =
                resolved == null || resolved.damage() == null
                        ? handler.replace(config.text("Durability", null))
                        : resolved.damage();
        if (durability != null && !durability.isEmpty() && meta instanceof Damageable damageable) {
            int maximum = item.getType().getMaxDurability();
            int damage =
                    durability.endsWith("%")
                            ? (short)
                                    (maximum
                                            * (1
                                                    - Double.parseDouble(
                                                                    durability.substring(
                                                                            0,
                                                                            durability.length()
                                                                                    - 1))
                                                            / 100))
                            : durability.startsWith("<")
                                    ? (short) (maximum - Short.parseShort(durability.substring(1)))
                                    : Short.parseShort(durability);
            damageable.setDamage(Math.max(0, damage));
        }
        String name = handler.replace(config.text("Name", null));
        if (name != null) meta.setDisplayName(color(name));
        if (!external || config.get("Lore") != null)
            meta.setLore(handler.replace(lore).stream().map(SxPaperRecipe::color).toList());
        for (String enchant : handler.replace(enchants)) {
            int colon = enchant.lastIndexOf(':');
            if (colon < 0) throw new IllegalArgumentException("Invalid SX enchantment: " + enchant);
            String key = enchant.substring(0, colon);
            int level = Integer.parseInt(enchant.substring(colon + 1));
            Enchantment type = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
            NamespacedKey enchantKey = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            if (type == null && enchantKey != null) type = Registry.ENCHANTMENT.get(enchantKey);
            if (type == null) throw new IllegalArgumentException("Unknown SX enchantment: " + key);
            if (level != 0) meta.addEnchant(type, level, true);
        }
        for (String flag : flags) {
            if (flag.equals("HIDE_PROFILE")) continue;
            if (flag.equals("HIDE_POTION_EFFECTS")) flag = "HIDE_ADDITIONAL_TOOLTIP";
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unknown SX ItemFlag: " + flag, error);
            }
        }
        if (!external || config.get("Unbreakable") != null)
            meta.setUnbreakable(config.bool("Unbreakable", false));
        if (meta instanceof LeatherArmorMeta leather && config.get("Color") != null)
            leather.setColor(
                    Color.fromRGB(
                            Integer.parseInt(handler.replace(config.text("Color", "FFFFFF")), 16)));
        if (config.get("CustomModelData") != null)
            meta.setCustomModelData(
                    Integer.valueOf(handler.replace(config.text("CustomModelData", "0"))));
        if (meta instanceof PotionMeta potion) {
            if (config.get("Potion") instanceof String text)
                potion.setBasePotionType(
                        PotionType.valueOf(handler.replace(text).toUpperCase(Locale.ROOT)));
            for (var entry : potions.values().entrySet()) {
                String effectName = handler.replace(entry.getKey());
                PotionEffectType effect = PotionEffectType.getByName(effectName);
                if (effect == null) continue; // SX accepts optional/unknown potion effects.
                SxConfig data = potions.section(entry.getKey());
                int duration = Integer.parseInt(handler.replace(data.text("duration", "1")));
                int amplifier = Integer.parseInt(handler.replace(data.text("amplifier", "1")));
                potion.addCustomEffect(
                        new PotionEffect(
                                effect,
                                duration,
                                amplifier,
                                data.bool("ambient", true),
                                data.bool("particles", true),
                                data.bool("icon", true)),
                        true);
            }
        }
        if (!config.bool("ClearAttribute", false)) {
            int index = 0;
            for (String field : handler.replace(attributes)) {
                String[] parts = field.split(":");
                if (parts.length < 3 || parts.length > 4)
                    throw new IllegalArgumentException("Invalid SX attribute: " + field);
                String key =
                        parts[0].toLowerCase(Locale.ROOT)
                                .replaceFirst("^(generic_|horse_|zombie_)", "");
                Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (attribute == null)
                    throw new IllegalArgumentException("Unknown SX attribute: " + parts[0]);
                double amountValue = Double.parseDouble(parts[1]);
                int operation = Integer.parseInt(parts[2]);
                if (operation < 0 || operation > 2 || !Double.isFinite(amountValue))
                    throw new IllegalArgumentException("Invalid SX attribute value: " + field);
                String slot = parts.length == 4 ? parts[3].toLowerCase(Locale.ROOT) : "any";
                if (slot.equals("hand")) slot = "mainhand";
                if (slot.equals("off_hand")) slot = "offhand";
                EquipmentSlotGroup slots = EquipmentSlotGroup.getByName(slot);
                if (slots == null)
                    throw new IllegalArgumentException("Unknown SX attribute slot: " + slot);
                if (amountValue == 0) meta.removeAttributeModifier(attribute);
                else
                    meta.addAttributeModifier(
                            attribute,
                            new AttributeModifier(
                                    new NamespacedKey("itemloom", "sx_" + index++),
                                    amountValue,
                                    AttributeModifier.Operation.values()[operation],
                                    slots));
            }
        }
        item.setItemMeta(meta);
        var nms = CraftItemStack.asNMSCopy(item);
        if (config.bool("ClearAttribute", false)) nms.remove(DataComponents.ATTRIBUTE_MODIFIERS);
        String skull = handler.replace(config.text("SkullName", null));
        if (skull != null && meta instanceof org.bukkit.inventory.meta.SkullMeta) {
            Map<String, Object> profile;
            if (skull.length() <= 16) profile = Map.of("name", skull);
            else {
                java.util.UUID uuid = java.util.UUID.fromString(skull);
                long most = uuid.getMostSignificantBits(), least = uuid.getLeastSignificantBits();
                profile =
                        Map.of(
                                "id",
                                new int[] {
                                    (int) (most >> 32), (int) most, (int) (least >> 32), (int) least
                                });
            }
            NmsItems.applyComponents(nms, Map.of("minecraft:profile", profile));
        }
        Object expandedComponents = handler.replace((Object) components.values());
        NmsItems.applyComponents(nms, SxConfig.stringKeys((Map<?, ?>) expandedComponents));
        if (flags.contains("HIDE_PROFILE")) {
            var display =
                    nms.getOrDefault(
                            DataComponents.TOOLTIP_DISPLAY,
                            net.minecraft.world.item.component.TooltipDisplay.DEFAULT);
            nms.set(
                    DataComponents.TOOLTIP_DISPLAY,
                    display.withHidden(DataComponents.PROFILE, true));
        }
        item = CraftItemStack.asCraftMirror(nms);
        if (!nbt.values().isEmpty()) {
            CompoundTag data = NmsItems.customData(item);
            Map<String, Object> expanded =
                    SxConfig.stringKeys((Map<?, ?>) handler.replace((Object) nbt.values()));
            // SX's legacy AttributeModifiers.UUID list represents an NBT int array.
            if (expanded.get("AttributeModifiers") instanceof List<?> modifiers) {
                var converted = new java.util.ArrayList<Object>();
                for (Object modifier : modifiers) {
                    if (modifier instanceof Map<?, ?> fields
                            && fields.get("UUID") instanceof List<?> parts) {
                        Map<String, Object> copy = SxConfig.stringKeys(fields);
                        copy.put(
                                "UUID",
                                parts.stream()
                                        .mapToInt(part -> Integer.parseInt(part.toString()))
                                        .toArray());
                        converted.add(copy);
                    } else converted.add(modifier);
                }
                expanded.put("AttributeModifiers", converted);
            }
            CompoundTag values = (CompoundTag) NmsItems.tag(expanded);
            if (external) data.merge(values);
            else for (var entry : values.entrySet()) data.put(entry.getKey(), entry.getValue());
            item = NmsItems.withCustomData(item, data);
        }
        return item;
    }

    private static String color(String text) {
        return text.replace('&', '§');
    }
}

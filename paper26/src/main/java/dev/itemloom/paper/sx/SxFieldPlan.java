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
import java.util.function.Consumer;
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

/** An ordered set of edits prepared from detached configuration, evaluated only at application. */
final class SxFieldPlan {
    private final SxConfig config;
    private final List<String> flags;
    private final List<PotionEffectPlan> effects;
    private final List<FieldEdit> edits;

    SxFieldPlan(SxConfig config) {
        this.config = config;
        flags = config.strings("ItemFlagList");
        effects = prepareEffects(config.section("Potion"));
        edits =
                List.of(
                        new FieldEdit("Amount", this::amount),
                        new FieldEdit("Durability", this::durability),
                        new FieldEdit("Name", this::name),
                        new FieldEdit("Lore", this::lore),
                        new FieldEdit("EnchantList", this::enchantments),
                        new FieldEdit("ItemFlagList", this::flags),
                        new FieldEdit("Unbreakable", this::unbreakable),
                        new FieldEdit("Color", this::color),
                        new FieldEdit("CustomModelData", this::modelData),
                        new FieldEdit("Potion", this::potion),
                        new FieldEdit("Attributes", this::attributes),
                        new FieldEdit("CommitMeta", this::commitMeta),
                        new FieldEdit("SkullName", this::profile),
                        new FieldEdit("Components", this::components),
                        new FieldEdit("HideProfile", this::hideProfile),
                        new FieldEdit("NBT", this::customData));
    }

    ItemStack apply(
            ItemStack item,
            SxExpressions handler,
            boolean external,
            String inlineDamage,
            String id) {
        EditingState state = new EditingState(item, handler, external, inlineDamage, id);
        for (FieldEdit edit : edits) edit.apply(state);
        return state.item;
    }

    private void amount(EditingState state) {
        if (state.external && config.get("Amount") == null) return;
        int count = Integer.parseInt(state.handler.replace(config.text("Amount", "1")));
        if (count < 1) throw state.invalid("Amount", Integer.toString(count));
        state.item.setAmount(count);
    }

    private void durability(EditingState state) {
        state.meta = state.item.getItemMeta();
        String value =
                state.inlineDamage != null
                        ? state.inlineDamage
                        : state.handler.replace(config.text("Durability", null));
        if (state.meta instanceof Damageable damageable && value != null && !value.isEmpty()) {
            damageable.setDamage(damage(value, state.item.getType().getMaxDurability()));
        }
    }

    static int damage(String value, int maximum) {
        short result;
        if (value.endsWith("%")) {
            double percentage = Double.parseDouble(value.substring(0, value.length() - 1));
            result = (short) (maximum * (1 - percentage / 100));
        } else if (value.startsWith("<")) {
            result = (short) (maximum - Short.parseShort(value.substring(1)));
        } else {
            result = Short.parseShort(value);
        }
        return Math.max(0, result);
    }

    private void name(EditingState state) {
        String value = state.handler.replace(config.text("Name", null));
        if (value != null) state.meta.setDisplayName(colors(value));
    }

    private void lore(EditingState state) {
        if (state.external && config.get("Lore") == null) return;
        List<String> values = state.handler.replace(config.strings("Lore"));
        state.meta.setLore(values.stream().map(SxFieldPlan::colors).toList());
    }

    static String colors(String value) {
        return value.replace('&', '\u00a7');
    }

    private void enchantments(EditingState state) {
        for (String value : state.handler.replace(config.strings("EnchantList"))) {
            EnchantmentInput input = EnchantmentInput.parse(value);
            Enchantment enchantment = Enchantment.getByName(input.key().toUpperCase(Locale.ROOT));
            if (enchantment == null) {
                NamespacedKey key = NamespacedKey.fromString(input.key().toLowerCase(Locale.ROOT));
                if (key != null) enchantment = Registry.ENCHANTMENT.get(key);
            }
            if (enchantment == null) throw state.invalid("EnchantList", value);
            if (input.level() != 0) state.meta.addEnchant(enchantment, input.level(), true);
        }
    }

    record EnchantmentInput(String key, int level) {
        static EnchantmentInput parse(String value) {
            int separator = value.lastIndexOf(':');
            if (separator < 0)
                throw new IllegalArgumentException("Invalid SX enchantment: " + value);
            return new EnchantmentInput(
                    value.substring(0, separator),
                    Integer.parseInt(value.substring(separator + 1)));
        }
    }

    private void flags(EditingState state) {
        for (String flag : flags) {
            if (flag.equals("HIDE_PROFILE")) continue;
            String name = flag.equals("HIDE_POTION_EFFECTS") ? "HIDE_ADDITIONAL_TOOLTIP" : flag;
            state.meta.addItemFlags(ItemFlag.valueOf(name));
        }
    }

    private void unbreakable(EditingState state) {
        if (!state.external || config.get("Unbreakable") != null) {
            state.meta.setUnbreakable(config.bool("Unbreakable", false));
        }
    }

    private void color(EditingState state) {
        if (state.meta instanceof LeatherArmorMeta leather && config.get("Color") != null) {
            String value = state.handler.replace(config.text("Color", "FFFFFF"));
            leather.setColor(Color.fromRGB(Integer.parseInt(value, 16)));
        }
    }

    private void modelData(EditingState state) {
        if (config.get("CustomModelData") != null) {
            state.meta.setCustomModelData(
                    Integer.valueOf(state.handler.replace(config.text("CustomModelData", "0"))));
        }
    }

    private static List<PotionEffectPlan> prepareEffects(SxConfig potion) {
        List<PotionEffectPlan> result = new ArrayList<>();
        for (String key : potion.values().keySet()) {
            SxConfig effect = potion.section(key);
            result.add(
                    new PotionEffectPlan(
                            key,
                            effect.text("duration", "1"),
                            effect.text("amplifier", "1"),
                            effect.bool("ambient", true),
                            effect.bool("particles", true),
                            effect.bool("icon", true)));
        }
        return List.copyOf(result);
    }

    private record PotionEffectPlan(
            String name,
            String duration,
            String amplifier,
            boolean ambient,
            boolean particles,
            boolean icon) {
        void apply(PotionMeta meta, SxExpressions handler) {
            PotionEffectType type = PotionEffectType.getByName(handler.replace(name));
            if (type == null) return;
            int ticks = Integer.parseInt(handler.replace(duration));
            int strength = Integer.parseInt(handler.replace(amplifier));
            meta.addCustomEffect(
                    new PotionEffect(type, ticks, strength, ambient, particles, icon), true);
        }
    }

    private void potion(EditingState state) {
        if (!(state.meta instanceof PotionMeta potion)) return;
        if (config.get("Potion") instanceof String raw) {
            potion.setBasePotionType(
                    PotionType.valueOf(state.handler.replace(raw).toUpperCase(Locale.ROOT)));
        }
        for (PotionEffectPlan effect : effects) effect.apply(potion, state.handler);
    }

    private void attributes(EditingState state) {
        if (config.bool("ClearAttribute", false)) return;
        int sequence = 0;
        for (String value : state.handler.replace(config.strings("Attributes"))) {
            AttributeInput input = AttributeInput.parse(value);
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(input.name()));
            if (attribute == null) throw state.invalid("Attributes", value);
            EquipmentSlotGroup slot = EquipmentSlotGroup.getByName(input.slot());
            if (slot == null) throw state.invalid("Attributes", value);
            if (input.amount() == 0) {
                state.meta.removeAttributeModifier(attribute);
            } else {
                state.meta.addAttributeModifier(
                        attribute,
                        new AttributeModifier(
                                new NamespacedKey("itemloom", "sx_" + sequence++),
                                input.amount(),
                                AttributeModifier.Operation.values()[input.operation()],
                                slot));
            }
        }
    }

    record AttributeInput(String name, double amount, int operation, String slot) {
        static AttributeInput parse(String value) {
            String[] parts = value.split(":");
            if (parts.length < 3 || parts.length > 4) {
                throw new IllegalArgumentException("Invalid SX attribute: " + value);
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
                throw new IllegalArgumentException("Invalid SX attribute: " + value);
            }
            String slot = parts.length == 3 ? "any" : parts[3].toLowerCase(Locale.ROOT);
            if (slot.equals("hand")) slot = "mainhand";
            if (slot.equals("off_hand")) slot = "offhand";
            return new AttributeInput(name, amount, operation, slot);
        }
    }

    private void commitMeta(EditingState state) {
        state.item.setItemMeta(state.meta);
        state.handle = CraftItemStack.asNMSCopy(state.item);
        if (config.bool("ClearAttribute", false))
            state.handle.remove(DataComponents.ATTRIBUTE_MODIFIERS);
    }

    private void profile(EditingState state) {
        String value = state.handler.replace(config.text("SkullName", null));
        if (value != null && state.meta instanceof SkullMeta) {
            NmsItems.applyComponents(
                    state.handle, Map.of("minecraft:profile", profileValue(value)));
        }
    }

    static Map<String, Object> profileValue(String value) {
        if (value.length() <= 16) return Map.of("name", value);
        UUID uuid = UUID.fromString(value);
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return Map.of(
                "id",
                new int[] {(int) (most >>> 32), (int) most, (int) (least >>> 32), (int) least});
    }

    private void components(EditingState state) {
        Object expanded = state.handler.replace((Object) config.section("Components").values());
        NmsItems.applyComponents(state.handle, SxConfig.stringKeys((Map<?, ?>) expanded));
    }

    private void hideProfile(EditingState state) {
        if (!flags.contains("HIDE_PROFILE")) return;
        TooltipDisplay tooltip =
                state.handle.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        state.handle.set(
                DataComponents.TOOLTIP_DISPLAY, tooltip.withHidden(DataComponents.PROFILE, true));
    }

    private void customData(EditingState state) {
        state.item = CraftItemStack.asCraftMirror(state.handle);
        Map<String, Object> raw = config.section("NBT").values();
        if (raw.isEmpty()) return;
        CompoundTag existing = NmsItems.customData(state.item);
        Object expanded = state.handler.replace((Object) raw);
        CompoundTag additions =
                (CompoundTag) NmsItems.tag(nbtValues(SxConfig.stringKeys((Map<?, ?>) expanded)));
        if (state.external) {
            existing.merge(additions);
        } else {
            for (String key : additions.keySet()) existing.put(key, additions.get(key));
        }
        state.item = NmsItems.withCustomData(state.item, existing);
    }

    static Map<String, Object> nbtValues(Map<String, Object> values) {
        if (!(values.get("AttributeModifiers") instanceof List<?> modifiers)) return values;
        List<Object> converted = new ArrayList<>(modifiers.size());
        for (Object modifier : modifiers) {
            if (modifier instanceof Map<?, ?> entry && entry.get("UUID") instanceof List<?> parts) {
                int[] uuid = new int[parts.size()];
                for (int i = 0; i < parts.size(); i++)
                    uuid[i] = Integer.parseInt(parts.get(i).toString());
                Map<Object, Object> copy = new LinkedHashMap<>(entry);
                copy.put("UUID", uuid);
                converted.add(copy);
            } else {
                converted.add(modifier);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("AttributeModifiers", converted);
        return result;
    }

    private record FieldEdit(String name, Consumer<EditingState> action) {
        void apply(EditingState state) {
            action.accept(state);
        }
    }

    private static final class EditingState {
        private ItemStack item;
        private final SxExpressions handler;
        private final boolean external;
        private final String inlineDamage;
        private final String id;
        private ItemMeta meta;
        private net.minecraft.world.item.ItemStack handle;

        private EditingState(
                ItemStack item,
                SxExpressions handler,
                boolean external,
                String inlineDamage,
                String id) {
            this.item = item;
            this.handler = handler;
            this.external = external;
            this.inlineDamage = inlineDamage;
            this.id = id;
        }

        private IllegalArgumentException invalid(String field, String value) {
            return new IllegalArgumentException(
                    "Invalid SX " + field + " for " + id + ": " + value);
        }
    }
}

package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.*;
import dev.itemloom.paper.nms.NmsItems;
import io.papermc.paper.adventure.PaperAdventure;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.*;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.enchantments.Enchantment;

public final class NiItemAppearance {
    private static final Map<String, String> FLAGS =
            Map.of(
                    "HIDE_ATTRIBUTES",
                    "attribute_modifiers",
                    "HIDE_ENCHANTS",
                    "enchantments",
                    "HIDE_STORED_ENCHANTS",
                    "stored_enchantments",
                    "HIDE_UNBREAKABLE",
                    "unbreakable",
                    "HIDE_DYE",
                    "dyed_color",
                    "HIDE_ARMOR_TRIM",
                    "trim",
                    "HIDE_PLACED_ON",
                    "can_place_on",
                    "HIDE_DESTROYS",
                    "can_break");
    private static final List<String> EXTRA =
            List.of(
                    "banner_patterns",
                    "bees",
                    "block_entity_data",
                    "block_state",
                    "bundle_contents",
                    "charged_projectiles",
                    "container",
                    "container_loot",
                    "firework_explosion",
                    "fireworks",
                    "instrument",
                    "jukebox_playable",
                    "map_id",
                    "painting_variant",
                    "pot_decorations",
                    "potion_contents",
                    "tropical_fish/pattern",
                    "written_book_content");
    private final Consumer<String> warning;
    private final boolean external;
    private Material material;
    private Integer damage, model, color;
    private Boolean unbreakable;
    private Component name, itemName;
    private List<Component> lore;
    private List<String> flags = List.of();
    private final Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
    private final Map<String, Object> components = new LinkedHashMap<>();

    public NiItemAppearance(NiConfig config, Consumer<String> warning) {
        this(config, warning, false);
    }

    public NiItemAppearance(NiConfig config, Consumer<String> warning, boolean external) {
        this.warning = warning;
        this.external = external;
        for (String key : config.keys()) {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "material", "type" -> material = material(config.string(key));
                case "damage" -> damage = (int) (short) config.integer(key, 0);
                case "custommodeldata", "custom-model-data" -> model = config.integer(key, 0);
                case "name" -> name = legacy(config.string(key));
                case "item-name" -> itemName = legacy(config.string(key));
                case "mini-name" -> name = mini(config.string(key));
                case "mini-item-name" -> itemName = mini(config.string(key));
                case "lore", "mini-lore" -> {
                    List<String> lines = config.strings(key);
                    if (!lines.isEmpty() || external) {
                        lore = new ArrayList<>();
                        boolean mini = key.equalsIgnoreCase("mini-lore");
                        for (String line : lines)
                            for (String part : line.split("\n"))
                                lore.add(mini ? mini(part) : legacy(part));
                    }
                }
                case "unbreakable" -> unbreakable = config.bool(key, false);
                case "color" -> {
                    Object value = config.get(key);
                    color = null;
                    if (value instanceof Integer number) color = Math.clamp(number, 0, 0xffffff);
                    else if (value instanceof String text) {
                        try {
                            color = Math.clamp(Integer.parseInt(text, 16), 0, 0xffffff);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                case "item-flags", "itemflags", "hide-flags", "hideflags" -> {
                    List<String> selected = config.strings(key);
                    if (!selected.isEmpty()) flags = selected;
                }
                case "enchantments" -> {
                    NiConfig selected = config.section(key);
                    if (selected != null)
                        for (String id : selected.keys()) {
                            Enchantment enchantment =
                                    Enchantment.getByName(id.toUpperCase(Locale.ENGLISH));
                            if (enchantment == null) {
                                NamespacedKey namespaced =
                                        NamespacedKey.fromString(id.toLowerCase(Locale.ENGLISH));
                                if (namespaced != null)
                                    enchantment = Enchantment.getByKey(namespaced);
                            }
                            int level = (short) selected.integer(id, 0);
                            if (enchantment != null && level > 0)
                                enchantments.put(enchantment, level);
                        }
                }
                case "components" -> {
                    NiConfig selected = config.section(key);
                    if (selected != null) components.putAll(selected.values());
                }
                default -> {}
            }
        }
    }

    public static Material material(String value) {
        return value == null ? null : Material.getMaterial(value.toUpperCase(Locale.ENGLISH));
    }

    public Material material() {
        return material;
    }

    private static Component legacy(String value) {
        return value == null
                ? null
                : PaperAdventure.asVanilla(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(value));
    }

    private static Component mini(String value) {
        return value == null
                ? null
                : PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(value));
    }

    public ItemStack apply(ItemStack source) {
        return apply(source, true);
    }

    public ItemStack apply(ItemStack source, boolean changeMaterial) {
        if (changeMaterial && material != null && material.isAir()) return ItemStack.EMPTY;
        ItemStack result = source.copy();
        if (result.isEmpty()) return result;
        if (changeMaterial && material != null) result.setItem(CraftMagicNumbers.getItem(material));
        if (damage != null) result.set(DataComponents.DAMAGE, damage);
        if (model != null)
            result.set(
                    DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(
                            List.of(model.floatValue()), List.of(), List.of(), List.of()));
        if (name != null) result.set(DataComponents.CUSTOM_NAME, name);
        if (itemName != null) result.set(DataComponents.ITEM_NAME, itemName);
        if (lore != null) result.set(DataComponents.LORE, new ItemLore(List.copyOf(lore)));
        if (Boolean.TRUE.equals(unbreakable)) result.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        else if (external && unbreakable != null) result.remove(DataComponents.UNBREAKABLE);
        if (color != null) result.set(DataComponents.DYED_COLOR, new DyedItemColor(color));
        enchantments.forEach(result.asBukkitMirror()::addUnsafeEnchantment);
        if (!flags.isEmpty()) {
            TooltipDisplay display =
                    result.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
            for (String flag : flags) {
                List<String> hidden =
                        flag.equals("HIDE_ADDITIONAL_TOOLTIP")
                                ? EXTRA
                                : FLAGS.containsKey(flag) ? List.of(FLAGS.get(flag)) : List.of();
                if (hidden.isEmpty()) warning.accept("Unknown item flag: " + flag);
                for (String id : hidden) {
                    DataComponentType<?> type =
                            BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                                    Identifier.parse("minecraft:" + id));
                    if (type != null) display = display.withHidden(type, true);
                }
            }
            result.set(DataComponents.TOOLTIP_DISPLAY, display);
        }
        for (var entry : components.entrySet()) {
            try {
                NmsItems.applyComponents(
                        result,
                        Collections.singletonMap(
                                entry.getKey(), NiTagValues.decode(entry.getValue())));
            } catch (RuntimeException error) {
                warning.accept(
                        "Invalid item component " + entry.getKey() + ": " + error.getMessage());
            }
        }
        return result;
    }
}

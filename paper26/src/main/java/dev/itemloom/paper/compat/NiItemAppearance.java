package dev.itemloom.paper.compat;

import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiTagValues;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.enchantments.Enchantment;

/**
 * NI's ordered appearance fields compiled against 26.2 components. This stays at the input
 * boundary: the legacy short conversions, empty-lore behavior and component precedence are
 * different from Keystone's general item builder and must not change its contract.
 */
public final class NiItemAppearance {
    private static final Map<String, String> FLAGS =
            Map.ofEntries(
                    Map.entry("HIDE_ATTRIBUTES", "attribute_modifiers"),
                            Map.entry("HIDE_ENCHANTS", "enchantments"),
                    Map.entry("HIDE_STORED_ENCHANTS", "stored_enchantments"),
                            Map.entry("HIDE_UNBREAKABLE", "unbreakable"),
                    Map.entry("HIDE_DYE", "dyed_color"), Map.entry("HIDE_ARMOR_TRIM", "trim"),
                    Map.entry("HIDE_PLACED_ON", "can_place_on"),
                            Map.entry("HIDE_DESTROYS", "can_break"));
    private static final List<String> ADDITIONAL =
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

    private Material material;
    private Integer damage, model, color;
    private Boolean unbreakable;
    private Component name, itemName;
    private List<Component> lore;
    private List<String> flags = List.of();
    private final Map<String, Object> enchantments = new LinkedHashMap<>();
    private final Map<String, Object> components = new LinkedHashMap<>();
    private final Consumer<String> warning;

    public NiItemAppearance(NiConfig config, Consumer<String> warning) {
        this(config, warning, false);
    }

    /** External prototypes opt into explicit empty-lore and false-unbreakable overrides. */
    public NiItemAppearance(NiConfig config, Consumer<String> warning, boolean external) {
        this.warning = warning;
        if (config == null) return;
        for (String key : config.keys()) {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "type", "material" -> material = material(config.string(key));
                case "damage" -> damage = (int) (short) config.integer(key, 0);
                case "custommodeldata", "custom-model-data" -> model = config.integer(key, 0);
                case "name" -> name = legacy(config.string(key));
                case "item-name" -> itemName = legacy(config.string(key));
                case "mini-name" -> name = mini(config.string(key));
                case "mini-item-name" -> itemName = mini(config.string(key));
                case "lore", "mini-lore" -> {
                    List<Component> lines = new ArrayList<>();
                    for (String line : config.strings(key))
                        for (String part : line.split("\n")) {
                            lines.add(
                                    key.equalsIgnoreCase("mini-lore") ? mini(part) : legacy(part));
                        }
                    if (external || !lines.isEmpty()) lore = List.copyOf(lines);
                }
                case "color" -> {
                    Object value = config.get(key);
                    if (value instanceof Integer rgb) color = Math.clamp(rgb, 0, 0xFFFFFF);
                    else if (value instanceof String text) {
                        try {
                            color = Integer.parseInt(text, 16);
                        } catch (NumberFormatException ignored) {
                            color = null;
                        }
                    }
                }
                case "unbreakable" -> {
                    if (external || config.bool(key, false)) unbreakable = config.bool(key, false);
                }
                case "item-flags", "itemflags", "hide-flags", "hideflags" -> {
                    List<String> requested = config.strings(key);
                    if (!requested.isEmpty()) flags = List.copyOf(requested);
                }
                case "enchantments" -> {
                    NiConfig values = config.section(key);
                    if (values == null) break;
                    for (String id : values.keys()) {
                        int level = (short) values.integer(id, 0);
                        Enchantment enchantment = enchantment(id);
                        if (level > 0 && enchantment != null)
                            enchantments.put(enchantment.getKey().toString(), level);
                    }
                }
                case "components" -> {
                    NiConfig values = config.section(key);
                    if (values != null)
                        values.values()
                                .forEach(
                                        (id, value) ->
                                                components.put(id, NiTagValues.decode(value)));
                }
                default -> {
                    /* sections/options/custom data belong to other compatibility stages. */
                }
            }
        }
    }

    public static Material material(String value) {
        return value == null ? null : Material.getMaterial(value.toUpperCase(Locale.ENGLISH));
    }

    public Material material() {
        return material;
    }

    /** Input and cached prototypes are never changed; the returned NMS stack is exclusively owned. */
    public ItemStack apply(ItemStack source) {
        return apply(source, true);
    }

    public ItemStack apply(ItemStack source, boolean changeMaterial) {
        if (changeMaterial && material != null && material.isAir()) return ItemStack.EMPTY;
        ItemStack copy = source.copy();
        if (changeMaterial && material != null) copy.setItem(CraftMagicNumbers.getItem(material));
        if (copy.isEmpty()) return copy;
        if (damage != null) copy.setDamageValue(damage);
        if (!enchantments.isEmpty())
            NmsItems.applyComponents(copy, Map.of("enchantments", enchantments));
        if (model != null)
            copy.set(
                    DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(
                            List.of(model.floatValue()), List.of(), List.of(), List.of()));
        if (name != null) copy.set(DataComponents.CUSTOM_NAME, name);
        if (itemName != null) copy.set(DataComponents.ITEM_NAME, itemName);
        if (lore != null) copy.set(DataComponents.LORE, new ItemLore(lore));
        if (color != null) copy.set(DataComponents.DYED_COLOR, new DyedItemColor(color));
        if (Boolean.TRUE.equals(unbreakable)) copy.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        else if (Boolean.FALSE.equals(unbreakable)) copy.remove(DataComponents.UNBREAKABLE);
        if (!flags.isEmpty()) {
            TooltipDisplay display =
                    copy.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
            for (String flag : flags) {
                if (flag.equals("HIDE_ADDITIONAL_TOOLTIP")) {
                    for (String id : ADDITIONAL) display = display.withHidden(component(id), true);
                } else if (FLAGS.containsKey(flag))
                    display = display.withHidden(component(FLAGS.get(flag)), true);
                else warning.accept("Unknown NI hide flag: " + flag);
            }
            copy.set(DataComponents.TOOLTIP_DISPLAY, display);
        }
        // Explicit components override every convenience field, independent of YAML key order.
        components.forEach(
                (key, value) -> {
                    try {
                        NmsItems.applyComponents(copy, Map.of(key, value));
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        // NI explicitly warns and omits an invalid component, retaining the other
                        // fields.
                        warning.accept(
                                "Component " + key + " was not applied: " + error.getMessage());
                    }
                });
        return copy;
    }

    private static DataComponentType<?> component(String name) {
        DataComponentType<?> value =
                BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(name));
        if (value == null)
            throw new IllegalArgumentException("Unknown 26.2 item component: " + name);
        return value;
    }

    @SuppressWarnings("deprecation")
    private static Enchantment enchantment(String id) {
        Enchantment old = Enchantment.getByName(id.toUpperCase(Locale.ROOT));
        if (old != null) return old;
        NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    @SuppressWarnings("deprecation")
    private static Component legacy(String text) {
        return text == null
                ? null
                : CraftChatMessage.fromString(ChatColor.translateAlternateColorCodes('&', text))[0];
    }

    private static Component mini(String text) {
        return text == null
                ? null
                : PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(text));
    }
}

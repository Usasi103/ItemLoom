package dev.itemloom.paper.sx;

import java.util.LinkedHashSet;
import java.util.List;
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
import org.bukkit.inventory.ItemStack;
import dev.itemloom.paper.integration.ExternalItemMaterial;
import dev.itemloom.paper.integration.OptionalItemSources;
import org.bukkit.entity.Player;

/** Owns an SX recipe, its external source boundary and reusable field plan. */
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
    private final SxFieldPlan fieldPlan;
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
        fieldPlan = new SxFieldPlan(config);
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
        return fieldPlan.apply(
                item, handler, external, resolved == null ? null : resolved.damage(), id);
    }
}

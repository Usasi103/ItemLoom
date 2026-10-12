package dev.itemloom.paper.sx;

import dev.itemloom.compat.sx.SxConfig;
import dev.itemloom.compat.sx.SxExpressions;
import dev.itemloom.compat.sx.SxRandom;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.integration.ExternalItemMaterial;
import dev.itemloom.paper.integration.OptionalItemSources;
import dev.itemloom.paper.nms.ItemStateCodec;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** A canonical definition with construction policy and saved generation identity. */
final class SxPaperRecipe {
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
    final Map<String, SxRandom> random;
    final Set<String> protectedPaths;
    final boolean update;
    final String id;
    private final SxConfig config;
    private final int legacyHash;
    private final ItemStateCodec identities = new ItemStateCodec();
    private final List<String> materials;
    private final OptionalItemSources itemSources;
    private final SxFieldPlan fields;
    private final ItemStack imported;
    private ItemStack prepared;

    SxPaperRecipe(SxRepository.Definition definition, SxConfig settings) {
        this(definition, settings, new OptionalItemSources());
    }

    SxPaperRecipe(
            SxRepository.Definition definition,
            SxConfig settings,
            OptionalItemSources itemSources) {
        id = definition.id();
        config = definition.config();
        legacyHash = config.values().hashCode();
        this.itemSources = itemSources;
        for (String key : config.values().keySet()) {
            if (!key.equalsIgnoreCase("ID") && !FIELDS.contains(key)) {
                throw new IllegalArgumentException(
                        "Unknown SX field " + definition.source() + "/" + id + "/" + key);
            }
        }
        String type = config.text("Type", "Default");
        if (!type.equals("Default") && !type.equals("Import")) {
            throw new IllegalArgumentException(
                    "Unknown SX Type in " + definition.source() + "/" + id + ": " + type);
        }
        if (type.equals("Import")) {
            if (!(config.get("Item") instanceof ItemStack item) || item.isEmpty()) {
                throw new IllegalArgumentException("Import needs a nonempty ItemStack: " + id);
            }
            imported = item.clone();
            materials = List.of();
        } else {
            imported = null;
            String materialKey = "ID";
            if (!config.values().containsKey(materialKey)) {
                materialKey =
                        config.values().keySet().stream()
                                .filter(key -> key.equalsIgnoreCase("ID"))
                                .findFirst()
                                .orElse("ID");
            }
            Object raw = config.get(materialKey);
            materials =
                    raw instanceof List<?>
                            ? List.copyOf(config.strings(materialKey))
                            : List.of(config.text(materialKey, "APPLE"));
            if (materials.isEmpty()) {
                throw new IllegalArgumentException("Empty SX material choices: " + id);
            }
            for (String material : materials) {
                if (!dynamic(material)) {
                    if (ExternalItemMaterial.isExternal(material)) {
                        ExternalItemMaterial.parse(material);
                    } else {
                        SxMaterials.resolve(material);
                    }
                }
            }
        }
        random = SxRandom.compile(config.section("Random").values());
        update = imported == null && config.bool("Update", false);
        fields = new SxFieldPlan(config);
        LinkedHashSet<String> protection = new LinkedHashSet<>(settings.strings("ProtectNBT"));
        for (String path : protection) {
            SxProtectedState.validate(path);
        }
        for (String path : config.strings("ProtectNBT")) {
            String target = path.startsWith("!") ? path.substring(1) : path;
            SxProtectedState.validate(target);
            if (path.startsWith("!")) {
                protection.remove(target);
            } else {
                protection.add(target);
            }
        }
        protectedPaths = Collections.unmodifiableSet(protection);
    }

    void prepare(SxExpressions handler) {
        if (imported == null
                && materials.size() == 1
                && !ExternalItemMaterial.isExternal(materials.getFirst())
                && !dynamic(config.values())) {
            prepared = construct(handler);
        }
    }

    ItemStack create(SxExpressions handler) {
        ItemStack item =
                imported != null
                        ? imported.clone()
                        : prepared == null ? construct(handler) : prepared.clone();
        CompoundTag properties = new CompoundTag();
        properties.putString("source", "sx");
        // Kept as persisted compatibility data; catalog update equality uses its full fingerprint.
        properties.putInt("sx_hash", legacyHash);
        return identities.write(item, new ItemIdentity(id, handler.getLockMap()), properties);
    }

    private ItemStack construct(SxExpressions handler) {
        int choice =
                materials.size() == 1 ? 0 : ThreadLocalRandom.current().nextInt(materials.size());
        String material = handler.replace(materials.get(choice));
        if (ExternalItemMaterial.isExternal(material)) {
            Player player = handler.getPlayer() instanceof Player online ? online : null;
            ItemStack base = itemSources.material(material, player, Map.of());
            return fields.apply(base.clone(), handler, true, null, id);
        }
        SxMaterials.Resolved resolved = SxMaterials.resolve(material);
        return fields.apply(
                new ItemStack(resolved.material()), handler, false, resolved.damage(), id);
    }

    private static boolean dynamic(Object value) {
        if (value instanceof String text) {
            return text.contains("<") || text.contains("%");
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .anyMatch(entry -> dynamic(entry.getKey()) || dynamic(entry.getValue()));
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(SxPaperRecipe::dynamic);
        }
        return false;
    }
}

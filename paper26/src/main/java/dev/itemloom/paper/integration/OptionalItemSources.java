package dev.itemloom.paper.integration;

import cn.gtemc.itembridge.api.context.BuildContext;
import cn.gtemc.itembridge.api.context.ContextKey;
import cn.gtemc.itembridge.core.BukkitItemBridge;
import dev.itemloom.paper.ItemsService;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** External prototypes and the legacy script lookup order, serialized on the server thread. */
public final class OptionalItemSources {
    private static final String[] LEGACY_ORDER = {"mythicmobs", "magicgem", "itemsadder", "oraxen"};
    private final ItemBridgeSource source;

    public OptionalItemSources() {
        source = new ItemBridgeSource();
    }

    /** A scoped registry can supply independent providers without changing the global plugin state. */
    OptionalItemSources(BukkitItemBridge bridge, Map<String, Plugin> owners) {
        source = new ItemBridgeSource(bridge, owners);
    }

    /** Resolves an explicit external material; a missing source or item is a configuration error. */
    public ItemStack material(String specification, Player player, Map<String, ?> context) {
        ItemsService.requireThread();
        ExternalItemMaterial material = ExternalItemMaterial.parse(specification);
        return source.item(
                material.provider(), material.id(), player, context(context), false, true);
    }

    public ItemStack getHookedItem(String id) {
        return getHookedItem(id, null, Map.of());
    }

    public ItemStack getHookedItem(String id, Player player, Map<String, ?> context) {
        ItemsService.requireThread();
        if (id == null || id.isBlank()) return null;
        if (ExternalItemMaterial.isExternal(id)) return material(id, player, context);
        BuildContext buildContext = context(context);
        int split = id.indexOf(':');
        if (split >= 0) {
            String prefix = id.substring(0, split).toLowerCase(Locale.ROOT);
            ExternalItemMaterial.requireAllowed(prefix);
            String key = id.substring(split + 1);
            String provider =
                    switch (prefix) {
                        case "mm", "mythicmobs" -> "mythicmobs";
                        case "mg", "magicgem" -> "magicgem";
                        case "ia", "itemsadder" -> "itemsadder";
                        case "or", "oraxen" -> "oraxen";
                        default -> null;
                    };
            ItemStack item =
                    provider == null
                            ? switch (prefix) {
                                case "vn", "vanilla" -> vanilla(key);
                                default -> null;
                            }
                            : source.item(provider, key, player, buildContext, false, false);
            if (item != null) return item;
        }
        for (String provider : LEGACY_ORDER) {
            ItemStack item = source.item(provider, id, player, buildContext, true, false);
            if (item != null) return item;
        }
        return vanilla(id.toUpperCase(Locale.ROOT));
    }

    private static ItemStack vanilla(String id) {
        Material material = Material.matchMaterial(id);
        return material == null ? null : new ItemStack(material);
    }

    private static BuildContext context(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return BuildContext.empty();
        BuildContext.Builder builder = BuildContext.builder();
        values.forEach(
                (key, value) -> {
                    if (key == null || key.isBlank())
                        throw new IllegalArgumentException(
                                "External item context key must not be blank");
                    if (value != null) put(builder, key, value);
                });
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void put(BuildContext.Builder builder, String key, Object value) {
        builder.with(ContextKey.of((Class<Object>) value.getClass(), key), value);
    }
}

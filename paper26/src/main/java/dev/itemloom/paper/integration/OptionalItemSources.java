package dev.itemloom.paper.integration;

import cn.gtemc.itembridge.api.context.BuildContext;
import cn.gtemc.itembridge.api.context.ContextKey;
import cn.gtemc.itembridge.core.BukkitItemBridge;
import dev.itemloom.paper.ItemsService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class OptionalItemSources {
    private static final List<String> SEARCH =
            List.of("mythicmobs", "magicgem", "itemsadder", "oraxen");
    private final ItemBridgeSource source;

    public OptionalItemSources() {
        source = new ItemBridgeSource();
    }

    OptionalItemSources(BukkitItemBridge bridge, Map<String, Plugin> owners) {
        source = new ItemBridgeSource(bridge, owners);
    }

    public ItemStack material(String specification, Player player, Map<String, ?> values) {
        ItemsService.requireThread();
        var material = ExternalItemMaterial.parse(specification);
        return source.item(
                material.provider(), material.id(), player, context(values), false, true);
    }

    public ItemStack getHookedItem(String id) {
        return getHookedItem(id, null, Map.of());
    }

    public ItemStack getHookedItem(String id, Player player, Map<String, ?> values) {
        ItemsService.requireThread();
        if (id == null || id.isBlank()) return null;
        if (ExternalItemMaterial.isExternal(id)) return material(id, player, values);
        BuildContext context = context(values);
        int colon = id.indexOf(':');
        if (colon >= 0) {
            String prefix = id.substring(0, colon).toLowerCase(Locale.ROOT);
            ExternalItemMaterial.requireAllowed(prefix);
            String provider =
                    switch (prefix) {
                        case "mm", "mythicmobs" -> "mythicmobs";
                        case "mg", "magicgem" -> "magicgem";
                        case "ia", "itemsadder" -> "itemsadder";
                        case "or", "oraxen" -> "oraxen";
                        default -> null;
                    };
            if (prefix.equals("vn") || prefix.equals("vanilla")) {
                ItemStack item = vanilla(id.substring(colon + 1));
                if (item != null) return item;
            }
            if (provider != null) {
                ItemStack item =
                        source.item(
                                provider, id.substring(colon + 1), player, context, false, false);
                if (item != null) return item;
            }
        }
        for (String provider : SEARCH) {
            ItemStack item = source.item(provider, id, player, context, true, false);
            if (item != null) return item;
        }
        return vanilla(id.toUpperCase(Locale.ROOT));
    }

    private static ItemStack vanilla(String id) {
        Material type = Material.matchMaterial(id);
        return type == null ? null : new ItemStack(type);
    }

    private static BuildContext context(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return BuildContext.empty();
        var builder = BuildContext.builder();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("外部物品参数名不能为空");
            if (entry.getValue() != null) put(builder, entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(BuildContext.Builder builder, String key, Object value) {
        builder.with(ContextKey.of((Class) value.getClass(), key), value);
    }
}

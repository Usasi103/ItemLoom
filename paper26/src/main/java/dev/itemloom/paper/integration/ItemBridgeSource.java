package dev.itemloom.paper.integration;

import cn.gtemc.itembridge.api.context.BuildContext;
import cn.gtemc.itembridge.core.BukkitItemBridge;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Retains provider registrations; each request obtains a fresh provider-owned value. */
final class ItemBridgeSource {
    private record Registry(
            BukkitItemBridge bridge, Map<String, Plugin> owners, Map<String, Throwable> failures) {
        Registry {
            owners = Map.copyOf(owners);
            failures = Map.copyOf(failures);
        }
    }

    private static final class SourceFailure extends IllegalArgumentException {
        SourceFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Registry registry;
    private final boolean injected;

    ItemBridgeSource() {
        injected = false;
    }

    ItemBridgeSource(BukkitItemBridge bridge, Map<String, Plugin> owners) {
        var normalized = new LinkedHashMap<String, Plugin>();
        owners.forEach((name, owner) -> normalized.put(name.toLowerCase(Locale.ROOT), owner));
        registry = new Registry(bridge, normalized, Map.of());
        injected = true;
    }

    ItemStack item(
            String provider,
            String id,
            Player player,
            BuildContext context,
            boolean checkExists,
            boolean required) {
        provider = provider.toLowerCase(Locale.ROOT);
        ExternalItemMaterial.requireAllowed(provider);
        try {
            if (!injected) {
                Plugin active = plugin(provider);
                if (registry == null || registry.owners.get(provider) != active)
                    registry = discover();
            }
            Registry current = registry;
            Plugin owner = current.owners.get(provider);
            if (owner == null || !owner.isEnabled()) {
                if (required) throw failure(provider, id, "plugin is missing or disabled", null);
                return null;
            }
            Throwable discovery = current.failures.get(provider);
            if (discovery != null) throw failure(provider, id, "来源初始化失败", discovery);
            if (!current.bridge.hasProvider(provider)) throw failure(provider, id, "来源未注册", null);
            if (checkExists && !current.bridge.has(provider, id)) {
                if (required) throw failure(provider, id, "物品不存在", null);
                return null;
            }
            ItemStack value = current.bridge.buildOrNull(provider, id, player, context);
            if (value == null || value.isEmpty()) {
                if (required) throw failure(provider, id, "返回空物品", null);
                return null;
            }
            return value.clone();
        } catch (SourceFailure failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw failure(provider, id, "物品生成失败", failure);
        }
    }

    private static Registry discover() {
        var owners = new LinkedHashMap<String, Plugin>();
        var failures = new LinkedHashMap<String, Throwable>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled() && ExternalItemMaterial.allowed(plugin.getName())) {
                owners.put(plugin.getName().toLowerCase(Locale.ROOT), plugin);
            }
        }
        var bridge =
                BukkitItemBridge.builder()
                        .immutable(true)
                        .detectSupportedPlugins(
                                ignored -> {},
                                (name, cause) -> failures.put(name.toLowerCase(Locale.ROOT), cause),
                                candidate ->
                                        candidate.isEnabled()
                                                && ExternalItemMaterial.allowed(
                                                        candidate.getName()))
                        .build();
        return new Registry(bridge, owners, failures);
    }

    private static Plugin plugin(String name) {
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (candidate.getName().equalsIgnoreCase(name)) return candidate;
        }
        return null;
    }

    private static SourceFailure failure(
            String provider, String id, String message, Throwable cause) {
        return new SourceFailure(
                "External item 'itembridge:" + provider + ":" + id + "': " + message, cause);
    }
}

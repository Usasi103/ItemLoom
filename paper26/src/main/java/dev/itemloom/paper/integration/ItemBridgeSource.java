package dev.itemloom.paper.integration;

import cn.gtemc.itembridge.api.context.BuildContext;
import cn.gtemc.itembridge.core.BukkitItemBridge;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Keeps only provider registrations; every request builds and clones its own item. */
final class ItemBridgeSource {
    private record Registry(
            BukkitItemBridge bridge, Map<String, Plugin> owners, Map<String, Throwable> failures) {}

    private final Function<String, Plugin> plugins;
    private final Supplier<Registry> discover;
    private Registry registry;

    ItemBridgeSource() {
        plugins = ItemBridgeSource::plugin;
        discover = ItemBridgeSource::discover;
    }

    ItemBridgeSource(BukkitItemBridge bridge, Map<String, Plugin> owners) {
        Map<String, Plugin> snapshot = Map.copyOf(owners);
        registry = new Registry(Objects.requireNonNull(bridge), snapshot, Map.of());
        Registry fixed = registry;
        plugins = snapshot::get;
        discover = () -> fixed;
    }

    ItemStack item(
            String provider,
            String id,
            Player player,
            BuildContext context,
            boolean checkExists,
            boolean required) {
        ExternalItemMaterial.requireAllowed(provider);
        Registry current = registry;
        Plugin plugin = current == null ? null : current.owners().get(provider);
        // Registration can be cached; item results cannot. Avoid copying/scanning Bukkit's
        // plugin array on every generation while still checking the owner's live state.
        if (plugin == null || !plugin.isEnabled()) plugin = plugins.apply(provider);
        if (plugin == null || !plugin.isEnabled()) {
            if (!required) return null;
            throw failure(provider, id, "plugin is missing or disabled", null);
        }
        try {
            if (current == null || current.owners().get(provider) != plugin) {
                current = discover.get();
                registry = current;
            }
            if (!current.bridge().hasProvider(provider))
                throw failure(
                        provider,
                        id,
                        "the installed plugin has no available ItemBridge provider",
                        current.failures().get(provider));
            if (checkExists && !current.bridge().has(provider, id)) return null;
            ItemStack item = current.bridge().buildOrNull(provider, id, player, context);
            if (item == null || item.isEmpty()) {
                if (!required) return null;
                throw failure(provider, id, "provider returned no item or AIR", null);
            }
            return item.clone();
        } catch (SourceFailure failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw failure(provider, id, "provider call failed: " + failure, failure);
        }
    }

    private static Registry discover() {
        Map<String, Plugin> owners = new HashMap<>();
        Map<String, Throwable> failures = new HashMap<>();
        BukkitItemBridge bridge =
                BukkitItemBridge.builder()
                        .detectSupportedPlugins(
                                name -> {
                                    String id = name.toLowerCase(Locale.ROOT);
                                    Plugin plugin = plugin(id);
                                    if (plugin != null) owners.put(id, plugin);
                                },
                                (name, error) -> failures.put(name.toLowerCase(Locale.ROOT), error),
                                plugin ->
                                        plugin.isEnabled()
                                                && ExternalItemMaterial.allowed(plugin.getName()))
                        .removeById("neigeitems")
                        .removeById("sxitem")
                        .removeById("itemloom")
                        .immutable(true)
                        .build();
        return new Registry(bridge, Map.copyOf(owners), Map.copyOf(failures));
    }

    private static Plugin plugin(String id) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins())
            if (plugin.getName().equalsIgnoreCase(id)) return plugin;
        return null;
    }

    private static SourceFailure failure(
            String provider, String id, String detail, Throwable cause) {
        return new SourceFailure(
                "External item 'itembridge:" + provider + ':' + id + "': " + detail, cause);
    }

    private static final class SourceFailure extends IllegalArgumentException {
        SourceFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

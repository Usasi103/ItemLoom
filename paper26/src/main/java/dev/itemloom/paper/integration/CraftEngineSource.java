package dev.itemloom.paper.integration;

import dev.itemloom.paper.ItemsService;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional public ItemSource adapter, verified against CraftEngine 26.9.1's shipped interfaces. */
public final class CraftEngineSource {
    private static final String ROOT = "net.momirealms.craftengine.";

    private CraftEngineSource() {}

    public static AutoCloseable install(JavaPlugin plugin, ItemsService items) {
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) return () -> {};
        AtomicReference<ItemsService> active = new AtomicReference<>(items);
        try {
            ClassLoader loader = craftEngine.getClass().getClassLoader();
            Class<?> engineType = Class.forName(ROOT + "core.plugin.CraftEngine", true, loader);
            Class<?> compatibility =
                    Class.forName(
                            ROOT + "core.plugin.compatibility.CompatibilityManager", true, loader);
            Class<?> sourceType =
                    Class.forName(ROOT + "core.plugin.compatibility.ItemSource", true, loader);
            Class<?> contextType = Class.forName(ROOT + "core.item.ItemBuildContext", true, loader);
            Class<?> playerType = Class.forName(ROOT + "core.entity.player.Player", true, loader);
            Class<?> itemType = Class.forName(ROOT + "core.item.Item", true, loader);
            Class<?> managerType = Class.forName(ROOT + "core.item.ItemManager", true, loader);
            Class<?> stackUtils = Class.forName(ROOT + "bukkit.util.ItemStackUtils", true, loader);
            Object engine = engineType.getMethod("instance").invoke(null);
            Object manager = engineType.getMethod("itemManager").invoke(engine);
            Object registry = engineType.getMethod("compatibilityManager").invoke(engine);
            Method player = contextType.getMethod("player");
            Method platformPlayer = playerType.getMethod("platformPlayer");
            Method wrap = managerType.getMethod("wrap", Object.class);
            Method unwrap = stackUtils.getMethod("getBukkitStack", itemType);
            Object previous =
                    compatibility
                            .getMethod("getItemSource", String.class)
                            .invoke(registry, "ItemLoom");
            if (previous != null && !"ItemLoom ItemSource".equals(previous.toString())) {
                throw new IllegalStateException(
                        "CraftEngine ItemLoom source is already registered");
            }
            Object source =
                    Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {sourceType},
                            (proxy, method, arguments) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    return switch (method.getName()) {
                                        case "toString" -> "ItemLoom ItemSource";
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == arguments[0];
                                        default -> null;
                                    };
                                }
                                if (method.getName().equals("plugin")) return "ItemLoom";
                                ItemsService service = active.get();
                                if (service == null) return null;
                                if (method.getName().equals("build")) {
                                    Object wrappedPlayer = player.invoke(arguments[1]);
                                    OfflinePlayer viewer =
                                            wrappedPlayer == null
                                                    ? null
                                                    : (OfflinePlayer)
                                                            platformPlayer.invoke(wrappedPlayer);
                                    String id = (String) arguments[0];
                                    if (!service.ids().contains(id)) return null;
                                    return wrap.invoke(
                                            manager, service.create(id, viewer, Map.of()));
                                }
                                if (method.getName().equals("id")) {
                                    ItemStack stack = (ItemStack) unwrap.invoke(null, arguments[0]);
                                    return service.identify(stack)
                                            .map(identity -> identity.id())
                                            .orElse(null);
                                }
                                throw new UnsupportedOperationException(
                                        "Unsupported CraftEngine ItemSource method: " + method);
                            });
            compatibility.getMethod("registerItemSource", sourceType).invoke(registry, source);
            plugin.getLogger().info("Registered CraftEngine ItemLoom item source");
            // CE exposes no unregister operation. Release the service and leave an inert source
            // until CE stops.
            return () -> active.set(null);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            active.set(null);
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "CraftEngine ItemLoom source registration failed; external ItemLoom items are unavailable",
                            failure);
            return () -> {};
        }
    }
}

package dev.itemloom.paper.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import dev.itemloom.paper.ItemsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Legacy external-item lookup order; provider classes are loaded only from enabled plugins. */
public final class OptionalItemSources {
    private final Map<String, Provider> providers = new HashMap<>();

    public ItemStack getHookedItem(String id) {
        ItemsService.requireThread();
        int split = id.indexOf(':');
        if (split >= 0) {
            String key = id.substring(split + 1);
            String provider =
                    switch (id.substring(0, split).toLowerCase(Locale.ROOT)) {
                        case "mm", "mythicmobs" -> "MythicMobs";
                        case "mg", "magicgem" -> "MagicGem";
                        case "ia", "itemsadder" -> "ItemsAdder";
                        case "or", "oraxen" -> "Oraxen";
                        default -> null;
                    };
            ItemStack item =
                    provider == null
                            ? switch (id.substring(0, split).toLowerCase(Locale.ROOT)) {
                                case "vn", "vanilla" -> vanilla(key);
                                default -> null;
                            }
                            : lookup(provider, key, false);
            if (item != null) return item;
        }
        for (String provider : new String[] {"MythicMobs", "MagicGem", "ItemsAdder", "Oraxen"}) {
            ItemStack item = lookup(provider, id, true);
            if (item != null) return item;
        }
        return vanilla(id.toUpperCase(Locale.ROOT));
    }

    private static ItemStack vanilla(String id) {
        Material material = Material.matchMaterial(id);
        return material == null ? null : new ItemStack(material);
    }

    private ItemStack lookup(String name, String id, boolean checkExists) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        if (plugin == null || !plugin.isEnabled()) {
            providers.remove(name);
            return null;
        }
        Provider provider = providers.get(name);
        if (provider == null || provider.plugin != plugin) {
            provider = new Provider(plugin);
            providers.put(name, provider);
        }
        return provider.item(id, checkExists);
    }

    private static final class Provider {
        final Plugin plugin;
        final Object target;
        final Method lookup, exists, build;

        Provider(Plugin plugin) {
            this.plugin = plugin;
            try {
                var loader = plugin.getClass().getClassLoader();
                switch (plugin.getName()) {
                    case "MythicMobs" -> {
                        Class<?> entry = loader.loadClass("io.lumine.mythic.bukkit.MythicBukkit");
                        target =
                                call(
                                        entry.getMethod("getItemManager"),
                                        call(entry.getMethod("inst"), null));
                        lookup = target.getClass().getMethod("getItemStack", String.class);
                        exists = target.getClass().getMethod("getItemNames");
                        build = null;
                    }
                    case "MagicGem" -> {
                        target = null;
                        lookup =
                                loader.loadClass("pku.yim.magicgem.gem.GemManager")
                                        .getMethod("getGemByName", String.class);
                        exists = null;
                        build = lookup.getReturnType().getMethod("getRealGem");
                    }
                    case "ItemsAdder" -> {
                        target = null;
                        Class<?> entry = loader.loadClass("dev.lone.itemsadder.api.CustomStack");
                        lookup = entry.getMethod("getInstance", String.class);
                        exists = entry.getMethod("isInRegistry", String.class);
                        build = entry.getMethod("getItemStack");
                    }
                    case "Oraxen" -> {
                        target = null;
                        Class<?> entry = loader.loadClass("io.th0rgal.oraxen.api.OraxenItems");
                        lookup = entry.getMethod("getItemById", String.class);
                        exists = entry.getMethod("exists", String.class);
                        build = lookup.getReturnType().getMethod("build");
                    }
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported item provider " + plugin.getName());
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "Unsupported item API: " + plugin.getName(), failure);
            }
        }

        ItemStack item(String id, boolean checkExists) {
            if (checkExists && exists != null) {
                Object found =
                        exists.getParameterCount() == 0
                                ? call(exists, target)
                                : call(exists, target, id);
                if (found instanceof java.util.Collection<?> ids
                        ? !ids.contains(id)
                        : !Boolean.TRUE.equals(found)) return null;
            }
            Object value = call(lookup, target, id);
            if (value == null) return null;
            ItemStack item = (ItemStack) (build == null ? value : call(build, value));
            return item == null ? null : item.clone();
        }
    }

    private static Object call(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException("Item provider failed", failure.getCause());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Item provider API inaccessible", failure);
        }
    }
}

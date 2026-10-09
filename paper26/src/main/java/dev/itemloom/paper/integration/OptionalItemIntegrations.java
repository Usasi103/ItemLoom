package dev.itemloom.paper.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Optional providers are resolved through their own loaders; their classes never enter our API. */
public final class OptionalItemIntegrations {
    private static final Object VAULT_LOCK = new Object();
    private Plugin vaultPlugin, mythicPlugin;
    private Vault vault;
    private Mythic mythic;

    public synchronized Vault vault() {
        Plugin provider = enabled("Vault");
        if (provider == null) {
            vaultPlugin = null;
            vault = null;
            return null;
        }
        if (provider != vaultPlugin) {
            vault = new Vault(provider);
            vaultPlugin = provider;
        }
        return vault;
    }

    public synchronized Mythic mythic() {
        Plugin provider = enabled("MythicMobs");
        if (provider == null) {
            mythicPlugin = null;
            mythic = null;
            return null;
        }
        if (provider != mythicPlugin) {
            mythic = new Mythic(provider);
            mythicPlugin = provider;
        }
        return mythic;
    }

    private static Plugin enabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    public static final class Vault {
        private final Plugin plugin;
        private final Class<?> economy;
        private final Method deposit, withdraw, balance;

        private Vault(Plugin plugin) {
            this.plugin = plugin;
            try {
                economy =
                        plugin.getClass()
                                .getClassLoader()
                                .loadClass("net.milkbowl.vault.economy.Economy");
                deposit = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                balance = economy.getMethod("getBalance", OfflinePlayer.class);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(
                        "Installed Vault does not expose the supported economy API", error);
            }
        }

        private Object provider() {
            if (!plugin.isEnabled()) return null;
            var registration = Bukkit.getServicesManager().getRegistration(economy);
            return registration == null || !registration.getPlugin().isEnabled()
                    ? null
                    : registration.getProvider();
        }

        public void giveMoney(OfflinePlayer player, double amount) {
            synchronized (VAULT_LOCK) {
                Object provider = provider();
                if (provider != null) invoke(deposit, provider, player, amount);
            }
        }

        public void takeMoney(OfflinePlayer player, double amount) {
            synchronized (VAULT_LOCK) {
                Object provider = provider();
                if (provider != null) invoke(withdraw, provider, player, amount);
            }
        }

        public double getMoney(OfflinePlayer player) {
            synchronized (VAULT_LOCK) {
                Object provider = provider();
                return provider == null
                        ? 0
                        : ((Number) invoke(balance, provider, player)).doubleValue();
            }
        }
    }

    public static final class Mythic {
        private final Plugin plugin;
        private final Object api;
        private final Method isMob, instance, type, name, cast;

        private Mythic(Plugin plugin) {
            this.plugin = plugin;
            try {
                Class<?> entry =
                        plugin.getClass()
                                .getClassLoader()
                                .loadClass("io.lumine.mythic.bukkit.MythicBukkit");
                api =
                        invoke(
                                entry.getMethod("getAPIHelper"),
                                invoke(entry.getMethod("inst"), null));
                isMob = api.getClass().getMethod("isMythicMob", Entity.class);
                instance = api.getClass().getMethod("getMythicMobInstance", Entity.class);
                type = instance.getReturnType().getMethod("getType");
                name = type.getReturnType().getMethod("getInternalName");
                cast =
                        api.getClass()
                                .getMethod(
                                        "castSkill",
                                        Entity.class,
                                        String.class,
                                        Entity.class,
                                        Location.class,
                                        Collection.class,
                                        Collection.class,
                                        float.class);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(
                        "Installed MythicMobs does not expose the supported skill and mob APIs",
                        error);
            }
        }

        public String getMythicId(Entity entity) {
            if (!plugin.isEnabled() || !Boolean.TRUE.equals(invoke(isMob, api, entity)))
                return null;
            return (String) invoke(name, invoke(type, invoke(instance, api, entity)));
        }

        public void castSkill(Entity entity, String skill) {
            castSkill(entity, skill, null);
        }

        public void castSkill(Entity entity, String skill, Entity trigger) {
            if (plugin.isEnabled())
                invoke(cast, api, entity, skill, trigger, entity.getLocation(), null, null, 1.0f);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException(
                    "Optional provider call failed: " + method.getName(), cause);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(
                    "Optional provider API is inaccessible: " + method.getName(), error);
        }
    }
}

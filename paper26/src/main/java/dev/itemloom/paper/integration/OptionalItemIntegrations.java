package dev.itemloom.paper.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Optional plugin API bindings are scoped to the supplying plugin's class loader. */
public final class OptionalItemIntegrations {
    private static final Object ECONOMY_LOCK = new Object();
    private Vault vault;
    private Mythic mythic;

    public synchronized Vault vault() {
        Plugin provider = enabled("Vault");
        if (provider == null) vault = null;
        else if (vault == null || vault.owner != provider) vault = new Vault(provider);
        return vault;
    }

    public synchronized Mythic mythic() {
        Plugin provider = enabled("MythicMobs");
        if (provider == null) mythic = null;
        else if (mythic == null || mythic.owner != provider) mythic = new Mythic(provider);
        return mythic;
    }

    private static Plugin enabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    public static final class Vault {
        private final Plugin owner;
        private final Class<?> economy;
        private final Method deposit;
        private final Method withdraw;
        private final Method balance;

        private Vault(Plugin owner) {
            this.owner = owner;
            try {
                economy =
                        owner.getClass()
                                .getClassLoader()
                                .loadClass("net.milkbowl.vault.economy.Economy");
                deposit = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                balance = economy.getMethod("getBalance", OfflinePlayer.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Vault Economy API", failure);
            }
        }

        private Object service() {
            if (!owner.isEnabled()) return null;
            var registration = Bukkit.getServicesManager().getRegistration(economy);
            return registration != null && registration.getPlugin().isEnabled()
                    ? registration.getProvider()
                    : null;
        }

        public void giveMoney(OfflinePlayer player, double amount) {
            synchronized (ECONOMY_LOCK) {
                Object service = service();
                if (service != null) invoke(deposit, service, player, amount);
            }
        }

        public void takeMoney(OfflinePlayer player, double amount) {
            synchronized (ECONOMY_LOCK) {
                Object service = service();
                if (service != null) invoke(withdraw, service, player, amount);
            }
        }

        public double getMoney(OfflinePlayer player) {
            synchronized (ECONOMY_LOCK) {
                Object service = service();
                return service == null
                        ? 0
                        : ((Number) invoke(balance, service, player)).doubleValue();
            }
        }
    }

    public static final class Mythic {
        private final Plugin owner;
        private final Method instance;
        private final Method helper;
        private final Method isMythic;
        private final Method activeMob;
        private final Method mobType;
        private final Method internalName;
        private final Method cast;

        private Mythic(Plugin owner) {
            this.owner = owner;
            try {
                Class<?> entry =
                        owner.getClass()
                                .getClassLoader()
                                .loadClass("io.lumine.mythic.bukkit.MythicBukkit");
                instance = entry.getMethod("inst");
                helper = entry.getMethod("getAPIHelper");
                Class<?> api = helper.getReturnType();
                isMythic = api.getMethod("isMythicMob", Entity.class);
                activeMob = api.getMethod("getMythicMobInstance", Entity.class);
                mobType = activeMob.getReturnType().getMethod("getType");
                internalName = mobType.getReturnType().getMethod("getInternalName");
                cast =
                        api.getMethod(
                                "castSkill",
                                Entity.class,
                                String.class,
                                Entity.class,
                                Location.class,
                                Collection.class,
                                Collection.class,
                                float.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported MythicMobs API", failure);
            }
        }

        private Object api() {
            return invoke(helper, invoke(instance, null));
        }

        public String getMythicId(Entity entity) {
            if (!owner.isEnabled()) return null;
            Object api = api();
            if (!Boolean.TRUE.equals(invoke(isMythic, api, entity))) return null;
            Object mob = invoke(activeMob, api, entity);
            return mob == null ? null : (String) invoke(internalName, invoke(mobType, mob));
        }

        public void castSkill(Entity entity, String skill) {
            castSkill(entity, skill, null);
        }

        public void castSkill(Entity entity, String skill, Entity trigger) {
            if (!owner.isEnabled()) return;
            invoke(cast, api(), entity, skill, trigger, entity.getLocation(), null, null, 1.0f);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Optional plugin API invocation failed", failure);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Optional plugin API is inaccessible", failure);
        }
    }
}

package dev.itemloom.paper.integration;

import dev.itemloom.api.ItemLoom;
import dev.itemloom.paper.ItemsService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Provides only native item drops; MythicMobs retains selection and delivery ownership. */
public final class MythicDrops implements Listener, AutoCloseable {
    private static final Set<String> RESERVED =
            Set.of(
                    "item",
                    "id",
                    "amount",
                    "a",
                    "chance",
                    "c",
                    "weight",
                    "w",
                    "condition",
                    "conditions",
                    "triggercondition",
                    "triggerconditions");
    private final JavaPlugin plugin;
    private final Plugin provider;
    private final ItemLoom items;
    private final ClassLoader loader;
    private final Class<? extends Event> eventType;
    private final Class<?> itemDrop;
    private final Class<?> activeMob;
    private final Method dropName;
    private final Method config;
    private final Method register;
    private final Method string;
    private final Method entries;
    private final Method placeholder;
    private final Method expand;
    private final Method cause;
    private final Method dropper;
    private final Method level;
    private final Method entity;
    private final Method mobDisplay;
    private final Method mobId;
    private final Method mobType;
    private final Method typeName;
    private final Method adapt;
    private volatile boolean closed;

    public static AutoCloseable install(JavaPlugin plugin, ItemLoom items) {
        ItemsService.requireThread();
        Plugin provider = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (provider == null || !provider.isEnabled()) return () -> {};
        var bridge = new MythicDrops(plugin, provider, items);
        Bukkit.getPluginManager()
                .registerEvent(
                        bridge.eventType,
                        bridge,
                        EventPriority.NORMAL,
                        (listener, event) -> bridge.loaded(event),
                        plugin);
        return bridge;
    }

    private MythicDrops(JavaPlugin plugin, Plugin provider, ItemLoom items) {
        this.plugin = plugin;
        this.provider = provider;
        this.items = items;
        loader = provider.getClass().getClassLoader();
        try {
            eventType = type("bukkit.events.MythicDropLoadEvent").asSubclass(Event.class);
            itemDrop = type("api.drops.IItemDrop");
            activeMob = type("core.mobs.ActiveMob");
            Class<?> metadata = type("api.drops.DropMetadata");
            Class<?> line = type("api.config.MythicLineConfig");
            dropName = eventType.getMethod("getDropName");
            config = eventType.getMethod("getConfig");
            register = eventType.getMethod("register", type("api.drops.IDrop"));
            string = line.getMethod("getString", String.class, String.class);
            entries = line.getMethod("entrySet");
            placeholder = line.getMethod("getPlaceholderString", String.class, String.class);
            expand =
                    type("api.skills.placeholders.PlaceholderString")
                            .getMethod("get", type("core.skills.placeholders.PlaceholderMeta"));
            cause = metadata.getMethod("getCause");
            dropper = metadata.getMethod("getDropper");
            level = metadata.getMethod("getLevel");
            entity = type("api.adapters.AbstractEntity").getMethod("getBukkitEntity");
            mobDisplay = activeMob.getMethod("getDisplayName");
            mobId = activeMob.getMethod("getUniqueId");
            mobType = activeMob.getMethod("getType");
            typeName = mobType.getReturnType().getMethod("getInternalName");
            adapt = type("bukkit.BukkitAdapter").getMethod("adapt", ItemStack.class);
            itemDrop.getMethod("getDrop", metadata, double.class);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported MythicMobs 5 drop API", failure);
        }
    }

    private Class<?> type(String suffix) throws ClassNotFoundException {
        return loader.loadClass("io.lumine.mythic." + suffix);
    }

    private void loaded(Event event) {
        if (closed || !"itemloom".equalsIgnoreCase((String) call(dropName, event))) return;
        Object configuration = call(config, event);
        String key = call(string, configuration, "item", null) != null ? "item" : "id";
        String id = (String) call(string, configuration, key, null);
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Mythic itemloom drop requires an item ID");
        Object itemId = call(placeholder, configuration, key, id);
        Map<String, Object> data = new LinkedHashMap<>();
        for (Object value : (Set<?>) call(entries, configuration)) {
            var entry = (Map.Entry<?, ?>) value;
            String name = String.valueOf(entry.getKey());
            String target;
            if (name.startsWith("data.")) target = name.substring(5);
            else {
                if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) continue;
                target = name;
            }
            if (target.isBlank() || data.containsKey(target))
                throw new IllegalArgumentException(
                        "Invalid or duplicate itemloom drop parameter: " + target);
            data.put(
                    target,
                    call(placeholder, configuration, name, String.valueOf(entry.getValue())));
        }
        Object proxy =
                Proxy.newProxyInstance(
                        loader,
                        new Class<?>[] {itemDrop},
                        (self, method, arguments) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "equals" -> self == arguments[0];
                                    case "hashCode" -> System.identityHashCode(self);
                                    case "toString" -> "ItemLoom drop[" + id + "]";
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    method.toString());
                                };
                            }
                            if (method.getName().equals("getDrop")
                                    && arguments != null
                                    && arguments.length == 2) {
                                return generate(
                                        itemId,
                                        data,
                                        arguments[0],
                                        ((Number) arguments[1]).doubleValue());
                            }
                            throw new UnsupportedOperationException(method.toString());
                        });
        call(register, event, proxy);
    }

    private Object generate(
            Object id, Map<String, Object> placeholders, Object metadata, double amount) {
        ItemsService.requireThread();
        if (closed || !plugin.isEnabled() || !provider.isEnabled())
            throw new IllegalStateException("ItemLoom Mythic drop bridge is closed");
        if (!Double.isFinite(amount) || amount > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid itemloom drop amount: " + amount);
        if (amount < 1) return call(adapt, null, new ItemStack(Material.AIR));
        var values = new LinkedHashMap<String, String>();
        placeholders.forEach(
                (key, value) -> values.put(key, (String) call(expand, value, metadata)));
        values.putIfAbsent("mob_level", String.valueOf(call(level, metadata)));
        Object mob = ((Optional<?>) call(dropper, metadata)).orElse(null);
        if (activeMob.isInstance(mob)) {
            values.putIfAbsent("mob_name_display", String.valueOf(call(mobDisplay, mob)));
            values.putIfAbsent(
                    "mob_name_internal", String.valueOf(call(typeName, call(mobType, mob))));
            values.putIfAbsent("mob_uuid", String.valueOf(call(mobId, mob)));
        }
        Object causing = ((Optional<?>) call(cause, metadata)).orElse(null);
        Entity source = causing == null ? null : (Entity) call(entity, causing);
        ItemStack generated =
                items.create(
                        (String) call(expand, id, metadata),
                        source instanceof Player player ? player : null,
                        values);
        if (generated == null)
            throw new IllegalArgumentException("ItemLoom drop generator returned null");
        ItemStack result = generated.clone();
        if (!result.isEmpty()) result.setAmount((int) Math.floor(amount));
        return call(adapt, null, result);
    }

    private static Object call(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("MythicMobs drop API failed", failure);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("MythicMobs drop API is inaccessible", failure);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
    }
}

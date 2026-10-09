package dev.itemloom.paper.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.paper.ItemsService;
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

/**
 * Optional MM5 native drop provider. Reflection is confined to the installed plugin's public
 * interfaces: no bundled Mythic classes, downloaded API jar, or legacy MM4 adapter is needed.
 * Mythic owns selection, count, equipment slots, tables and delivery. Each invocation creates
 * one independent stack; repeated table rolls are the native way to roll equipment separately.
 */
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
    private final JavaPlugin owner;
    private final Plugin mythic;
    private final ItemLoom items;
    private final Class<?> itemDrop;
    private final Method dropName, config, register, string, entries, placeholder, resolve, adapt;
    private final Method cause, dropper, entity, level, mobName, mobId, mobType, typeName;
    private final Class<?> activeMob;
    private volatile boolean closed;

    public static AutoCloseable install(JavaPlugin owner, ItemLoom items) {
        ItemsService.requireThread();
        Plugin provider = Bukkit.getPluginManager().getPlugin("MythicMobs");
        return provider == null || !provider.isEnabled()
                ? () -> {}
                : new MythicDrops(owner, provider, items);
    }

    private MythicDrops(JavaPlugin owner, Plugin mythic, ItemLoom items) {
        this.owner = owner;
        this.mythic = mythic;
        this.items = items;
        try {
            ClassLoader loader = mythic.getClass().getClassLoader();
            Class<? extends Event> eventType =
                    loader.loadClass("io.lumine.mythic.bukkit.events.MythicDropLoadEvent")
                            .asSubclass(Event.class);
            Class<?> line = loader.loadClass("io.lumine.mythic.api.config.MythicLineConfig");
            Class<?> metadata = loader.loadClass("io.lumine.mythic.api.drops.DropMetadata");
            itemDrop = loader.loadClass("io.lumine.mythic.api.drops.IItemDrop");
            // Fail during installation if the actual API is not the supported MM5 contract.
            itemDrop.getMethod("getDrop", metadata, double.class);
            dropName = eventType.getMethod("getDropName");
            config = eventType.getMethod("getConfig");
            register =
                    eventType.getMethod(
                            "register", loader.loadClass("io.lumine.mythic.api.drops.IDrop"));
            string = line.getMethod("getString", String.class, String.class);
            entries = line.getMethod("entrySet");
            placeholder = line.getMethod("getPlaceholderString", String.class, String.class);
            resolve =
                    loader.loadClass("io.lumine.mythic.api.skills.placeholders.PlaceholderString")
                            .getMethod(
                                    "get",
                                    loader.loadClass(
                                            "io.lumine.mythic.core.skills.placeholders.PlaceholderMeta"));
            adapt =
                    loader.loadClass("io.lumine.mythic.bukkit.BukkitAdapter")
                            .getMethod("adapt", ItemStack.class);
            cause = metadata.getMethod("getCause");
            dropper = metadata.getMethod("getDropper");
            level = metadata.getMethod("getLevel");
            entity =
                    loader.loadClass("io.lumine.mythic.api.adapters.AbstractEntity")
                            .getMethod("getBukkitEntity");
            activeMob = loader.loadClass("io.lumine.mythic.core.mobs.ActiveMob");
            mobName = activeMob.getMethod("getDisplayName");
            mobId = activeMob.getMethod("getUniqueId");
            mobType = activeMob.getMethod("getType");
            typeName = mobType.getReturnType().getMethod("getInternalName");
            Bukkit.getPluginManager()
                    .registerEvent(
                            eventType,
                            this,
                            EventPriority.NORMAL,
                            (listener, event) -> loaded(event),
                            owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "MythicMobs does not expose the supported MM5 native drop API", error);
        }
    }

    private void loaded(Event event) {
        if (closed || !"itemloom".equalsIgnoreCase((String) call(dropName, event))) return;
        Object line = call(config, event);
        String key = call(string, line, "item", null) == null ? "id" : "item";
        String id = (String) call(string, line, key, "");
        if (id.isBlank())
            throw new IllegalArgumentException("itemloom drop requires item=<id> or id=<id>");
        Object itemId = call(placeholder, line, key, id);
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (Object raw : (Set<?>) call(entries, line)) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
            String name = entry.getKey().toString();
            // data.* permits item parameters whose names collide with native MM fields.
            boolean explicit = name.startsWith("data.");
            if (explicit || !RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
                String parameter = explicit ? name.substring(5) : name;
                if (parameter.isBlank() || parameters.containsKey(parameter))
                    throw new IllegalArgumentException(
                            "Duplicate or blank itemloom parameter: " + parameter);
                parameters.put(
                        parameter, call(placeholder, line, name, entry.getValue().toString()));
            }
        }
        Map<String, Object> frozen = Map.copyOf(parameters);
        Object provider =
                Proxy.newProxyInstance(
                        itemDrop.getClassLoader(),
                        new Class<?>[] {itemDrop},
                        (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "getDrop" ->
                                        generate(itemId, frozen, args[0], (double) args[1]);
                                case "toString" -> "ItemLoom drop " + id;
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default ->
                                        throw new UnsupportedOperationException(
                                                "Unknown Mythic drop operation: " + method);
                            };
                        });
        call(register, event, provider);
    }

    private Object generate(
            Object itemId, Map<String, Object> parameters, Object metadata, double amount) {
        ItemsService.requireThread();
        if (closed || !owner.isEnabled() || !mythic.isEnabled())
            throw new IllegalStateException("ItemLoom Mythic bridge is closed");
        if (!Double.isFinite(amount) || amount > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid Mythic drop amount: " + amount);
        if (amount < 1) return call(adapt, null, new ItemStack(Material.AIR));
        Map<String, String> values = new LinkedHashMap<>();
        parameters.forEach(
                (key, value) -> values.put(key, (String) call(resolve, value, metadata)));
        values.putIfAbsent("mob_level", String.valueOf(call(level, metadata)));
        Object mob = ((Optional<?>) call(dropper, metadata)).orElse(null);
        if (activeMob.isInstance(mob)) {
            values.putIfAbsent("mob_name_display", String.valueOf(call(mobName, mob)));
            values.putIfAbsent(
                    "mob_name_internal", String.valueOf(call(typeName, call(mobType, mob))));
            values.putIfAbsent("mob_uuid", String.valueOf(call(mobId, mob)));
        }
        Object source = ((Optional<?>) call(cause, metadata)).orElse(null);
        Entity bukkit = source == null ? null : (Entity) call(entity, source);
        String id = (String) call(resolve, itemId, metadata);
        ItemStack result =
                items.create(id, bukkit instanceof Player player ? player : null, values);
        if (result == null)
            throw new IllegalStateException("ItemLoom generator returned null: " + id);
        result = result.clone();
        if (!result.isEmpty()) result.setAmount((int) Math.floor(amount));
        return call(adapt, null, result);
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
    }

    private static Object call(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException(
                    "Mythic drop call failed: " + method.getName(), error.getCause());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(
                    "Mythic drop API inaccessible: " + method.getName(), error);
        }
    }
}

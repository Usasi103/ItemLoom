package dev.itemloom.paper.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import dev.itemloom.paper.ItemsService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** MM5 selects native tables, but only item rewards can participate in bag consumption. */
public final class MythicLootTables {
    // ClassValue releases bindings with the optional plugin's class loader; no table/player state
    // is cached.
    private static final ClassValue<MythicLootTables> BINDINGS =
            new ClassValue<>() {
                @Override
                protected MythicLootTables computeValue(Class<?> type) {
                    try {
                        return new MythicLootTables(type);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(
                                "Unsupported MythicMobs item table API", failure);
                    }
                }
            };
    private final ClassLoader loader;
    private final Class<?> table, drop, custom, nested, itemDrop, metadata, abstractItem;
    private final Method amount, unwrap, generateItem, adaptItem;
    private final Method manager,
            findTable,
            tableEntries,
            entriesView,
            nestedTable,
            adaptEntity,
            generateTable,
            bagDrops;
    private final Constructor<?> newMetadata;

    private MythicLootTables(Class<?> plugin) throws ReflectiveOperationException {
        this.loader = plugin.getClassLoader();
        table = type("core.drops.DropTable");
        drop = type("core.drops.Drop");
        custom = type("core.drops.droppables.CustomDrop");
        nested = type("core.drops.droppables.DropTableDrop");
        itemDrop = type("api.drops.IItemDrop");
        metadata = type("api.drops.DropMetadata");
        abstractItem = type("api.adapters.AbstractItemStack");
        amount = drop.getMethod("getAmount");
        unwrap = custom.getMethod("getDrop");
        generateItem = itemDrop.getMethod("getDrop", metadata, double.class);
        adaptItem = type("bukkit.BukkitAdapter").getMethod("adapt", abstractItem);
        manager = plugin.getMethod("getDropManager");
        findTable = manager.getReturnType().getMethod("getDropTable", String.class);
        tableEntries = table.getMethod("getDrops");
        entriesView = tableEntries.getReturnType().getMethod("getView");
        nestedTable = nested.getMethod("getDropTable");
        adaptEntity = type("bukkit.BukkitAdapter").getMethod("adapt", Entity.class);
        newMetadata =
                type("core.drops.DropMetadataImpl")
                        .getConstructor(
                                type("api.skills.SkillCaster"),
                                type("api.adapters.AbstractEntity"),
                                double.class);
        generateTable = table.getMethod("generate", metadata);
        bagDrops = generateTable.getReturnType().getMethod("getDrops");
    }

    public static List<ItemStack> generate(String id, Player player) {
        ItemsService.requireThread();
        var plugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (plugin == null || !plugin.isEnabled())
            throw new IllegalStateException("MythicMobs is unavailable");
        try {
            var api = BINDINGS.get(plugin.getClass());
            Object manager = call(api.manager, plugin);
            Object table =
                    ((Optional<?>) call(api.findTable, manager, id))
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown MythicMobs drop table: " + id));
            api.new Validation().validate(table, 0);
            Object entity = call(api.adaptEntity, null, player);
            Object meta = api.newMetadata.newInstance(null, entity, 1.0);
            Object bag = call(api.generateTable, table, meta);
            Collection<?> drops = (Collection<?>) call(api.bagDrops, bag);
            if (drops.size() > 256)
                throw new IllegalArgumentException("Mythic bag exceeds 256 reward entries");
            // Inspect every selected entry before invoking a generator. Never call LootBag.give,
            // which would also execute commands, currency and experience before bag consumption.
            List<Reward> rewards = new ArrayList<>();
            for (Object drop : drops) {
                Object generator = api.item(drop);
                double count = (double) call(api.amount, drop);
                if (!Double.isFinite(count) || count < 0 || count > 4096)
                    throw new IllegalArgumentException("Invalid Mythic reward amount");
                rewards.add(new Reward(generator, count));
            }
            List<ItemStack> result = new ArrayList<>();
            for (Reward reward : rewards) {
                Object item = call(api.generateItem, reward.generator(), meta, reward.amount());
                if (item == null)
                    throw new IllegalStateException("Mythic reward generator returned null");
                result.add(((ItemStack) call(api.adaptItem, null, item)).clone());
            }
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported MythicMobs item table API", failure);
        }
    }

    private record Reward(Object generator, double amount) {}

    private Object item(Object value) {
        Object provider =
                custom.isInstance(value)
                        ? ((Optional<?>) call(unwrap, value))
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Unloaded Mythic custom drop"))
                        : value;
        if (!itemDrop.isInstance(provider))
            throw new IllegalArgumentException(
                    "Loot bags require item-only Mythic tables: " + provider.getClass().getName());
        return provider;
    }

    /** Recheck each request: MM or another plugin can mutate an existing table without replacing its identity. */
    private final class Validation {
        private record Shape(int height, int expandedWork) {}

        private final Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<Object, Shape> complete = new IdentityHashMap<>();
        private int remaining = 4096;

        private Shape validate(Object value, int depth) {
            if (value == null || depth > 32 || path.contains(value))
                throw new IllegalArgumentException("Cyclic or excessive Mythic table nesting");
            Shape known = complete.get(value);
            if (known != null) {
                if (depth + known.height() > 32)
                    throw new IllegalArgumentException("Excessive Mythic table nesting");
                return known;
            }
            path.add(value);
            try {
                Collection<?> entries =
                        (Collection<?>) call(entriesView, call(tableEntries, value));
                if (entries.size() > 256)
                    throw new IllegalArgumentException("Mythic table exceeds 256 entries");
                remaining -= 1 + entries.size();
                if (remaining < 0)
                    throw new IllegalArgumentException(
                            "Mythic table validation exceeds 4096 visits");
                int height = 0, expandedWork = 1 + entries.size();
                for (Object entry : entries) {
                    if (nested.isInstance(entry)) {
                        Shape child = validate(call(nestedTable, entry), depth + 1);
                        height = Math.max(height, 1 + child.height());
                        expandedWork += child.expandedWork();
                        if (expandedWork > 4096)
                            throw new IllegalArgumentException(
                                    "Mythic table expansion exceeds 4096 visits");
                    } else item(entry);
                }
                Shape shape = new Shape(height, expandedWork);
                complete.put(value, shape);
                return shape;
            } finally {
                path.remove(value);
            }
        }
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return loader.loadClass("io.lumine.mythic." + name);
    }

    private static Object call(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException(
                    "Mythic table call failed: " + method.getName(), error.getCause());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Mythic table API inaccessible", error);
        }
    }
}

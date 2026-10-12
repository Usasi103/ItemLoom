package dev.itemloom.paper.integration;

import dev.itemloom.paper.ItemsService;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Validates a current Mythic table graph before asking its native selector for item rewards. */
public final class MythicLootTables {
    private static final ClassValue<MythicLootTables> BINDINGS =
            new ClassValue<>() {
                @Override
                protected MythicLootTables computeValue(Class<?> type) {
                    return new MythicLootTables(type);
                }
            };
    private final Class<?> tableType;
    private final Class<?> tableDropType;
    private final Class<?> customType;
    private final Class<?> itemType;
    private final Method manager;
    private final Method findTable;
    private final Method tableDrops;
    private final Method view;
    private final Method generate;
    private final Method selectedDrops;
    private final Method childTable;
    private final Method customDrop;
    private final Method amount;
    private final Method item;
    private final Method adaptEntity;
    private final Method adaptItem;
    private final Constructor<?> metadata;

    private MythicLootTables(Class<?> pluginType) {
        ClassLoader loader = pluginType.getClassLoader();
        try {
            tableType = load(loader, "core.drops.DropTable");
            tableDropType = load(loader, "core.drops.droppables.DropTableDrop");
            customType = load(loader, "core.drops.droppables.CustomDrop");
            itemType = load(loader, "api.drops.IItemDrop");
            Class<?> dropMetadata = load(loader, "api.drops.DropMetadata");
            Class<?> abstractEntity = load(loader, "api.adapters.AbstractEntity");
            Class<?> adapter = load(loader, "bukkit.BukkitAdapter");
            manager = pluginType.getMethod("getDropManager");
            findTable = manager.getReturnType().getMethod("getDropTable", String.class);
            tableDrops = tableType.getMethod("getDrops");
            view = tableDrops.getReturnType().getMethod("getView");
            generate = tableType.getMethod("generate", dropMetadata);
            selectedDrops = generate.getReturnType().getMethod("getDrops");
            childTable = tableDropType.getMethod("getDropTable");
            customDrop = customType.getMethod("getDrop");
            amount = load(loader, "core.drops.Drop").getMethod("getAmount");
            item = itemType.getMethod("getDrop", dropMetadata, double.class);
            adaptEntity = adapter.getMethod("adapt", Entity.class);
            adaptItem = adapter.getMethod("adapt", load(loader, "api.adapters.AbstractItemStack"));
            metadata =
                    load(loader, "core.drops.DropMetadataImpl")
                            .getConstructor(
                                    load(loader, "api.skills.SkillCaster"),
                                    abstractEntity,
                                    double.class);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported MythicMobs 5 loot table API", failure);
        }
    }

    private static Class<?> load(ClassLoader loader, String suffix) throws ClassNotFoundException {
        return loader.loadClass("io.lumine.mythic." + suffix);
    }

    public static List<ItemStack> generate(String name, Player player) {
        ItemsService.requireThread();
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (plugin == null || !plugin.isEnabled())
            throw new IllegalArgumentException("MythicMobs is not enabled");
        return BINDINGS.get(plugin.getClass()).collect(plugin, name, player);
    }

    private List<ItemStack> collect(Plugin plugin, String name, Player player) {
        Object table =
                ((Optional<?>) call(findTable, call(manager, plugin), name))
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown MythicMobs drop table: " + name));
        new Validation().visit(table, 0);
        Object context = metadata(player);
        Object bag = call(generate, table, context);
        Collection<?> selected = collection(call(selectedDrops, bag));
        if (selected.size() > 256)
            throw new IllegalArgumentException("Too many selected MythicMobs rewards");
        var rewards = new ArrayList<Reward>();
        for (Object drop : selected) {
            if (rewards.size() >= 256)
                throw new IllegalArgumentException("Too many selected MythicMobs rewards");
            Object generator = generator(drop);
            double count = ((Number) call(amount, drop)).doubleValue();
            if (!Double.isFinite(count) || count < 0 || count > 4096)
                throw new IllegalArgumentException("Invalid MythicMobs reward amount: " + count);
            rewards.add(new Reward(generator, count));
        }
        var result = new ArrayList<ItemStack>(rewards.size());
        for (Reward reward : rewards) {
            Object generated = call(item, reward.generator, context, reward.amount);
            if (generated == null)
                throw new IllegalArgumentException("MythicMobs item reward returned null");
            ItemStack value = (ItemStack) call(adaptItem, null, generated);
            if (value == null)
                throw new IllegalArgumentException("MythicMobs item adaptation returned null");
            result.add(value.clone());
        }
        return result;
    }

    private record Reward(Object generator, double amount) {}

    private record Shape(int height, int work) {}

    private final class Validation {
        private final Map<Object, Shape> checked = new IdentityHashMap<>();
        private final Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());
        private int inspections;

        Shape visit(Object table, int depth) {
            if (!tableType.isInstance(table))
                throw new IllegalArgumentException("Invalid MythicMobs nested table");
            if (depth > 32) throw new IllegalArgumentException("MythicMobs table depth exceeds 32");
            if (path.contains(table))
                throw new IllegalArgumentException("Cyclic MythicMobs drop table");
            Shape previous = checked.get(table);
            if (previous != null) {
                if (depth + previous.height > 32)
                    throw new IllegalArgumentException("MythicMobs table depth exceeds 32");
                return previous;
            }
            path.add(table);
            try {
                Collection<?> drops = collection(call(view, call(tableDrops, table)));
                if (drops.size() > 256)
                    throw new IllegalArgumentException(
                            "MythicMobs table has more than 256 entries");
                int height = 0;
                int work = 0;
                int entries = 0;
                for (Object drop : drops) {
                    if (++entries > 256 || ++inspections > 4096)
                        throw new IllegalArgumentException(
                                "MythicMobs table inspection budget exceeded");
                    work++;
                    if (tableDropType.isInstance(drop)) {
                        Shape nested = visit(call(childTable, drop), depth + 1);
                        height = Math.max(height, nested.height + 1);
                        work += nested.work;
                    } else {
                        generator(drop);
                    }
                    if (work > 4096)
                        throw new IllegalArgumentException(
                                "MythicMobs table expansion budget exceeded");
                }
                Shape shape = new Shape(height, work);
                checked.put(table, shape);
                return shape;
            } finally {
                path.remove(table);
            }
        }
    }

    private Object generator(Object drop) {
        if (itemType.isInstance(drop)) return drop;
        if (customType.isInstance(drop)) {
            Object provided = ((Optional<?>) call(customDrop, drop)).orElse(null);
            if (itemType.isInstance(provided)) return provided;
        }
        throw new IllegalArgumentException("MythicMobs table contains a non-item reward");
    }

    private Object metadata(Player player) {
        try {
            return metadata.newInstance(
                    null, player == null ? null : call(adaptEntity, null, player), 1.0);
        } catch (InvocationTargetException wrapped) {
            throw propagate(wrapped.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("MythicMobs drop metadata is inaccessible", failure);
        }
    }

    private static Collection<?> collection(Object value) {
        if (value instanceof Collection<?> collection) return collection;
        throw new IllegalStateException("Unsupported MythicMobs drop collection");
    }

    private static Object call(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            throw propagate(wrapped.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("MythicMobs table API is inaccessible", failure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("MythicMobs table API failed", failure);
    }
}

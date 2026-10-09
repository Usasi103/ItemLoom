package dev.itemloom.probe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.itemloom.paper.integration.MythicLootTables;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Mutates only temporary, in-memory MM tables in the isolated verification server. */
final class MythicTableProbe {
    private static final String KEY = "ILOptimizationGraphProbe";
    private final Object manager;
    private final Class<?> table;
    private final java.lang.reflect.Field target;
    private final Method drop;
    private int sequence;

    private MythicTableProbe() throws Exception {
        var plugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        var loader = plugin.getClass().getClassLoader();
        manager = plugin.getClass().getMethod("getDropManager").invoke(plugin);
        table = loader.loadClass("io.lumine.mythic.core.drops.DropTable");
        target =
                loader.loadClass("io.lumine.mythic.core.drops.droppables.DropTableDrop")
                        .getDeclaredField("dropTable");
        target.setAccessible(true);
        drop = manager.getClass().getMethod("getDrop", String.class, String.class);
    }

    @SuppressWarnings("unchecked")
    static List<String> run(Player player) throws Exception {
        var f = new MythicTableProbe();
        var field = f.manager.getClass().getDeclaredField("dropTables");
        field.setAccessible(true);
        var tables = (Map<String, Object>) field.get(f.manager);
        Object previous = tables.get(KEY);
        var checks = new ArrayList<String>();
        try {
            Object diamond = f.drop.invoke(f.manager, "il-probe", "diamond 1 1");
            Object leaf = f.table(List.of(diamond));
            Object shared = f.table(List.of(f.edge(leaf), f.edge(leaf)));
            tables.put(KEY, shared);
            var generated = MythicLootTables.generate(KEY, player);
            check(
                    checks,
                    generated.stream().mapToInt(org.bukkit.inventory.ItemStack::getAmount).sum()
                            == 2,
                    "MM shared subtable remains two independent native selections");
            f.replace(leaf, List.of(f.drop.invoke(f.manager, "il-probe", "exp 1 1")));
            reject(
                    checks,
                    () -> MythicLootTables.generate(KEY, player),
                    "MM warmed table mutation revalidates forbidden reward types");
            f.replace(leaf, List.of(diamond));
            check(
                    checks,
                    MythicLootTables.generate(KEY, player).size() > 0,
                    "MM restoring the same table identity works without IL reload");

            Object cyclic = f.table(List.of());
            f.replace(cyclic, List.of(f.edge(cyclic)));
            tables.put(KEY, cyclic);
            reject(
                    checks,
                    () -> MythicLootTables.generate(KEY, player),
                    "MM cycles reject before native generation");
            Object chain = leaf;
            for (int i = 0; i < 32; i++) chain = f.table(List.of(f.edge(chain)));
            tables.put(KEY, chain);
            check(
                    checks,
                    MythicLootTables.generate(KEY, player).size() > 0,
                    "MM depth 32 remains permitted");
            tables.put(KEY, f.table(List.of(f.edge(chain))));
            reject(
                    checks,
                    () -> MythicLootTables.generate(KEY, player),
                    "MM depth 33 rejects before native generation");

            Object tail = leaf;
            for (int i = 0; i < 20; i++) tail = f.table(List.of(f.edge(tail)));
            Object deep = tail;
            for (int i = 0; i < 20; i++) deep = f.table(List.of(f.edge(deep)));
            tables.put(KEY, f.table(List.of(f.edge(tail), f.edge(deep))));
            reject(
                    checks,
                    () -> MythicLootTables.generate(KEY, player),
                    "MM memoized subtree still enforces depth when revisited deeper");
            Object dag = leaf;
            for (int i = 0; i < 12; i++) dag = f.table(List.of(f.edge(dag), f.edge(dag)));
            tables.put(KEY, dag);
            reject(
                    checks,
                    () -> MythicLootTables.generate(KEY, player),
                    "MM small shared graph with excessive expansion rejects before native generation");
            tables.put(KEY, f.table(java.util.Collections.nCopies(257, diamond)));
            reject(
                    checks,
                    () -> MythicLootTables.generate(KEY, player),
                    "MM per-table entry bound retained");
            tables.put(KEY, leaf);
            check(
                    checks,
                    MythicLootTables.generate(KEY, player).size() > 0,
                    "MM table replacement resolves current registry after prior failures");
            return checks;
        } finally {
            if (previous == null) tables.remove(KEY);
            else tables.put(KEY, previous);
        }
    }

    private Object table(List<Object> entries) throws Exception {
        Object value =
                table.getConstructor(String.class, String.class, List.class)
                        .newInstance("il-probe", "il-probe-" + sequence++, List.of());
        replace(value, entries);
        return value;
    }

    private void replace(Object value, List<Object> entries) throws Exception {
        Object weighted = table.getMethod("getDrops").invoke(value);
        weighted.getClass().getMethod("clear").invoke(weighted);
        weighted.getClass()
                .getMethod("addAll", java.util.Collection.class)
                .invoke(weighted, entries);
    }

    private Object edge(Object child) throws Exception {
        Object edge = drop.invoke(manager, "il-probe", "ILBagVanilla 1 1");
        target.set(edge, child);
        return edge;
    }

    private static void reject(List<String> checks, Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IllegalArgumentException expected) {
            checks.add(label);
        }
    }

    private static void check(List<String> checks, boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks.add(label);
    }
}

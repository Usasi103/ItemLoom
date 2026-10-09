package dev.itemloom.paper.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;

/** Detached bag rules. NI item/pack files keep their original vocabulary. */
public record LootBagConfig(
        Map<String, Bag> bags, Map<String, String> legacyIds, Map<String, Table> tables) {
    public record Range(int min, int max) {
        int roll() {
            return ThreadLocalRandom.current().nextInt(min, max + 1);
        }
    }

    public record Entry(String item, Range amount, int weight, Map<String, String> data) {}

    public record Table(Range rolls, List<Entry> entries, int weight) {}

    public record Bag(
            boolean enabled, int cooldown, String pack, String table, String mythic, Table loot) {}

    public static LootBagConfig read(NiRepository.Input input) {
        String text = input.sources().get("loot-bags.yml");
        if (text == null) return new LootBagConfig(Map.of(), Map.of(), Map.of());
        Map<String, Object> root = NiYaml.read(text, "loot-bags.yml").values();
        keys(root, Set.of("bags", "tables"), "loot-bags.yml");
        Map<String, Table> tables = new LinkedHashMap<>();
        map(root.getOrDefault("tables", Map.of()), "tables")
                .forEach((id, raw) -> tables.put(id, table(raw, "tables." + id)));
        Map<String, Bag> bags = new LinkedHashMap<>();
        Map<String, String> legacy = new LinkedHashMap<>();
        map(root.getOrDefault("bags", Map.of()), "bags")
                .forEach(
                        (id, raw) -> {
                            String path = "bags." + id;
                            if (!input.items().containsKey(id))
                                throw invalid(path, "unknown item definition");
                            Map<String, Object> bag = map(raw, path);
                            keys(
                                    bag,
                                    Set.of(
                                            "enabled",
                                            "cooldown-ticks",
                                            "legacy-id",
                                            "pack",
                                            "table",
                                            "mythic",
                                            "loot"),
                                    path);
                            Object flag = bag.getOrDefault("enabled", true);
                            if (!(flag instanceof Boolean enabled))
                                throw invalid(path, "enabled must be boolean");
                            int cooldown =
                                    integer(
                                            bag.getOrDefault("cooldown-ticks", 20),
                                            1,
                                            72000,
                                            path + ".cooldown-ticks");
                            String pack = optional(bag, "pack", path),
                                    table = optional(bag, "table", path),
                                    mythic = optional(bag, "mythic", path);
                            Table loot =
                                    bag.containsKey("loot")
                                            ? table(bag.get("loot"), path + ".loot")
                                            : null;
                            int sources =
                                    (pack == null ? 0 : 1)
                                            + (table == null ? 0 : 1)
                                            + (mythic == null ? 0 : 1)
                                            + (loot == null ? 0 : 1);
                            if (sources > 1 || enabled && sources != 1)
                                throw invalid(
                                        path,
                                        "choose exactly one of pack, table, mythic, loot (disabled bags may omit it)");
                            if (pack != null && !input.packs().containsKey(pack))
                                throw invalid(path, "unknown item pack " + pack);
                            if (table != null && !tables.containsKey(table))
                                throw invalid(path, "unknown table " + table);
                            String old = optional(bag, "legacy-id", path);
                            if (old != null && legacy.putIfAbsent(old, id) != null)
                                throw invalid(path, "duplicate legacy-id " + old);
                            bags.put(id, new Bag(enabled, cooldown, pack, table, mythic, loot));
                        });
        return new LootBagConfig(Map.copyOf(bags), Map.copyOf(legacy), Map.copyOf(tables));
    }

    private static Table table(Object raw, String path) {
        Map<String, Object> table = map(raw, path);
        keys(table, Set.of("rolls", "entries"), path);
        Range rolls = range(table.getOrDefault("rolls", 1), 64, path + ".rolls");
        if (!(table.get("entries") instanceof List<?> list) || list.isEmpty() || list.size() > 256)
            throw invalid(path, "entries must contain 1..256 entries");
        List<Entry> entries = new ArrayList<>();
        int total = 0;
        for (Object value : list) {
            Map<String, Object> entry = map(value, path + ".entries");
            keys(entry, Set.of("item", "amount", "weight", "data"), path + ".entries");
            String item = optional(entry, "item", path);
            if (item == null) throw invalid(path, "entry requires item");
            int weight = integer(entry.getOrDefault("weight", 1), 1, 1000000, path + ".weight");
            Range amount = range(entry.getOrDefault("amount", 1), 64, path + ".amount");
            Map<String, String> data = new LinkedHashMap<>();
            map(entry.getOrDefault("data", Map.of()), path + ".data")
                    .forEach(
                            (key, val) -> {
                                if (!(val instanceof String text))
                                    throw invalid(path, "data values must be strings");
                                data.put(key, text);
                            });
            entries.add(new Entry(item, amount, weight, Map.copyOf(data)));
            total += weight;
        }
        return new Table(rolls, List.copyOf(entries), total);
    }

    private static Range range(Object raw, int max, String path) {
        if (raw instanceof Number) {
            int value = integer(raw, 1, max, path);
            return new Range(value, value);
        }
        Map<String, Object> range = map(raw, path);
        keys(range, Set.of("min", "max"), path);
        int low = integer(range.get("min"), 1, max, path + ".min");
        return new Range(low, integer(range.get("max"), low, max, path + ".max"));
    }

    private static int integer(Object raw, int min, int max, String path) {
        if (!(raw instanceof Integer value) || value < min || value > max)
            throw invalid(path, "expected integer " + min + ".." + max);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> map)) throw invalid(path, "expected mapping");
        return (Map<String, Object>) map;
    }

    private static String optional(Map<String, Object> map, String key, String path) {
        if (!map.containsKey(key)) return null;
        if (!(map.get(key) instanceof String text) || text.isBlank())
            throw invalid(path + '.' + key, "expected nonblank string");
        return text;
    }

    private static void keys(Map<String, Object> map, Set<String> allowed, String path) {
        for (String key : map.keySet())
            if (!allowed.contains(key)) throw invalid(path, "unknown key " + key);
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("loot-bags.yml / " + path + ": " + message);
    }
}

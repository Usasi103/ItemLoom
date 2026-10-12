package dev.itemloom.paper.action;

import dev.itemloom.compat.ni.NiRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public record LootBagConfig(
        Map<String, Bag> bags, Map<String, String> legacyIds, Map<String, Table> tables) {
    public LootBagConfig {
        bags = Map.copyOf(bags);
        legacyIds = Map.copyOf(legacyIds);
        tables = Map.copyOf(tables);
    }

    public static LootBagConfig read(NiRepository.Input input) {
        String source = input.sources().get("loot-bags.yml");
        if (source == null) return new LootBagConfig(Map.of(), Map.of(), Map.of());
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(source);
        } catch (InvalidConfigurationException failure) {
            throw new IllegalArgumentException("loot-bags.yml: invalid YAML", failure);
        }
        Map<String, Object> root = mapping(yaml, "root");
        keys(root, Set.of("bags", "tables"), "root");
        Map<String, Table> tables = new LinkedHashMap<>();
        if (root.containsKey("tables")) {
            mapping(root.get("tables"), "tables")
                    .forEach((id, value) -> tables.put(id, table(value, "tables." + id)));
        }
        Map<String, Bag> bags = new LinkedHashMap<>();
        Map<String, String> legacy = new LinkedHashMap<>();
        if (root.containsKey("bags")) {
            for (var configured : mapping(root.get("bags"), "bags").entrySet()) {
                String id = configured.getKey();
                String path = "bags." + id;
                if (!input.items().containsKey(id)) throw invalid(path, "unknown bag item");
                Map<String, Object> fields = mapping(configured.getValue(), path);
                keys(
                        fields,
                        Set.of(
                                "enabled",
                                "cooldown-ticks",
                                "legacy-id",
                                "pack",
                                "table",
                                "mythic",
                                "loot"),
                        path);
                boolean enabled = true;
                if (fields.containsKey("enabled")) {
                    if (!(fields.get("enabled") instanceof Boolean value))
                        throw invalid(path + ".enabled", "expected boolean");
                    enabled = value;
                }
                int cooldown =
                        fields.containsKey("cooldown-ticks")
                                ? integer(
                                        fields.get("cooldown-ticks"),
                                        1,
                                        72000,
                                        path + ".cooldown-ticks")
                                : 20;
                String oldId = optional(fields, "legacy-id", path);
                String pack = optional(fields, "pack", path);
                String table = optional(fields, "table", path);
                String mythic = optional(fields, "mythic", path);
                Table loot =
                        fields.containsKey("loot")
                                ? table(fields.get("loot"), path + ".loot")
                                : null;
                int count =
                        (pack == null ? 0 : 1)
                                + (table == null ? 0 : 1)
                                + (mythic == null ? 0 : 1)
                                + (loot == null ? 0 : 1);
                if (count > 1 || enabled && count != 1)
                    throw invalid(path, "expected exactly one reward source when enabled");
                if (pack != null && !input.packs().containsKey(pack))
                    throw invalid(path + ".pack", "unknown pack");
                if (table != null && !tables.containsKey(table))
                    throw invalid(path + ".table", "unknown table");
                if (oldId != null && legacy.putIfAbsent(oldId, id) != null)
                    throw invalid(path + ".legacy-id", "duplicate legacy id");
                bags.put(id, new Bag(enabled, cooldown, pack, table, mythic, loot));
            }
        }
        return new LootBagConfig(bags, legacy, tables);
    }

    private static Table table(Object value, String path) {
        Map<String, Object> fields = mapping(value, path);
        keys(fields, Set.of("rolls", "entries"), path);
        Range rolls = range(fields.getOrDefault("rolls", 1), path + ".rolls");
        if (!(fields.get("entries") instanceof List<?> entries)
                || entries.isEmpty()
                || entries.size() > 256) {
            throw invalid(path + ".entries", "expected 1..256 entries");
        }
        List<Entry> result = new ArrayList<>();
        int total = 0;
        for (int index = 0; index < entries.size(); index++) {
            String entryPath = path + ".entries[" + index + "]";
            Map<String, Object> entry = mapping(entries.get(index), entryPath);
            keys(entry, Set.of("item", "amount", "weight", "data"), entryPath);
            String item = string(entry.get("item"), entryPath + ".item");
            Range amount = range(entry.getOrDefault("amount", 1), entryPath + ".amount");
            int weight =
                    integer(entry.getOrDefault("weight", 1), 1, 1000000, entryPath + ".weight");
            Map<String, String> data = new LinkedHashMap<>();
            if (entry.containsKey("data")) {
                mapping(entry.get("data"), entryPath + ".data")
                        .forEach(
                                (key, text) -> {
                                    if (!(text instanceof String actual))
                                        throw invalid(
                                                entryPath + ".data." + key, "expected string");
                                    data.put(key, actual);
                                });
            }
            result.add(new Entry(item, amount, weight, data));
            total += weight;
        }
        return new Table(rolls, result, total);
    }

    private static Range range(Object value, String path) {
        if (value instanceof Integer) {
            int fixed = integer(value, 1, 64, path);
            return new Range(fixed, fixed);
        }
        Map<String, Object> fields = mapping(value, path);
        keys(fields, Set.of("min", "max"), path);
        int min = integer(fields.get("min"), 1, 64, path + ".min");
        int max = integer(fields.get("max"), 1, 64, path + ".max");
        if (min > max) throw invalid(path, "minimum exceeds maximum");
        return new Range(min, max);
    }

    private static Map<String, Object> mapping(Object value, String path) {
        if (value instanceof ConfigurationSection section)
            return new LinkedHashMap<>(section.getValues(false));
        if (!(value instanceof Map<?, ?> values)) throw invalid(path, "expected mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach(
                (key, element) -> {
                    if (!(key instanceof String text) || text.isBlank())
                        throw invalid(path, "expected nonblank string key");
                    result.put(text, element);
                });
        return result;
    }

    private static void keys(Map<String, Object> values, Set<String> allowed, String path) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) throw invalid(path + "." + key, "unknown key");
        }
    }

    private static int integer(Object value, int min, int max, String path) {
        if (!(value instanceof Integer number) || number < min || number > max) {
            throw invalid(path, "expected integer in " + min + ".." + max);
        }
        return number;
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank())
            throw invalid(path, "expected nonblank string");
        return text;
    }

    private static String optional(Map<String, Object> values, String key, String path) {
        return values.containsKey(key) ? string(values.get(key), path + "." + key) : null;
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException("loot-bags.yml: " + path + ": " + reason);
    }

    public record Range(int min, int max) {
        int roll() {
            return ThreadLocalRandom.current().nextInt(min, max + 1);
        }
    }

    public record Entry(String item, Range amount, int weight, Map<String, String> data) {
        public Entry {
            data = Map.copyOf(data);
        }
    }

    public record Table(Range rolls, List<Entry> entries, int weight) {
        public Table {
            entries = List.copyOf(entries);
        }
    }

    public record Bag(
            boolean enabled, int cooldown, String pack, String table, String mythic, Table loot) {}
}

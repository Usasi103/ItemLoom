package dev.itemloom.paper.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.itemloom.compat.ni.NiConfig;

/** Pure compatibility decisions; callback execution and source commits are separate. */
final class TriggerRules {
    private TriggerRules() {}

    record Deduction(int affectedCount, Integer charge, int returnedCount) {}

    static Deduction deduct(int count, Integer charge, int amount) {
        if (amount <= 0 || count <= 0) return null;
        if (charge == null) return amount > count ? null : new Deduction(count - amount, null, 0);
        if (amount > charge) return null;
        return new Deduction(amount == charge ? 0 : 1, charge - amount, count - 1);
    }

    static int amount(String text) {
        if (text == null) return 1;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException malformed) {
            return 1;
        }
    }

    static boolean skipVisit(long interval, long remaining) {
        return interval > 0 && remaining > 0;
    }

    static NiConfig normalize(NiConfig source, boolean upgrade) {
        if (!upgrade) return source;
        Map<String, Object> output = new LinkedHashMap<>(source.values());
        boolean converted = false;
        for (String name : List.of("left", "right", "all", "eat", "drop", "pick")) {
            Object value = source.get(name);
            if (value == null || value instanceof Map<?, ?>) continue;
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("sync", source.strings(name));
            for (String inherited : List.of("cooldown", "group"))
                if (source.contains(inherited)) trigger.put(inherited, source.get(inherited));
            boolean consumes =
                    source.bool("consume." + name, false)
                            || name.equals("all")
                                    && (source.bool("consume.left", false)
                                            || source.bool("consume.right", false));
            if (consumes)
                trigger.put(
                        "consume",
                        source.contains("consume.amount")
                                ? Map.of("amount", source.get("consume.amount"))
                                : Map.of());
            output.put(name, trigger);
            converted = true;
        }
        if (!converted) return source;
        for (String legacy : List.of("consume", "cooldown", "group")) output.remove(legacy);
        return new NiConfig(output);
    }
}

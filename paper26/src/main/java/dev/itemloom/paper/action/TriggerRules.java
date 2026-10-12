package dev.itemloom.paper.action;

import dev.itemloom.compat.ni.NiConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TriggerRules {
    private TriggerRules() {}

    static Deduction deduct(int count, Integer charge, int amount) {
        if (count <= 0 || amount <= 0) return null;
        if (charge == null) return amount > count ? null : new Deduction(count - amount, null, 0);
        if (amount > charge) return null;
        int remaining = charge - amount;
        return new Deduction(remaining == 0 ? 0 : 1, remaining, count - 1);
    }

    static int amount(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException failure) {
            return 1;
        }
    }

    static boolean skipVisit(long interval, long remaining) {
        return interval > 0 && remaining > 0;
    }

    static NiConfig normalize(NiConfig source, boolean upgrade) {
        if (!upgrade) return source;
        Map<String, Object> result = new LinkedHashMap<>(source.values());
        boolean converted = false;
        for (String name : List.of("left", "right", "all", "eat", "drop", "pick")) {
            Object existing = source.get(name);
            if (existing == null || existing instanceof Map<?, ?>) continue;
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("sync", source.strings(name));
            for (String inherited : List.of("cooldown", "group")) {
                if (source.contains(inherited)) action.put(inherited, source.get(inherited));
            }
            boolean consumes = source.bool("consume." + name, false);
            if (name.equals("all")) {
                consumes |=
                        source.bool("consume.left", false) || source.bool("consume.right", false);
            }
            if (consumes) {
                Map<String, Object> consume = new LinkedHashMap<>();
                if (source.contains("consume.amount"))
                    consume.put("amount", source.get("consume.amount"));
                action.put("consume", consume);
            }
            result.put(name, action);
            converted = true;
        }
        if (!converted) return source;
        result.remove("consume");
        result.remove("cooldown");
        result.remove("group");
        return new NiConfig(result);
    }

    record Deduction(int affectedCount, Integer charge, int returnedCount) {}
}

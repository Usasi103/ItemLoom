package dev.itemloom.paper.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** The count placeholder's text grammar and ordered inventory observation. */
public final class InventoryRequirements {
    private InventoryRequirements() {}

    public static <T> boolean fulfilled(
            String payload,
            Iterable<T> inventory,
            Function<? super T, String> identity,
            ToIntFunction<? super T> amount) {
        String[] fields = payload.split("[_\\\\]", -1);
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (int index = 0; index + 1 < fields.length; index += 2) {
            try {
                int requested = Integer.parseInt(fields[index + 1]);
                if (requested > 0) remaining.put(fields[index], requested);
            } catch (NumberFormatException ignored) {
                // Invalid later pairs leave earlier positive requirements intact.
            }
        }
        for (T entry : inventory) {
            String id = identity.apply(entry);
            Integer needed = remaining.get(id);
            if (needed == null) continue;
            int rest = needed - amount.applyAsInt(entry);
            if (rest <= 0) remaining.remove(id);
            else remaining.put(id, rest);
        }
        return remaining.isEmpty();
    }
}

package dev.itemloom.paper.compat;

import java.util.*;
import java.util.function.*;

public final class InventoryRequirements {
    private InventoryRequirements() {}

    public static <T> boolean fulfilled(
            String payload,
            Iterable<T> inventory,
            Function<? super T, String> identity,
            ToIntFunction<? super T> amount) {
        Map<String, Integer> remaining = new HashMap<>();
        String[] tokens = payload.split("[_\\\\]", -1);
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            try {
                int count = Integer.parseInt(tokens[i + 1]);
                if (count > 0) remaining.put(tokens[i], count);
            } catch (NumberFormatException ignored) {
            }
        }
        for (T item : inventory) {
            String id = identity.apply(item);
            Integer needed = remaining.get(id);
            if (needed == null) continue;
            int left = needed - amount.applyAsInt(item);
            if (left <= 0) remaining.remove(id);
            else remaining.put(id, left);
        }
        return remaining.isEmpty();
    }
}

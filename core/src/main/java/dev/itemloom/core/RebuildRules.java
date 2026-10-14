package dev.itemloom.core;

import java.util.Map;

/** Pure rules for rebuilding an existing item. Shared by the Bukkit layer and the tests. */
public final class RebuildRules {
    private RebuildRules() {}

    /**
     * Custom durability or charge to keep on a rebuilt item: the original remaining value clamped
     * to the rebuilt maximum. Returns null when the rebuilt item has no positive maximum, meaning
     * the state must not be carried (a remaining value without a maximum would break maintenance).
     */
    public static Integer keptState(int remaining, Integer maximum) {
        if (maximum == null || maximum <= 0) {
            return null;
        }
        return Math.max(0, Math.min(remaining, maximum));
    }

    /**
     * First overridden roll whose value the rebuilt identity does not carry, or null when every
     * requested value is present. Null overrides remove a roll and are not checked.
     */
    public static String missingOverride(
            Map<String, String> rebuilt, Map<String, String> overrides) {
        if (overrides == null) {
            return null;
        }
        for (Map.Entry<String, String> change : overrides.entrySet()) {
            String wanted = change.getValue();
            if (wanted != null && !wanted.equals(rebuilt.get(change.getKey()))) {
                return change.getKey();
            }
        }
        return null;
    }
}

package dev.itemloom.paper.runtime;

import dev.itemloom.paper.action.LootBagConfig;
import java.util.Map;
import java.util.Objects;

public record RuntimePolicies(
        Map<String, Integer> usageRestrictions,
        LootBagConfig lootBags,
        boolean resetCooldownOnQuit,
        DropOwnershipPolicy dropOwnership) {
    public RuntimePolicies {
        usageRestrictions = Map.copyOf(usageRestrictions);
        Objects.requireNonNull(lootBags, "lootBags");
        Objects.requireNonNull(dropOwnership, "dropOwnership");
    }
}

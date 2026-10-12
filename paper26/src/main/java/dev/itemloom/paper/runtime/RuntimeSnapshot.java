package dev.itemloom.paper.runtime;

import java.util.List;
import java.util.Objects;

public record RuntimeSnapshot(
        long revision,
        List<ItemDefinitions> itemFormats,
        PackAccess packs,
        FunctionAccess functions,
        ItemPersistence persistence,
        PlaceholderAccess placeholders,
        DisplayAccess display,
        MaintenanceAccess maintenance,
        UpdateQueueAccess secondaryUpdates,
        RuntimePolicies policies,
        RevisionLifecycle lifecycle,
        RuntimeEvents events) {
    public RuntimeSnapshot {
        itemFormats = List.copyOf(itemFormats);
        Objects.requireNonNull(packs, "packs");
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(placeholders, "placeholders");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(maintenance, "maintenance");
        Objects.requireNonNull(secondaryUpdates, "secondaryUpdates");
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(events, "events");
    }
}

package dev.itemloom.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable item identity and saved generation outcomes, independent of a storage encoding. */
public record ItemIdentity(String id, Map<String, String> rolls) {
    public ItemIdentity {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Item id is required");
        rolls = Collections.unmodifiableMap(new LinkedHashMap<>(rolls));
    }
}

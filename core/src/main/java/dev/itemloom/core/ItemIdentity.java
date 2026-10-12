package dev.itemloom.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ItemIdentity(String id, Map<String, String> rolls) {
    public ItemIdentity {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Item id cannot be blank");
        }
        rolls =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(rolls, "rolls")));
    }
}

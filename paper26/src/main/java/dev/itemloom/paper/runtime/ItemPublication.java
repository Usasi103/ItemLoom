package dev.itemloom.paper.runtime;

import java.util.Set;
import java.util.Objects;

public record ItemPublication(Object token, Set<String> ids) {
    public ItemPublication {
        Objects.requireNonNull(token, "token");
        ids = Set.copyOf(ids);
    }
}

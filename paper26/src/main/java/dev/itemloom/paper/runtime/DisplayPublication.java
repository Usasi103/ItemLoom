package dev.itemloom.paper.runtime;

import java.util.Map;
import java.util.Objects;

public record DisplayPublication(
        Object token, Map<String, DisplayRenderer> templates, boolean itemPlaceholdersEnabled) {
    public DisplayPublication {
        Objects.requireNonNull(token, "token");
        templates = Map.copyOf(templates);
    }
}

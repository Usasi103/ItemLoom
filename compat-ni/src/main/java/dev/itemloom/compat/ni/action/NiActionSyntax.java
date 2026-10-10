package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.NiConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Decodes action configuration without executing actions or selecting branches. */
final class NiActionSyntax {
    sealed interface Form permits Empty, Text, Sequence, Branch, Nested {}

    enum Empty implements Form {
        INSTANCE
    }

    record Text(String source) implements Form {}

    record Sequence(List<?> values) implements Form {
        Sequence {
            // Null entries are valid no-op steps, so List.copyOf is unsuitable here.
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    record Branch(Kind kind, NiConfig config) implements Form {}

    /** Keep the nested compile boundary so the caller can retain its host dispatch scope. */
    record Nested(Object value) implements Form {}

    enum Kind {
        CONDITION("condition"),
        LABEL("label"),
        REPEAT("repeat"),
        WHILE("while"),
        CONTAINS("contains"),
        KEY("key"),
        INT_TREE("int-tree"),
        DOUBLE_TREE("double-tree"),
        WEIGHT("weight"),
        CONDITION_WEIGHT("condition-weight");

        private final String token;

        Kind(String token) {
            this.token = token;
        }
    }

    private static final Map<String, Kind> EXPLICIT =
            Stream.of(Kind.values())
                    .collect(Collectors.toUnmodifiableMap(kind -> kind.token, Function.identity()));
    private static final List<Kind> INFERRED =
            List.of(Kind.CONDITION, Kind.REPEAT, Kind.WHILE, Kind.LABEL);

    private NiActionSyntax() {}

    static Form classify(Object input) {
        if (input instanceof String text) return new Text(text);
        if (input instanceof List<?> list) return new Sequence(list);
        NiConfig config = NiValues.config(input);
        if (config == null) return Empty.INSTANCE;

        String type = config.string("type");
        Kind explicit = type == null ? null : EXPLICIT.get(type.toLowerCase(Locale.ROOT));
        if (explicit != null) return new Branch(explicit, config);
        for (Kind candidate : INFERRED) {
            if (config.contains(candidate.token)) return new Branch(candidate, config);
        }

        if (config.keys().size() != 1) return Empty.INSTANCE;
        String key = config.keys().iterator().next();
        Object value = config.get(key);
        if (key.equals("actions")) return new Nested(value);
        return value instanceof String text ? new Text(key + ": " + text) : Empty.INSTANCE;
    }
}

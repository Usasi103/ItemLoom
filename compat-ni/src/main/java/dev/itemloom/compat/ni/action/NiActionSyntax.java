package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.NiConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class NiActionSyntax {
    sealed interface Form permits Empty, Text, Sequence, Branch, Nested {}

    enum Empty implements Form {
        INSTANCE
    }

    record Text(String source) implements Form {}

    record Sequence(List<?> values) implements Form {
        Sequence {
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    record Branch(Kind kind, NiConfig config) implements Form {}

    record Nested(Object value) implements Form {}

    enum Kind {
        CONDITION,
        LABEL,
        REPEAT,
        WHILE,
        CONTAINS,
        KEY,
        INT_TREE,
        DOUBLE_TREE,
        WEIGHT,
        CONDITION_WEIGHT
    }

    static Form classify(Object input) {
        if (input instanceof String text) {
            return new Text(text);
        }
        if (input instanceof List<?> list) {
            return new Sequence(list);
        }
        NiConfig config = NiValues.config(input);
        if (config == null) {
            return Empty.INSTANCE;
        }
        String type = config.string("type", "").toLowerCase(Locale.ROOT);
        Kind kind =
                switch (type) {
                    case "condition" -> Kind.CONDITION;
                    case "label" -> Kind.LABEL;
                    case "repeat" -> Kind.REPEAT;
                    case "while" -> Kind.WHILE;
                    case "contains" -> Kind.CONTAINS;
                    case "key" -> Kind.KEY;
                    case "int-tree" -> Kind.INT_TREE;
                    case "double-tree" -> Kind.DOUBLE_TREE;
                    case "weight" -> Kind.WEIGHT;
                    case "condition-weight" -> Kind.CONDITION_WEIGHT;
                    default -> null;
                };
        if (kind != null) {
            return new Branch(kind, config);
        }
        for (Kind inferred : List.of(Kind.CONDITION, Kind.REPEAT, Kind.WHILE, Kind.LABEL)) {
            if (config.get(inferred.name().toLowerCase(Locale.ROOT)) != null) {
                return new Branch(inferred, config);
            }
        }
        if (config.values().size() == 1) {
            var entry = config.values().entrySet().iterator().next();
            if (entry.getKey().equals("actions")) {
                return new Nested(entry.getValue());
            }
            if (entry.getValue() instanceof String value) {
                return new Text(entry.getKey() + ": " + value);
            }
        }
        return Empty.INSTANCE;
    }
}

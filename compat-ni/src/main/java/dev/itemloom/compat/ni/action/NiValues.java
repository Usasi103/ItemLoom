package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;

/** Compiled value plans separate branch selection from conversion and request evaluation. */
public final class NiValues {
    private static final Consumer<String> NO_VALIDATION = source -> {};

    private NiValues() {}

    public static <T> Function<NiActionContext, T> compile(Object input, Class<T> type) {
        return compile(input, type, NO_VALIDATION);
    }

    public static <T> Function<NiActionContext, T> compile(
            Object input, Class<T> type, Consumer<String> validate) {
        return NiValueSyntax.compile(
                input,
                type,
                validate,
                config -> branch(config, child -> compile(child, type, validate), validate),
                value -> convert(value, type),
                value ->
                        value == null
                                ? null
                                : type.isInstance(value)
                                                || type == String.class
                                                || value instanceof Number
                                        ? convert(value, type)
                                        : null);
    }

    public static <T> Function<NiActionContext, List<T>> compileList(Object input, Class<T> type) {
        return compileList(input, type, NO_VALIDATION);
    }

    public static <T> Function<NiActionContext, List<T>> compileList(
            Object input, Class<T> type, Consumer<String> validate) {
        return list(input, type, validate, false);
    }

    private static <T> Function<NiActionContext, List<T>> list(
            Object input, Class<T> type, Consumer<String> validate, boolean selected) {
        if (input == null) {
            return context -> null;
        }
        if (input instanceof List<?> sequence) {
            List<Function<NiActionContext, List<T>>> children = new ArrayList<>();
            for (Object child : sequence) {
                children.add(list(child, type, validate, false));
            }
            return context -> {
                List<T> result = new ArrayList<>();
                for (var child : children) {
                    List<T> values = child.apply(context);
                    if (values != null) {
                        result.addAll(values);
                    }
                }
                return result;
            };
        }
        NiConfig config = config(input);
        if (config != null) {
            if (config.hasKey("type")
                    && (config.get("type") == null
                            || "null".equalsIgnoreCase(config.string("type")))) {
                return context -> new ArrayList<>(Collections.singletonList(null));
            }
            Function<NiActionContext, List<T>> choice =
                    branch(config, child -> list(child, type, validate, true), validate);
            if (choice != null) {
                return choice;
            }
        }
        Function<NiActionContext, T> scalar = compile(input, type, validate);
        return context -> {
            T value = scalar.apply(context);
            if (value == null) {
                return selected ? null : new ArrayList<>();
            }
            return new ArrayList<>(Collections.singletonList(value));
        };
    }

    private static <T> Function<NiActionContext, T> branch(
            NiConfig config,
            Function<Object, Function<NiActionContext, T>> compile,
            Consumer<String> validate) {
        String type = config.string("type", "").toLowerCase(Locale.ROOT);
        if (List.of("contains", "key", "int-tree", "double-tree").contains(type)) {
            Function<NiActionContext, T> fallback = compile.apply(config.get("default-evaluator"));
            var selection = select(config, "evaluators", fallback, compile, validate);
            return context -> {
                Function<NiActionContext, T> chosen = selection.apply(context);
                return chosen == null ? null : chosen.apply(context);
            };
        }
        if (type.equals("weight") || type.equals("condition-weight")) {
            var entries =
                    weighted(
                            config.get("evaluators"),
                            type.equals("condition-weight"),
                            "evaluator",
                            compile,
                            validate);
            return context -> {
                List<Function<NiActionContext, T>> choices = choose(entries, context, 1, false);
                return choices.isEmpty() || choices.getFirst() == null
                        ? null
                        : choices.getFirst().apply(context);
            };
        }
        if (type.equals("condition") || config.get("condition") != null) {
            String condition = config.string("condition");
            if (condition != null) {
                validate.accept(condition);
            }
            Function<NiActionContext, T> yes = compile.apply(config.get("then"));
            Function<NiActionContext, T> no = compile.apply(config.get("else"));
            return context -> (context.condition(condition) ? yes : no).apply(context);
        }
        return null;
    }

    public static <T> Function<NiActionContext, T> select(
            NiConfig config, String branchesKey, T fallback, Function<Object, T> compile) {
        return select(config, branchesKey, fallback, compile, NO_VALIDATION);
    }

    public static <T> Function<NiActionContext, T> select(
            NiConfig config,
            String branchesKey,
            T fallback,
            Function<Object, T> compile,
            Consumer<String> validate) {
        String mode = config.string("type", "").toLowerCase(Locale.ROOT);
        String global = config.string("global-id", "key");
        if (mode.equals("contains")) {
            Function<NiActionContext, String> request =
                    compile(config.get("key"), String.class, validate);
            var elements = new HashSet<>(config.strings("elements"));
            T hit =
                    compile.apply(
                            config.get(
                                    branchesKey.equals("actions")
                                            ? "contains-action"
                                            : "contains-evaluator"));
            return context -> {
                String key = request.apply(context);
                context.getGlobal().put(global, key);
                return key != null && elements.contains(key) ? hit : fallback;
            };
        }
        NiConfig branches = config(config.get(branchesKey));
        Map<String, Object> entries = branches == null ? Map.of() : branches.values();
        if (mode.equals("key")) {
            Map<String, T> cases = new LinkedHashMap<>();
            entries.forEach((key, value) -> cases.put(key, compile.apply(value)));
            Function<NiActionContext, String> request =
                    compile(config.get("key"), String.class, validate);
            return context -> {
                String key = request.apply(context);
                context.getGlobal().put(global, key);
                return key != null && cases.containsKey(key) ? cases.get(key) : fallback;
            };
        }
        String field = branchesKey.equals("actions") ? "key" : "value";
        Direction direction = direction(config.get("action-type"));
        if (mode.equals("int-tree")) {
            NavigableMap<Integer, T> tree = new TreeMap<>();
            entries.forEach(
                    (key, value) -> {
                        Integer parsed = convert(key, Integer.class);
                        if (parsed != null) {
                            tree.put(parsed, compile.apply(value));
                        }
                    });
            Function<NiActionContext, Integer> request =
                    compile(config.get(field), Integer.class, validate);
            return context ->
                    lookup(context, global, request.apply(context), tree, direction, fallback);
        }
        NavigableMap<Double, T> tree = new TreeMap<>();
        entries.forEach(
                (key, value) -> {
                    Double parsed = convert(key, Double.class);
                    if (parsed != null) {
                        tree.put(parsed, compile.apply(value));
                    }
                });
        Function<NiActionContext, Double> request =
                compile(config.get(field), Double.class, validate);
        return context ->
                lookup(context, global, request.apply(context), tree, direction, fallback);
    }

    private enum Direction {
        LOWER,
        FLOOR,
        HIGHER,
        CEILING
    }

    private static Direction direction(Object value) {
        if (value instanceof Number number) {
            int index = number.intValue();
            return index >= 0 && index < 4 ? Direction.values()[index] : Direction.LOWER;
        }
        if (value != null) {
            try {
                return Direction.valueOf(value.toString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return Direction.LOWER;
            }
        }
        return Direction.LOWER;
    }

    private static <K, T> T lookup(
            NiActionContext context,
            String global,
            K request,
            NavigableMap<K, T> branches,
            Direction direction,
            T fallback) {
        context.getGlobal().put(global, request);
        if (request == null) {
            return fallback;
        }
        Map.Entry<K, T> entry =
                switch (direction) {
                    case LOWER -> branches.lowerEntry(request);
                    case FLOOR -> branches.floorEntry(request);
                    case HIGHER -> branches.higherEntry(request);
                    case CEILING -> branches.ceilingEntry(request);
                };
        return entry == null ? fallback : entry.getValue();
    }

    public record Weighted<T>(
            T value, Function<NiActionContext, Double> weight, String condition, int index) {}

    public static <T> List<Weighted<T>> weighted(
            Object input, boolean conditional, String field, Function<Object, T> compile) {
        return weighted(input, conditional, field, compile, NO_VALIDATION);
    }

    public static <T> List<Weighted<T>> weighted(
            Object input,
            boolean conditional,
            String field,
            Function<Object, T> compile,
            Consumer<String> validate) {
        if (!(input instanceof List<?> source)) {
            return List.of();
        }
        List<Weighted<T>> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            NiConfig entry = config(source.get(index));
            if (entry == null) {
                continue;
            }
            String condition = conditional ? entry.string("condition") : null;
            if (condition != null) {
                validate.accept(condition);
            }
            Function<NiActionContext, Double> weight;
            if (conditional) {
                weight = compile(entry.get("weight"), Double.class, validate);
            } else {
                double fixed = entry.decimal("weight", 1);
                weight = context -> fixed;
            }
            result.add(new Weighted<>(compile.apply(entry.get(field)), weight, condition, index));
        }
        return List.copyOf(result);
    }

    public static <T> List<T> choose(
            List<Weighted<T>> entries, NiActionContext context, int count, boolean ordered) {
        return sample(entries, context, count, ordered).values();
    }

    public record Selection<T>(List<T> values, boolean combineResults) {}

    private record Eligible<T>(Weighted<T> entry, double weight) {}

    public static <T> Selection<T> sample(
            List<Weighted<T>> entries, NiActionContext context, int count, boolean ordered) {
        List<Eligible<T>> eligible = new ArrayList<>();
        for (Weighted<T> entry : entries) {
            if (!context.condition(entry.condition())) {
                continue;
            }
            Double evaluated = entry.weight().apply(context);
            double weight = evaluated == null ? 1 : evaluated;
            if (!(weight <= 0)) {
                eligible.add(new Eligible<>(entry, weight));
            }
        }
        if (count >= eligible.size()) {
            return new Selection<>(values(eligible), true);
        }
        List<Eligible<T>> selected = new ArrayList<>();
        for (int draw = 0; draw < count && !eligible.isEmpty(); draw++) {
            double total = eligible.stream().mapToDouble(Eligible::weight).sum();
            double position;
            try {
                position = context.evaluation().generation().random().nextDouble(total);
            } catch (IllegalArgumentException invalidDistribution) {
                break;
            }
            int selectedIndex = -1;
            for (int index = 0; index < eligible.size(); index++) {
                position -= eligible.get(index).weight();
                if (position < 0) {
                    selectedIndex = index;
                    break;
                }
            }
            if (selectedIndex < 0) {
                break;
            }
            selected.add(eligible.remove(selectedIndex));
        }
        if (ordered) {
            selected.sort(Comparator.comparingInt(value -> value.entry().index()));
        }
        return new Selection<>(values(selected), count != 1);
    }

    private static <T> List<T> values(List<Eligible<T>> entries) {
        List<T> values = new ArrayList<>();
        entries.forEach(entry -> values.add(entry.entry().value()));
        return Collections.unmodifiableList(values);
    }

    public static NiConfig config(Object input) {
        if (input instanceof NiConfig config) {
            return config;
        }
        if (input instanceof ConfigurationSection section) {
            return NiYaml.fromSection(section);
        }
        if (input instanceof LegacyConfigReader reader) {
            return reader.view();
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> converted.put(String.valueOf(key), value));
            return NiConfig.mapReader(converted);
        }
        return null;
    }

    public static <T> T convert(Object input, Class<T> type) {
        if (input == null) {
            return null;
        }
        if (type.isInstance(input)) {
            return type.cast(input);
        }
        if (type == String.class) {
            return type.cast(input.toString());
        }
        if (type == Boolean.class) {
            return input.toString().equalsIgnoreCase("true")
                    ? type.cast(Boolean.TRUE)
                    : input.toString().equalsIgnoreCase("false") ? type.cast(Boolean.FALSE) : null;
        }
        Number number;
        if (input instanceof Number numeric) {
            number = numeric;
        } else if (input instanceof String text
                && text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            if (type == Long.class && text.matches("[+-]?[0-9]+")) {
                return type.cast(strictLong(text));
            }
            number = Double.valueOf(text);
        } else {
            return null;
        }
        if (type == Integer.class) {
            return type.cast(number.intValue());
        }
        if (type == Long.class) {
            return type.cast(number.longValue());
        }
        if (type == Double.class) {
            return type.cast(number.doubleValue());
        }
        return null;
    }

    public static Integer strictInteger(String text) {
        Long parsed = strictLong(text);
        return parsed == null
                ? null
                : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, parsed));
    }

    public static Long strictLong(String text) {
        if (!text.matches("[+-]?[0-9]+")) {
            return null;
        }
        BigInteger integer = new BigInteger(text);
        if (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        if (integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        return integer.longValue();
    }
}

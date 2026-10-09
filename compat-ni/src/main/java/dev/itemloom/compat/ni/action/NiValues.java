package dev.itemloom.compat.ni.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Consumer;
import dev.itemloom.compat.ni.NiConfig;

/** NI scalar evaluators compile independently of the server actions consuming their results. */
public final class NiValues {
    private static final Consumer<String> UNCHECKED = source -> {};

    private NiValues() {}

    public static <T> Function<NiActionContext, T> compile(Object input, Class<T> type) {
        return compile(input, type, UNCHECKED);
    }

    public static <T> Function<NiActionContext, T> compile(
            Object input, Class<T> type, Consumer<String> validate) {
        if (input == null) return context -> null;
        if (input instanceof String text) {
            int delimiter = text.indexOf(": ");
            String prefix =
                    (delimiter < 0 ? text : text.substring(0, delimiter)).toLowerCase(Locale.ROOT);
            String content = delimiter < 0 ? null : text.substring(delimiter + 2);
            if (prefix.equals("js")) {
                if (content != null) validate.accept(content);
                return context -> {
                    try {
                        return content == null
                                ? null
                                : scriptValue(context.evaluate(content), type);
                    } catch (RuntimeException error) {
                        context.evaluation().warning("Value script failed: " + error.getMessage());
                        return null;
                    }
                };
            }
            if (prefix.equals("raw")) {
                T value = convert(content, type);
                return context -> value;
            }
            T constant = convert(text, type);
            if (constant != null
                    && !(type == String.class && text.contains("<") && text.contains(">")))
                return context -> constant;
            return context -> convert(context.parse(text), type);
        }
        if (input instanceof List<?> list) {
            List<Function<NiActionContext, T>> alternatives =
                    list.stream().map(value -> compile(value, type, validate)).toList();
            return context -> {
                for (var alternative : alternatives) {
                    T value = alternative.apply(context);
                    if (value != null) return value;
                }
                return null;
            };
        }
        NiConfig config = config(input);
        if (config == null) {
            T value = convert(input, type);
            return context -> value;
        }
        var branch = branches(config, value -> compile(value, type, validate), validate);
        if (branch != null) return branch;
        if (config.keys().size() != 1) return context -> null;
        if (config.keys().size() == 1) {
            String key = config.keys().iterator().next();
            if (config.get(key) == null) return context -> null;
            if ((key.equals("js") || key.equals("raw")) && config.get(key) != null)
                return compile(key + ": " + config.string(key), type, validate);
        }
        T converted = convert(input, type);
        return context -> converted;
    }

    public static <T> Function<NiActionContext, List<T>> compileList(Object input, Class<T> type) {
        return compileList(input, type, UNCHECKED);
    }

    public static <T> Function<NiActionContext, List<T>> compileList(
            Object input, Class<T> type, Consumer<String> validate) {
        return compileList(input, type, validate, false);
    }

    private static <T> Function<NiActionContext, List<T>> compileList(
            Object input, Class<T> type, Consumer<String> validate, boolean fallback) {
        if (input == null) return context -> null;
        if (input instanceof List<?> values) {
            var children =
                    values.stream().map(value -> compileList(value, type, validate)).toList();
            return context -> {
                List<T> result = new ArrayList<>();
                for (var child : children) {
                    List<T> value = child.apply(context);
                    if (value != null) result.addAll(value);
                }
                return result;
            };
        }
        NiConfig config = config(input);
        if (config != null) {
            if (config.hasKey("type")
                    && (config.get("type") == null
                            || "null".equalsIgnoreCase(config.get("type").toString()))) {
                return context -> new ArrayList<>(java.util.Collections.singletonList(null));
            }
            var branch =
                    branches(config, value -> compileList(value, type, validate, true), validate);
            if (branch != null) return branch;
        }
        var scalar = compile(input, type, validate);
        return context -> {
            T value = scalar.apply(context);
            return value == null
                    ? fallback ? null : new ArrayList<>()
                    : new ArrayList<>(List.of(value));
        };
    }

    private static <T> Function<NiActionContext, T> branches(
            NiConfig config,
            Function<Object, Function<NiActionContext, T>> compile,
            Consumer<String> validate) {
        String kind = config.string("type", "").toLowerCase(Locale.ROOT);
        if (List.of("contains", "key", "int-tree", "double-tree").contains(kind)) {
            var fallback = compile.apply(config.get("default-evaluator"));
            var selector = select(config, "evaluators", fallback, compile, validate);
            return context -> selector.apply(context).apply(context);
        }
        if (kind.equals("weight") || kind.equals("condition-weight")) {
            var entries =
                    weighted(
                            config.get("evaluators"),
                            kind.equals("condition-weight"),
                            "evaluator",
                            compile,
                            validate);
            return context -> {
                var selected = choose(entries, context, 1, false);
                return selected.isEmpty() ? null : selected.getFirst().apply(context);
            };
        }
        if (kind.equals("condition") || config.contains("condition")) {
            var yes = compile.apply(config.get("then"));
            var no = compile.apply(config.get("else"));
            String condition = config.string("condition");
            if (condition != null) validate.accept(condition);
            return context -> (context.condition(condition) ? yes : no).apply(context);
        }
        return null;
    }

    /** Shares branch selection semantics between actions and value evaluators. */
    public static <T> Function<NiActionContext, T> select(
            NiConfig config, String branchesKey, T fallback, Function<Object, T> compile) {
        return select(config, branchesKey, fallback, compile, UNCHECKED);
    }

    public static <T> Function<NiActionContext, T> select(
            NiConfig config,
            String branchesKey,
            T fallback,
            Function<Object, T> compile,
            Consumer<String> validate) {
        String kind = config.string("type", "").toLowerCase(Locale.ROOT);
        String globalId = config.string("global-id", "key");
        if (kind.equals("contains")) {
            var key = compile(config.get("key"), String.class, validate);
            var elements = new java.util.HashSet<>(config.strings("elements"));
            T contained =
                    compile.apply(
                            config.get(
                                    branchesKey.equals("actions")
                                            ? "contains-action"
                                            : "contains-evaluator"));
            return context -> {
                String value = key.apply(context);
                context.getGlobal().put(globalId, value);
                return elements.contains(value) ? contained : fallback;
            };
        }
        NiConfig branches = config.section(branchesKey);
        if (kind.equals("key")) {
            var key = compile(config.get("key"), String.class, validate);
            Map<String, T> compiled = new HashMap<>();
            if (branches != null)
                branches.values()
                        .forEach((name, value) -> compiled.put(name, compile.apply(value)));
            return context -> {
                String value = key.apply(context);
                context.getGlobal().put(globalId, value);
                return compiled.getOrDefault(value, fallback);
            };
        }
        boolean integers = kind.equals("int-tree");
        Object rawKey = config.get(branchesKey.equals("actions") ? "key" : "value");
        Function<NiActionContext, ? extends Number> key =
                integers
                        ? compile(rawKey, Integer.class, validate)
                        : compile(rawKey, Double.class, validate);
        TreeMap<Double, T> compiled = new TreeMap<>();
        if (branches != null)
            branches.values()
                    .forEach(
                            (name, value) -> {
                                Double number =
                                        integers ? integerKey(name) : convert(name, Double.class);
                                if (number != null) compiled.put(number, compile.apply(value));
                            });
        Object requested = config.get("action-type");
        String direction =
                requested instanceof Number number
                        ? number.intValue() >= 0 && number.intValue() < 4
                                ? List.of("LOWER", "FLOOR", "HIGHER", "CEILING")
                                        .get(number.intValue())
                                : "LOWER"
                        : requested == null
                                ? "LOWER"
                                : requested.toString().toUpperCase(Locale.ROOT);
        return context -> {
            Number value = key.apply(context);
            context.getGlobal().put(globalId, value);
            if (value == null) return fallback;
            var branch =
                    switch (direction) {
                        case "FLOOR" -> compiled.floorEntry(value.doubleValue());
                        case "HIGHER" -> compiled.higherEntry(value.doubleValue());
                        case "CEILING" -> compiled.ceilingEntry(value.doubleValue());
                        default -> compiled.lowerEntry(value.doubleValue());
                    };
            return branch == null ? fallback : branch.getValue();
        };
    }

    private static Double integerKey(String value) {
        Integer parsed = convert(value, Integer.class);
        return parsed == null ? null : parsed.doubleValue();
    }

    public record Weighted<T>(
            T value, Function<NiActionContext, Double> weight, String condition, int index) {}

    public static <T> List<Weighted<T>> weighted(
            Object input, boolean conditional, String field, Function<Object, T> compile) {
        return weighted(input, conditional, field, compile, UNCHECKED);
    }

    public static <T> List<Weighted<T>> weighted(
            Object input,
            boolean conditional,
            String field,
            Function<Object, T> compile,
            Consumer<String> validate) {
        List<Weighted<T>> result = new ArrayList<>();
        if (input instanceof List<?> list)
            for (int index = 0; index < list.size(); index++) {
                NiConfig config = config(list.get(index));
                if (config == null) continue;
                Function<NiActionContext, Double> weight;
                if (conditional) weight = compile(config.get("weight"), Double.class, validate);
                else {
                    double fixed = config.decimal("weight", 1);
                    weight = context -> fixed;
                }
                String condition = conditional ? config.string("condition") : null;
                if (condition != null) validate.accept(condition);
                result.add(
                        new Weighted<>(compile.apply(config.get(field)), weight, condition, index));
            }
        return List.copyOf(result);
    }

    public static <T> List<T> choose(
            List<Weighted<T>> entries, NiActionContext context, int count, boolean ordered) {
        return sample(entries, context, count, ordered).values();
    }

    public record Selection<T>(List<T> values, boolean combineResults) {}

    public static <T> Selection<T> sample(
            List<Weighted<T>> entries, NiActionContext context, int count, boolean ordered) {
        record Candidate<T>(T value, double weight, int index) {}
        List<Candidate<T>> candidates = new ArrayList<>(), selected = new ArrayList<>();
        for (Weighted<T> entry : entries)
            if (context.condition(entry.condition())) {
                Double weight = entry.weight().apply(context);
                double actual = weight == null ? 1 : weight;
                if (!(actual <= 0))
                    candidates.add(new Candidate<>(entry.value(), actual, entry.index()));
            }
        if (count >= candidates.size())
            return new Selection<>(candidates.stream().map(Candidate::value).toList(), true);
        for (int pick = 0; pick < count; pick++) {
            double total = candidates.stream().mapToDouble(Candidate::weight).sum();
            double point = context.evaluation().generation().random().nextDouble() * total;
            for (int index = 0; index < candidates.size(); index++) {
                point -= candidates.get(index).weight();
                if (point <= 0) {
                    selected.add(candidates.remove(index));
                    break;
                }
            }
        }
        if (ordered) selected.sort(java.util.Comparator.comparingInt(Candidate::index));
        return new Selection<>(selected.stream().map(Candidate::value).toList(), count != 1);
    }

    public static NiConfig config(Object input) {
        if (input instanceof NiConfig config) return config;
        if (input instanceof org.bukkit.configuration.ConfigurationSection section)
            return dev.itemloom.compat.ni.NiYaml.fromSection(section);
        if (input instanceof dev.itemloom.compat.ni.script.LegacyConfigReader reader)
            return reader.view();
        if (!(input instanceof Map<?, ?> map)) return null;
        Map<String, Object> result = new HashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return NiConfig.mapReader(result);
    }

    @SuppressWarnings("unchecked")
    public static <T> T convert(Object input, Class<T> type) {
        if (input == null) return null;
        if (type.isInstance(input)) return type.cast(input);
        if (type == String.class) return (T) input.toString();
        if (type == Boolean.class)
            return (T)
                    (input.toString().equalsIgnoreCase("true")
                            ? Boolean.TRUE
                            : input.toString().equalsIgnoreCase("false") ? Boolean.FALSE : null);
        try {
            if (input instanceof String text && !numericText(text, true)) return null;
            Number number =
                    input instanceof Number value ? value : Double.valueOf(input.toString());
            if (type == Integer.class) return (T) Integer.valueOf(number.intValue());
            if (type == Long.class) {
                Long exact = input instanceof String text ? strictLong(text) : null;
                return (T) (exact != null ? exact : Long.valueOf(number.longValue()));
            }
            if (type == Double.class) return (T) Double.valueOf(number.doubleValue());
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    public static Integer strictInteger(String text) {
        Long value = strictLong(text);
        return value == null ? null : (int) Math.clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static Long strictLong(String text) {
        if (!numericText(text, false)) return null;
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return text.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static boolean numericText(String text, boolean decimal) {
        if (text.isEmpty()) return false;
        int start = text.charAt(0) == '+' || text.charAt(0) == '-' ? 1 : 0;
        boolean dot = false, digit = false;
        for (int index = start; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch >= '0' && ch <= '9') digit = true;
            else if (ch == '.' && decimal && !dot) dot = true;
            else return false;
        }
        return digit;
    }

    private static <T> T scriptValue(Object input, Class<T> type) {
        if (input == null) return null;
        if (type.isInstance(input)) return type.cast(input);
        if (type == String.class || input instanceof Number) return convert(input, type);
        return null;
    }
}

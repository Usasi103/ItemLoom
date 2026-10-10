package dev.itemloom.compat.ni;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** NI node execution. All source-language choices are confined to this module. */
public final class NiNodes {
    public interface Extension {
        String configured(NiConfig config, NiEvaluation evaluation);

        String inline(List<String> arguments, NiEvaluation evaluation);
    }

    private static final Map<String, List<String>> SCALARS =
            Map.ofEntries(
                    Map.entry("number", List.of("min", "max", "fixed", "mode")),
                    Map.entry("calculation", List.of("formula", "fixed", "min", "max", "mode")),
                    Map.entry("fastcalc", List.of("formula", "fixed", "min", "max", "mode")),
                    Map.entry(
                            "gaussian",
                            List.of("base", "spread", "maxSpread", "fixed", "min", "max", "mode")),
                    Map.entry("chance", List.of("success", "total", "repeat", "min", "max")),
                    Map.entry("format", List.of("value", "format", "mode")),
                    Map.entry("default", List.of("key", "default")),
                    Map.entry("inherit", List.of("template")));
    private static final Set<String> SEQUENCES =
            Set.of("weightjoin", "weightdeclare", "rweightjoin", "rweightdeclare");
    private final Map<String, NiTemplate> templates = new LinkedHashMap<>(64, .75f, true);
    private final Map<String, Extension> extensions = new LinkedHashMap<>();
    private final NiTextNodes textNodes = new NiTextNodes();

    public synchronized NiTemplate template(String source) {
        NiTemplate result = templates.get(source);
        if (result == null) {
            result = NiTemplate.compile(source);
            if (templates.size() >= 2048) templates.remove(templates.keySet().iterator().next());
            templates.put(source, result);
        }
        return result;
    }

    /** Registration happens while preparing a revision, before it is installed. */
    public void register(String id, Extension extension) {
        extensions.put(id, Objects.requireNonNull(extension));
    }

    public String configured(NiConfig config, NiEvaluation evaluation) {
        String type = config.string("type");
        if (type == null) return null;
        Extension extension = extensions.get(type);
        // The modern registry wins; registered legacy parsers are its fallback.
        if (extension != null
                && (evaluation.mode() == NiEvaluation.Mode.SECTION || !modern(type))) {
            return extension.configured(config, evaluation.withMode(NiEvaluation.Mode.SECTION));
        }
        if (SEQUENCES.contains(type))
            return weightedSequence(type, config, evaluation.withMode(NiEvaluation.Mode.SECTION));
        if (SCALARS.containsKey(type)) {
            List<String> arguments = new ArrayList<>();
            for (String key : SCALARS.get(type)) {
                boolean literal =
                        evaluation.mode() == NiEvaluation.Mode.SECTION
                                && (key.equals("mode")
                                        || type.equals("default") && key.equals("default")
                                        || type.equals("inherit"));
                arguments.add(literal ? config.string(key) : evaluation.text(config.string(key)));
            }
            return scalar(type, arguments, evaluation, true);
        }
        return switch (type) {
            case "strings", "weight" -> choices(type, config.strings("values"), evaluation, true);
            case "js" -> script(config.string("path"), config.strings("args"), evaluation, true);
            case "join" -> join(config, evaluation);
            case "repeat" -> repeat(config, evaluation);
            case "when" -> when(config, evaluation);
            case "check" -> {
                String value = evaluation.text(config.string("value"));
                if (evaluation.player() != null)
                    evaluation.host().check(config.get("actions"), evaluation, value);
                yield value;
            }
            case "papi" -> null; // NI declares this as an inline-only parser.
            case "string", "regex" ->
                    evaluation.mode() == NiEvaluation.Mode.SECTION
                            ? null
                            : textNodes.configured(type, config, evaluation);
            case "gradient" ->
                    NiGradientText.render(
                            java.util.Arrays.asList(
                                    evaluation.text(config.string("colorStart")),
                                    evaluation.text(config.string("colorEnd")),
                                    evaluation.text(config.string("step")),
                                    evaluation.text(config.string("text"))),
                            evaluation.mode() == NiEvaluation.Mode.SECTION);
            case "lore_size", "whole_lore", "amount", "damage", "name", "type", "item_id" ->
                    evaluation.host().itemValue(type, "");
            case "data", "nbt", "lore" -> null;
            default -> {
                evaluation.warning("Unknown configured node type: " + type);
                yield null; // Unknown NI/other-plugin tags retain their original source text.
            }
        };
    }

    private static boolean modern(String type) {
        return SCALARS.containsKey(type)
                || Set.of(
                                "strings",
                                "weight",
                                "js",
                                "join",
                                "repeat",
                                "when",
                                "check",
                                "papi",
                                "amount",
                                "damage",
                                "data",
                                "item_id",
                                "lore",
                                "lore_size",
                                "name",
                                "nbt",
                                "regex",
                                "string",
                                "type",
                                "whole_lore",
                                "gradient")
                        .contains(type);
    }

    public String inline(String type, String parameters, NiEvaluation evaluation) {
        Extension extension = extensions.get(type);
        if (extension != null
                && (evaluation.mode() == NiEvaluation.Mode.SECTION || !modern(type))) {
            return extension.inline(
                    NiTemplate.arguments(parameters, 0),
                    evaluation.withMode(NiEvaluation.Mode.SECTION));
        }
        var context = dev.itemloom.compat.ni.action.NiActionContext.currentOrNull();
        if (evaluation.mode() == NiEvaluation.Mode.ACTION
                && context != null
                && context.has(dev.itemloom.compat.ni.action.NiContextKeys.PAPI_ENVIRONMENT)) {
            if (type.equals("calculation") && !evaluation.host().papiJavascript()) return null;
            if (type.equals("regex") && !evaluation.host().papiRegex()) return null;
        }
        if (SCALARS.containsKey(type)) {
            int limit =
                    evaluation.mode() == NiEvaluation.Mode.ACTION ? SCALARS.get(type).size() : 0;
            return scalar(type, NiTemplate.arguments(parameters, limit), evaluation, false);
        }
        List<String> arguments = NiTemplate.arguments(parameters, 0);
        return switch (type) {
            case "strings", "weight" -> choices(type, arguments, evaluation, false);
            case "js" ->
                    script(
                            arguments.get(0),
                            arguments.subList(1, arguments.size()),
                            evaluation,
                            false);
            case "papi" -> evaluation.host().placeholder(evaluation.player(), parameters);
            case "repeat" -> {
                if (evaluation.mode() == NiEvaluation.Mode.SECTION) yield null;
                List<String> parts = NiTemplate.arguments(parameters, 3);
                yield repeated(
                        arg(parts, 0),
                        integer(arg(parts, 1), 1),
                        arg(parts, 2),
                        "",
                        "",
                        null,
                        evaluation);
            }
            case "join",
                    "when",
                    "check",
                    "weightjoin",
                    "rweightjoin",
                    "weightdeclare",
                    "rweightdeclare" ->
                    null;
            case "string", "regex" ->
                    evaluation.mode() == NiEvaluation.Mode.SECTION
                            ? null
                            : textNodes.inline(type, parameters);
            case "gradient" ->
                    NiGradientText.render(
                            NiTemplate.arguments(parameters, 4),
                            evaluation.mode() == NiEvaluation.Mode.SECTION);
            case "amount",
                    "damage",
                    "data",
                    "item_id",
                    "lore",
                    "lore_size",
                    "name",
                    "nbt",
                    "type",
                    "whole_lore" ->
                    evaluation.host().itemValue(type, parameters);
            default -> null;
        };
    }

    private String scalar(
            String type, List<String> args, NiEvaluation evaluation, boolean configured) {
        var random = evaluation.generation().random();
        boolean modern = evaluation.mode() == NiEvaluation.Mode.ACTION;
        if (modern) {
            String fields =
                    switch (type) {
                        case "number" -> "ddi";
                        case "gaussian" -> "dddidd";
                        case "chance" -> "ddiii";
                        case "calculation", "fastcalc" -> "xidd";
                        case "format" -> "d";
                        default -> "";
                    };
            for (int i = 0; i < fields.length(); i++) {
                if (fields.charAt(i) != 'x'
                        && !numeric(arg(args, i), fields.charAt(i) == 'i', true)) {
                    evaluation.warning(type + " has an invalid numeric argument: " + arg(args, i));
                    return null;
                }
            }
        }
        return switch (type) {
            case "number" -> {
                if (arg(args, 0) == null || arg(args, 1) == null) yield null;
                if (!numeric(arg(args, 0), false, modern) || !numeric(arg(args, 1), false, modern))
                    yield null;
                double minimum = decimal(arg(args, 0), 0), maximum = decimal(arg(args, 1), 0);
                yield rounded(
                        minimum >= maximum ? minimum : random.nextDouble(minimum, maximum),
                        arg(args, 2),
                        arg(args, 3));
            }
            case "gaussian" -> {
                if (arg(args, 0) == null || arg(args, 1) == null || arg(args, 2) == null)
                    yield null;
                if (!numeric(arg(args, 0), false, modern)
                        || !numeric(arg(args, 1), false, modern)
                        || !numeric(arg(args, 2), false, modern)) yield null;
                double spread = random.nextGaussian() * decimal(arg(args, 1), 0);
                double range = decimal(arg(args, 2), 0);
                double value =
                        decimal(arg(args, 0), 0) * (1 + Math.min(range, Math.max(-range, spread)));
                yield rounded(
                        clamp(value, arg(args, 4), arg(args, 5)),
                        Integer.toString(integer(arg(args, 3), modern ? 0 : 1)),
                        arg(args, 6));
            }
            case "chance" -> {
                if (modern && arg(args, 0) == null) yield null;
                double success = decimal(arg(args, 0), 0), total = decimal(arg(args, 1), 1);
                int repeat = integer(arg(args, 2), 1), hits = 0;
                for (int index = 0; index < repeat; index++)
                    if (success > random.nextDouble(0, total)) hits++;
                yield Integer.toString((int) clamp(hits, arg(args, 3), arg(args, 4)));
            }
            case "calculation", "fastcalc" -> {
                if (arg(args, 0) == null) yield null;
                double value;
                if (type.equals("fastcalc")) value = NiArithmetic.evaluate(arg(args, 0));
                else {
                    try {
                        Object result = evaluation.scripts().evaluate(arg(args, 0), Map.of());
                        value =
                                result instanceof Number number
                                        ? number.doubleValue()
                                        : Double.parseDouble(String.valueOf(result));
                    } catch (RuntimeException error) {
                        evaluation.warning("Invalid calculation formula: " + arg(args, 0));
                        value = 0;
                    }
                }
                yield rounded(clamp(value, arg(args, 2), arg(args, 3)), arg(args, 1), arg(args, 4));
            }
            case "format" -> {
                DecimalFormat format =
                        new DecimalFormat(Objects.requireNonNullElse(arg(args, 1), "#.#"));
                format.setRoundingMode(rounding(arg(args, 2)));
                yield format.format(decimal(arg(args, 0), 0));
            }
            case "default" -> {
                String key = arg(args, 0), fallback = arg(args, 1);
                if (key != null) evaluation.value(key);
                Object value = evaluation.cache().getOrDefault(key, fallback);
                yield evaluation.mode() == NiEvaluation.Mode.ACTION
                        ? String.valueOf(value)
                        : value == null ? null : value.toString();
            }
            case "inherit" -> {
                String target = arg(args, 0);
                if (evaluation.mode() == NiEvaluation.Mode.SECTION && !configured)
                    target = String.join("_", args);
                if (target == null) yield null;
                yield evaluation.mode() == NiEvaluation.Mode.ACTION
                        ? evaluation.fresh().value(target)
                        : evaluation.uncached(target);
            }
            default -> throw new AssertionError(type);
        };
    }

    private String choices(
            String type, List<String> original, NiEvaluation evaluation, boolean configured) {
        if (original.isEmpty()) return null;
        List<String> values = new ArrayList<>(original);
        boolean eager =
                configured
                        && (type.equals("weight") || evaluation.mode() == NiEvaluation.Mode.ACTION);
        if (eager) values.replaceAll(evaluation::text);
        String selected;
        if (type.equals("strings"))
            selected = values.get(evaluation.generation().random().nextInt(values.size()));
        else {
            Map<String, BigDecimal> weights = new HashMap<>();
            for (String value : values) {
                int separator = value.indexOf("::");
                BigDecimal weight = BigDecimal.ONE;
                if (separator >= 0) {
                    try {
                        weight = new BigDecimal(value.substring(0, separator));
                    } catch (NumberFormatException ignored) {
                        weight = BigDecimal.ONE;
                    }
                    value = value.substring(separator + 2);
                }
                if (weight.signum() > 0) weights.merge(value, weight, BigDecimal::add);
            }
            BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal point =
                    BigDecimal.valueOf(evaluation.generation().random().nextDouble())
                            .multiply(total);
            BigDecimal cumulative = BigDecimal.ZERO;
            selected = null;
            for (var entry : weights.entrySet()) {
                cumulative = cumulative.add(entry.getValue());
                if (point.compareTo(cumulative) <= 0) {
                    selected = entry.getKey();
                    break;
                }
            }
        }
        return configured && evaluation.mode() == NiEvaluation.Mode.SECTION
                ? evaluation.text(selected)
                : selected;
    }

    private String script(
            String path, List<String> arguments, NiEvaluation evaluation, boolean configured) {
        if (path == null) return null;
        if (configured && evaluation.mode() == NiEvaluation.Mode.ACTION)
            path = evaluation.text(path);
        int delimiter = path.indexOf("::");
        if (delimiter < 0)
            throw new IllegalArgumentException("Script path requires file::function: " + path);
        Object result =
                evaluation
                        .scripts()
                        .invoke(
                                path.substring(0, delimiter),
                                path.substring(delimiter + 2),
                                evaluation.bindings(),
                                arguments.toArray());
        if (evaluation.mode() == NiEvaluation.Mode.SECTION && result == null) return null;
        String value = String.valueOf(result);
        return evaluation.mode() == NiEvaluation.Mode.ACTION || configured
                ? evaluation.text(value)
                : value;
    }

    private String join(NiConfig config, NiEvaluation evaluation) {
        Object raw =
                config.get(
                        evaluation.mode() == NiEvaluation.Mode.ACTION && config.contains("values")
                                ? "values"
                                : "list");
        if (!(raw instanceof List<?>)) {
            if (evaluation.mode() == NiEvaluation.Mode.ACTION) return null;
            raw = List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : (List<?>) raw) if (item != null) values.add(String.valueOf(item));
        String separator = parsed(config, "separator", ", ", evaluation);
        String prefix = parsed(config, "prefix", "", evaluation);
        String postfix = parsed(config, "postfix", "", evaluation);
        int limit = integer(evaluation.text(config.string("limit")), values.size());
        boolean truncated = limit < values.size();
        limit = Math.max(0, Math.min(limit, values.size()));
        String suffix = evaluation.text(config.string("truncated"));
        String transform = config.string("transform");
        if ("true".equals(evaluation.text(config.string("shuffled")))) shuffle(values, evaluation);
        StringBuilder result = new StringBuilder(prefix);
        Map<String, Object> bindings = evaluation.bindings();
        bindings.put("list", values);
        for (int index = 0; index < limit; index++) {
            String value = evaluation.text(values.get(index));
            result.append(transformed(value, index, transform, bindings, evaluation));
            if (index + 1 < limit || truncated && suffix != null) result.append(separator);
        }
        if (truncated && suffix != null) result.append(suffix);
        return result.append(postfix).toString();
    }

    private String repeat(NiConfig config, NiEvaluation evaluation) {
        String content = parsed(config, "content", "", evaluation);
        // Maintain the entry point's parameter evaluation order.
        if (evaluation.mode() == NiEvaluation.Mode.SECTION) {
            String separator = parsed(config, "separator", "", evaluation);
            String prefix = parsed(config, "prefix", "", evaluation);
            String postfix = parsed(config, "postfix", "", evaluation);
            int count = integer(evaluation.text(config.string("repeat")), 1);
            return repeated(
                    content,
                    count,
                    separator,
                    prefix,
                    postfix,
                    config.string("transform"),
                    evaluation);
        }
        int count = integer(evaluation.text(config.string("repeat")), 1);
        return repeated(
                content,
                count,
                parsed(config, "separator", "", evaluation),
                parsed(config, "prefix", "", evaluation),
                parsed(config, "postfix", "", evaluation),
                config.string("transform"),
                evaluation);
    }

    private static String repeated(
            String content,
            int count,
            String separator,
            String prefix,
            String postfix,
            String transform,
            NiEvaluation evaluation) {
        StringBuilder result = new StringBuilder(Objects.requireNonNullElse(prefix, ""));
        Map<String, Object> bindings = evaluation.bindings();
        for (int index = 0; index < count; index++) {
            if (index > 0 && separator != null) result.append(separator);
            result.append(
                    transformed(
                            Objects.requireNonNullElse(content, ""),
                            index,
                            transform,
                            bindings,
                            evaluation));
        }
        return result.append(Objects.requireNonNullElse(postfix, "")).toString();
    }

    private static String transformed(
            String value,
            int index,
            String script,
            Map<String, Object> bindings,
            NiEvaluation evaluation) {
        if (script == null || script.isEmpty()) return value;
        bindings.put("it", value);
        bindings.put("index", index);
        return Objects.toString(evaluation.scripts().transform(script, bindings), "");
    }

    private record Weighted(String value, double weight, int order) {}

    private String weightedSequence(String type, NiConfig config, NiEvaluation evaluation) {
        boolean declare = type.endsWith("declare"), repeatable = type.startsWith("r");
        String separator = declare ? null : parsed(config, "separator", ", ", evaluation);
        String prefix = declare ? null : parsed(config, "prefix", "", evaluation);
        String postfix = declare ? null : parsed(config, "postfix", "", evaluation);
        String transform = declare ? null : config.string("transform");
        boolean shuffled = "true".equals(evaluation.text(config.string("shuffled")));
        boolean ordered = "true".equals(evaluation.text(config.string("order")));
        List<Weighted> pool = new ArrayList<>();
        Map<String, Weighted> merged = new HashMap<>();
        List<String> values = config.strings("list");
        for (int index = 0; index < values.size(); index++) {
            String value = evaluation.text(values.get(index));
            int split = value.indexOf("::");
            double weight = split < 0 ? 1 : decimal(value.substring(0, split), 1);
            String item = split < 0 ? value : value.substring(split + 2);
            if (repeatable) pool.add(new Weighted(item, weight, index));
            else if (weight > 0) {
                Weighted old = merged.get(item);
                merged.put(
                        item, new Weighted(item, weight + (old == null ? 0 : old.weight()), index));
            }
        }
        if (!repeatable) pool.addAll(merged.values());
        String key = declare ? evaluation.text(config.string("key")) : null;
        int count =
                Math.max(
                        0,
                        Math.min(
                                integer(evaluation.text(config.string("amount")), 1), pool.size()));
        int originalCount = count;
        Map<String, String> cache = evaluation.legacyCache();
        if (declare && !repeatable && key != null && cache != null) {
            for (int index = 0; index < originalCount; index++) {
                String old = cache.get(key + "." + index);
                if (pool.removeIf(item -> Objects.equals(item.value(), old))) count--;
            }
        }
        List<Weighted> selected = sample(pool, count, evaluation);
        if (shuffled) shuffle(selected, evaluation);
        else if (ordered) selected.sort(Comparator.comparingInt(Weighted::order));
        if (declare) {
            boolean keepElse =
                    !repeatable && "true".equals(evaluation.text(config.string("putelse")));
            if (key != null && cache != null) {
                int index = 0;
                for (Weighted value : selected) {
                    while (cache.containsKey(key + "." + index)) index++;
                    cache.put(key + "." + index++, value.value());
                }
                cache.put(key + ".length", Integer.toString(originalCount));
                if (keepElse) {
                    index = 0;
                    for (Weighted value : pool) cache.put(key + ".else." + index++, value.value());
                    cache.put(key + ".else.length", Integer.toString(index));
                }
            }
            return cache == null ? null : cache.get(key + ".0");
        }
        List<String> selectedValues = selected.stream().map(Weighted::value).toList();
        Map<String, Object> bindings = evaluation.bindings();
        bindings.put("list", selectedValues);
        StringBuilder result = new StringBuilder(prefix);
        for (int index = 0; index < selected.size(); index++) {
            if (index > 0) result.append(separator);
            result.append(
                    transformed(
                            selected.get(index).value(), index, transform, bindings, evaluation));
        }
        return result.append(postfix).toString();
    }

    private static List<Weighted> sample(List<Weighted> pool, int count, NiEvaluation evaluation) {
        List<Weighted> result = new ArrayList<>();
        double total = pool.stream().mapToDouble(Weighted::weight).sum();
        for (int pick = 0; pick < count && !pool.isEmpty(); pick++) {
            double point = evaluation.generation().random().nextDouble() * total, cumulative = 0;
            for (int index = 0; index < pool.size(); index++) {
                Weighted value = pool.get(index);
                cumulative += value.weight();
                if (point <= cumulative) {
                    result.add(value);
                    pool.remove(index);
                    total -= value.weight();
                    break;
                }
            }
        }
        return result;
    }

    private String when(NiConfig config, NiEvaluation evaluation) {
        String value = evaluation.text(config.string("value"));
        if (!(config.get("conditions") instanceof List<?> conditions)) return null;
        Map<String, Object> cache = evaluation.cache();
        Object previous = cache.get("value");
        boolean action = evaluation.mode() == NiEvaluation.Mode.ACTION;
        boolean matched = false;
        if (value != null) cache.put("value", value);
        try {
            for (Object entry : conditions) {
                if (!(entry instanceof Map<?, ?> choice)) {
                    matched = true;
                    return evaluation.text(String.valueOf(entry));
                }
                Object condition = choice.get("condition");
                if (condition != null && !(condition instanceof String)) continue;
                Map<String, Object> bindings = new HashMap<>();
                bindings.put("value", value);
                bindings.put("cache", cache);
                Object sections = NiYaml.toSection(evaluation.sections());
                bindings.put(
                        "sections",
                        action
                                ? new dev.itemloom.compat.ni.script.LegacyConfigReader.BukkitReader(
                                        (org.bukkit.configuration.ConfigurationSection) sections)
                                : sections);
                Object player =
                        evaluation.player() instanceof org.bukkit.entity.Player
                                ? evaluation.player()
                                : null;
                var child =
                        new dev.itemloom.compat.ni.action.NiActionContext(
                                evaluation.action(player),
                                player,
                                action ? bindings : null,
                                bindings,
                                evaluation.scripts()::isOpen);
                if (child.condition((String) condition)) {
                    matched = true;
                    return evaluation.text(String.valueOf(choice.get("result")));
                }
            }
            return null;
        } finally {
            if (!action && matched) cache.remove("value");
            else if (action && value != null) {
                if (previous == null) cache.remove("value");
                else cache.put("value", previous);
            }
        }
    }

    static String parsed(NiConfig config, String key, String fallback, NiEvaluation evaluation) {
        return Objects.requireNonNullElse(evaluation.text(config.string(key)), fallback);
    }

    static String arg(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    static int integer(String text, int fallback) {
        try {
            return text == null || text.isEmpty() ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static double decimal(String text, double fallback) {
        try {
            return text == null || text.isEmpty() ? fallback : Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean numeric(String text, boolean integer, boolean emptyAllowed) {
        if (text == null || text.isEmpty()) return emptyAllowed;
        try {
            if (integer) Integer.parseInt(text);
            else Double.parseDouble(text);
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static double clamp(double value, String minimum, String maximum) {
        if (minimum != null && !minimum.isEmpty()) value = Math.max(value, decimal(minimum, value));
        if (maximum != null && !maximum.isEmpty()) value = Math.min(value, decimal(maximum, value));
        return value;
    }

    private static String rounded(double value, String precision, String mode) {
        return BigDecimal.valueOf(value).setScale(integer(precision, 0), rounding(mode)).toString();
    }

    private static RoundingMode rounding(String mode) {
        if (mode == null || mode.isEmpty() || mode.equals("UNNECESSARY"))
            return RoundingMode.HALF_UP;
        try {
            return RoundingMode.valueOf(mode);
        } catch (IllegalArgumentException ignored) {
            return RoundingMode.HALF_UP;
        }
    }

    private static <T> void shuffle(List<T> values, NiEvaluation evaluation) {
        for (int i = values.size() - 1; i > 0; i--) {
            int other = evaluation.generation().random().nextInt(i + 1);
            T value = values.get(i);
            values.set(i, values.get(other));
            values.set(other, value);
        }
    }
}

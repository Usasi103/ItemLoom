package dev.itemloom.compat.ni;

import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiContextKeys;
import java.math.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NiNodes {
    public interface Extension {
        String configured(NiConfig config, NiEvaluation evaluation);

        String inline(List<String> arguments, NiEvaluation evaluation);
    }

    private static final Map<String, List<String>> FIELDS =
            Map.of(
                    "number", List.of("min", "max", "fixed", "mode"),
                    "calculation", List.of("formula", "fixed", "min", "max", "mode"),
                    "fastcalc", List.of("formula", "fixed", "min", "max", "mode"),
                    "gaussian",
                            List.of("base", "spread", "maxSpread", "fixed", "min", "max", "mode"),
                    "chance", List.of("success", "total", "repeat", "min", "max"),
                    "format", List.of("value", "format", "mode"),
                    "default", List.of("key", "default"),
                    "inherit", List.of("template"));
    private static final Set<String> ITEM =
            Set.of(
                    "lore_size",
                    "whole_lore",
                    "amount",
                    "damage",
                    "name",
                    "type",
                    "item_id",
                    "data",
                    "nbt",
                    "lore");
    private final Map<String, Extension> extensions = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, NiTemplate> templates = new LinkedHashMap<>(64, .75f, true);
    private final NiTextNodes text = new NiTextNodes();

    public synchronized NiTemplate template(String source) {
        NiTemplate result = templates.get(source);
        if (result == null) {
            result = NiTemplate.compile(source);
            templates.put(source, result);
            if (templates.size() > 2048) templates.remove(templates.keySet().iterator().next());
        }
        return result;
    }

    public void register(String id, Extension extension) {
        extensions.put(id, Objects.requireNonNull(extension));
    }

    private boolean builtin(String type) {
        return FIELDS.containsKey(type)
                || ITEM.contains(type)
                || Set.of(
                                "js",
                                "papi",
                                "check",
                                "gradient",
                                "strings",
                                "weight",
                                "join",
                                "repeat",
                                "when",
                                "weightjoin",
                                "weightdeclare",
                                "rweightjoin",
                                "rweightdeclare",
                                "string",
                                "regex")
                        .contains(type);
    }

    public String configured(NiConfig config, NiEvaluation evaluation) {
        String type = config.string("type");
        if (type == null) return null;
        Extension extension = extensions.get(type);
        if (extension != null && (evaluation.mode() == NiEvaluation.Mode.SECTION || !builtin(type)))
            return extension.configured(config, evaluation.withMode(NiEvaluation.Mode.SECTION));
        if (FIELDS.containsKey(type)) {
            List<String> args = new ArrayList<>();
            for (String field : FIELDS.get(type)) {
                String value = config.string(field);
                boolean literal =
                        evaluation.mode() == NiEvaluation.Mode.SECTION
                                && (field.equals("mode")
                                        || type.equals("default") && field.equals("default")
                                        || type.equals("inherit"));
                args.add(literal ? value : evaluation.text(value));
            }
            return scalar(type, args, evaluation);
        }
        if (ITEM.contains(type))
            return Set.of("data", "nbt", "lore").contains(type)
                    ? null
                    : evaluation.host().itemValue(type, "");
        return switch (type) {
            case "js" -> script(config.string("path"), config.strings("args"), evaluation, true);
            case "papi" -> null;
            case "check" -> {
                String value = evaluation.text(config.string("value"));
                if (evaluation.player() != null)
                    evaluation.host().check(config.get("actions"), evaluation, value);
                yield value;
            }
            case "gradient" ->
                    NiGradientText.render(
                            Arrays.asList(
                                    evaluation.text(config.string("colorStart")),
                                    evaluation.text(config.string("colorEnd")),
                                    evaluation.text(config.string("step")),
                                    evaluation.text(config.string("text"))),
                            evaluation.mode() == NiEvaluation.Mode.SECTION);
            case "strings", "weight" ->
                    NiCollectionNodes.choices(type, config.strings("values"), evaluation, true);
            case "join" -> NiCollectionNodes.join(config, evaluation);
            case "repeat" -> NiCollectionNodes.repeat(config, evaluation);
            case "when" -> NiCollectionNodes.when(config, evaluation);
            case "weightjoin", "weightdeclare", "rweightjoin", "rweightdeclare" ->
                    NiCollectionNodes.weightedSequence(
                            type, config, evaluation.withMode(NiEvaluation.Mode.SECTION));
            case "string", "regex" ->
                    evaluation.mode() == NiEvaluation.Mode.ACTION
                            ? text.configured(type, config, evaluation)
                            : null;
            default -> {
                evaluation.warning("Unknown node type: " + type);
                yield null;
            }
        };
    }

    public String inline(String type, String parameters, NiEvaluation evaluation) {
        Extension extension = extensions.get(type);
        if (extension != null && (evaluation.mode() == NiEvaluation.Mode.SECTION || !builtin(type)))
            return extension.inline(
                    NiTemplate.arguments(parameters, 0),
                    evaluation.withMode(NiEvaluation.Mode.SECTION));
        NiActionContext action = NiActionContext.currentOrNull();
        if (evaluation.mode() == NiEvaluation.Mode.ACTION
                && action != null
                && action.has(NiContextKeys.PAPI_ENVIRONMENT)) {
            if (type.equals("calculation") && !evaluation.host().papiJavascript()) return null;
            if (type.equals("regex") && !evaluation.host().papiRegex()) return null;
        }
        if (FIELDS.containsKey(type)) {
            List<String> args =
                    NiTemplate.arguments(
                            parameters,
                            evaluation.mode() == NiEvaluation.Mode.ACTION
                                    ? FIELDS.get(type).size()
                                    : 0);
            if (type.equals("inherit") && evaluation.mode() == NiEvaluation.Mode.SECTION)
                return evaluation.uncached(String.join("_", args));
            return scalar(type, args, evaluation);
        }
        if (ITEM.contains(type)) return evaluation.host().itemValue(type, parameters);
        return switch (type) {
            case "js" -> {
                List<String> args = NiTemplate.arguments(parameters, 0);
                yield script(arg(args, 0), args.subList(1, args.size()), evaluation, false);
            }
            case "papi" -> evaluation.host().placeholder(evaluation.player(), parameters);
            case "gradient" ->
                    NiGradientText.render(
                            NiTemplate.arguments(parameters, 4),
                            evaluation.mode() == NiEvaluation.Mode.SECTION);
            case "strings", "weight" ->
                    NiCollectionNodes.choices(
                            type, NiTemplate.arguments(parameters, 0), evaluation, false);
            case "repeat" ->
                    NiCollectionNodes.inlineRepeat(NiTemplate.arguments(parameters, 3), evaluation);
            case "string", "regex" ->
                    evaluation.mode() == NiEvaluation.Mode.ACTION
                            ? text.inline(type, parameters)
                            : null;
            default -> null;
        };
    }

    private String script(
            String path, List<String> args, NiEvaluation evaluation, boolean configured) {
        if (path == null) return null;
        if (configured && evaluation.mode() == NiEvaluation.Mode.ACTION)
            path = evaluation.text(path);
        int split = path.indexOf("::");
        if (split < 0)
            throw new IllegalArgumentException("Expected script file::function: " + path);
        Object result =
                evaluation
                        .scripts()
                        .invoke(
                                path.substring(0, split),
                                path.substring(split + 2),
                                evaluation.bindings(),
                                args.toArray());
        if (result == null && evaluation.mode() == NiEvaluation.Mode.SECTION) return null;
        String value = String.valueOf(result);
        return configured || evaluation.mode() == NiEvaluation.Mode.ACTION
                ? evaluation.text(value)
                : value;
    }

    private String scalar(String type, List<String> args, NiEvaluation evaluation) {
        boolean action = evaluation.mode() == NiEvaluation.Mode.ACTION;
        if (action && !valid(type, args)) {
            evaluation.warning("Invalid numeric arguments for " + type);
            return null;
        }
        String first = arg(args, 0);
        switch (type) {
            case "default":
                {
                    if (first != null) evaluation.value(first);
                    Object value = evaluation.cache().getOrDefault(first, arg(args, 1));
                    return value == null && !action ? null : String.valueOf(value);
                }
            case "inherit":
                return first == null
                        ? null
                        : action ? evaluation.fresh().value(first) : evaluation.uncached(first);
            case "format":
                {
                    DecimalFormat format = new DecimalFormat(defaulted(arg(args, 1), "#.#"));
                    format.setRoundingMode(rounding(arg(args, 2)));
                    return format.format(decimal(first, 0));
                }
            case "number":
                {
                    String last = arg(args, 1);
                    if (first == null || last == null) return null;
                    if (!action && (!numeric(first) || !numeric(last))) return null;
                    double min = decimal(first, 0), max = decimal(last, 0);
                    double value =
                            min >= max
                                    ? min
                                    : evaluation.generation().random().nextDouble(min, max);
                    return rounded(value, integer(arg(args, 2), 0), arg(args, 3));
                }
            case "gaussian":
                {
                    if (!numeric(first) || !numeric(arg(args, 1)) || !numeric(arg(args, 2)))
                        return null;
                    double base = Double.parseDouble(first),
                            spread = Double.parseDouble(arg(args, 1)),
                            cap = Double.parseDouble(arg(args, 2));
                    double displacement =
                            Math.max(
                                    -cap,
                                    Math.min(
                                            cap,
                                            evaluation.generation().random().nextGaussian()
                                                    * spread));
                    return rounded(
                            clamp(base + base * displacement, arg(args, 4), arg(args, 5)),
                            integer(arg(args, 3), action ? 0 : 1),
                            arg(args, 6));
                }
            case "chance":
                {
                    if (action && (first == null || first.isEmpty())) return null;
                    double success = decimal(first, 0), total = decimal(arg(args, 1), 1);
                    int count = 0;
                    for (int i = 0; i < integer(arg(args, 2), 1); i++)
                        if (success > evaluation.generation().random().nextDouble(total)) count++;
                    return String.valueOf((int) clamp(count, arg(args, 3), arg(args, 4)));
                }
            case "calculation", "fastcalc":
                {
                    if (first == null) return null;
                    double value;
                    if (type.equals("fastcalc")) value = NiArithmetic.evaluate(first);
                    else {
                        try {
                            Object output = evaluation.scripts().evaluate(first, Map.of());
                            value =
                                    output instanceof Number number
                                            ? number.doubleValue()
                                            : Double.parseDouble(String.valueOf(output));
                        } catch (RuntimeException error) {
                            evaluation.warning("Invalid calculation formula: " + first);
                            value = 0;
                        }
                    }
                    return rounded(
                            clamp(value, arg(args, 2), arg(args, 3)),
                            integer(arg(args, 1), 0),
                            arg(args, 4));
                }
            default:
                return null;
        }
    }

    private static boolean valid(String type, List<String> args) {
        int[] doubles =
                switch (type) {
                    case "number" -> new int[] {0, 1};
                    case "gaussian" -> new int[] {0, 1, 2, 4, 5};
                    case "chance" -> new int[] {0, 1};
                    case "calculation", "fastcalc" -> new int[] {2, 3};
                    case "format" -> new int[] {0};
                    default -> new int[0];
                };
        int[] integers =
                switch (type) {
                    case "number" -> new int[] {2};
                    case "gaussian" -> new int[] {3};
                    case "chance" -> new int[] {2, 3, 4};
                    case "calculation", "fastcalc" -> new int[] {1};
                    default -> new int[0];
                };
        try {
            for (int index : doubles) {
                String value = arg(args, index);
                if (value != null && !value.isEmpty()) Double.parseDouble(value);
            }
            for (int index : integers) {
                String value = arg(args, index);
                if (value != null && !value.isEmpty()) Integer.parseInt(value);
            }
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static boolean numeric(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static double clamp(double value, String min, String max) {
        return Math.min(
                decimal(max, Double.POSITIVE_INFINITY),
                Math.max(decimal(min, Double.NEGATIVE_INFINITY), value));
    }

    private static RoundingMode rounding(String mode) {
        try {
            RoundingMode value = RoundingMode.valueOf(mode);
            return value == RoundingMode.UNNECESSARY ? RoundingMode.HALF_UP : value;
        } catch (IllegalArgumentException | NullPointerException error) {
            return RoundingMode.HALF_UP;
        }
    }

    private static String rounded(double value, int scale, String mode) {
        return BigDecimal.valueOf(value).setScale(scale, rounding(mode)).toPlainString();
    }

    static String arg(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    static int integer(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    static double decimal(String text, double fallback) {
        try {
            return text == null ? fallback : Double.parseDouble(text);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}

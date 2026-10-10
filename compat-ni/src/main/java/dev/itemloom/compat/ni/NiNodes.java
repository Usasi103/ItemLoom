package dev.itemloom.compat.ni;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
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
            return NiCollectionNodes.weightedSequence(
                    type, config, evaluation.withMode(NiEvaluation.Mode.SECTION));
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
            case "strings", "weight" ->
                    NiCollectionNodes.choices(type, config.strings("values"), evaluation, true);
            case "js" -> script(config.string("path"), config.strings("args"), evaluation, true);
            case "join" -> NiCollectionNodes.join(config, evaluation);
            case "repeat" -> NiCollectionNodes.repeat(config, evaluation);
            case "when" -> NiCollectionNodes.when(config, evaluation);
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
            case "strings", "weight" ->
                    NiCollectionNodes.choices(type, arguments, evaluation, false);
            case "js" ->
                    script(
                            arguments.get(0),
                            arguments.subList(1, arguments.size()),
                            evaluation,
                            false);
            case "papi" -> evaluation.host().placeholder(evaluation.player(), parameters);
            case "repeat" ->
                    NiCollectionNodes.inlineRepeat(NiTemplate.arguments(parameters, 3), evaluation);
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
}

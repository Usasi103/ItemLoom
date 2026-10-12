package dev.itemloom.compat.sx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.script.Bindings;

/** A request-local evaluator; recursion shares one bounded work account. */
public final class SxExpressions {
    public static final String DELETE = "<DeleteLore>";
    private static final int MAX_TEXT = 1_048_576;
    private static final Pattern COMPARISON = Pattern.compile(" (eq|ne|gt|lt|ge|le) ");

    @FunctionalInterface
    public interface ScriptCall {
        Object call(String file, String function, SxExpressions handler, String[] arguments);
    }

    private final Object player;
    private final Map<String, SxRandom> local;
    private final Map<String, SxRandom> global;
    private final Map<String, String> other;
    private final Map<String, String> locks = new LinkedHashMap<>();
    private final RandomGenerator rng;
    private final UnaryOperator<String> placeholders;
    private final ScriptCall scripts;
    private final Clock clock;
    private final int precision;
    private final String timeFormat;
    private int depth;
    private int work;

    public SxExpressions(
            Object player,
            Map<String, SxRandom> local,
            Map<String, SxRandom> global,
            Map<String, String> parameters,
            RandomGenerator rng,
            UnaryOperator<String> placeholders,
            ScriptCall scripts,
            SxConfig settings,
            Clock clock) {
        this.player = player;
        this.local = local;
        this.global = global;
        this.other = new LinkedHashMap<>(parameters);
        this.rng = rng;
        this.placeholders = placeholders;
        this.scripts = scripts;
        this.clock = clock;
        precision = Integer.parseInt(settings.text("DecimalPrecision", "2"));
        if (precision < 0 || precision > 12) {
            throw new IllegalArgumentException("DecimalPrecision must be in 0..12");
        }
        timeFormat = settings.text("TimeFormat", "yyyy/MM/dd HH:mm");
        new SimpleDateFormat(timeFormat);
    }

    public Object getPlayer() {
        return player;
    }

    public Map<String, String> getLockMap() {
        return locks;
    }

    public Map<String, String> getOtherMap() {
        return other;
    }

    public Map<String, SxRandom> getLocalMap() {
        return local;
    }

    public String random(String key) {
        String value = other.get(key);
        if (value == null && local.containsKey(key)) {
            value = local.get(key).pick(rng);
        }
        if (value == null && global.containsKey(key)) {
            value = global.get(key).pick(rng);
        }
        return value;
    }

    public String replace(String source) {
        if (source == null) {
            return null;
        }
        if (depth == 0) {
            work = 0;
        }
        if (++depth > 64) {
            depth--;
            throw new IllegalArgumentException("SX expression nesting exceeds 64");
        }
        try {
            length(source);
            StringBuilder output = new StringBuilder();
            int index = 0;
            while (index < source.length()) {
                step();
                int open = source.indexOf('<', index);
                if (open < 0) {
                    output.append(source, index, source.length());
                    break;
                }
                if (open > index && source.charAt(open - 1) == '$') {
                    output.append(source, index, open - 1).append('<');
                    index = open + 1;
                    continue;
                }
                output.append(source, index, open);
                int close = closing(source, open);
                if (close < 0) {
                    output.append(source, open, source.length());
                    break;
                }
                String body = source.substring(open + 1, close);
                if (!body.contains(":")) {
                    output.append('<').append(body).append('>');
                } else {
                    String expanded = replace(body);
                    int colon = expanded.indexOf(':');
                    if (colon < 0) {
                        output.append('<').append(expanded).append('>');
                    } else {
                        String kind = expanded.substring(0, colon);
                        String result = evaluate(kind, expanded.substring(colon + 1));
                        output.append(
                                result == null
                                        ? DELETE
                                        : kind.equals("l") ? result : replace(result));
                    }
                }
                length(output);
                index = close + 1;
            }
            length(output);
            String result = placeholders.apply(output.toString());
            if (result != null) {
                length(result);
            }
            return result;
        } finally {
            depth--;
        }
    }

    private int closing(String source, int open) {
        int nested = 1;
        for (int index = open + 1; index < source.length(); index++) {
            char token = source.charAt(index);
            if (token == '<') {
                if (++nested + depth > 65) {
                    throw new IllegalArgumentException("SX expression nesting exceeds 64");
                }
                step();
            } else if (token == '>') {
                step();
                if (--nested == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private void step() {
        if (++work > 8192) {
            throw new IllegalArgumentException("SX expression work exceeds 8192 steps");
        }
    }

    private static void length(CharSequence text) {
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("SX expression exceeds maximum text length");
        }
    }

    public List<String> replace(List<String> source) {
        List<String> result = new ArrayList<>();
        for (String line : source) {
            String expanded = replace(line);
            if (expanded != null) {
                for (String part : expanded.split("\n")) {
                    if (!part.contains(DELETE)) {
                        result.add(part);
                    }
                }
            }
        }
        return result;
    }

    public Object replace(Object value) {
        if (value instanceof String text) {
            return SxValueRules.scalar(replace(text));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), replace(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(child -> result.add(replace(child)));
            return result;
        }
        return value;
    }

    private String evaluate(String kind, String argument) {
        return switch (kind) {
            case "s" -> argument.contains(":") ? choice(argument) : random(argument);
            case "l" ->
                    SxValueRules.locked(
                            argument, locks, other, this::random, rng::nextInt, this::replace);
            case "i", "r" -> integer(argument);
            case "d" -> decimal(argument);
            case "b" -> filter(argument);
            case "c" ->
                    argument.startsWith("int")
                            ? Long.toString(Math.round(SxMath.evaluate(argument.substring(3))))
                            : rounded(SxMath.evaluate(argument));
            case "max", "min" -> extreme(argument, kind.equals("max"));
            case "eq", "like" -> equality(argument, kind.equals("like"));
            case "cmp" -> compare(argument);
            case "if" -> condition(argument);
            case "when" -> SxBranchTime.branch(argument);
            case "null" -> "";
            case "u" ->
                    (argument.equals("random")
                                    ? UUID.randomUUID()
                                    : UUID.nameUUIDFromBytes(
                                            argument.getBytes(StandardCharsets.UTF_8)))
                            .toString();
            case "t" -> SxBranchTime.time(argument, timeFormat, clock);
            case "j" -> script(argument);
            default -> throw new IllegalArgumentException("Unknown SX expression: " + kind);
        };
    }

    private String choice(String choices) {
        String[] values = choices.split(":", -1);
        return values[rng.nextInt(values.length)];
    }

    private String integer(String argument) {
        int split = argument.indexOf('_');
        if (split < 0) {
            return argument;
        }
        int a = Integer.parseInt(argument.substring(0, split));
        int b = Integer.parseInt(argument.substring(split + 1));
        return Long.toString(rng.nextLong(Math.min(a, b), (long) Math.max(a, b) + 1));
    }

    private String decimal(String argument) {
        int split = argument.indexOf('_');
        if (split < 0) {
            return argument;
        }
        double a = Double.parseDouble(argument.substring(0, split));
        double b = Double.parseDouble(argument.substring(split + 1));
        return rounded(a + rng.nextDouble() * (b - a));
    }

    private String rounded(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite SX decimal result");
        }
        return Double.toString(
                BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue());
    }

    private static String filter(String input) {
        int hash = input.indexOf('#');
        int colon = input.indexOf(':');
        int split = hash < 0 ? colon : colon < 0 ? hash : Math.min(hash, colon);
        if (split <= 0) {
            return null;
        }
        String wanted = input.substring(0, split);
        for (String choice : input.substring(split + 1).split(":", -1)) {
            if (choice.equals(wanted)) {
                return "";
            }
        }
        return null;
    }

    private static String extreme(String input, boolean maximum) {
        String best = null;
        double value = 0;
        for (String candidate : input.split(":", -1)) {
            double parsed = Double.parseDouble(candidate);
            if (best == null || (maximum ? parsed > value : parsed < value)) {
                best = candidate;
                value = parsed;
            }
        }
        return best;
    }

    private static String equality(String input, boolean contains) {
        int split = input.indexOf("==");
        if (split < 0) {
            return "false";
        }
        String a = input.substring(0, split);
        String b = input.substring(split + 2);
        return Boolean.toString(contains ? a.contains(b) || b.contains(a) : a.equals(b));
    }

    private static String compare(String input) {
        Matcher match = COMPARISON.matcher(input);
        if (!match.find()) {
            return "false";
        }
        String a = input.substring(0, match.start()).trim();
        String b = input.substring(match.end()).trim();
        int comparison;
        try {
            comparison = Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException ignored) {
            comparison = a.compareTo(b);
        }
        return Boolean.toString(
                switch (match.group(1)) {
                    case "eq" -> comparison == 0;
                    case "ne" -> comparison != 0;
                    case "gt" -> comparison > 0;
                    case "lt" -> comparison < 0;
                    case "ge" -> comparison >= 0;
                    case "le" -> comparison <= 0;
                    default -> false;
                });
    }

    private static String condition(String input) {
        int question = input.indexOf('?');
        int colon = question < 0 ? -1 : input.indexOf(':', question + 1);
        if (colon < 0) {
            return "";
        }
        String condition = input.substring(0, question).trim();
        return switch (condition) {
            case "true", "1", "yes", "ok" -> input.substring(question + 1, colon);
            default -> input.substring(colon + 1);
        };
    }

    private String script(String input) {
        int dot = input.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("Script expression needs file.function");
        }
        int hash = input.indexOf('#', dot + 1);
        String function = input.substring(dot + 1, hash < 0 ? input.length() : hash);
        String[] arguments = hash < 0 ? null : input.substring(hash + 1).split(",");
        Object value = scripts.call(input.substring(0, dot), function, this, arguments);
        Collection<?> lines =
                value instanceof Bindings bindings
                        ? bindings.values()
                        : value instanceof Collection<?> collection ? collection : null;
        return lines == null
                ? value == null ? null : value.toString()
                : String.join("\n", lines.stream().map(String::valueOf).toList());
    }
}

package dev.itemloom.compat.sx;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/** Request-local implementation of SX 4.5.11's expression language, without SX classes. */
public final class SxExpressions {
    public static final String DELETE = "<DeleteLore>";
    private static final int MAX_DEPTH = 64, MAX_STEPS = 8192, MAX_TEXT = 1_048_576;
    private static final java.util.regex.Pattern COMPARE =
            java.util.regex.Pattern.compile(" (eq|ne|gt|lt|ge|le) ");

    @FunctionalInterface
    public interface ScriptCall {
        Object call(String file, String function, SxExpressions handler, String[] arguments);
    }

    private final Object player;
    private final Map<String, SxRandom> local, global;
    private final Map<String, String> locks = new LinkedHashMap<>(), other = new LinkedHashMap<>();
    private final RandomGenerator rng;
    private final UnaryOperator<String> placeholders;
    private final ScriptCall scripts;
    private final double precision;
    private final String timeFormat;
    private final Clock clock;
    private int depth, steps;

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
        this.rng = rng;
        other.putAll(parameters);
        this.placeholders = placeholders;
        this.scripts = scripts;
        this.clock = clock;
        int digits = Integer.parseInt(settings.text("DecimalPrecision", "2"));
        if (digits < 0 || digits > 12)
            throw new IllegalArgumentException("SX DecimalPrecision must be 0..12");
        precision = Math.pow(10, digits);
        timeFormat = settings.text("TimeFormat", "yyyy/MM/dd HH:mm");
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
        if (value == null && local.containsKey(key)) value = local.get(key).pick(rng);
        if (value == null && global.containsKey(key)) value = global.get(key).pick(rng);
        return value;
    }

    public String replace(String source) {
        if (source == null) return null;
        if (++depth > MAX_DEPTH) {
            depth--;
            throw new IllegalArgumentException("SX expression cycle or nesting over " + MAX_DEPTH);
        }
        try {
            if (source.length() > MAX_TEXT)
                throw new IllegalArgumentException("SX expression text is too large");
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < source.length(); ) {
                if (++steps > MAX_STEPS)
                    throw new IllegalArgumentException("SX expression work limit exceeded");
                char ch = source.charAt(i);
                if (ch == '$' && i + 1 < source.length() && source.charAt(i + 1) == '<') {
                    result.append('<');
                    i += 2;
                    continue;
                }
                if (ch != '<') {
                    result.append(ch);
                    i++;
                    continue;
                }
                int end = closing(source, i);
                if (end < 0) {
                    result.append(source.substring(i));
                    break;
                }
                String raw = source.substring(i + 1, end);
                String key = replace(raw);
                int separator = key.indexOf(':');
                if (separator < 0) result.append('<').append(key).append('>');
                else {
                    String kind = key.substring(0, separator),
                            argument = key.substring(separator + 1);
                    String value = expression(kind, argument);
                    result.append(
                            value == null ? DELETE : (kind.equals("l") ? value : replace(value)));
                }
                if (result.length() > MAX_TEXT)
                    throw new IllegalArgumentException("SX expanded text is too large");
                i = end + 1;
            }
            return placeholders.apply(result.toString());
        } finally {
            depth--;
        }
    }

    private static int closing(String source, int start) {
        int level = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '<') level++;
            else if (ch == '>' && --level == 0) return i;
        }
        return -1;
    }

    public List<String> replace(List<String> source) {
        List<String> result = new ArrayList<>();
        for (String line : source) {
            String value = replace(line);
            if (value == null) continue;
            // SX splits multiline random groups into independent Lore/enchantment entries.
            for (String part : value.split("\n")) if (!part.contains(DELETE)) result.add(part);
        }
        return result;
    }

    public Object replace(Object value) {
        if (value instanceof String text) return typed(replace(text));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, v) -> result.put(key.toString(), replace(v)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::replace).toList();
        return value;
    }

    private static Object typed(String value) {
        if (value == null || !value.startsWith("[")) return value;
        int end = value.indexOf(']');
        if (end < 0) return value;
        String number = value.substring(end + 1);
        return switch (value.substring(1, end)) {
            case "byte" -> Byte.valueOf(number);
            case "short" -> Short.valueOf(number);
            case "int" -> Integer.valueOf(number);
            case "long" -> Long.valueOf(number);
            case "float" -> Float.valueOf(number);
            case "double" -> Double.valueOf(number);
            default -> value;
        };
    }

    private String expression(String kind, String argument) {
        return switch (kind) {
            case "s" -> argument.contains(":") ? choose(argument) : random(argument);
            case "l" -> lock(argument);
            case "i", "r" -> integer(argument);
            case "d" -> decimal(argument);
            case "b" -> matches(argument);
            case "c" -> {
                boolean integer = argument.startsWith("int");
                double number = SxMath.evaluate(integer ? argument.substring(3) : argument);
                yield integer ? Long.toString(Math.round(number)) : format(number);
            }
            case "max", "min" -> extreme(argument, kind.equals("max"));
            case "eq", "like" -> {
                int split = argument.indexOf("==");
                if (split < 0) yield "false";
                String a = argument.substring(0, split), b = argument.substring(split + 2);
                yield Boolean.toString(
                        kind.equals("eq") ? a.equals(b) : a.contains(b) || b.contains(a));
            }
            case "cmp" -> compare(argument);
            case "if" -> conditional(argument);
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
            default ->
                    throw new IllegalArgumentException("Unsupported SX expression type: " + kind);
        };
    }

    private String lock(String argument) {
        int hash = argument.indexOf('#');
        String key = hash < 0 ? argument : argument.substring(0, hash);
        String value = locks.get(key);
        if (value != null) return value;
        if (hash < 0) value = random(key);
        else {
            value = other.get(key);
            if (value == null) value = choose(argument.substring(hash + 1));
        }
        value = replace(value);
        locks.put(key, value);
        return value;
    }

    private String choose(String text) {
        String[] choices = text.split(":", -1);
        return choices[rng.nextInt(choices.length)];
    }

    private String integer(String text) {
        int split = text.indexOf('_');
        if (split < 0) return text;
        int a = Integer.parseInt(text.substring(0, split)),
                b = Integer.parseInt(text.substring(split + 1));
        return Long.toString(rng.nextLong(Math.min(a, b), (long) Math.max(a, b) + 1));
    }

    private String decimal(String text) {
        int split = text.indexOf('_');
        if (split < 0) return text;
        double a = Double.parseDouble(text.substring(0, split)),
                b = Double.parseDouble(text.substring(split + 1));
        return format(a + rng.nextDouble() * (b - a));
    }

    private String format(double number) {
        if (!Double.isFinite(number))
            throw new IllegalArgumentException("Non-finite SX numeric result");
        return Double.toString(Math.round(number * precision) / precision);
    }

    private String matches(String text) {
        int split = text.indexOf('#'), colon = text.indexOf(':');
        if (split < 0 || colon >= 0 && colon < split) split = colon;
        if (split <= 0) return null;
        String match = text.substring(0, split);
        for (String option : text.substring(split + 1).split(":", -1))
            if (match.equals(option)) return "";
        return null;
    }

    private String extreme(String text, boolean max) {
        String selected = null;
        double best = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (String part : text.split(":")) {
            double value = Double.parseDouble(part);
            if (selected == null || (max ? value > best : value < best)) {
                selected = part;
                best = value;
            }
        }
        return selected;
    }

    private static String compare(String text) {
        var matcher = COMPARE.matcher(text);
        if (!matcher.find()) return "false";
        String a = text.substring(0, matcher.start()).trim(),
                b = text.substring(matcher.end()).trim();
        int compare;
        try {
            compare = Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException ignored) {
            compare = a.compareTo(b);
        }
        return Boolean.toString(
                switch (matcher.group(1)) {
                    case "eq" -> compare == 0;
                    case "ne" -> compare != 0;
                    case "gt" -> compare > 0;
                    case "lt" -> compare < 0;
                    case "ge" -> compare >= 0;
                    default -> compare <= 0;
                });
    }

    private static String conditional(String text) {
        int question = text.indexOf('?'), colon = text.indexOf(':', question + 1);
        if (question < 0 || colon < 0) return "";
        boolean condition =
                List.of("true", "1", "yes", "ok").contains(text.substring(0, question).trim());
        return condition ? text.substring(question + 1, colon) : text.substring(colon + 1);
    }

    private String script(String text) {
        int dot = text.indexOf('.');
        if (dot < 1) throw new IllegalArgumentException("SX script must be File.function: " + text);
        int hash = text.indexOf('#', dot);
        Object result =
                scripts.call(
                        text.substring(0, dot),
                        text.substring(dot + 1, hash < 0 ? text.length() : hash),
                        this,
                        hash < 0 ? null : text.substring(hash + 1).split(","));
        if (result == null) return null;
        if (result instanceof javax.script.Bindings bindings) result = bindings.values();
        if (result instanceof java.util.Collection<?> list)
            return String.join("\n", list.stream().map(String::valueOf).toList());
        return result.toString();
    }
}

package dev.itemloom.compat.ni;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Text requests are decoded once, then dispatched without evaluating unused fields again. */
final class NiTextNodes {
    private static final int PATTERN_LIMIT = 256;
    private final Map<PatternKey, Pattern> patterns = new LinkedHashMap<>(16, 0.75f, true);

    String configured(String type, NiConfig config, NiEvaluation evaluation) {
        if ("string".equals(type)) {
            return string(
                    new StringRequest(
                            evaluation.text(config.string("mode")),
                            evaluation.text(config.string("value")),
                            evaluation.text(config.string("arg")),
                            evaluation.text(config.string("start")),
                            evaluation.text(config.string("end")),
                            evaluation.text(config.string("default")),
                            evaluation.text(config.string("target")),
                            evaluation.text(config.string("replacement"))));
        }
        return regex(
                new RegexRequest(
                        evaluation.text(config.string("mode")),
                        evaluation.text(config.string("value")),
                        evaluation.text(config.string("pattern")),
                        evaluation.text(config.string("group")),
                        evaluation.text(config.string("default")),
                        evaluation.text(config.string("replacement")),
                        evaluation.text(config.string("flags")),
                        evaluation.text(config.string("literal-replacement"))));
    }

    String inline(String type, String source) {
        List<String> arguments = NiTemplate.arguments(source, 0);
        return "string".equals(type)
                ? string(StringRequest.inline(arguments))
                : regex(RegexRequest.inline(arguments));
    }

    static String color(int value) {
        String hex = String.format(Locale.ROOT, "%06x", Math.clamp(value, 0, 0xffffff));
        StringBuilder result = new StringBuilder("\u00a7x");
        for (int index = 0; index < hex.length(); index++) {
            result.append('\u00a7').append(hex.charAt(index));
        }
        return result.toString();
    }

    private static String string(StringRequest request) {
        if (request == null || request.value() == null) return null;
        StringOperation operation = StringOperation.named(request.mode());
        return operation == null ? null : operation.apply(request);
    }

    private String regex(RegexRequest request) {
        if (request == null || request.value() == null || request.pattern() == null) return null;
        RegexOperation operation = RegexOperation.named(request.mode());
        if (operation == null) return null;
        Integer flags = flags(request.flags());
        if (flags == null) return null;
        try {
            Matcher matcher =
                    compiled(new PatternKey(request.pattern(), flags)).matcher(request.value());
            return operation.apply(matcher, request);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalidInput) {
            return null;
        }
    }

    private synchronized Pattern compiled(PatternKey key) {
        Pattern existing = patterns.get(key);
        if (existing != null) return existing;
        Pattern compiled = Pattern.compile(key.expression(), key.flags());
        patterns.put(key, compiled);
        if (patterns.size() > PATTERN_LIMIT) {
            var oldest = patterns.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return compiled;
    }

    private static Integer flags(String source) {
        int result = 0;
        if (source == null) return result;
        for (int index = 0; index < source.length(); index++) {
            int next =
                    switch (source.charAt(index)) {
                        case 'i' -> Pattern.CASE_INSENSITIVE;
                        case 'm' -> Pattern.MULTILINE;
                        case 's' -> Pattern.DOTALL;
                        case 'u' -> Pattern.UNICODE_CASE;
                        case 'U' -> Pattern.UNICODE_CHARACTER_CLASS;
                        case 'x' -> Pattern.COMMENTS;
                        case 'd' -> Pattern.UNIX_LINES;
                        case 'l' -> Pattern.LITERAL;
                        case 'c' -> Pattern.CANON_EQ;
                        default -> -1;
                    };
            if (next == -1) return null;
            result |= next;
        }
        return result;
    }

    private record PatternKey(String expression, int flags) {}

    private record StringRequest(
            String mode,
            String value,
            String argument,
            String start,
            String end,
            String fallback,
            String target,
            String replacement) {
        private static StringRequest inline(List<String> tokens) {
            StringOperation operation = StringOperation.named(tokens.getFirst());
            if (operation == null || !operation.accepts(tokens.size())) return null;
            String value = null;
            String argument = null;
            String start = null;
            String end = null;
            String fallback = null;
            String target = null;
            String replacement = null;
            switch (operation) {
                case LOWER, UPPER, TRIM, LENGTH -> value = tokens.get(1);
                case CONTAINS, STARTS, ENDS -> {
                    argument = tokens.get(1);
                    value = tokens.get(2);
                }
                case REPLACE -> {
                    target = tokens.get(1);
                    replacement = tokens.get(2);
                    value = tokens.get(3);
                }
                case SUBSTRING -> {
                    start = tokens.get(1);
                    end = tokens.get(2);
                    fallback = optional(tokens, 3);
                    value = optional(tokens, 4);
                }
            }
            return new StringRequest(
                    tokens.getFirst(), value, argument, start, end, fallback, target, replacement);
        }
    }

    private enum StringOperation {
        LOWER(2, 2),
        UPPER(2, 2),
        TRIM(2, 2),
        LENGTH(2, 2),
        CONTAINS(3, 3),
        STARTS(3, 3),
        ENDS(3, 3),
        REPLACE(4, 4),
        SUBSTRING(3, 5);

        private static final Map<String, StringOperation> BY_NAME =
                Arrays.stream(values())
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        operation -> operation.name().toLowerCase(Locale.ROOT),
                                        Function.identity()));
        private final int minimum;
        private final int maximum;

        StringOperation(int minimum, int maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
        }

        static StringOperation named(String name) {
            return name == null ? null : BY_NAME.get(name);
        }

        boolean accepts(int size) {
            return size >= minimum && size <= maximum;
        }

        String apply(StringRequest request) {
            String value = request.value();
            return switch (this) {
                case LOWER -> value.toLowerCase(Locale.ROOT);
                case UPPER -> value.toUpperCase(Locale.ROOT);
                case TRIM -> value.trim();
                case LENGTH -> Integer.toString(value.length());
                case CONTAINS ->
                        request.argument() == null
                                ? null
                                : Boolean.toString(value.contains(request.argument()));
                case STARTS ->
                        request.argument() == null
                                ? null
                                : Boolean.toString(value.startsWith(request.argument()));
                case ENDS ->
                        request.argument() == null
                                ? null
                                : Boolean.toString(value.endsWith(request.argument()));
                case REPLACE ->
                        request.target() == null || request.replacement() == null
                                ? null
                                : value.replace(request.target(), request.replacement());
                case SUBSTRING -> substring(request);
            };
        }
    }

    private static String substring(StringRequest request) {
        if (request.start() == null) return null;
        int start;
        int end;
        try {
            start = request.start().isEmpty() ? 0 : Integer.parseInt(request.start());
            end =
                    request.end() == null || request.end().isEmpty()
                            ? request.value().length()
                            : Integer.parseInt(request.end());
        } catch (NumberFormatException invalidIndex) {
            return null;
        }
        int length = request.value().length();
        if (start < 0 || end < 0 || start > length || end > length) return request.fallback();
        return start > end ? "" : request.value().substring(start, end);
    }

    private record RegexRequest(
            String mode,
            String value,
            String pattern,
            String group,
            String fallback,
            String replacement,
            String flags,
            String literalReplacement) {
        private static RegexRequest inline(List<String> tokens) {
            RegexOperation operation = RegexOperation.named(tokens.getFirst());
            if (operation == null || !operation.accepts(tokens.size())) return null;
            String group = null;
            String fallback = null;
            String replacement = null;
            String flags = null;
            String literalReplacement = null;
            switch (operation) {
                case MATCHES, FIND, COUNT -> flags = optional(tokens, 3);
                case GROUP -> {
                    group = optional(tokens, 3);
                    fallback = optional(tokens, 4);
                    flags = optional(tokens, 5);
                }
                case REPLACE_FIRST, REPLACE_ALL -> {
                    replacement = tokens.get(3);
                    flags = optional(tokens, 4);
                    literalReplacement = optional(tokens, 5);
                }
            }
            return new RegexRequest(
                    tokens.getFirst(),
                    tokens.get(1),
                    tokens.get(2),
                    group,
                    fallback,
                    replacement,
                    flags,
                    literalReplacement);
        }
    }

    private enum RegexOperation {
        MATCHES("matches", 3, 4),
        FIND("find", 3, 4),
        COUNT("count", 3, 4),
        GROUP("group", 3, 6),
        REPLACE_FIRST("replace-first", 4, 6),
        REPLACE_ALL("replace-all", 4, 6);

        private static final Map<String, RegexOperation> BY_NAME =
                Arrays.stream(values())
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        operation -> operation.name, Function.identity()));
        private final String name;
        private final int minimum;
        private final int maximum;

        RegexOperation(String name, int minimum, int maximum) {
            this.name = name;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        static RegexOperation named(String name) {
            return name == null ? null : BY_NAME.get(name);
        }

        boolean accepts(int size) {
            return size >= minimum && size <= maximum;
        }

        String apply(Matcher matcher, RegexRequest request) {
            return switch (this) {
                case MATCHES -> Boolean.toString(matcher.matches());
                case FIND -> Boolean.toString(matcher.find());
                case COUNT -> {
                    int count = 0;
                    while (matcher.find()) count++;
                    yield Integer.toString(count);
                }
                case GROUP -> group(matcher, request);
                case REPLACE_FIRST, REPLACE_ALL -> {
                    String replacement = request.replacement();
                    if (replacement == null) yield null;
                    String literal = request.literalReplacement();
                    if ("true".equalsIgnoreCase(literal))
                        replacement = Matcher.quoteReplacement(replacement);
                    else if (literal != null
                            && !literal.isEmpty()
                            && !"false".equalsIgnoreCase(literal)) yield null;
                    yield this == REPLACE_FIRST
                            ? matcher.replaceFirst(replacement)
                            : matcher.replaceAll(replacement);
                }
            };
        }
    }

    private static String group(Matcher matcher, RegexRequest request) {
        if (!matcher.find()) return request.fallback();
        String group = request.group();
        String matched;
        if (group == null || group.isEmpty()) matched = matcher.group();
        else {
            Integer number;
            try {
                number = Integer.valueOf(group);
            } catch (NumberFormatException namedGroup) {
                number = null;
            }
            matched = number == null ? matcher.group(group) : matcher.group(number);
        }
        return matched == null ? request.fallback() : matched;
    }

    private static String optional(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }
}

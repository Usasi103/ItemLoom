package dev.itemloom.compat.ni;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text-only node operations, with bounded compiled regex storage owned by a revision. */
final class NiTextNodes {
    private record RegexKey(String source, int flags) {}

    private final Map<RegexKey, Pattern> patterns = new LinkedHashMap<>(16, .75f, true);

    String configured(String type, NiConfig config, NiEvaluation evaluation) {
        List<String> keys =
                type.equals("string")
                        ? List.of(
                                "mode",
                                "value",
                                "arg",
                                "start",
                                "end",
                                "default",
                                "target",
                                "replacement")
                        : List.of(
                                "mode",
                                "value",
                                "pattern",
                                "group",
                                "default",
                                "replacement",
                                "flags",
                                "literal-replacement");
        String[] values = new String[keys.size()];
        for (int index = 0; index < keys.size(); index++)
            values[index] = evaluation.text(config.string(keys.get(index)));
        return type.equals("string") ? string(values) : regex(values);
    }

    String inline(String type, String source) {
        List<String> args = NiTemplate.arguments(source, 0);
        String mode = args.get(0);
        String[] values = new String[8];
        values[0] = mode;
        if (type.equals("string")) {
            switch (mode) {
                case "lower", "upper", "trim", "length" -> {
                    if (args.size() != 2) return null;
                    values[1] = args.get(1);
                }
                case "contains", "starts", "ends" -> {
                    if (args.size() != 3) return null;
                    values[1] = args.get(2);
                    values[2] = args.get(1);
                }
                case "replace" -> {
                    if (args.size() != 4) return null;
                    values[1] = args.get(3);
                    values[6] = args.get(1);
                    values[7] = args.get(2);
                }
                case "substring" -> {
                    if (args.size() < 3 || args.size() > 5) return null;
                    values[1] = NiNodes.arg(args, 4);
                    values[3] = args.get(1);
                    values[4] = NiNodes.arg(args, 2);
                    values[5] = NiNodes.arg(args, 3);
                }
                default -> {
                    return null;
                }
            }
            return string(values);
        }
        if (args.size() < 3) return null;
        values[1] = args.get(1);
        values[2] = args.get(2);
        switch (mode) {
            case "matches", "find", "count" -> {
                if (args.size() > 4) return null;
                values[6] = NiNodes.arg(args, 3);
            }
            case "group" -> {
                if (args.size() > 6) return null;
                values[3] = NiNodes.arg(args, 3);
                values[4] = NiNodes.arg(args, 4);
                values[6] = NiNodes.arg(args, 5);
            }
            case "replace-first", "replace-all" -> {
                if (args.size() < 4 || args.size() > 6) return null;
                values[5] = args.get(3);
                values[6] = NiNodes.arg(args, 4);
                values[7] = NiNodes.arg(args, 5);
            }
            default -> {
                return null;
            }
        }
        return regex(values);
    }

    private String string(String[] values) {
        String mode = values[0], value = values[1], argument = values[2];
        if (mode == null || value == null) return null;
        return switch (mode) {
            case "lower" -> value.toLowerCase(Locale.ROOT);
            case "upper" -> value.toUpperCase(Locale.ROOT);
            case "trim" -> value.trim();
            case "length" -> Integer.toString(value.length());
            case "contains" -> argument == null ? null : Boolean.toString(value.contains(argument));
            case "starts" -> argument == null ? null : Boolean.toString(value.startsWith(argument));
            case "ends" -> argument == null ? null : Boolean.toString(value.endsWith(argument));
            case "replace" ->
                    values[6] == null || values[7] == null
                            ? null
                            : value.replace(values[6], values[7]);
            case "substring" -> {
                if (values[3] == null) yield null;
                int start, end;
                try {
                    start = values[3].isEmpty() ? 0 : Integer.parseInt(values[3]);
                    end =
                            values[4] == null || values[4].isEmpty()
                                    ? value.length()
                                    : Integer.parseInt(values[4]);
                } catch (NumberFormatException error) {
                    yield null;
                }
                if (start < 0 || end < 0 || start > value.length() || end > value.length())
                    yield values[5];
                yield value.substring(start, Math.max(start, end));
            }
            default -> null;
        };
    }

    private String regex(String[] values) {
        String mode = values[0], value = values[1], pattern = values[2];
        if (mode == null || value == null || pattern == null) return null;
        int flags = 0;
        if (values[6] != null) {
            for (char flag : values[6].toCharArray()) {
                int bit =
                        switch (flag) {
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
                if (bit < 0) return null;
                flags |= bit;
            }
        }
        try {
            Matcher matcher = pattern(pattern, flags).matcher(value);
            return switch (mode) {
                case "matches" -> Boolean.toString(matcher.matches());
                case "find" -> Boolean.toString(matcher.find());
                case "count" -> {
                    int count = 0;
                    while (matcher.find()) count++;
                    yield Integer.toString(count);
                }
                case "group" -> {
                    if (!matcher.find()) yield values[4];
                    String group = values[3], result;
                    if (group == null || group.isEmpty()) result = matcher.group();
                    else {
                        Integer index;
                        try {
                            index = Integer.valueOf(group);
                        } catch (NumberFormatException error) {
                            index = null;
                        }
                        result = index == null ? matcher.group(group) : matcher.group(index);
                    }
                    yield result == null ? values[4] : result;
                }
                case "replace-first", "replace-all" -> {
                    String replacement = values[5], literal = values[7];
                    if (replacement == null) yield null;
                    if (literal != null
                            && !literal.isEmpty()
                            && !literal.equalsIgnoreCase("true")
                            && !literal.equalsIgnoreCase("false")) yield null;
                    if ("true".equalsIgnoreCase(literal))
                        replacement = Matcher.quoteReplacement(replacement);
                    yield mode.equals("replace-first")
                            ? matcher.replaceFirst(replacement)
                            : matcher.replaceAll(replacement);
                }
                default -> null;
            };
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            return null;
        }
    }

    private synchronized Pattern pattern(String expression, int flags) {
        RegexKey key = new RegexKey(expression, flags);
        Pattern result = patterns.get(key);
        if (result == null) {
            result = Pattern.compile(expression, flags);
            if (patterns.size() >= 256) patterns.remove(patterns.keySet().iterator().next());
            patterns.put(key, result);
        }
        return result;
    }

    static String gradient(List<String> args, boolean legacy) {
        if (NiNodes.arg(args, 0) == null
                || NiNodes.arg(args, 1) == null
                || NiNodes.arg(args, 3) == null) return null;
        int start, end;
        try {
            start = Integer.parseInt(args.get(0), 16);
            end = Integer.parseInt(args.get(1), 16);
        } catch (NumberFormatException error) {
            if (!legacy) return null;
            start = 0;
            end = 0;
        }
        start = Math.max(0, Math.min(0xffffff, start));
        end = Math.max(0, Math.min(0xffffff, end));
        int step = NiNodes.integer(NiNodes.arg(args, 2), 1);
        if (legacy) step = Math.max(1, step);
        String text = args.get(3);
        if (text.length() <= step) return color(start) + text;
        int red = start >> 16, green = start >> 8 & 255, blue = start & 255;
        int redStep = ((end >> 16) - red) * step / (text.length() - 1);
        int greenStep = ((end >> 8 & 255) - green) * step / (text.length() - 1);
        int blueStep = ((end & 255) - blue) * step / (text.length() - 1);
        StringBuilder output = new StringBuilder();
        int current = 1;
        for (int index = 0; index < text.length(); index++) {
            if (current == 1) {
                output.append(
                        color(
                                (Math.clamp(red, 0, 255) << 16)
                                        | (Math.clamp(green, 0, 255) << 8)
                                        | Math.clamp(blue, 0, 255)));
                red += redStep;
                green += greenStep;
                blue += blueStep;
            }
            output.append(text.charAt(index));
            current = current == step ? 1 : current + 1;
        }
        return output.toString();
    }

    static String color(int value) {
        String hex = String.format(Locale.ROOT, "%06x", Math.clamp(value, 0, 0xffffff));
        StringBuilder output = new StringBuilder("§x");
        for (char letter : hex.toCharArray()) output.append('§').append(letter);
        return output.toString();
    }
}

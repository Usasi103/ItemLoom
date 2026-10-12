package dev.itemloom.compat.ni;

import java.util.*;
import java.util.regex.*;

final class NiTextNodes {
    private record PatternKey(String source, int flags) {}

    private final LinkedHashMap<PatternKey, Pattern> patterns = new LinkedHashMap<>(32, .75f, true);

    String configured(String type, NiConfig config, NiEvaluation evaluation) {
        String[] names =
                "string".equals(type)
                        ? new String[] {
                            "mode",
                            "value",
                            "arg",
                            "start",
                            "end",
                            "default",
                            "target",
                            "replacement"
                        }
                        : new String[] {
                            "mode",
                            "value",
                            "pattern",
                            "group",
                            "default",
                            "replacement",
                            "flags",
                            "literal-replacement"
                        };
        Map<String, String> values = new HashMap<>();
        for (String name : names) values.put(name, evaluation.text(config.string(name)));
        return "string".equals(type) ? string(values) : regex(values);
    }

    String inline(String type, String source) {
        List<String> args = NiTemplate.arguments(source, 0);
        if (args.isEmpty()) return null;
        Map<String, String> values = new HashMap<>();
        String mode = args.get(0);
        values.put("mode", mode);
        if ("string".equals(type)) {
            switch (mode) {
                case "lower", "upper", "trim", "length" -> {
                    if (args.size() != 2) return null;
                    values.put("value", args.get(1));
                }
                case "contains", "starts", "ends" -> {
                    if (args.size() != 3) return null;
                    values.put("arg", args.get(1));
                    values.put("value", args.get(2));
                }
                case "replace" -> {
                    if (args.size() != 4) return null;
                    values.put("target", args.get(1));
                    values.put("replacement", args.get(2));
                    values.put("value", args.get(3));
                }
                case "substring" -> {
                    if (args.size() < 3 || args.size() > 5) return null;
                    values.put("start", args.get(1));
                    values.put("end", args.get(2));
                    if (args.size() > 3) values.put("default", args.get(3));
                    if (args.size() > 4) values.put("value", args.get(4));
                }
                default -> {
                    return null;
                }
            }
            return string(values);
        }
        String[] fields;
        if (Set.of("matches", "find", "count").contains(mode)) {
            if (args.size() < 3 || args.size() > 4) return null;
            fields = new String[] {"mode", "value", "pattern", "flags"};
        } else if (mode.equals("group")) {
            if (args.size() < 3 || args.size() > 6) return null;
            fields = new String[] {"mode", "value", "pattern", "group", "default", "flags"};
        } else if (mode.equals("replace-first") || mode.equals("replace-all")) {
            if (args.size() < 4 || args.size() > 6) return null;
            fields =
                    new String[] {
                        "mode", "value", "pattern", "replacement", "flags", "literal-replacement"
                    };
        } else return null;
        for (int i = 0; i < args.size(); i++) values.put(fields[i], args.get(i));
        return regex(values);
    }

    private String string(Map<String, String> values) {
        String mode = values.get("mode"), text = values.get("value"), arg = values.get("arg");
        if (mode == null || text == null) return null;
        return switch (mode) {
            case "lower" -> text.toLowerCase(Locale.ROOT);
            case "upper" -> text.toUpperCase(Locale.ROOT);
            case "trim" -> text.trim();
            case "length" -> String.valueOf(text.length());
            case "contains" -> arg == null ? null : String.valueOf(text.contains(arg));
            case "starts" -> arg == null ? null : String.valueOf(text.startsWith(arg));
            case "ends" -> arg == null ? null : String.valueOf(text.endsWith(arg));
            case "replace" ->
                    values.get("target") == null || values.get("replacement") == null
                            ? null
                            : text.replace(values.get("target"), values.get("replacement"));
            case "substring" -> substring(text, values);
            default -> null;
        };
    }

    private String substring(String text, Map<String, String> values) {
        if (values.get("start") == null) return null;
        try {
            int from = values.get("start").isEmpty() ? 0 : Integer.parseInt(values.get("start"));
            String end = values.get("end");
            int to = end == null || end.isEmpty() ? text.length() : Integer.parseInt(end);
            if (from < 0 || to < 0 || from > text.length() || to > text.length())
                return values.get("default");
            return from > to ? "" : text.substring(from, to);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private synchronized Pattern pattern(String source, int flags) {
        PatternKey key = new PatternKey(source, flags);
        Pattern pattern = patterns.get(key);
        if (pattern == null) {
            pattern = Pattern.compile(source, flags);
            patterns.put(key, pattern);
            if (patterns.size() > 256) patterns.remove(patterns.keySet().iterator().next());
        }
        return pattern;
    }

    private String regex(Map<String, String> values) {
        String mode = values.get("mode"),
                text = values.get("value"),
                source = values.get("pattern");
        if (mode == null || text == null || source == null) return null;
        try {
            int flags = 0;
            String requested = values.get("flags");
            if (requested != null)
                for (char flag : requested.toCharArray())
                    flags |=
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
                                default -> throw new IllegalArgumentException();
                            };
            Matcher match = pattern(source, flags).matcher(text);
            return switch (mode) {
                case "matches" -> String.valueOf(match.matches());
                case "find" -> String.valueOf(match.find());
                case "count" -> {
                    int count = 0;
                    while (match.find()) count++;
                    yield String.valueOf(count);
                }
                case "group" -> {
                    if (!match.find()) yield values.get("default");
                    String group = values.get("group");
                    String value;
                    if (group == null || group.isEmpty()) value = match.group();
                    else if (group.matches("[+-]?[0-9]+"))
                        value = match.group(Integer.parseInt(group));
                    else value = match.group(group);
                    yield value == null ? values.get("default") : value;
                }
                case "replace-first", "replace-all" -> {
                    String replacement = values.get("replacement"),
                            literal = values.get("literal-replacement");
                    if (replacement == null) yield null;
                    if (literal != null && !literal.isEmpty()) {
                        if (literal.equalsIgnoreCase("true"))
                            replacement = Matcher.quoteReplacement(replacement);
                        else if (!literal.equalsIgnoreCase("false")) yield null;
                    }
                    yield mode.equals("replace-first")
                            ? match.replaceFirst(replacement)
                            : match.replaceAll(replacement);
                }
                default -> null;
            };
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            return null;
        }
    }

    static String color(int value) {
        String rgb = String.format(Locale.ROOT, "%06x", Math.clamp(value, 0, 0xffffff));
        StringBuilder result = new StringBuilder("§x");
        for (char c : rgb.toCharArray()) result.append('§').append(c);
        return result.toString();
    }
}

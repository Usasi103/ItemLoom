package dev.itemloom.paper.compat.script;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.*;

final class LegacyRegexText {
    record Rule(String pattern, String replacement) {}

    private record Applied(String text, boolean matched) {}

    private LegacyRegexText() {}

    static String name(
            String text, List<Rule> rules, boolean all, UnaryOperator<String> transform) {
        if (text == null) return null;
        for (Rule rule : rules) text = apply(text, rule, all, transform).text;
        return text;
    }

    static List<String> lore(
            List<String> lines, List<Rule> rules, boolean all, UnaryOperator<String> transform) {
        if (lines == null) return null;
        List<Rule> available = new ArrayList<>(rules);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String output = line;
            for (Iterator<Rule> iterator = available.iterator(); iterator.hasNext(); ) {
                Applied applied = apply(line, iterator.next(), all, transform);
                output = applied.text;
                if (!all && applied.matched) iterator.remove();
            }
            int copies = 1;
            for (int i = 0; i < output.length(); i++) if (output.charAt(i) == '\n') copies++;
            for (int i = 0; i < copies; i++) result.add(output);
        }
        return result;
    }

    private static Applied apply(
            String source, Rule rule, boolean all, UnaryOperator<String> transform) {
        Matcher match = Pattern.compile(rule.pattern).matcher(source);
        if (!match.find()) return new Applied(source, false);
        StringBuilder result = new StringBuilder();
        int end = 0;
        do {
            result.append(source, end, match.start());
            result.append(Objects.requireNonNull(transform.apply(groups(rule.replacement, match))));
            end = match.end();
        } while (all && match.find());
        result.append(source, end, source.length());
        return new Applied(result.toString(), true);
    }

    private static String groups(String replacement, Matcher match) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < replacement.length(); ) {
            int end = i + 1;
            if (replacement.charAt(i) == '$') {
                while (end < replacement.length()
                        && replacement.charAt(end) >= '0'
                        && replacement.charAt(end) <= '9') end++;
                if (end > i + 1) {
                    int group = Integer.parseInt(replacement.substring(i + 1, end));
                    if (group <= match.groupCount()) {
                        String value = match.group(group);
                        if (value != null) result.append(value);
                        i = end;
                        continue;
                    }
                }
            }
            result.append(replacement, i, end);
            i = end;
        }
        return result.toString();
    }
}

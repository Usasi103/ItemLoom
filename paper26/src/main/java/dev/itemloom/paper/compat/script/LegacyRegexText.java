package dev.itemloom.paper.compat.script;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/** Regex text operations with the legacy editor's replacement and lore rules. */
final class LegacyRegexText {
    private static final Pattern GROUP_REFERENCE = Pattern.compile("\\$([0-9]+)");

    private LegacyRegexText() {}

    static String name(
            String text, List<Rule> rules, boolean all, UnaryOperator<String> transform) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Rule rule : rules) {
            result = apply(result, rule, all, transform).text();
        }
        return result;
    }

    static List<String> lore(
            List<String> lines, List<Rule> rules, boolean all, UnaryOperator<String> transform) {
        if (lines == null) {
            return null;
        }
        List<Rule> available = new ArrayList<>(rules);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String text = line;
            Iterator<Rule> remaining = available.iterator();
            while (remaining.hasNext()) {
                Applied applied = apply(line, remaining.next(), all, transform);
                text = applied.text();
                if (!all && applied.matched()) {
                    remaining.remove();
                }
            }
            long copies = 1 + text.chars().filter(character -> character == '\n').count();
            for (long copy = 0; copy < copies; copy++) {
                result.add(text);
            }
        }
        return result;
    }

    private static Applied apply(
            String text, Rule rule, boolean all, UnaryOperator<String> transform) {
        Iterator<MatchResult> matches =
                Pattern.compile(rule.pattern())
                        .matcher(text)
                        .results()
                        .limit(all ? Long.MAX_VALUE : 1)
                        .iterator();
        if (!matches.hasNext()) {
            return new Applied(text, false);
        }
        StringBuilder result = new StringBuilder();
        int copiedUntil = 0;
        while (matches.hasNext()) {
            MatchResult match = matches.next();
            result.append(text, copiedUntil, match.start());
            String expanded = expandGroups(rule.replacement(), match);
            result.append(Objects.requireNonNull(transform.apply(expanded)));
            copiedUntil = match.end();
        }
        return new Applied(result.append(text, copiedUntil, text.length()).toString(), true);
    }

    private static String expandGroups(String replacement, MatchResult match) {
        Iterator<MatchResult> references =
                GROUP_REFERENCE.matcher(replacement).results().iterator();
        StringBuilder result = new StringBuilder();
        int copiedUntil = 0;
        while (references.hasNext()) {
            MatchResult reference = references.next();
            result.append(replacement, copiedUntil, reference.start());
            int group = Integer.parseInt(reference.group(1));
            result.append(
                    group <= match.groupCount()
                            ? Objects.toString(match.group(group), "")
                            : reference.group());
            copiedUntil = reference.end();
        }
        return result.append(replacement, copiedUntil, replacement.length()).toString();
    }

    record Rule(String pattern, String replacement) {}

    private record Applied(String text, boolean matched) {}
}

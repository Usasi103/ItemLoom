package dev.itemloom.paper.compat.script;

import java.util.*;

/** Selects literal spans in the input before rendering any replacement. */
public final class LegacyLiteralText {
    private record Match(int start, String key) {
        int end() {
            return start + key.length();
        }
    }

    private LegacyLiteralText() {}

    public static String name(String source, Map<String, String> rules, boolean all) {
        return render(source, rules, all, new HashSet<>(), false).getFirst();
    }

    public static List<String> lore(List<String> source, Map<String, String> rules, boolean all) {
        List<String> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String line : source) result.addAll(render(line, rules, all, used, true));
        return result;
    }

    private static List<String> render(
            String source,
            Map<String, String> rules,
            boolean all,
            Set<String> used,
            boolean split) {
        List<Match> candidates = new ArrayList<>();
        for (String key : rules.keySet()) {
            if (key.isEmpty()) continue;
            for (int from = 0, at; (at = source.indexOf(key, from)) >= 0; from = at + 1)
                candidates.add(new Match(at, key));
        }
        candidates.sort(
                Comparator.<Match>comparingInt(match -> match.key.length())
                        .reversed()
                        .thenComparingInt(Match::start));
        BitSet occupied = new BitSet(source.length());
        List<Match> selected = new ArrayList<>();
        for (Match match : candidates) {
            int next = occupied.nextSetBit(match.start);
            if (next >= 0 && next < match.end()) continue;
            occupied.set(match.start, match.end());
            selected.add(match);
        }
        selected.sort(Comparator.comparingInt(Match::start));
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int cursor = 0;
        for (Match match : selected) {
            line.append(source, cursor, match.start);
            String replacement = rules.get(match.key);
            boolean apply = all || used.add(match.key);
            if (!apply || (!all && replacement == null))
                line.append(source, match.start, match.end());
            else if (replacement != null) {
                if (!split) line.append(replacement);
                else {
                    int from = 0, newline;
                    while ((newline = replacement.indexOf('\n', from)) >= 0) {
                        line.append(replacement, from, newline);
                        result.add(line.toString());
                        line.setLength(0);
                        from = newline + 1;
                    }
                    line.append(replacement, from, replacement.length());
                }
            }
            cursor = match.end();
        }
        line.append(source, cursor, source.length());
        result.add(line.toString());
        return result;
    }
}

package dev.itemloom.paper.compat.script;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Literal display edits selected against original text before any output is produced. */
public final class LegacyLiteralText {
    private LegacyLiteralText() {}

    private record Match(int start, String key, String replacement) {
        int end() {
            return start + key.length();
        }
    }

    public static String name(String source, Map<String, String> rules, boolean all) {
        return edit(source, rules, all, new HashSet<>(), false).getFirst();
    }

    public static List<String> lore(List<String> source, Map<String, String> rules, boolean all) {
        Set<String> used = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String line : source) result.addAll(edit(line, rules, all, used, true));
        return result;
    }

    private static List<Match> select(String source, Map<String, String> rules) {
        List<Match> candidates = new ArrayList<>();
        rules.forEach(
                (key, replacement) -> {
                    if (key.isEmpty()) return;
                    for (int start = source.indexOf(key);
                            start >= 0;
                            start = source.indexOf(key, start + 1)) {
                        candidates.add(new Match(start, key, replacement));
                    }
                });
        candidates.sort(
                Comparator.<Match>comparingInt(match -> match.key().length())
                        .reversed()
                        .thenComparingInt(Match::start));
        BitSet occupied = new BitSet(source.length());
        List<Match> selected = new ArrayList<>();
        for (Match match : candidates) {
            int nextOccupied = occupied.nextSetBit(match.start());
            if (nextOccupied >= 0 && nextOccupied < match.end()) continue;
            occupied.set(match.start(), match.end());
            selected.add(match);
        }
        selected.sort(Comparator.comparingInt(Match::start));
        return selected;
    }

    private static List<String> edit(
            String source,
            Map<String, String> rules,
            boolean all,
            Set<String> used,
            boolean splitReplacements) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int copied = 0;
        for (Match match : select(source, rules)) {
            line.append(source, copied, match.start());
            boolean replace = all || used.add(match.key());
            String replacement = replace ? match.replacement() : match.key();
            boolean inserted = replace && replacement != null;
            if (replacement == null && !all) replacement = match.key();
            if (replacement != null) {
                if (splitReplacements && inserted) {
                    int start = 0;
                    for (int end = replacement.indexOf('\n');
                            end >= 0;
                            end = replacement.indexOf('\n', start)) {
                        line.append(replacement, start, end);
                        lines.add(line.toString());
                        line.setLength(0);
                        start = end + 1;
                    }
                    line.append(replacement, start, replacement.length());
                } else line.append(replacement);
            }
            copied = match.end();
        }
        lines.add(line.append(source, copied, source.length()).toString());
        return lines;
    }
}

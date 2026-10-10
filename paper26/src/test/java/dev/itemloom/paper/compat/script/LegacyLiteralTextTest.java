package dev.itemloom.paper.compat.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacyLiteralTextTest {
    @Test
    void longestOccurrencesThenEarliestStartWinWithoutRescanningOutput() {
        assertEquals("XY", LegacyLiteralText.name("aaa", Map.of("aa", "X", "a", "Y"), true));
        assertEquals("zY", LegacyLiteralText.name("zabc", Map.of("za", "X", "abc", "Y"), true));
        assertEquals("Xc", LegacyLiteralText.name("abc", Map.of("ab", "X", "bc", "Y"), true));
        assertEquals("bC", LegacyLiteralText.name("ab", Map.of("a", "b", "b", "C"), true));
        assertEquals(
                "XX",
                LegacyLiteralText.name(
                        "\ud83d\ude00\ud83d\ude00", Map.of("\ud83d\ude00", "X"), true));
    }

    @Test
    void usedKeysKeepBlockingShorterMatchesAcrossLoreLines() {
        assertEquals("X aa", LegacyLiteralText.name("aa aa", Map.of("aa", "X"), false));
        assertEquals("Xab", LegacyLiteralText.name("abab", Map.of("ab", "X", "b", "Y"), false));
        assertEquals(
                List.of("X", "YZ", "ab"),
                LegacyLiteralText.lore(List.of("ab", "ab"), Map.of("a", "X\nY", "b", "Z"), false));
    }

    @Test
    void nullableAndEmptyReplacementsAndKeysHaveDistinctSemantics() {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("a", null);
        rules.put("", "insert");
        assertEquals("a a", LegacyLiteralText.name("a a", rules, false));
        assertEquals(" ", LegacyLiteralText.name("a a", rules, true));
        rules.put("a", "");
        assertEquals(" a", LegacyLiteralText.name("a a", rules, false));
        assertEquals("A", LegacyLiteralText.name("Aa", rules, true));
    }

    @Test
    void onlyInsertedLoreNewlinesSplitAndTrailingEmptyPartsSurvive() {
        assertEquals(
                List.of("", "b"), LegacyLiteralText.lore(List.of("ab"), Map.of("a", "\n"), true));
        assertEquals(
                List.of("prefixX", "", "Ysuffix", "a"),
                LegacyLiteralText.lore(
                        List.of("prefixasuffix", "a"), Map.of("a", "X\n\nY"), false));
        assertEquals(
                List.of("x", "", ""),
                LegacyLiteralText.lore(List.of("a"), Map.of("a", "x\n\n"), true));
        assertEquals("x\ny", LegacyLiteralText.name("a", Map.of("a", "x\ny"), true));
        assertEquals(
                List.of("source\nline"),
                LegacyLiteralText.lore(List.of("source\nline"), Map.of(), true));
        Map<String, String> nullable = new LinkedHashMap<>();
        nullable.put("source\nline", null);
        assertEquals(
                List.of("source\nline"),
                LegacyLiteralText.lore(List.of("source\nline"), nullable, false));
        assertEquals(
                List.of("X", "source\nline"),
                LegacyLiteralText.lore(
                        List.of("source\nline", "source\nline"),
                        Map.of("source\nline", "X"),
                        false));
    }

    @Test
    void sourcesAndRulesAreNotMutated() {
        List<String> source = List.of("a", "a");
        Map<String, String> rules = Map.of("a", "X");
        assertEquals(List.of("X", "a"), LegacyLiteralText.lore(source, rules, false));
        assertEquals(List.of("a", "a"), source);
        assertEquals(Map.of("a", "X"), rules);
    }
}

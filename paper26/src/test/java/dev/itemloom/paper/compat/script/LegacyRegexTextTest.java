package dev.itemloom.paper.compat.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.itemloom.paper.compat.script.LegacyRegexText.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

class LegacyRegexTextTest {
    private static final UnaryOperator<String> IDENTITY = UnaryOperator.identity();

    @Test
    void nameRulesTransformThePreviousRulesResult() {
        List<Rule> rules = List.of(new Rule("a", "b"), new Rule("b", "c"));
        assertEquals("ca", LegacyRegexText.name("aa", rules, false, IDENTITY));
        assertEquals("cc", LegacyRegexText.name("aa", rules, true, IDENTITY));
    }

    @Test
    void firstAndAllModesUseNonoverlappingMatches() {
        List<Rule> rules = List.of(new Rule("aa", "x"));
        assertEquals("xaa", LegacyRegexText.name("aaaa", rules, false, IDENTITY));
        assertEquals("xx", LegacyRegexText.name("aaaa", rules, true, IDENTITY));
    }

    @Test
    void transformsEachExpandedReplacementExactlyOnce() {
        List<String> calls = new ArrayList<>();
        String result =
                LegacyRegexText.name(
                        "x1 y2",
                        List.of(new Rule("([xy])([0-9])", "$2:$1")),
                        true,
                        replacement -> {
                            calls.add(replacement);
                            return "[" + replacement + "]";
                        });
        assertEquals("[1:x] [2:y]", result);
        assertEquals(List.of("1:x", "2:y"), calls);
    }

    @Test
    void loreRulesAlwaysReceiveTheOriginalLine() {
        List<Rule> rules = List.of(new Rule("a", "b"), new Rule("b", "c"));
        assertEquals(
                List.of("a", "c"), LegacyRegexText.lore(List.of("a", "b"), rules, false, IDENTITY));
        assertEquals(
                List.of("a", "a"), LegacyRegexText.lore(List.of("a", "a"), rules, true, IDENTITY));
    }

    @Test
    void lastAvailableLoreRuleWinsAndFirstModeConsumesMatches() {
        List<String> calls = new ArrayList<>();
        List<String> result =
                LegacyRegexText.lore(
                        List.of("a", "b", "a"),
                        List.of(new Rule("a", "A"), new Rule("b", "B")),
                        false,
                        replacement -> {
                            calls.add(replacement);
                            return replacement;
                        });
        assertEquals(List.of("a", "B", "a"), result);
        assertEquals(List.of("A", "B"), calls);
    }

    @Test
    void allModeRetainsEveryLoreRuleForLaterLines() {
        List<Rule> rules = List.of(new Rule("a", "first"), new Rule("a", "last"));
        assertEquals(
                List.of("last", "a"),
                LegacyRegexText.lore(List.of("a", "a"), rules, false, IDENTITY));
        AtomicInteger calls = new AtomicInteger();
        assertEquals(
                List.of("last", "last"),
                LegacyRegexText.lore(
                        List.of("a", "a"),
                        rules,
                        true,
                        replacement -> {
                            calls.incrementAndGet();
                            return replacement;
                        }));
        assertEquals(4, calls.get());
    }

    @Test
    void firstModeConsumesARuleEvenWhenItsReplacementIsIdentical() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(
                List.of("a", "a"),
                LegacyRegexText.lore(
                        List.of("a", "a"),
                        List.of(new Rule("a", "a")),
                        false,
                        replacement -> {
                            calls.incrementAndGet();
                            return replacement;
                        }));
        assertEquals(1, calls.get());
    }

    @Test
    void numberedReferencesKeepTheSpecifiedLiteralCases() {
        String replacement = "<$0|$1|$2|$3|$01|$000|\\$1|${named}|$-1|$|$\u0661>";
        assertEquals(
                "<a|a||$3|a|a|\\a|${named}|$-1|$|$\u0661>",
                LegacyRegexText.name(
                        "a", List.of(new Rule("(a)(b)?", replacement)), true, IDENTITY));
        assertEquals(
                "l:a:$123",
                LegacyRegexText.name(
                        "abcdefghijkl",
                        List.of(new Rule("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)", "$12:$1:$123")),
                        true,
                        IDENTITY));
    }

    @Test
    void groupAndCallbackResultsStayLiteralWithoutAnotherExpansion() {
        String literal = "$2\\folder";
        assertEquals(
                literal,
                LegacyRegexText.name(literal, List.of(new Rule("(.+)", "$1")), true, IDENTITY));
        assertEquals(
                "$0\\end",
                LegacyRegexText.name(
                        "a", List.of(new Rule("a", "$0")), true, ignored -> "$0\\end"));
    }

    @Test
    void missingMatchesDoNotValidateReplacementsOrInvokeCallbacks() {
        AtomicInteger calls = new AtomicInteger();
        UnaryOperator<String> callback =
                replacement -> {
                    calls.incrementAndGet();
                    return null;
                };
        for (String replacement :
                new String[] {null, "$2147483648", "$9999999999999999999999999"}) {
            assertEquals(
                    "safe",
                    LegacyRegexText.name(
                            "safe", List.of(new Rule("missing", replacement)), true, callback));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void matchingInvalidReplacementsFailBeforeTheCallback() {
        AtomicInteger calls = new AtomicInteger();
        UnaryOperator<String> callback =
                replacement -> {
                    calls.incrementAndGet();
                    return replacement;
                };
        assertThrows(
                NullPointerException.class,
                () -> LegacyRegexText.name("a", List.of(new Rule("a", null)), false, callback));
        assertThrows(
                NumberFormatException.class,
                () ->
                        LegacyRegexText.name(
                                "a", List.of(new Rule("a", "$2147483648")), false, callback));
        assertEquals(0, calls.get());
        assertThrows(
                NullPointerException.class,
                () ->
                        LegacyRegexText.name(
                                "a", List.of(new Rule("a", "b")), false, ignored -> null));
    }

    @Test
    void patternsAreCompiledOnlyForAnAppliedRule() {
        List<Rule> invalid = List.of(new Rule("(", "replacement"));
        assertNull(LegacyRegexText.name(null, invalid, true, IDENTITY));
        assertNull(LegacyRegexText.lore(null, invalid, true, IDENTITY));
        assertEquals(List.of(), LegacyRegexText.lore(List.of(), invalid, true, IDENTITY));
        assertThrows(
                PatternSyntaxException.class,
                () -> LegacyRegexText.name("a", invalid, false, IDENTITY));
        assertThrows(
                PatternSyntaxException.class,
                () -> LegacyRegexText.lore(List.of("a"), invalid, false, IDENTITY));
    }

    @Test
    void zeroWidthMatchesFollowJavaFindBoundaries() {
        List<Rule> rules = List.of(new Rule("", "_"));
        assertEquals("_a_b_", LegacyRegexText.name("ab", rules, true, IDENTITY));
        assertEquals("_ab", LegacyRegexText.name("ab", rules, false, IDENTITY));
        assertEquals("_", LegacyRegexText.name("", rules, true, IDENTITY));
        assertEquals(
                List.of("_a", "b"),
                LegacyRegexText.lore(List.of("a", "b"), rules, false, IDENTITY));
        assertEquals(
                "a_b", LegacyRegexText.name("ab", List.of(new Rule("(?=b)", "_")), true, IDENTITY));
    }

    @Test
    void newlineExpansionDuplicatesWholeStringsIncludingTrailingNewlines() {
        assertEquals(
                List.of("a\nb\n", "a\nb\n", "a\nb\n", "plain", "\n", "\n"),
                LegacyRegexText.lore(List.of("a\nb\n", "plain", "\n"), List.of(), true, IDENTITY));
        AtomicInteger calls = new AtomicInteger();
        assertEquals(
                List.of("x\ny\n", "x\ny\n", "x\ny\n"),
                LegacyRegexText.lore(
                        List.of("a"),
                        List.of(new Rule("a", "x\ny\n")),
                        true,
                        replacement -> {
                            calls.incrementAndGet();
                            return replacement;
                        }));
        assertEquals(1, calls.get());
    }

    @Test
    void consumesOnlyItsOwnRuleCopyAndPreservesInputLines() {
        List<String> lines = new ArrayList<>(List.of("a", "a"));
        List<Rule> rules = new ArrayList<>(List.of(new Rule("a", "b")));
        assertEquals(List.of("b", "a"), LegacyRegexText.lore(lines, rules, false, IDENTITY));
        assertEquals(List.of("a", "a"), lines);
        assertEquals(List.of(new Rule("a", "b")), rules);
        assertEquals(List.of("b", "a"), LegacyRegexText.lore(lines, rules, false, IDENTITY));
    }
}

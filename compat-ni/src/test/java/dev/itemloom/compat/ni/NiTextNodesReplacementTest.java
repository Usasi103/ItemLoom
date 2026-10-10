package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.core.GenerationContext;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NiTextNodesReplacementTest {
    private final NiTextNodes nodes = new NiTextNodes();

    private static NiEvaluation evaluation(NiScripts scripts) {
        return new NiEvaluation(
                new GenerationContext(Map.of(), new Random(91)),
                new NiConfig(Map.of()),
                null,
                NiEvaluation.Mode.ACTION,
                new NiNodes(),
                scripts,
                null);
    }

    private String configured(String type, Map<String, ?> settings) {
        return nodes.configured(type, new NiConfig(settings), evaluation(null));
    }

    @Test
    void configuredFieldsAreAllExpandedInContractOrderEvenForUnknownModes() {
        List<String> stringFields =
                List.of("mode", "value", "arg", "start", "end", "default", "target", "replacement");
        List<String> regexFields =
                List.of(
                        "mode",
                        "value",
                        "pattern",
                        "group",
                        "default",
                        "replacement",
                        "flags",
                        "literal-replacement");
        for (String type : List.of("string", "regex")) {
            List<String> fields = type.equals("string") ? stringFields : regexFields;
            StringBuilder source =
                    new StringBuilder("var calls=[]; function read(){return calls.join(',');}\n");
            Map<String, String> settings = new LinkedHashMap<>();
            for (int index = 0; index < fields.size(); index++) {
                String field = fields.get(index);
                source.append("function field")
                        .append(index)
                        .append("(){calls.push('")
                        .append(field)
                        .append("');return 'invalid';}\n");
                settings.put(field, "<js::calls.js::field" + index + ">");
            }
            try (NiScripts scripts =
                    new NiScripts(Map.of("calls.js", source.toString()), Map.of())) {
                NiEvaluation context = evaluation(scripts);
                assertNull(nodes.configured(type, new NiConfig(settings), context));
                assertEquals(String.join(",", fields), context.text("<js::calls.js::read>"));
            }
        }
    }

    @Test
    void missingRequiredValuesDoNotSkipLaterConfiguredExpansions() {
        try (NiScripts scripts =
                new NiScripts(
                        Map.of("calls.js", "var calls=0; function next(){return String(++calls);}"),
                        Map.of())) {
            NiEvaluation context = evaluation(scripts);
            assertNull(
                    nodes.configured(
                            "string",
                            new NiConfig(Map.of("replacement", "<js::calls.js::next>")),
                            context));
            assertNull(
                    nodes.configured(
                            "regex",
                            new NiConfig(Map.of("literal-replacement", "<js::calls.js::next>")),
                            context));
            assertEquals("3", context.text("<js::calls.js::next>"));
        }
    }

    @Test
    void inlineStringsKeepEscapingArityAndLiteralTokens() {
        assertEquals("A_B", nodes.inline("string", "upper_a\\_b"));
        assertEquals("<NODE>", nodes.inline("string", "upper_<node>"));
        assertEquals("true", nodes.inline("string", "contains_b_a\\_b"));
        assertEquals("false", nodes.inline("string", "starts_b_ab"));
        assertEquals("true", nodes.inline("string", "ends_b_ab"));
        assertEquals("$1$1", nodes.inline("string", "replace_._$1_.."));
        for (String invalid :
                List.of(
                        "",
                        "Upper_a",
                        "upper",
                        "upper_a_b",
                        "contains_a",
                        "replace_a_b",
                        "substring_1",
                        "substring_1_2_f_abc_extra")) {
            assertNull(nodes.inline("string", invalid), invalid);
        }
        assertNull(nodes.inline("string", "substring_1_abc"));
        assertNull(nodes.inline("string", "substring_1_2_abc"));
        assertEquals("b", nodes.inline("string", "substring_1_2_f_abc"));
    }

    @Test
    void stringCaseUsesRootAndLengthCountsUtf16() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("i", nodes.inline("string", "lower_I"));
            assertEquals("I", nodes.inline("string", "upper_i"));
        } finally {
            Locale.setDefault(previous);
        }
        assertEquals("2", nodes.inline("string", "length_\ud83d\ude00"));
        assertEquals("\u2003value\u2003", nodes.inline("string", "trim_ \u2003value\u2003 \t"));
        assertEquals("0", nodes.inline("string", "length_"));
    }

    @Test
    void substringDistinguishesMalformedIndicesFromOutOfRangeFallbacks() {
        record Case(String start, String end, String expected) {}
        List<Case> cases =
                List.of(
                        new Case(null, "", null), new Case("", null, "abcd"),
                        new Case("", "", "abcd"), new Case("1", "3", "bc"),
                        new Case("3", "1", ""), new Case("4", "4", ""),
                        new Case("-1", "2", "fallback"), new Case("1", "-1", "fallback"),
                        new Case("5", "1", "fallback"), new Case("1", "5", "fallback"),
                        new Case("x", "2", null), new Case("1", "x", null),
                        new Case("2147483648", "2", null), new Case("+1", "3", "bc"));
        for (Case sample : cases) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("mode", "substring");
            fields.put("value", "abcd");
            fields.put("start", sample.start());
            fields.put("end", sample.end());
            fields.put("default", "fallback");
            assertEquals(sample.expected(), configured("string", fields), sample.toString());
        }
        assertNull(configured("string", Map.of("mode", "substring", "value", "x", "start", "9")));
        assertNull(configured("string", Map.of("mode", "contains", "value", "x")));
        assertNull(configured("string", Map.of("mode", "replace", "value", "x", "target", "x")));
        assertNull(
                configured("string", Map.of("mode", "replace", "value", "x", "replacement", "y")));
    }

    @Test
    void anyOtherTypeUsesRegexGrammarAndExactModeNames() {
        assertEquals("true", nodes.inline("other", "matches_abc_abc"));
        assertEquals("true", nodes.inline(null, "find_abc_b"));
        assertEquals("false", nodes.inline("regex", "matches_abc_b"));
        assertEquals("4", nodes.inline("regex", "count_abc_"));
        assertEquals("2", nodes.inline("regex", "count_ab_(?=.)"));
        for (String invalid :
                List.of(
                        "",
                        "matches_x",
                        "Matches_x_x",
                        "matches_x_x_i_extra",
                        "group_x_x_0_f_i_extra",
                        "replace-all_x_x",
                        "replace-first_x_x_y_i_true_extra")) {
            assertNull(nodes.inline("regex", invalid), invalid);
        }
        assertEquals(
                "true",
                configured("unexpected", Map.of("mode", "matches", "value", "x", "pattern", "x")));
    }

    @Test
    void flagsCombineRepeatAndRejectUnknownCharacters() {
        record Case(String value, String pattern, String flags, String expected) {}
        for (Case sample :
                List.of(
                        new Case("A", "a", "ii", "true"),
                        new Case("a\nb", "^b", "m", "true"),
                        new Case("a\nb", "a.b", "s", "true"),
                        new Case("\u00c9", "\u00e9", "iu", "true"),
                        new Case("\u00e9", "\\w", "U", "true"),
                        new Case("ab", "a b", "x", "true"),
                        new Case("\r", ".", "d", "true"),
                        new Case("a.b", "a.b", "l", "true"),
                        new Case("e\u0301", "\u00e9", "c", "true"),
                        new Case("a", "a", "q", null),
                        new Case("a", "a", "i ", null))) {
            assertEquals(
                    sample.expected(),
                    configured(
                            "regex",
                            Map.of(
                                    "mode",
                                    "find",
                                    "value",
                                    sample.value(),
                                    "pattern",
                                    sample.pattern(),
                                    "flags",
                                    sample.flags())),
                    sample.toString());
        }
        assertEquals("false", nodes.inline("regex", "matches_A_a"));
        assertEquals("true", nodes.inline("regex", "matches_A_a_i"));
        assertEquals("false", nodes.inline("regex", "matches_A_a"));
    }

    @Test
    void regexGroupsChooseFirstMatchAndPreserveFallbackDistinctions() {
        assertEquals("aa", nodes.inline("regex", "group_aa bb_(?<word>a+)_word_f"));
        assertEquals("b", nodes.inline("regex", "group_b_(a)?b__f"));
        assertEquals("b", nodes.inline("regex", "group_b_(a)?b_0_f"));
        assertEquals("f", nodes.inline("regex", "group_b_(a)?b_1_f"));
        assertEquals("f", nodes.inline("regex", "group_z_(a)_unknown_f"));
        assertNull(nodes.inline("regex", "group_a_(a)_unknown_f"));
        assertNull(nodes.inline("regex", "group_a_(a)_-1_f"));
        assertNull(nodes.inline("regex", "group_a_(a)_2_f"));
        assertNull(nodes.inline("regex", "group_a_(a)_2147483648_f"));
        assertEquals("a", nodes.inline("regex", "group_a_(a)_+1_f"));
    }

    @Test
    void regexReplacementUsesJavaGroupsUnlessExplicitlyLiteral() {
        assertEquals("a1aa", nodes.inline("regex", "replace-first_aaa_(a)_$11"));
        assertEquals("a1a1a1", nodes.inline("regex", "replace-all_aaa_(a)_$11__FaLsE"));
        assertEquals("$1$1", nodes.inline("regex", "replace-all_aa_a_$1__TrUe"));
        assertEquals("", nodes.inline("regex", "replace-all_a_a_"));
        assertNull(nodes.inline("regex", "replace-all_a_a_x__yes"));
        assertNull(nodes.inline("regex", "replace-all_a_a_$2"));
        assertNull(nodes.inline("regex", "replace-first_a_[_x"));
        assertNull(
                configured(
                        "regex",
                        Map.of(
                                "mode",
                                "replace-all",
                                "value",
                                "a",
                                "pattern",
                                "a",
                                "replacement",
                                "\\\\")));
        assertNull(
                configured("regex", Map.of("mode", "replace-all", "value", "a", "pattern", "a")));
        assertEquals(
                "\\",
                configured(
                        "regex",
                        Map.of(
                                "mode",
                                "replace-all",
                                "value",
                                "a",
                                "pattern",
                                "a",
                                "replacement",
                                "\\\\",
                                "literal-replacement",
                                "true")));
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Pattern> patterns() throws ReflectiveOperationException {
        Field field = NiTextNodes.class.getDeclaredField("patterns");
        field.setAccessible(true);
        return (Map<Object, Pattern>) field.get(nodes);
    }

    @Test
    void compiledPatternCacheIsPerInstanceBoundedAndAccessOrdered() throws Exception {
        for (int index = 0; index < 256; index++)
            assertEquals("false", nodes.inline("regex", "matches_x_p" + index));
        assertEquals(256, patterns().size());
        assertEquals("false", nodes.inline("regex", "matches_x_p0"));
        assertEquals("false", nodes.inline("regex", "matches_x_new"));
        List<String> retained = patterns().values().stream().map(Pattern::pattern).toList();
        assertEquals(256, retained.size());
        assertTrue(retained.contains("p0"));
        assertFalse(retained.contains("p1"));
        assertNull(nodes.inline("regex", "matches_x_["));
        assertEquals(retained, patterns().values().stream().map(Pattern::pattern).toList());
        NiTextNodes other = new NiTextNodes();
        other.inline("regex", "matches_x_x");
        assertEquals(retained, patterns().values().stream().map(Pattern::pattern).toList());
    }

    @Test
    void concurrentRegexRequestsDoNotShareMatcherState() throws Exception {
        List<Callable<String>> requests = new ArrayList<>();
        for (int index = 0; index < 600; index++) {
            int number = index;
            requests.add(() -> nodes.inline("regex", "group_v" + number + "_v([0-9]+)_1"));
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = executor.invokeAll(requests);
            for (int index = 0; index < results.size(); index++)
                assertEquals(Integer.toString(index), results.get(index).get());
        }
    }

    @Test
    void rgbEncodingClampsAndPadsAllSixLowercaseNibbles() {
        assertEquals("\u00a7x\u00a70\u00a70\u00a70\u00a70\u00a70\u00a70", NiTextNodes.color(-1));
        assertEquals("\u00a7x\u00a70\u00a70\u00a70\u00a70\u00a7a\u00a7b", NiTextNodes.color(0xab));
        assertEquals(
                "\u00a7x\u00a7f\u00a7f\u00a7f\u00a7f\u00a7f\u00a7f",
                NiTextNodes.color(Integer.MAX_VALUE));
    }
}

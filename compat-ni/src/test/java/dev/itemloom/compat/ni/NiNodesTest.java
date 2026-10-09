package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import dev.itemloom.core.GenerationContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NiNodesTest {
    private static final NiEvaluation.Host HOST =
            new NiEvaluation.Host() {
                public String placeholder(Object player, String parameters) {
                    return null;
                }

                public String itemValue(String key, String parameters) {
                    throw new AssertionError("Unexpected item context: " + key);
                }

                public void check(Object actions, NiEvaluation evaluation, String value) {
                    throw new AssertionError("Unexpected check action");
                }
            };

    private NiEvaluation evaluation(String sections, NiScripts scripts, NiEvaluation.Mode mode) {
        return new NiEvaluation(
                new GenerationContext(Map.of(), new Random(7)),
                NiYaml.read(sections, "nodes-test"),
                null,
                mode,
                new NiNodes(),
                scripts,
                HOST);
    }

    @Test
    void modernAndLegacyStringsKeepTheirDifferentEvaluationOrder() {
        String source =
                """
                first: first-result
                second: second-result
                choice:
                  type: strings
                  values: ['<first>', '<second>']
                """;
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation modern = evaluation(source, scripts, NiEvaluation.Mode.ACTION);
            NiEvaluation legacy = evaluation(source, scripts, NiEvaluation.Mode.SECTION);
            assertNotNull(modern.value("choice"));
            assertTrue(modern.generation().rolls().containsKey("first"));
            assertTrue(modern.generation().rolls().containsKey("second"));
            assertNotNull(legacy.value("choice"));
            assertEquals(2, legacy.generation().rolls().size()); // one candidate plus choice
        }
    }

    @Test
    void inheritedModernValueUsesFreshCacheAndDoesNotRerollSavedNode() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var context =
                    evaluation(
                            "roll: {type: number, min: 5, max: 5, fixed: 1}\ncopy: {type: inherit, template: roll}",
                            scripts,
                            NiEvaluation.Mode.ACTION);
            context.generation().rolls().put("roll", "99");
            assertEquals("99", context.value("roll"));
            assertEquals("5.0", context.value("copy"));
            assertEquals("99", context.value("roll"));
        }
    }

    @Test
    void repeatResolvesContentOnceAndTransformGetsLocalScope() {
        try (NiScripts scripts =
                new NiScripts(
                        Map.of(
                                "counter.js",
                                "var calls=0; function next(){return String(++calls);}"),
                        Map.of())) {
            var context =
                    evaluation(
                            """
                    repeated:
                      type: repeat
                      content: '<js::counter.js::next>'
                      repeat: 3
                      separator: '|'
                      transform: 'return this.index + ":" + this.it;'
                    """,
                            scripts,
                            NiEvaluation.Mode.ACTION);
            assertEquals("0:1|1:1|2:1", context.value("repeated"));
            assertEquals("2", context.text("<js::counter.js::next>"));
        }
    }

    @Test
    void joinTruncationDoesNotEvaluateDiscardedValues() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var context =
                    evaluation(
                            """
                    kept: K
                    discarded: D
                    joined:
                      type: join
                      list: ['<kept>', '<discarded>']
                      prefix: '['
                      postfix: ']'
                      separator: '|'
                      limit: 1
                      truncated: '...'
                    """,
                            scripts,
                            NiEvaluation.Mode.ACTION);
            assertEquals("[K|...]", context.value("joined"));
            assertFalse(context.generation().rolls().containsKey("discarded"));
        }
    }

    @Test
    void weightedDeclarationRetainsPreviouslyRolledSlots() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var context =
                    evaluation(
                            """
                    selection:
                      type: weightdeclare
                      key: selected
                      list: ['1::a', '1::b', '1::c']
                      amount: 2
                      putelse: true
                    """,
                            scripts,
                            NiEvaluation.Mode.ACTION);
            context.generation().rolls().put("selected.0", "b");
            assertEquals("b", context.value("selection"));
            assertEquals("2", context.generation().rolls().get("selected.length"));
            assertNotEquals("b", context.generation().rolls().get("selected.1"));
            assertEquals("1", context.generation().rolls().get("selected.else.length"));
        }
    }

    @Test
    void scriptArgumentsStayLiteralAndVarsUsesGenerationCache() {
        String source =
                "function render(arg){ return arg + ':' + this.vars(arg) + ':' + this.vars(arg); }";
        try (NiScripts scripts = new NiScripts(Map.of("test.js", source), Map.of())) {
            var context =
                    evaluation(
                            """
                    rolled: {type: number, min: 10, max: 99}
                    script:
                      type: js
                      path: test.js::render
                      args: ['<rolled>']
                    """,
                            scripts,
                            NiEvaluation.Mode.ACTION);
            String value = context.value("script");
            String cached = context.generation().rolls().get("rolled");
            // The returned string receives the normal node expansion pass.
            assertEquals(cached + ':' + cached + ':' + cached, value);
        }
    }

    @Test
    void expressionBindingsDoNotLeakBetweenRequests() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            assertEquals(3, ((Number) scripts.evaluate("a + 1", Map.of("a", 2))).intValue());
            assertEquals("undefined", scripts.evaluate("typeof a", Map.of()));
        }
    }

    @Test
    void numericFallbacksFollowEachLegacyEntryPoint() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var modern = evaluation("{}", scripts, NiEvaluation.Mode.ACTION);
            var legacy = modern.withMode(NiEvaluation.Mode.SECTION);
            assertEquals("<number::bad_1>", modern.text("<number::bad_1>"));
            assertEquals("<number::bad_1>", legacy.text("<number::bad_1>"));
            assertEquals("<number::1_1_bad>", modern.text("<number::1_1_bad>"));
            assertEquals("1", legacy.text("<number::1_1_bad>"));
            assertEquals("100", modern.text("<gaussian::100_0_0>"));
            assertEquals("100.0", legacy.text("<gaussian::100_0_0>"));
            assertEquals("0", modern.text("<calculation::missing.function() >"));
            // Correct the original modern parser passing a String into DecimalFormat.
            assertEquals("1.3", modern.text("<format::1.25_0.0>"));
        }
    }

    @Test
    void realDivisionScriptUsesExistingRolledEquipmentValues() throws Exception {
        String root = System.getProperty("niConfigRoot");
        Assumptions.assumeTrue(root != null, "Real configuration root is required");
        String division = Files.readString(Path.of(root, "Scripts", "Division.js"));
        try (NiScripts scripts = new NiScripts(Map.of("Division.js", division), Map.of())) {
            var context =
                    evaluation(
                            "stat: {type: number, min: 1, max: 2}",
                            scripts,
                            NiEvaluation.Mode.ACTION);
            context.generation().rolls().put("stat", "10");
            String result =
                    String.valueOf(
                            scripts.invoke(
                                    "Division.js",
                                    "gearScore",
                                    context.bindings(),
                                    "100",
                                    "20",
                                    "<stat>",
                                    "0",
                                    "10"));
            assertEquals("120", result);
            assertEquals("10", context.generation().rolls().get("stat"));
        }
    }
}

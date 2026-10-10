package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import dev.itemloom.core.GenerationContext;
import org.junit.jupiter.api.Test;

class NiCollectionNodesTest {
    private static NiEvaluation evaluation(
            NiScripts scripts,
            NiEvaluation.Mode mode,
            RandomGenerator random,
            List<String> warnings) {
        return new NiEvaluation(
                new GenerationContext(Map.of(), random),
                new NiConfig(Map.of("named", "resolved")),
                null,
                mode,
                new NiNodes(),
                scripts,
                new NiEvaluation.Host() {
                    public String placeholder(Object player, String parameters) {
                        return null;
                    }

                    public String itemValue(String key, String parameters) {
                        throw new AssertionError("Unexpected item host call");
                    }

                    public void check(Object actions, NiEvaluation context, String value) {
                        throw new AssertionError("Unexpected action host call");
                    }

                    public void warning(String message) {
                        warnings.add(message);
                    }
                });
    }

    private static NiEvaluation evaluation(NiScripts scripts, NiEvaluation.Mode mode) {
        return evaluation(scripts, mode, new Random(8712), new ArrayList<>());
    }

    private static NiConfig config(Object... fields) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < fields.length; index += 2)
            values.put((String) fields[index], fields[index + 1]);
        return new NiConfig(values);
    }

    private static String tag(String function) {
        return "<js::trace.js::" + function + ">";
    }

    private static NiScripts tracing(List<String> events) {
        return new NiScripts(
                Map.of(
                        "trace.js",
                        """
                        function emit(key, value) { events.add(key); return value; }
                        function content() { return emit('content', 'C'); }
                        function separator() { return emit('separator', '|'); }
                        function prefix() { return emit('prefix', '['); }
                        function postfix() { return emit('postfix', ']'); }
                        function repeat() { return emit('repeat', '2'); }
                        function limit() { return emit('limit', '1'); }
                        function truncated() { return emit('truncated', '...'); }
                        function shuffled() { return emit('shuffled', 'false'); }
                        function order() { return emit('order', 'true'); }
                        function first() { return emit('first', 'A'); }
                        function second() { return emit('second', 'B'); }
                        function key() { return emit('key', 'selected'); }
                        function amount() { return emit('amount', '2'); }
                        function putelse() { return emit('putelse', 'true'); }
                        function failure() { throw new Error('result failed'); }
                        """),
                Map.of("events", events));
    }

    @Test
    void choicesExpandInModeOrderAndInlineKeepsLiteralArguments() {
        List<String> events = new ArrayList<>();
        try (NiScripts scripts = tracing(events)) {
            NiEvaluation section = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiEvaluation action = section.withMode(NiEvaluation.Mode.ACTION);
            List<String> source = List.of(tag("first"), tag("second"));
            assertNotNull(NiCollectionNodes.choices("strings", source, section, true));
            assertEquals(1, events.size());
            events.clear();
            assertNotNull(NiCollectionNodes.choices("strings", source, action, true));
            assertEquals(List.of("first", "second"), events);
            events.clear();
            assertEquals(
                    tag("first"),
                    NiCollectionNodes.choices("strings", List.of(tag("first")), action, false));
            assertTrue(events.isEmpty());
            assertNull(NiCollectionNodes.choices("strings", List.of(), section, true));
            assertNull(
                    NiCollectionNodes.choices("weight", List.of("0::A", "-1::B"), action, false));

            section.cache().put("deferred", "<named>");
            assertEquals(
                    "resolved",
                    NiCollectionNodes.choices("weight", List.of("<deferred>"), section, true));
            assertEquals(
                    "<named>",
                    NiCollectionNodes.choices("weight", List.of("<deferred>"), action, true));
            assertTrue(events.isEmpty());
            for (NiEvaluation context : List.of(section, action)) {
                events.clear();
                assertEquals(
                        "B",
                        NiCollectionNodes.choices(
                                "weight",
                                List.of("0::" + tag("first"), "1::" + tag("second")),
                                context,
                                true));
                assertEquals(List.of("first", "second"), events);
            }
        }
    }

    @Test
    void decimalWeightsPreservePrecisionFallbacksAndDuplicateProbability() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            assertEquals(
                    "A::B",
                    NiCollectionNodes.choices("weight", List.of("2::A::B"), context, false));
            for (String invalid : List.of("bad", "NaN", "Infinity", "", " 2"))
                assertEquals(
                        "A",
                        NiCollectionNodes.choices(
                                "weight", List.of(invalid + "::A"), context, false));
            assertEquals(
                    "A",
                    NiCollectionNodes.choices(
                            "weight", List.of("1e-2147483647::A"), context, false));
            List<String> disparate = List.of("1e2147483647::A", "1e-2147483647::B");
            for (int index = 0; index < 20; index++)
                assertEquals("A", NiCollectionNodes.choices("weight", disparate, context, false));
            int a = 0;
            for (int index = 0; index < 6000; index++)
                if ("A"
                        .equals(
                                NiCollectionNodes.choices(
                                        "weight",
                                        List.of("1e-400::A", "2e-400::A", "3e-400::B"),
                                        context,
                                        false))) a++;
            assertTrue(
                    a > 2700 && a < 3300,
                    "Duplicate mass should give A probability 1/2, observed " + a);
        }
    }

    @Test
    void joinExpandsFieldsBeforeIncludedItemsAndTransformsSeeUnexpandedFullList() {
        List<String> events = new ArrayList<>();
        try (NiScripts scripts = tracing(events)) {
            NiConfig config =
                    config(
                            "list",
                            List.of(tag("first"), tag("second")),
                            "separator",
                            tag("separator"),
                            "prefix",
                            tag("prefix"),
                            "postfix",
                            tag("postfix"),
                            "limit",
                            tag("limit"),
                            "truncated",
                            tag("truncated"),
                            "shuffled",
                            tag("shuffled"),
                            "transform",
                            "return this.index + ':' + this.it + ':' + this.list.size() + ':' + this.list.get(1);");
            assertEquals(
                    "[0:A:2:" + tag("second") + "|...]",
                    NiCollectionNodes.join(config, evaluation(scripts, NiEvaluation.Mode.SECTION)));
            assertEquals(
                    List.of(
                            "separator",
                            "prefix",
                            "postfix",
                            "limit",
                            "truncated",
                            "shuffled",
                            "first"),
                    events);
        }
    }

    @Test
    void joinHandlesFilteredPositionsTruncationAndModeSpecificSources() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation section = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiEvaluation action = section.withMode(NiEvaluation.Mode.ACTION);
            NiConfig config =
                    config("list", Arrays.asList(null, "A", 2, null), "values", List.of("V"));
            assertEquals("A, 2", NiCollectionNodes.join(config, section));
            assertEquals("V", NiCollectionNodes.join(config, action));
            assertNull(NiCollectionNodes.join(config("values", "scalar"), action));
            assertEquals(
                    "[]", NiCollectionNodes.join(config("prefix", "[", "postfix", "]"), section));
            assertEquals(
                    "A, ",
                    NiCollectionNodes.join(
                            config("list", List.of("A", "B"), "limit", 1, "truncated", ""),
                            section));
            assertEquals(
                    "[...]",
                    NiCollectionNodes.join(
                            config(
                                    "list",
                                    List.of("A", "B"),
                                    "limit",
                                    -1,
                                    "prefix",
                                    "[",
                                    "postfix",
                                    "]",
                                    "truncated",
                                    "..."),
                            section));
            assertEquals(
                    "A, B",
                    NiCollectionNodes.join(
                            config("list", List.of("A", "B"), "limit", " 1"), section));
            Map<String, Object> generated = new HashMap<>();
            generated.put("values", null);
            generated.put("list", List.of("FALLBACK"));
            assertEquals("FALLBACK", NiCollectionNodes.join(NiConfig.mapReader(generated), action));
        }
    }

    @Test
    void repeatHasDifferentFieldOrderButExpandsContentOnlyOnce() {
        List<String> events = new ArrayList<>();
        try (NiScripts scripts = tracing(events)) {
            NiConfig config =
                    config(
                            "content",
                            tag("content"),
                            "repeat",
                            tag("repeat"),
                            "separator",
                            tag("separator"),
                            "prefix",
                            tag("prefix"),
                            "postfix",
                            tag("postfix"),
                            "transform",
                            "return typeof this.list + ':' + this.index + ':' + this.it;");
            NiEvaluation section = evaluation(scripts, NiEvaluation.Mode.SECTION);
            assertEquals(
                    "[undefined:0:C|undefined:1:C]", NiCollectionNodes.repeat(config, section));
            assertEquals(List.of("content", "separator", "prefix", "postfix", "repeat"), events);
            events.clear();
            assertEquals(
                    "[undefined:0:C|undefined:1:C]",
                    NiCollectionNodes.repeat(config, section.withMode(NiEvaluation.Mode.ACTION)));
            assertEquals(List.of("content", "repeat", "separator", "prefix", "postfix"), events);
            assertEquals(
                    "[]",
                    NiCollectionNodes.repeat(
                            config("content", "A", "repeat", 0, "prefix", "[", "postfix", "]"),
                            section));
            assertEquals(
                    "A", NiCollectionNodes.repeat(config("content", "A", "repeat", " 2"), section));
        }
    }

    @Test
    void inlineRepeatIsActionOnlyAndKeepsTextLiteral() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation section = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiEvaluation action = section.withMode(NiEvaluation.Mode.ACTION);
            assertNull(NiCollectionNodes.inlineRepeat(List.of("A", "2"), section));
            assertEquals(
                    "<named>|<named>",
                    NiCollectionNodes.inlineRepeat(List.of("<named>", "2", "|"), action));
            assertEquals("A", NiCollectionNodes.inlineRepeat(List.of("A", "bad"), action));
            assertEquals("", NiCollectionNodes.inlineRepeat(List.of(), action));
            assertEquals("", NiCollectionNodes.inlineRepeat(List.of("A", "-2"), action));
        }
    }

    @Test
    void weightedSequencesDistinguishTextsFromOccurrencesAndTransformSelectedOrder() {
        try (NiScripts scripts =
                new NiScripts(
                        Map.of("literal.js", "function value(){return '<named>';}"), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiConfig config =
                    config(
                            "list",
                            List.of("A", "B", "A"),
                            "amount",
                            10,
                            "order",
                            true,
                            "separator",
                            "|");
            assertEquals("B|A", NiCollectionNodes.weightedSequence("weightjoin", config, context));
            assertEquals(
                    "A|B|A", NiCollectionNodes.weightedSequence("rweightjoin", config, context));
            NiConfig transformed =
                    config.with(
                            "transform",
                            "return this.index + ':' + this.it + ':' + this.list.size();");
            assertEquals(
                    "0:B:2|1:A:2",
                    NiCollectionNodes.weightedSequence("weightjoin", transformed, context));
            assertEquals(
                    "|",
                    NiCollectionNodes.weightedSequence(
                            "weightjoin", config.with("transform", "return null;"), context));
            assertEquals(
                    "<named>",
                    NiCollectionNodes.weightedSequence(
                            "weightjoin",
                            config("list", List.of("<js::literal.js::value>")),
                            context));
        }
    }

    @Test
    void shuffleTakesPrecedenceOverSourceOrderWithoutMutatingInputs() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            RandomGenerator random =
                    new Random(0) {
                        @Override
                        public double nextDouble() {
                            return .5;
                        }

                        @Override
                        public int nextInt(int bound) {
                            return 0;
                        }
                    };
            NiEvaluation context =
                    evaluation(scripts, NiEvaluation.Mode.SECTION, random, new ArrayList<>());
            List<String> list = new ArrayList<>(List.of("A", "B", "C"));
            NiConfig config = config("list", list, "amount", 3, "order", true, "shuffled", true);
            assertEquals(
                    "B, C, A", NiCollectionNodes.weightedSequence("weightjoin", config, context));
            assertEquals("B, C, A", NiCollectionNodes.join(config, context));
            assertEquals(List.of("A", "B", "C"), list);
            assertEquals(List.of("A", "B", "C"), config.strings("list"));
        }
    }

    @Test
    void weightedSequencesExpandFieldsInOrderEvenWithoutCache() {
        List<String> events = new ArrayList<>();
        try (NiScripts scripts = tracing(events)) {
            NiEvaluation section = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiConfig config =
                    config(
                            "list",
                            List.of(tag("first"), tag("second")),
                            "separator",
                            tag("separator"),
                            "prefix",
                            tag("prefix"),
                            "postfix",
                            tag("postfix"),
                            "shuffled",
                            tag("shuffled"),
                            "order",
                            tag("order"),
                            "amount",
                            tag("amount"),
                            "key",
                            tag("key"),
                            "putelse",
                            tag("putelse"));
            assertEquals(
                    "[A|B]", NiCollectionNodes.weightedSequence("weightjoin", config, section));
            assertEquals(
                    List.of(
                            "separator",
                            "prefix",
                            "postfix",
                            "shuffled",
                            "order",
                            "first",
                            "second",
                            "amount"),
                    events);
            events.clear();
            NiEvaluation uncached = section.legacyContext(null, null, section.sections());
            assertNull(NiCollectionNodes.weightedSequence("weightdeclare", config, uncached));
            assertEquals(
                    List.of("shuffled", "order", "first", "second", "key", "amount", "putelse"),
                    events);
            events.clear();
            assertNull(NiCollectionNodes.weightedSequence("rweightdeclare", config, uncached));
            assertEquals(List.of("shuffled", "order", "first", "second", "key", "amount"), events);
        }
    }

    @Test
    void declarationsPreserveOccupiedSlotsAndOriginalRequestedLength() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            Map<String, String> cache = context.legacyCache();
            cache.put("x.0", "A");
            cache.put("x.1", null);
            cache.put("x.9", "OLD");
            cache.put("x.else.9", "OLD_ELSE");
            NiConfig config =
                    config(
                            "list",
                            List.of("A", "B", "C"),
                            "amount",
                            2,
                            "key",
                            "x",
                            "putelse",
                            true);
            assertEquals("A", NiCollectionNodes.weightedSequence("weightdeclare", config, context));
            assertTrue(cache.containsKey("x.1"));
            assertNull(cache.get("x.1"));
            assertTrue(List.of("B", "C").contains(cache.get("x.2")));
            assertEquals("2", cache.get("x.length"));
            assertEquals("1", cache.get("x.else.length"));
            assertNotEquals(cache.get("x.2"), cache.get("x.else.0"));
            assertEquals("OLD", cache.get("x.9"));
            assertEquals("OLD_ELSE", cache.get("x.else.9"));
            assertEquals(
                    "A",
                    NiCollectionNodes.weightedSequence(
                            "rweightdeclare", config("list", List.of("A"), "key", "x"), context));
            assertEquals("A", cache.get("x.3"));
            assertEquals("1", cache.get("x.length"));
            cache.put("null.0", "NULL_KEY");
            Map<String, String> snapshot = new HashMap<>(cache);
            assertEquals(
                    "NULL_KEY",
                    NiCollectionNodes.weightedSequence(
                            "weightdeclare",
                            config("list", List.of("A"), "putelse", true),
                            context));
            assertEquals(snapshot, cache);
        }
    }

    @Test
    void finiteWeightedRacesHaveTheRequiredDistributionAndDoNotReuseOccurrences() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            int b = 0;
            NiConfig source = config("list", List.of("1::A", "3::B"));
            for (int index = 0; index < 6000; index++)
                if ("B".equals(NiCollectionNodes.weightedSequence("weightjoin", source, context)))
                    b++;
            assertTrue(b > 4200 && b < 4800, "Expected B probability 3/4, observed " + b);
            NiConfig repeated =
                    config("list", List.of("A", "A", "B"), "amount", 100, "order", true);
            assertEquals(
                    "A, A, B",
                    NiCollectionNodes.weightedSequence("rweightjoin", repeated, context));
        }
    }

    @Test
    void declarationExclusionsRebuildEligibleMassAfterInfiniteAndOverflowingWeights() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            context.legacyCache().put("x.0", "A");
            NiConfig infinite =
                    config(
                            "key",
                            "x",
                            "list",
                            List.of("Infinity::A", "1::B"),
                            "amount",
                            2,
                            "putelse",
                            true);
            assertEquals(
                    "A", NiCollectionNodes.weightedSequence("weightdeclare", infinite, context));
            assertEquals("B", context.legacyCache().get("x.1"));
            assertEquals("0", context.legacyCache().get("x.else.length"));
            context.legacyCache().clear();
            context.legacyCache().put("x.0", "A");
            NiConfig overflow =
                    config(
                            "key",
                            "x",
                            "list",
                            List.of("1e308::A", "1e308::B", "1::C"),
                            "amount",
                            3,
                            "order",
                            true);
            assertEquals(
                    "A", NiCollectionNodes.weightedSequence("weightdeclare", overflow, context));
            assertEquals("B", context.legacyCache().get("x.1"));
            assertEquals("C", context.legacyCache().get("x.2"));
            assertEquals("3", context.legacyCache().get("x.length"));
        }
    }

    @Test
    void degenerateDoubleWeightsRetainObservedBoundaryBehavior() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            for (double draw : new double[] {0, .1, .5, .9, Math.nextDown(1.0)}) {
                NiEvaluation context =
                        evaluation(
                                scripts, NiEvaluation.Mode.SECTION, fixed(draw), new ArrayList<>());
                assertEquals("A", sequence("rweightjoin", List.of("0::A"), 3, context));
                assertEquals("", sequence("weightjoin", List.of("0::A"), 3, context));
                assertEquals("", sequence("rweightjoin", List.of("-1::A"), 3, context));
                assertEquals("", sequence("rweightjoin", List.of("1::A", "NaN::B"), 3, context));
                assertEquals(
                        draw == 0 ? "" : "A",
                        sequence("rweightjoin", List.of("-Infinity::A"), 3, context));
                assertEquals(
                        draw == 0 ? "" : "A",
                        sequence("weightjoin", List.of("Infinity::A", "Infinity::B"), 3, context));
                assertEquals(
                        draw == 0 ? "" : "B",
                        sequence("weightjoin", List.of("1e308::A", "1e308::B"), 3, context));
                assertEquals(
                        draw == 0 ? "" : "Z",
                        sequence("weightjoin", List.of("1e308::Z", "1e308::A"), 3, context));
                assertEquals(
                        draw == 0 ? "" : "A",
                        sequence(
                                "weightjoin",
                                List.of("Infinity::Z", "Infinity::A", "Infinity::B"),
                                3,
                                context));
                assertEquals(
                        draw == 0 ? "" : "Z",
                        sequence(
                                "rweightjoin",
                                List.of("Infinity::Z", "Infinity::A", "Infinity::B"),
                                3,
                                context));
                assertEquals(
                        draw == 0 ? "" : "Aa",
                        sequence(
                                "weightjoin",
                                List.of("Infinity::Aa", "Infinity::BB", "Infinity::C"),
                                3,
                                context));
                assertEquals(
                        draw == 0 ? "A|B" : "B|A",
                        sequence("rweightjoin", List.of("0::A", "1::B"), 3, context));
                assertEquals(
                        "C", sequence("rweightjoin", List.of("-2::A", "1::B", "3::C"), 3, context));
                assertEquals(
                        "A", sequence("rweightjoin", List.of("2::A", "-3::B", "2::C"), 3, context));
                assertEquals(
                        draw > 2.0 / 3 ? "A" : "",
                        sequence("rweightjoin", List.of("-2::A", "-1::B"), 3, context));
            }
        }
    }

    @Test
    void exceptionalSelectionRetriesAfterADrawThatFindsNoBoundary() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation infinite =
                    evaluation(scripts, NiEvaluation.Mode.SECTION, draws(0, .5), new ArrayList<>());
            NiEvaluation negative =
                    evaluation(
                            scripts, NiEvaluation.Mode.SECTION, draws(.1, .9), new ArrayList<>());
            assertEquals("A", sequence("rweightjoin", List.of("Infinity::A", "1::B"), 2, infinite));
            assertEquals("A", sequence("rweightjoin", List.of("-2::A", "-1::B"), 2, negative));
        }
    }

    private static String sequence(
            String type, List<String> values, int amount, NiEvaluation context) {
        return NiCollectionNodes.weightedSequence(
                type, config("list", values, "amount", amount, "separator", "|"), context);
    }

    private static RandomGenerator fixed(double draw) {
        return new Random(0) {
            @Override
            public double nextDouble() {
                return draw;
            }
        };
    }

    private static RandomGenerator draws(double... draws) {
        return new Random(0) {
            private int index;

            @Override
            public double nextDouble() {
                return draws[index++];
            }
        };
    }

    @Test
    void whenCleansCacheAccordingToModeAndWhetherABranchWasAccepted() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            for (NiEvaluation.Mode mode : NiEvaluation.Mode.values()) {
                NiEvaluation context = evaluation(scripts, mode);
                context.cache().put("value", "OLD");
                assertEquals(
                        "V",
                        NiCollectionNodes.when(
                                config("value", "V", "conditions", List.of("<value>")), context));
                assertEquals(
                        mode == NiEvaluation.Mode.ACTION ? "OLD" : null,
                        context.cache().get("value"));
                context.cache().put("value", "OLD");
                assertNull(
                        NiCollectionNodes.when(
                                config("value", "V", "conditions", List.of()), context));
                assertEquals(
                        mode == NiEvaluation.Mode.ACTION ? "OLD" : "V",
                        context.cache().get("value"));
                context.cache().put("value", "OLD");
                assertEquals(
                        "OLD",
                        NiCollectionNodes.when(config("conditions", List.of("<value>")), context));
                assertEquals(
                        mode == NiEvaluation.Mode.ACTION ? "OLD" : null,
                        context.cache().get("value"));
                context.cache().put("value", "OLD");
                assertNull(
                        NiCollectionNodes.when(config("value", "V", "conditions", "bad"), context));
                assertEquals("OLD", context.cache().get("value"));
            }
        }
    }

    @Test
    void whenExposesLiveCacheAndModeSpecificConditionBindings() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            for (NiEvaluation.Mode mode : NiEvaluation.Mode.values()) {
                NiEvaluation context = evaluation(scripts, mode);
                String sectionType =
                        mode == NiEvaluation.Mode.ACTION
                                ? "sections.getHandle().getString('named') === 'resolved' && global === context.getParams()"
                                : "sections.getString('named') === 'resolved' && global !== context.getParams()";
                String condition =
                        "cache.put('observed', value); "
                                + sectionType
                                + " && player === null && target === null";
                NiConfig config =
                        config(
                                "value",
                                "V",
                                "conditions",
                                List.of(
                                        Map.of("condition", 1, "result", "SKIP"),
                                        Map.of("condition", condition, "result", "<observed>"),
                                        "FALLBACK"));
                assertEquals("V", NiCollectionNodes.when(config, context));
                assertEquals("V", context.cache().get("observed"));
            }
        }
    }

    @Test
    void whenConditionsHaveFreshScopesAndSectionsButShareExplicitCache() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            for (NiEvaluation.Mode mode : NiEvaluation.Mode.values()) {
                NiEvaluation context = evaluation(scripts, mode);
                String section =
                        mode == NiEvaluation.Mode.ACTION ? "sections.getHandle()" : "sections";
                String first =
                        "global.put('sticky', 'LEAK'); value = 'CHANGED'; "
                                + "cache.put('retained', 'YES'); "
                                + section
                                + ".set('shared', 'YES'); false;";
                String second =
                        "!global.containsKey('sticky') && value === 'V' && "
                                + "cache.get('retained') === 'YES' && "
                                + section
                                + ".getString('shared') === null";
                NiConfig config =
                        config(
                                "value",
                                "V",
                                "conditions",
                                List.of(
                                        Map.of("condition", first, "result", "BAD"),
                                        Map.of("condition", second, "result", "OK"),
                                        "LEAKED"));
                assertEquals("OK", NiCollectionNodes.when(config, context));
            }
        }
    }

    @Test
    void transformsReceiveFreshScriptObjectsAndJoinKeepsMutableListIdentity() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiConfig repeated =
                    config(
                            "content",
                            "A",
                            "repeat",
                            3,
                            "separator",
                            "|",
                            "transform",
                            "this.counter = (this.counter || 0) + 1; return String(this.counter);");
            assertEquals("1|1|1", NiCollectionNodes.repeat(repeated, context));
            NiConfig joined =
                    config(
                            "list",
                            List.of("A", "B"),
                            "amount",
                            2,
                            "order",
                            true,
                            "separator",
                            "|",
                            "transform",
                            "if (this.index === 0) this.list.set(0, 'MARK'); return this.list.get(0);");
            assertEquals("MARK|MARK", NiCollectionNodes.join(joined, context));
            assertEquals(List.of("A", "B"), joined.strings("list"));
        }
    }

    @Test
    void weightedTransformsReceiveAnUnmodifiableSelectedSnapshot() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiConfig source = config("list", List.of("A", "B"), "amount", 2, "order", true);
            for (String mutation :
                    List.of(
                            "this.list.set(0, 'CHANGED');",
                            "this.list.add('EXTRA');",
                            "this.list.remove(0);")) {
                NiConfig transformed = source.with("transform", mutation + " return this.it;");
                assertThrows(
                        RuntimeException.class,
                        () ->
                                NiCollectionNodes.weightedSequence(
                                        "weightjoin", transformed, context));
            }
            assertEquals(List.of("A", "B"), source.strings("list"));
            assertEquals("A, B", NiCollectionNodes.weightedSequence("weightjoin", source, context));
        }
    }

    @Test
    void whenHandlesConditionFailuresNullResultsAndResultExpansionErrors() {
        List<String> events = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (NiScripts scripts = tracing(events)) {
            NiEvaluation action =
                    evaluation(scripts, NiEvaluation.Mode.ACTION, new Random(1), warnings);
            NiConfig config =
                    config(
                            "conditions",
                            List.of(
                                    Map.of(
                                            "condition",
                                            "throw new Error('bad condition')",
                                            "result",
                                            "BAD"),
                                    Map.of()));
            assertEquals("null", NiCollectionNodes.when(config, action));
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("Action condition failed"));
            for (NiEvaluation.Mode mode : NiEvaluation.Mode.values()) {
                NiEvaluation context = action.withMode(mode);
                context.cache().put("value", "OLD");
                assertThrows(
                        RuntimeException.class,
                        () ->
                                NiCollectionNodes.when(
                                        config("value", "V", "conditions", List.of(tag("failure"))),
                                        context));
                assertEquals(
                        mode == NiEvaluation.Mode.ACTION ? "OLD" : null,
                        context.cache().get("value"));
            }
        }
    }

    @Test
    void transformErrorsPropagateFromAllRenderingNodes() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiEvaluation context = evaluation(scripts, NiEvaluation.Mode.SECTION);
            NiConfig config =
                    config(
                            "list",
                            List.of("A"),
                            "content",
                            "A",
                            "transform",
                            "throw new Error('transform failed');");
            assertThrows(RuntimeException.class, () -> NiCollectionNodes.join(config, context));
            assertThrows(RuntimeException.class, () -> NiCollectionNodes.repeat(config, context));
            assertThrows(
                    RuntimeException.class,
                    () -> NiCollectionNodes.weightedSequence("weightjoin", config, context));
        }
    }
}

package dev.itemloom.compat.ni.action;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.script.LegacyActionResult;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.core.GenerationContext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

class NiValueSyntaxReplacementTest {
    private static <T> Function<NiActionContext, T> compile(
            Object input, Class<T> type, Consumer<String> validate) {
        return NiValueSyntax.compile(
                input,
                type,
                validate,
                config -> null,
                value -> NiValues.convert(value, type),
                value -> NiValues.convert(value, type));
    }

    private static NiActionContext context(NiScripts scripts, List<String> warnings) {
        NiEvaluation.Host host =
                new NiEvaluation.Host() {
                    public String placeholder(Object player, String parameters) {
                        return null;
                    }

                    public String itemValue(String key, String parameters) {
                        return null;
                    }

                    public void check(Object actions, NiEvaluation evaluation, String value) {}

                    public void warning(String message) {
                        warnings.add(message);
                    }
                };
        return new NiActionContext(
                new NiEvaluation(
                        new GenerationContext(Map.of(), new Random(3)),
                        null,
                        null,
                        NiEvaluation.Mode.ACTION,
                        new NiNodes(),
                        scripts,
                        host),
                null,
                null,
                scripts::isOpen);
    }

    @Test
    void nullAndScalarInputsCompileWithoutRequiringRuntimeContext() {
        AtomicInteger conversions = new AtomicInteger();
        Function<Object, Object> conversion =
                value -> {
                    conversions.incrementAndGet();
                    return value;
                };
        var absent =
                NiValueSyntax.compile(
                        null,
                        Object.class,
                        script -> fail(),
                        config -> fail(),
                        conversion,
                        value -> fail());
        assertNull(absent.apply(null));
        assertEquals(0, conversions.get());
        Object scalar = new Object();
        var constant =
                NiValueSyntax.compile(
                        scalar,
                        Object.class,
                        script -> fail(),
                        config -> fail(),
                        conversion,
                        value -> fail());
        assertEquals(1, conversions.get());
        assertSame(scalar, constant.apply(null));
        assertSame(scalar, constant.apply(null));
        assertEquals(1, conversions.get());
    }

    @Test
    void alternativesChooseFirstNonNullAndValidateEveryChildBeforeEvaluation() {
        List<String> validated = new ArrayList<>();
        assertEquals(
                0,
                compile(Arrays.asList(null, 0, "js: 7"), Integer.class, validated::add)
                        .apply(null));
        assertEquals(List.of("7"), validated);
        assertFalse(compile(List.of(false, true), Boolean.class, validated::add).apply(null));
        assertEquals(
                "", compile(List.of("raw: ", "later"), String.class, validated::add).apply(null));
        assertNull(
                compile(
                                List.of("raw: invalid", List.of("raw: also-invalid")),
                                Integer.class,
                                validated::add)
                        .apply(null));
        IllegalArgumentException invalid = new IllegalArgumentException("invalid expression");
        assertSame(
                invalid,
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                compile(
                                        List.of(1, List.of("js: invalid")),
                                        Integer.class,
                                        script -> {
                                            throw invalid;
                                        })));
    }

    @Test
    void exactDelimiterAndRawValuesBypassParsingWhileTemplatesRefreshEachTime() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var context = context(scripts, new ArrayList<>());
            context.getGlobal().put("value", 3);
            var parsed = compile("<value>", Integer.class, script -> fail());
            var text = compile("prefix <value>", String.class, script -> fail());
            assertEquals(3, parsed.apply(context));
            assertEquals("prefix 3", text.apply(context));
            context.getGlobal().put("value", 8);
            assertEquals(8, parsed.apply(context));
            assertEquals("prefix 8", text.apply(context));
            assertEquals(
                    "<value>", compile("RAW: <value>", String.class, script -> fail()).apply(null));
            assertEquals(
                    "raw:8", compile("raw:<value>", String.class, script -> fail()).apply(context));
            assertEquals(
                    "js:\ttrue", compile("js:\ttrue", String.class, script -> fail()).apply(null));
            assertEquals("a: b", compile("raw: a: b", String.class, script -> fail()).apply(null));
            assertEquals("left<", compile("left<", String.class, script -> fail()).apply(null));
            assertNull(compile("raw", String.class, script -> fail()).apply(null));
        }
    }

    @Test
    void stringsContainingBothBracketDirectionsRemainDynamicEvenWithoutAValidPlaceholder() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            AtomicInteger conversions = new AtomicInteger();
            var compiled =
                    NiValueSyntax.compile(
                            ">text<",
                            String.class,
                            script -> fail(),
                            config -> null,
                            value -> {
                                conversions.incrementAndGet();
                                return (String) value;
                            },
                            value -> fail());
            assertEquals(1, conversions.get());
            var context = context(scripts, new ArrayList<>());
            assertEquals(">text<", compiled.apply(context));
            assertEquals(">text<", compiled.apply(context));
            assertEquals(3, conversions.get());
        }
    }

    @Test
    void configBranchesWinAndSingletonConversionReceivesTheOriginalObject() {
        Object input = new HashMap<>(Map.of("custom", "value"));
        AtomicInteger branches = new AtomicInteger();
        var converted =
                NiValueSyntax.compile(
                        input,
                        Object.class,
                        script -> fail(),
                        config -> {
                            branches.incrementAndGet();
                            return null;
                        },
                        value -> {
                            assertSame(input, value);
                            return value;
                        },
                        value -> fail());
        assertSame(input, converted.apply(null));
        assertEquals(1, branches.get());
        var branch =
                NiValueSyntax.compile(
                        Map.of("js", "invalid", "other", "value"),
                        String.class,
                        script -> fail(),
                        config -> context -> "selected",
                        value -> fail(),
                        value -> fail());
        assertEquals("selected", branch.apply(null));
        assertNull(compile(Map.of("one", 1, "two", 2), String.class, script -> fail()).apply(null));
        Map<String, Object> nullable = new HashMap<>();
        nullable.put("raw", null);
        assertNull(
                compile(NiConfig.mapReader(nullable), String.class, script -> fail()).apply(null));
        assertEquals("3", compile(Map.of("raw", 3), String.class, script -> fail()).apply(null));
        Object upper = Map.of("JS", "bad(");
        assertSame(
                upper,
                NiValueSyntax.compile(
                                upper,
                                Object.class,
                                script -> fail(),
                                config -> null,
                                Function.identity(),
                                value -> fail())
                        .apply(null));
    }

    @Test
    void scriptConversionIsDistinctAndRuntimeFailureWarnsWithoutSkippingValidation() {
        List<String> warnings = new ArrayList<>();
        List<String> validated = new ArrayList<>();
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var context = context(scripts, warnings);
            var script =
                    NiValueSyntax.compile(
                            Map.of("js", "6 + 1"),
                            String.class,
                            validated::add,
                            config -> null,
                            value -> fail(),
                            value -> "script-" + ((Number) value).intValue());
            assertEquals(List.of("6 + 1"), validated);
            assertEquals("script-7", script.apply(context));
            assertNull(
                    compile("js: missingVariable()", Object.class, scripts::validate)
                            .apply(context));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().startsWith("Value script failed: "));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            compile(
                                    Arrays.asList(null, "js: broken("),
                                    Object.class,
                                    scripts::validate));
            assertNull(compile("js", Object.class, source -> fail()).apply(context));
            assertNull(compile("js", Object.class, source -> fail()).apply(null));
            assertEquals(1, warnings.size());
        }
    }

    @Test
    void conditionAcceptsOnlyTrueOrContinuingActionResultsAndWarnsOnFailure() {
        List<String> warnings = new ArrayList<>();
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var context = context(scripts, warnings);
            assertTrue(context.condition(null));
            assertTrue(context.condition("true"));
            assertFalse(context.condition("1"));
            assertFalse(context.condition("'true'"));
            context.getBindings().put("answer", new LegacyActionResult.Success());
            assertTrue(context.condition("answer"));
            context.getBindings().put("answer", new LegacyActionResult.Stop());
            assertFalse(context.condition("answer"));
            context.getBindings().put("answer", ActionFlow.Result.CONTINUE);
            assertTrue(context.condition("answer"));
            context.getBindings().put("answer", ActionFlow.Result.STOP);
            assertFalse(context.condition("answer"));
            assertFalse(context.condition("throw new Error('expected')"));
            assertTrue(warnings.getFirst().startsWith("Action condition failed: "));
        }
    }
}

package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NiTemplateTest {
    @Test
    void nestedNamesAndUnknownCraftEngineTags() {
        Map<String, String> values = Map.of("quality", "rare", "stats.rare", "7.5");
        String input = "<stats.<quality>> <image:pack:icon> <papi:gearcompare:>";
        assertEquals(
                "7.5 <image:pack:icon> <papi:gearcompare:>",
                NiTemplate.compile(input).render(values::get));
    }

    @Test
    void resultTokensAreLiteralUntilExplicitlyParsedByTheirNode() {
        assertEquals("<other>", NiTemplate.compile("<value>").render(key -> "<other>"));
    }

    @Test
    void innerTokensStillEvaluateInsideUnclosedOuterToken() {
        List<String> called = new ArrayList<>();
        assertEquals(
                "before<aXtail",
                NiTemplate.compile("before<a<b>tail")
                        .render(
                                key -> {
                                    called.add(key);
                                    return "X";
                                }));
        assertEquals(List.of("b"), called);
    }

    @Test
    void templateAndArgumentsHaveDifferentTrailingEscapeRules() {
        assertEquals("text", NiTemplate.compile("text\\").render(key -> null));
        assertEquals(List.of("text\\"), NiTemplate.arguments("text\\", 0));
        assertEquals(List.of("a_b", "c", "d_e"), NiTemplate.arguments("a\\_b_c_d_e", 3));
        assertEquals("<value> \\n", NiTemplate.compile("\\<value> \\n").render(key -> "bad"));
    }

    @Test
    void deeplyNestedInputDoesNotUseJavaCallStack() {
        String source = "<".repeat(20_000) + "x" + ">".repeat(20_000);
        assertEquals("x", NiTemplate.compile(source).render(Function.identity()));
    }

    @Test
    void differentialAgainstReferenceJar() throws Exception {
        String jar = System.getProperty("niReferenceJar");
        Assumptions.assumeTrue(
                jar != null,
                "Explicit NI API reference JAR is required for differential execution");
        // Test-only isolated loader; the shipped modules never link or package reference classes.
        try (URLClassLoader loader =
                new URLClassLoader(
                        new java.net.URL[] {Path.of(jar).toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            Method parse =
                    loader.loadClass("pers.neige.neigeitems.utils.SectionUtilsJ")
                            .getMethod(
                                    "parse",
                                    String.class,
                                    char.class,
                                    char.class,
                                    char.class,
                                    Function.class);
            Method split =
                    loader.loadClass("pers.neige.neigeitems.utils.StringUtils")
                            .getMethod("split", String.class, char.class, char.class, int.class);
            Method placeholders =
                    loader.loadClass(
                                    "pers.neige.neigeitems.hook.placeholderapi.PlaceholderTextParser")
                            .getMethod(
                                    "parse",
                                    String.class,
                                    java.util.function.Predicate.class,
                                    java.util.function.BiFunction.class,
                                    boolean.class);
            Random random = new Random(20261007);
            char[] alphabet = "abc<>\\_% n".toCharArray();
            for (int sample = 0; sample < 20_000; sample++) {
                StringBuilder input = new StringBuilder();
                for (int j = random.nextInt(120); j > 0; j--)
                    input.append(alphabet[random.nextInt(alphabet.length)]);
                String text = input.toString();
                List<String> expectedCalls = new ArrayList<>();
                List<String> actualCalls = new ArrayList<>();
                Function<String, String> expectedResolver =
                        key -> {
                            expectedCalls.add(key);
                            return key.length() % 2 == 0 ? "[" + key + "]" : null;
                        };
                Function<String, String> actualResolver =
                        key -> {
                            actualCalls.add(key);
                            return key.length() % 2 == 0 ? "[" + key + "]" : null;
                        };
                assertEquals(
                        parse.invoke(null, text, '<', '>', '\\', expectedResolver),
                        NiTemplate.compile(text).render(actualResolver),
                        text);
                assertEquals(expectedCalls, actualCalls, text);
                int limit = random.nextInt(5);
                assertEquals(
                        split.invoke(null, text, '_', '\\', limit),
                        NiTemplate.arguments(text, limit),
                        text);
                assertEquals(
                        split.invoke(null, text, ' ', '\\', limit),
                        NiTemplate.split(text, ' ', limit),
                        text);
                assertEquals(
                        placeholders.invoke(
                                null,
                                text,
                                (java.util.function.Predicate<String>) id -> true,
                                (java.util.function.BiFunction<String, String, String>)
                                        (id, params) -> null,
                                true),
                        dev.itemloom.compat.ni.action.NiActionText.placeholders(text),
                        text);
            }
        }
    }
}

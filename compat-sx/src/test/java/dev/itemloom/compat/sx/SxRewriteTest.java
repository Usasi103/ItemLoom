package dev.itemloom.compat.sx;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SxRewriteTest {
    @Test
    void scriptsResolvePluginClassesWhenCallingThreadCannotSeeThem() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(null) {});
            try (SxScripts scripts =
                    new SxScripts(
                            input(
                                    Map.of(),
                                    Map.of(),
                                    Map.of(),
                                    Map.of(
                                            "Plugin.js",
                                            "var Config = Java.type('dev.itemloom.compat.sx.SxConfig'); "
                                                    + "function typeName() { return Config.class.getName(); }")),
                            Map.of())) {
                assertEquals(
                        "dev.itemloom.compat.sx.SxConfig",
                        scripts.call("Plugin", "typeName", null, null));
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void snapshotDetachesCollectionsAndRetainsNullsAndOrder() {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<Object> lines = new ArrayList<>(List.of("before"));
        nested.put("list", lines);
        nested.put("nil", null);
        SxConfig snapshot = new SxConfig(nested);
        lines.set(0, "after");
        nested.put("later", true);
        assertEquals(List.of("list", "nil"), new ArrayList<>(snapshot.values().keySet()));
        assertEquals(List.of("before"), snapshot.get("list"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values().put("new", 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((List<?>) snapshot.get("list")).clear());
    }

    @Test
    void fingerprintCoversSemanticInputsWithoutSourceFiles() {
        Map<String, Object> aa = new LinkedHashMap<>(Map.of("Name", "Aa"));
        Map<String, Object> bb = new LinkedHashMap<>(Map.of("Name", "BB"));
        assertEquals(aa.hashCode(), bb.hashCode());
        String original = fingerprint(aa, Map.of(), Map.of("pool", List.of("a", "b")), Map.of());
        assertNotEquals(
                original, fingerprint(bb, Map.of(), Map.of("pool", List.of("a", "b")), Map.of()));
        assertEquals(
                original,
                fingerprint(
                        new LinkedHashMap<>(aa),
                        Map.of(),
                        Map.of("pool", new ArrayList<>(List.of("a", "b"))),
                        Map.of()));
        assertNotEquals(
                original,
                fingerprint(
                        aa,
                        Map.of("DecimalPrecision", 3),
                        Map.of("pool", List.of("a", "b")),
                        Map.of()));
        assertNotEquals(
                original, fingerprint(aa, Map.of(), Map.of("pool", List.of("b", "a")), Map.of()));
        assertNotEquals(
                fingerprint(aa, Map.of(), Map.of("pool", 1), Map.of()),
                fingerprint(aa, Map.of(), Map.of("pool", "1"), Map.of()));
        assertNotEquals(
                fingerprint(aa, Map.of(), Map.of("pool", List.of(Map.of("1", "a"))), Map.of()),
                fingerprint(aa, Map.of(), Map.of("pool", List.of(Map.of("2", "a"))), Map.of()));
        assertNotEquals(
                original,
                fingerprint(
                        aa,
                        Map.of(),
                        Map.of("pool", List.of("a", "b")),
                        Map.of("Local.js", "function name() { return 'x'; }")));
    }

    @Test
    void fingerprintsRetainScriptRouteAndEncounterOrderAndInputBoundaries() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("Global/a.js", "var value = 1;");
        forward.put("Global/b.js", "value += 1;");
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("Global/b.js", "value += 1;");
        reverse.put("Global/a.js", "var value = 1;");
        assertNotEquals(
                fingerprint(Map.of(), Map.of(), Map.of(), forward),
                fingerprint(Map.of(), Map.of(), Map.of(), reverse));
        assertNotEquals(
                fingerprint(
                        Map.of("Name", "ab", "Lore", List.of("c")), Map.of(), Map.of(), Map.of()),
                fingerprint(
                        Map.of("Name", "a", "Lore", List.of("bc")), Map.of(), Map.of(), Map.of()));
        SxRepository.Input first = input(Map.of(), Map.of(), Map.of(), forward);
        SxRepository.Input changedSource =
                new SxRepository.Input(
                        first.settings(),
                        first.items(),
                        first.random(),
                        first.scripts(),
                        Map.of("Item/a.yml", "name: source"));
        assertNotEquals(SxFingerprint.of(first), SxFingerprint.of(changedSource));
    }

    @Test
    void poolRejectsNonfiniteTotalsAndZeroWeightsNeverWin() {
        for (String weight : List.of("NaN", "Infinity", "-Infinity", "-1", "0")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SxRandom.compile(Map.of("pool", List.of(Map.of(weight, "value")))));
        }
        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("0", "never");
        weights.put("1", "always");
        SxRandom pool = SxRandom.compile(Map.of("pool", List.of(weights))).get("pool");
        Random zero =
                new Random() {
                    @Override
                    public double nextDouble() {
                        return 0;
                    }
                };
        assertEquals("always", pool.pick(zero));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SxRandom.compile(
                                Map.of(
                                        "pool",
                                        List.of(Map.of("1e308", "a"), Map.of("1e308", "b")))));
    }

    @Test
    void expressionBudgetResetsAfterFailureAndUnselectedNestedWorkIsEager() {
        List<String> calls = new ArrayList<>();
        SxExpressions handler =
                new SxExpressions(
                        null,
                        SxRandom.compile(Map.of("loop", "<s:loop>")),
                        Map.of(),
                        Map.of(),
                        new Random(1),
                        text -> text,
                        (file, function, h, args) -> {
                            calls.add(function);
                            return "unused";
                        },
                        new SxConfig(Map.of()),
                        Clock.systemUTC());
        assertEquals("yes", handler.replace("<if:true?yes:<j:test.unselected>>"));
        assertEquals(List.of("unselected"), calls);
        assertThrows(IllegalArgumentException.class, () -> handler.replace("<s:loop>"));
        assertThrows(IllegalArgumentException.class, () -> handler.replace("x".repeat(1_048_577)));
        assertThrows(IllegalArgumentException.class, () -> handler.replace("<null:>".repeat(9000)));
        assertEquals("still works", handler.replace("still works"));
    }

    @Test
    void fullIntegerRangeIncludesMaximumWithoutOverflow() {
        Random upper =
                new Random() {
                    @Override
                    public long nextLong(long origin, long bound) {
                        assertEquals(Integer.MIN_VALUE, origin);
                        assertEquals(2_147_483_648L, bound);
                        return bound - 1;
                    }
                };
        SxExpressions handler =
                new SxExpressions(
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        upper,
                        text -> text,
                        (file, name, h, args) -> null,
                        new SxConfig(Map.of()),
                        Clock.systemUTC());
        assertEquals("2147483647", handler.replace("<i:-2147483648_2147483647>"));
    }

    private static String fingerprint(
            Map<String, Object> item,
            Map<String, Object> settings,
            Map<String, ?> pools,
            Map<String, String> scripts) {
        return SxFingerprint.of(input(item, settings, pools, scripts));
    }

    private static SxRepository.Input input(
            Map<String, Object> item,
            Map<String, Object> settings,
            Map<String, ?> pools,
            Map<String, String> scripts) {
        return new SxRepository.Input(
                new SxConfig(settings),
                Map.of(
                        "entry",
                        new SxRepository.Definition(
                                "entry", "synthetic", new SxConfig(item), null)),
                SxRandom.compile(pools),
                scripts,
                Map.of());
    }
}

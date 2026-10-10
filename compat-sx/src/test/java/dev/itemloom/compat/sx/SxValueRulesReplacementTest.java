package dev.itemloom.compat.sx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SxValueRulesReplacementTest {
    @Test
    void numericMarkersDecodeWithExactWrapperTypes() {
        Map<String, Object> samples =
                Map.of(
                        "[byte]-128",
                        (byte) -128,
                        "[short]32767",
                        (short) 32767,
                        "[int]2147483647",
                        Integer.MAX_VALUE,
                        "[long]-9223372036854775808",
                        Long.MIN_VALUE,
                        "[float]2.5",
                        2.5F,
                        "[double]-3.25",
                        -3.25D);
        samples.forEach((input, expected) -> assertEquals(expected, SxValueRules.scalar(input)));
        assertEquals(Double.POSITIVE_INFINITY, SxValueRules.scalar("[double]Infinity"));
    }

    @Test
    void unrecognizedMarkersRemainLiteralAndRecognizedInvalidNumbersFail() {
        assertNull(SxValueRules.scalar(null));
        for (String input : List.of("", "x[int]1", " [int]1", "[INT]1", "[other]1", "[int")) {
            assertSame(input, SxValueRules.scalar(input));
        }
        for (String input : List.of("[int] 1", "[byte]128", "[short]32768", "[int]1]", "[long]")) {
            assertThrows(NumberFormatException.class, () -> SxValueRules.scalar(input), input);
        }
        // Whitespace accepted by Java's floating-point wrapper remains accepted.
        assertEquals(1D, SxValueRules.scalar("[double] 1 "));
    }

    @Test
    void cachedNonNullValueBypassesEverySourceAndExpansion() {
        Map<String, String> locks = new LinkedHashMap<>(Map.of("key", "cached"));
        assertEquals("cached", SxValueRules.locked("key#one:two", locks, null, null, null, null));
    }

    @Test
    void plainKeyUsesOnlyRandomSourceAndExpandsOnceBeforeCommit() {
        Map<String, String> locks = new LinkedHashMap<>();
        List<String> events = new ArrayList<>();
        String result =
                SxValueRules.locked(
                        "key",
                        locks,
                        null,
                        key -> {
                            events.add("random:" + key);
                            return "raw";
                        },
                        bound -> {
                            throw new AssertionError("Unexpected inline choice");
                        },
                        value -> {
                            assertFalse(locks.containsKey("key"));
                            events.add("expand:" + value);
                            return "expanded";
                        });
        assertEquals("expanded", result);
        assertEquals(List.of("random:key", "expand:raw"), events);
        assertEquals(Map.of("key", "expanded"), locks);
    }

    @Test
    void inlineSourcesPreferOverridesAndKeepEmptyTrailingChoicesAndLaterHashes() {
        Map<String, String> locks = new LinkedHashMap<>();
        assertEquals(
                "!",
                SxValueRules.locked(
                        "key#one:two",
                        locks,
                        Map.of("key", ""),
                        null,
                        bound -> {
                            throw new AssertionError("Override must bypass chooser");
                        },
                        value -> value + "!"));
        locks.clear();
        assertEquals(
                "",
                SxValueRules.locked(
                        "key#one:two:",
                        locks,
                        Map.of(),
                        null,
                        bound -> {
                            assertEquals(3, bound);
                            return 2;
                        },
                        Function.identity()));
        locks.clear();
        assertEquals(
                "one#tail",
                SxValueRules.locked(
                        "key#one#tail", locks, Map.of(), null, bound -> 0, Function.identity()));
    }

    @Test
    void nullCacheAndOverrideFallThroughButNullExpansionIsInserted() {
        Map<String, String> locks = new LinkedHashMap<>();
        locks.put("key", null);
        Map<String, String> other = new LinkedHashMap<>();
        other.put("key", null);
        assertNull(
                SxValueRules.locked(
                        "key#chosen",
                        locks,
                        other,
                        null,
                        bound -> 0,
                        value -> {
                            assertEquals("chosen", value);
                            return null;
                        }));
        assertTrue(locks.containsKey("key"));
        assertNull(locks.get("key"));
        assertEquals(
                "again",
                SxValueRules.locked("key", locks, null, key -> "again", null, Function.identity()));
    }

    @Test
    void recursiveCacheMutationIsAllowedAndTheOuterCommitWins() {
        Map<String, String> locks = new LinkedHashMap<>();
        assertEquals(
                "outer",
                SxValueRules.locked(
                        "key",
                        locks,
                        null,
                        key -> "raw",
                        null,
                        raw -> {
                            locks.put("nested", "kept");
                            locks.put("key", "inner");
                            return "outer";
                        }));
        assertEquals(Map.of("nested", "kept", "key", "outer"), locks);
    }

    @Test
    void exceptionsLeaveThePendingCacheEntryUnwritten() {
        Map<String, String> locks = new LinkedHashMap<>();
        var failure = new IllegalStateException("expansion failed");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                SxValueRules.locked(
                                        "key",
                                        locks,
                                        null,
                                        key -> "raw",
                                        null,
                                        raw -> {
                                            throw failure;
                                        })));
        assertFalse(locks.containsKey("key"));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        SxValueRules.locked(
                                "key", Map.of(), null, key -> null, null, Function.identity()));
    }
}

package dev.itemloom.paper.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LegacyStateRulesReplacementTest {
    @Test
    void durabilityUsesLiteralQuantizationAndVisibilityBoundaryExpectations() {
        record Case(int maximum, int remaining, int capacity, short expected) {}
        List<Case> cases =
                List.of(
                        new Case(100, 100, 100, (short) 0),
                        new Case(100, 99, 100, (short) 1),
                        new Case(100, 1, 100, (short) 99),
                        new Case(100, 0, 100, (short) 100),
                        new Case(100, 101, 100, (short) -1),
                        new Case(100, -1, 100, (short) 101),
                        new Case(1, 50, 100, (short) 0),
                        new Case(0, 50, 100, (short) 0),
                        new Case(-1, 50, 100, (short) 0),
                        new Case(-100, 50, 100, (short) 0),
                        new Case(100, 0, 0, (short) 0),
                        new Case(100, 1, 0, (short) 0),
                        new Case(100, -1, 0, (short) 1),
                        new Case(-100, 1, 0, (short) -2),
                        new Case(0, 1, 0, (short) -1),
                        new Case(100, 1, -100, (short) 100),
                        new Case(100, -50, -100, (short) 50),
                        new Case(100, -101, -100, (short) 1),
                        new Case(32767, 0, 1, (short) 32767),
                        new Case(32768, 0, 1, (short) 1),
                        new Case(65535, 0, 1, (short) 1),
                        new Case(65536, 0, 1, (short) 1),
                        new Case(65534, 1, 2, (short) 32767),
                        new Case(65536, 1, 2, (short) 1),
                        new Case(Integer.MAX_VALUE, 0, 1, (short) 1),
                        new Case(Integer.MAX_VALUE, 1, 2, (short) 1),
                        new Case(Integer.MIN_VALUE, 0, 1, (short) 1),
                        new Case(Integer.MIN_VALUE, 1, 2, (short) 0),
                        new Case(100, Integer.MAX_VALUE, 1, (short) 0),
                        new Case(100, Integer.MIN_VALUE, 1, (short) 1));
        for (Case sample : cases) {
            assertEquals(
                    sample.expected(),
                    LegacyStateRules.damage(
                            sample.maximum(), sample.remaining(), sample.capacity()),
                    sample.toString());
        }
    }

    @Test
    void healthyPartialDurabilityRemainsVisibleWithoutAppearingBroken() {
        for (int maximum : List.of(2, 3, 100, 32767)) {
            for (int remaining = 1; remaining < 1000; remaining++) {
                short result = LegacyStateRules.damage(maximum, remaining, 1000);
                assertTrue(result >= 1 && result < maximum);
            }
        }
    }

    @Test
    void differingSizesDecideBeforeIteratorsOrEntryComparisonAreRequested() {
        Iterable<String> unread =
                () -> {
                    throw new AssertionError("Unexpected iteration");
                };
        Comparator<String> unused =
                (left, right) -> {
                    throw new AssertionError("Unexpected entry comparison");
                };
        assertEquals(-1, LegacyStateRules.compareOrdered(1, unread, 3, unread, unused));
        assertEquals(1, LegacyStateRules.compareOrdered(7, unread, 3, unread, unused));
        assertEquals(
                0, LegacyStateRules.compareOrdered(0, List.<String>of(), 0, List.of(), unused));
    }

    @Test
    void orderedComparisonRetainsMagnitudeOrderAndFirstDifferenceShortCircuit() {
        AtomicInteger calls = new AtomicInteger();
        Comparator<String> compare =
                (left, right) -> {
                    calls.incrementAndGet();
                    if (left.equals("unread"))
                        throw new AssertionError("Read beyond first mismatch");
                    return left.compareTo(right);
                };
        assertEquals(
                -25,
                LegacyStateRules.compareOrdered(
                        3,
                        List.of("same", "a", "unread"),
                        3,
                        List.of("same", "z", "unread"),
                        compare));
        assertEquals(2, calls.get());
        Map<String, String> left = new LinkedHashMap<>();
        left.put("z", "same");
        left.put("a", "same");
        Map<String, String> right = new LinkedHashMap<>();
        right.put("a", "same");
        right.put("z", "same");
        Comparator<Map.Entry<String, String>> entries =
                Map.Entry.<String, String>comparingByKey()
                        .thenComparing(Map.Entry.comparingByValue());
        assertEquals(
                25,
                LegacyStateRules.compareOrdered(
                        left.size(), left.entrySet(), right.size(), right.entrySet(), entries));
        assertEquals(
                -25,
                LegacyStateRules.compareOrdered(
                        1, Map.of("k", "a").entrySet(), 1, Map.of("k", "z").entrySet(), entries));
    }

    private record Key(int type, int amount, short damage, String compound) {}

    @Test
    void itemOrderingPreservesEachKeysExactResultAndNullOrdering() {
        Comparator<Key> order =
                LegacyStateRules.itemOrder(
                        Key::type, Key::amount, Key::damage, Key::compound, String::compareTo);
        assertEquals(
                -1,
                order.compare(
                        new Key(Integer.MIN_VALUE, 0, (short) 0, null),
                        new Key(Integer.MAX_VALUE, 0, (short) 0, null)));
        assertEquals(
                -1,
                order.compare(
                        new Key(0, Integer.MIN_VALUE, (short) 0, null),
                        new Key(0, Integer.MAX_VALUE, (short) 0, null)));
        assertEquals(
                -65535,
                order.compare(
                        new Key(0, 1, Short.MIN_VALUE, "z"), new Key(0, 1, Short.MAX_VALUE, "a")));
        assertEquals(
                -25, order.compare(new Key(0, 1, (short) 2, "a"), new Key(0, 1, (short) 2, "z")));
        assertEquals(
                -1, order.compare(new Key(0, 1, (short) 2, null), new Key(0, 1, (short) 2, "a")));
        assertEquals(
                1, order.compare(new Key(0, 1, (short) 2, "a"), new Key(0, 1, (short) 2, null)));
        assertEquals(
                0, order.compare(new Key(0, 1, (short) 2, null), new Key(0, 1, (short) 2, null)));
    }

    @Test
    void itemComparatorOnlyReadsKeysRequiredToDecideOrder() {
        List<String> reads = new ArrayList<>();
        Comparator<Key> order =
                LegacyStateRules.itemOrder(
                        key -> {
                            reads.add("type");
                            return key.type();
                        },
                        key -> {
                            reads.add("amount");
                            return key.amount();
                        },
                        key -> {
                            reads.add("damage");
                            return key.damage();
                        },
                        key -> {
                            reads.add("compound");
                            return key.compound();
                        },
                        String::compareTo);
        Key left = new Key(0, 1, (short) 2, "a");
        assertEquals(-1, order.compare(left, new Key(1, 0, (short) 0, null)));
        assertEquals(List.of("type", "type"), reads);
        reads.clear();
        assertEquals(-1, order.compare(left, new Key(0, 2, (short) 0, null)));
        assertEquals(List.of("type", "type", "amount", "amount"), reads);
        reads.clear();
        assertEquals(-5, order.compare(left, new Key(0, 1, (short) 7, null)));
        assertEquals(List.of("type", "type", "amount", "amount", "damage", "damage"), reads);
    }

    @Test
    void numericSnapshotCopiesOnlyPresentKnownFieldsAndDetachesFromSource() {
        Map<String, Integer> source =
                new LinkedHashMap<>(Map.of("charge", 0, "durability", -7, "unknown", 19));
        List<String> presenceReads = new ArrayList<>();
        List<String> numericReads = new ArrayList<>();
        LegacyStateRules.NumericState state =
                LegacyStateRules.captureNumericState(
                        key -> {
                            presenceReads.add(key);
                            return source.containsKey(key);
                        },
                        key -> {
                            numericReads.add(key);
                            return source.get(key);
                        });
        assertEquals(List.of("charge", "durability"), presenceReads);
        assertEquals(List.of("charge", "durability"), numericReads);
        source.put("charge", 100);
        source.remove("durability");
        Map<String, Integer> target =
                new LinkedHashMap<>(Map.of("charge", 9, "durability", 9, "new-only", 9));
        state.applyTo(target::put);
        assertEquals(Map.of("charge", 0, "durability", -7, "new-only", 9), target);
        Map<String, Integer> second = new LinkedHashMap<>();
        state.applyTo(second::put);
        assertEquals(Map.of("charge", 0, "durability", -7), second);
    }

    @Test
    void absentNumericFieldsDoNotReadDefaultsOrOverwriteGeneratedValues() {
        Map<String, Integer> target =
                new LinkedHashMap<>(Map.of("charge", 4, "durability", 8, "new-only", 3));
        LegacyStateRules.captureNumericState(
                        key -> false,
                        key -> {
                            throw new AssertionError("Absent numeric field was read");
                        })
                .applyTo(target::put);
        assertEquals(Map.of("charge", 4, "durability", 8, "new-only", 3), target);
        LegacyStateRules.captureNumericState("charge"::equals, key -> 0).applyTo(target::put);
        assertEquals(Map.of("charge", 0, "durability", 8, "new-only", 3), target);
    }
}

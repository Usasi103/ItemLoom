package dev.itemloom.paper.compat.script;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.core.GenerationBudget;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class PackSelectionTest {
    private static final class Entry {
        final String name;
        double chance;

        Entry(String name, double chance) {
            this.name = name;
            this.chance = chance;
        }

        double probability() {
            return chance;
        }

        void probability(double value) {
            chance = value;
        }
    }

    private static GenerationBudget budget() {
        return new GenerationBudget(100_000, 100_000, 100_000);
    }

    @Test
    void nonpositiveMaximumDoesNotCreateOrGenerateEntries() {
        AtomicInteger callbacks = new AtomicInteger();
        List<String> result =
                PackSelection.select(
                        List.of("a"),
                        1,
                        0,
                        name -> {
                            callbacks.incrementAndGet();
                            return new Entry(name, 1);
                        },
                        Entry::probability,
                        Entry::probability,
                        entry -> {
                            callbacks.incrementAndGet();
                            return List.of(entry.name);
                        },
                        batch -> callbacks.incrementAndGet(),
                        new GenerationBudget(0, 0, 0));
        assertTrue(result.isEmpty());
        assertEquals(0, callbacks.get());
    }

    @Test
    void sequentialMaximumCountsBatchesAndObservesOneAdditionalSuccess() {
        List<String> evaluated = new ArrayList<>();
        List<List<String>> accepted = new ArrayList<>();
        List<String> result =
                PackSelection.select(
                        List.of("a", "empty", "b", "discarded", "unreached"),
                        null,
                        2,
                        name -> new Entry(name, 1),
                        Entry::probability,
                        Entry::probability,
                        entry -> {
                            evaluated.add(entry.name);
                            return entry.name.equals("empty")
                                    ? List.of()
                                    : List.of(entry.name, entry.name);
                        },
                        accepted::add,
                        budget());
        assertEquals(List.of("a", "a", "b", "b"), result);
        assertEquals(List.of("a", "empty", "b", "discarded"), evaluated);
        assertEquals(List.of(List.of("a", "a"), List.of("b", "b")), accepted);
    }

    @Test
    void nullBudgetSupportsBothSequentialAndMinimumSelection() {
        for (Integer minimum : new Integer[] {null, 1}) {
            AtomicInteger generated = new AtomicInteger();
            AtomicInteger retained = new AtomicInteger();
            List<String> result =
                    PackSelection.select(
                            List.of("a", "b"),
                            minimum,
                            1,
                            name -> new Entry(name, 1),
                            Entry::probability,
                            Entry::probability,
                            entry -> {
                                generated.incrementAndGet();
                                return List.of(entry.name);
                            },
                            batch -> retained.incrementAndGet(),
                            null,
                            new Random(19));
            assertEquals(1, result.size());
            assertEquals(2, generated.get());
            assertEquals(1, retained.get());
        }
    }

    @Test
    void sequentialEarlyStopNeverConstructsLaterMalformedOrSideEffectEntries() {
        List<String> operations = new ArrayList<>();
        List<String> result =
                PackSelection.select(
                        List.of("a\nempty\nextra\nmalformed", "side-effect"),
                        null,
                        1,
                        name -> {
                            operations.add("create:" + name);
                            if (name.equals("malformed"))
                                throw new NumberFormatException("bad amount");
                            return new Entry(name, 1);
                        },
                        Entry::probability,
                        Entry::probability,
                        entry -> {
                            operations.add("generate:" + entry.name);
                            return entry.name.equals("empty") ? List.of() : List.of(entry.name);
                        },
                        batch -> {},
                        new GenerationBudget(3, 10, 10));
        assertEquals(List.of("a"), result);
        assertEquals(
                List.of(
                        "create:a",
                        "generate:a",
                        "create:empty",
                        "generate:empty",
                        "create:extra",
                        "generate:extra"),
                operations);
    }

    @Test
    void sequentialZeroProbabilityEntriesReachTheGeneratorWithoutAnotherGate() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        List<String> generated = new ArrayList<>();
        List<String> result =
                PackSelection.select(
                        List.of("first", "extra", "unreached"),
                        null,
                        1,
                        name -> new Entry(name, 0),
                        entry -> {
                            reads.incrementAndGet();
                            return entry.chance;
                        },
                        (entry, value) -> {
                            writes.incrementAndGet();
                            entry.chance = value;
                        },
                        entry -> {
                            generated.add(entry.name);
                            return List.of(entry.name);
                        },
                        batch -> {},
                        null);
        assertEquals(List.of("first"), result);
        assertEquals(List.of("first", "extra"), generated);
        assertEquals(0, reads.get());
        assertEquals(0, writes.get());
    }

    @Test
    void multilineDuplicatesHaveSeparateEntriesAndUnlimitedOrderIsPreserved() {
        List<Entry> entries = new ArrayList<>();
        List<String> result =
                PackSelection.select(
                        List.of("a\na", "b"),
                        null,
                        null,
                        name -> {
                            Entry entry = new Entry(name, 1);
                            entries.add(entry);
                            return entry;
                        },
                        Entry::probability,
                        Entry::probability,
                        entry -> List.of(entry.name),
                        batch -> {},
                        budget());
        assertEquals(List.of("a", "a", "b"), result);
        assertEquals(3, entries.size());
        assertNotSame(entries.get(0), entries.get(1));
    }

    @Test
    void minimumEvaluatesEveryCandidateWithoutAlwaysFavoringFirstLine() {
        Set<String> winners = new HashSet<>();
        for (int seed = 0; seed < 64; seed++) {
            List<String> evaluated = new ArrayList<>();
            AtomicInteger retained = new AtomicInteger();
            List<String> result =
                    PackSelection.select(
                            List.of("first", "second", "third"),
                            1,
                            1,
                            name -> new Entry(name, 1),
                            Entry::probability,
                            Entry::probability,
                            entry -> {
                                evaluated.add(entry.name);
                                return List.of(entry.name);
                            },
                            batch -> retained.incrementAndGet(),
                            budget(),
                            new Random(seed));
            assertEquals(1, result.size());
            assertEquals(3, evaluated.size());
            assertEquals(3, new HashSet<>(evaluated).size());
            assertEquals(1, retained.get());
            winners.add(result.getFirst());
        }
        assertEquals(Set.of("first", "second", "third"), winners);
    }

    @Test
    void weightedMinimumUsesProbabilityAndNeverSelectsAnEntryTwice() {
        int highWeightWins = 0;
        for (int seed = 0; seed < 512; seed++) {
            List<String> result =
                    PackSelection.select(
                            List.of("high", "low"),
                            1,
                            1,
                            name -> new Entry(name, name.equals("high") ? 1 : 0.001),
                            Entry::probability,
                            Entry::probability,
                            entry -> entry.chance == 1 ? List.of(entry.name) : List.of(),
                            batch -> {},
                            budget(),
                            new Random(seed));
            if (result.equals(List.of("high"))) highWeightWins++;
        }
        assertTrue(highWeightWins > 480, "Weighted minimum should favor the much larger weight");

        List<String> result =
                PackSelection.select(
                        List.of("a", "b", "c"),
                        3,
                        null,
                        name -> new Entry(name, 0.2),
                        Entry::probability,
                        Entry::probability,
                        entry -> entry.chance == 1 ? List.of(entry.name) : List.of(),
                        batch -> {},
                        budget(),
                        new Random(7));
        assertEquals(3, result.size());
        assertEquals(Set.of("a", "b", "c"), new HashSet<>(result));
    }

    @Test
    void probabilitiesAboveOneRetainTheirRelativeSamplingWeights() {
        Random random = new Random(91241);
        int highWeightWins = 0;
        int highRewardWins = 0;
        for (int draw = 0; draw < 1024; draw++) {
            List<Entry> entries = new ArrayList<>();
            var guaranteed = new java.util.concurrent.atomic.AtomicReference<String>();
            List<String> result =
                    PackSelection.select(
                            List.of("high", "low"),
                            1,
                            1,
                            name -> {
                                Entry entry = new Entry(name, name.equals("high") ? 90 : 10);
                                entries.add(entry);
                                return entry;
                            },
                            Entry::probability,
                            (entry, value) -> {
                                entry.probability(value);
                                guaranteed.set(entry.name);
                            },
                            entry -> List.of(entry.name),
                            batch -> {},
                            null,
                            random);
            String winner = guaranteed.get();
            if (winner.equals("high")) highWeightWins++;
            if (result.getFirst().equals("high")) highRewardWins++;
            for (Entry entry : entries)
                if (!entry.name.equals(winner))
                    assertEquals(entry.name.equals("high") ? 90.0 : 10.0, entry.chance);
        }
        assertTrue(
                highWeightWins > 840 && highWeightWins < 980,
                "90:10 weights should remain distinct even though both gates always pass: "
                        + highWeightWins);
        assertTrue(
                highRewardWins > 400 && highRewardWins < 624,
                "The maximum must not prioritize guaranteed entries when both gates always pass");
    }

    @Test
    void minimumWithoutMaximumCanGuaranteeZeroWeightsButMaximumFiltersThem() {
        List<String> guaranteed =
                PackSelection.select(
                        List.of("a", "b", "c"),
                        2,
                        null,
                        name -> new Entry(name, 0),
                        Entry::probability,
                        Entry::probability,
                        entry -> entry.chance == 1 ? List.of(entry.name) : List.of(),
                        batch -> {},
                        budget(),
                        new Random(11));
        assertEquals(2, guaranteed.size());
        assertEquals(2, new HashSet<>(guaranteed).size());

        List<String> evaluated = new ArrayList<>();
        List<String> limited =
                PackSelection.select(
                        List.of("zero", "positive", "negative"),
                        2,
                        1,
                        name ->
                                new Entry(
                                        name,
                                        name.equals("positive")
                                                ? 0.5
                                                : name.equals("zero") ? 0 : -1),
                        Entry::probability,
                        Entry::probability,
                        entry -> {
                            evaluated.add(entry.name);
                            return List.of(entry.name);
                        },
                        batch -> {},
                        budget(),
                        new Random(11));
        assertEquals(List.of("positive"), limited);
        assertEquals(List.of("positive"), evaluated);
    }

    @Test
    void discardedGenerationDoesNotSpendRetainedRewardBudget() {
        GenerationBudget allowance = new GenerationBudget(3, 1, 1);
        AtomicInteger generated = new AtomicInteger();
        List<String> result =
                PackSelection.select(
                        List.of("a", "b", "c"),
                        null,
                        1,
                        name -> new Entry(name, 1),
                        Entry::probability,
                        Entry::probability,
                        entry -> {
                            generated.incrementAndGet();
                            return List.of(entry.name);
                        },
                        batch -> allowance.retain(batch.size(), batch.size()),
                        allowance);
        assertEquals(List.of("a"), result);
        assertEquals(2, generated.get());
        allowance.work(0);
        allowance.retain(0, 0);
    }

    @Test
    void budgetIsReservedBeforeParsingAndBeforeMinimumSelection() {
        AtomicInteger created = new AtomicInteger();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PackSelection.select(
                                List.of("a\nb\nc"),
                                null,
                                null,
                                name -> {
                                    created.incrementAndGet();
                                    return new Entry(name, 1);
                                },
                                Entry::probability,
                                Entry::probability,
                                entry -> List.of(entry.name),
                                batch -> {},
                                new GenerationBudget(2, 10, 10)));
        assertEquals(2, created.get());

        AtomicInteger generated = new AtomicInteger();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PackSelection.select(
                                List.of("a", "b"),
                                1,
                                null,
                                name -> new Entry(name, 1),
                                Entry::probability,
                                Entry::probability,
                                entry -> {
                                    generated.incrementAndGet();
                                    return List.of(entry.name);
                                },
                                batch -> {},
                                new GenerationBudget(3, 10, 10)));
        assertEquals(0, generated.get());
    }

    @Test
    void invalidMinimumWeightsAreZeroAndPositiveInfinityIsPreserved() {
        Map<String, Double> evaluated = new HashMap<>();
        PackSelection.select(
                List.of("NaN", "Infinity", "-Infinity"),
                0,
                null,
                name -> new Entry(name, Double.parseDouble(name)),
                Entry::probability,
                Entry::probability,
                entry -> {
                    evaluated.put(entry.name, entry.chance);
                    return List.<String>of();
                },
                batch -> {},
                budget());
        assertEquals(
                Map.of("NaN", 0.0, "Infinity", Double.POSITIVE_INFINITY, "-Infinity", 0.0),
                evaluated);
    }
}

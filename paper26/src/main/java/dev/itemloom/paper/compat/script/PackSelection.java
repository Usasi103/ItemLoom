package dev.itemloom.paper.compat.script;

import dev.itemloom.core.GenerationBudget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * Selection policy for fresh pack entries; item construction and reward accounting are callbacks.
 */
public final class PackSelection {
    private PackSelection() {}

    public static <E, T> List<T> select(
            List<String> rawLines,
            Integer minimum,
            Integer maximum,
            Function<String, E> createEntry,
            ToDoubleFunction<E> probability,
            ObjDoubleConsumer<E> setProbability,
            Function<E, List<T>> generate,
            Consumer<List<T>> accepted,
            GenerationBudget budget) {
        return select(
                rawLines,
                minimum,
                maximum,
                createEntry,
                probability,
                setProbability,
                generate,
                accepted,
                budget,
                ThreadLocalRandom.current());
    }

    /**
     * Limits count successful entries, not the number of rewards returned by each entry. The caller
     * normalizes configured limits against the raw line count before calling this method. Only
     * accepted batches consume reward allowance; even discarded batches may do generation work,
     * which belongs to the generator's budget. A null budget leaves work accounting to the caller.
     */
    public static <E, T> List<T> select(
            List<String> rawLines,
            Integer minimum,
            Integer maximum,
            Function<String, E> createEntry,
            ToDoubleFunction<E> probability,
            ObjDoubleConsumer<E> setProbability,
            Function<E, List<T>> generate,
            Consumer<List<T>> accepted,
            GenerationBudget budget,
            RandomGenerator random) {
        if (maximum != null && maximum <= 0) return new ArrayList<>();
        if (minimum == null)
            return sequential(rawLines, maximum, createEntry, generate, accepted, budget);

        List<Candidate<E>> candidates = new ArrayList<>();
        for (String raw : rawLines) {
            for (String line : raw.split("\n", -1)) {
                work(budget, 1);
                E entry = Objects.requireNonNull(createEntry.apply(line), "entry");
                double supplied = probability.applyAsDouble(entry);
                double chance = probability(supplied);
                if (Double.compare(supplied, chance) != 0) setProbability.accept(entry, chance);
                if (maximum == null || chance > 0) candidates.add(new Candidate<>(entry, chance));
            }
        }
        guaranteeMinimum(candidates, minimum, setProbability, budget, random);

        List<T> rewards = new ArrayList<>();
        int successes = 0;
        for (Candidate<E> candidate : candidates) {
            List<T> batch =
                    Objects.requireNonNull(generate.apply(candidate.entry), "generated batch");
            if (batch.isEmpty()) continue;
            if (maximum == null || successes < maximum) {
                accepted.accept(batch);
                rewards.addAll(batch);
                successes++;
            }
        }
        return rewards;
    }

    /** Parse and evaluate one entry at a time so discarded tail entries have no side effects. */
    private static <E, T> List<T> sequential(
            List<String> rawLines,
            Integer maximum,
            Function<String, E> createEntry,
            Function<E, List<T>> generate,
            Consumer<List<T>> accepted,
            GenerationBudget budget) {
        List<T> rewards = new ArrayList<>();
        int successes = 0;
        for (String raw : rawLines) {
            for (String line : raw.split("\n", -1)) {
                work(budget, 1);
                E entry = Objects.requireNonNull(createEntry.apply(line), "entry");
                List<T> batch = Objects.requireNonNull(generate.apply(entry), "generated batch");
                if (batch.isEmpty()) continue;
                // The extra successful entry is evaluated, but not retained. Subsequent entries
                // must not be constructed: parsing them can itself consume randomness or fail.
                if (maximum != null && successes == maximum) return rewards;
                accepted.accept(batch);
                rewards.addAll(batch);
                successes++;
            }
        }
        return rewards;
    }

    private static void work(GenerationBudget budget, int amount) {
        if (budget != null) budget.work(amount);
    }

    private static final class Candidate<E> {
        private final E entry;
        private final double probability;
        private double rank;

        Candidate(E entry, double probability) {
            this.entry = entry;
            this.probability = probability;
        }
    }

    private static <E> void guaranteeMinimum(
            List<Candidate<E>> candidates,
            int minimum,
            ObjDoubleConsumer<E> setProbability,
            GenerationBudget budget,
            RandomGenerator random) {
        work(budget, candidates.size());
        for (int end = candidates.size() - 1; end > 0; end--) {
            int other = random.nextInt(end + 1);
            Candidate<E> moved = candidates.set(other, candidates.get(end));
            candidates.set(end, moved);
        }
        // Independent exponential clocks produce a weighted sample without replacement. Log
        // space avoids overflowing when a valid probability is very small. Zero-weight entries
        // come last in the already shuffled order and can fill a minimum without a maximum.
        for (Candidate<E> candidate : candidates)
            candidate.rank =
                    candidate.probability == 0
                            ? Double.POSITIVE_INFINITY
                            : candidate.probability == Double.POSITIVE_INFINITY
                                    ? Double.NEGATIVE_INFINITY
                                    : Math.log(-Math.log1p(-random.nextDouble()))
                                            - Math.log(candidate.probability);
        List<Candidate<E>> weighted = new ArrayList<>(candidates);
        weighted.sort(Comparator.comparingDouble(candidate -> candidate.rank));
        int guaranteed = Math.min(Math.max(0, minimum), candidates.size());
        for (int index = 0; index < guaranteed; index++) {
            setProbability.accept(weighted.get(index).entry, 1);
        }
        // Guarantee selection changes gates, not traversal priority. Keep the independent
        // shuffled order so a maximum does not silently give guaranteed entries priority.
    }

    /** Invalid minimum-selection weights are zero; positive values retain their relative weight. */
    private static double probability(double value) {
        if (Double.isNaN(value) || value <= 0) return 0;
        return value;
    }
}

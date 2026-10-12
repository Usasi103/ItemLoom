package dev.itemloom.paper.compat.script;

import dev.itemloom.core.GenerationBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/** Keeps sampling guarantees separate from traversal and from retained reward batches. */
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
        List<T> result = new ArrayList<>();
        if (maximum != null && maximum <= 0) return result;
        int retained = 0;
        if (minimum == null) {
            for (String raw : rawLines) {
                for (String line : raw.split("\n", -1)) {
                    work(budget, 1);
                    E entry = Objects.requireNonNull(createEntry.apply(line), "entry");
                    List<T> batch = Objects.requireNonNull(generate.apply(entry), "batch");
                    if (batch.isEmpty()) continue;
                    if (maximum != null && retained >= maximum) return result;
                    accepted.accept(batch);
                    result.addAll(batch);
                    retained++;
                }
            }
            return result;
        }

        List<Candidate<E>> candidates = new ArrayList<>();
        for (String raw : rawLines) {
            for (String line : raw.split("\n", -1)) {
                work(budget, 1);
                E entry = Objects.requireNonNull(createEntry.apply(line), "entry");
                double supplied = probability.applyAsDouble(entry);
                double weight = supplied > 0 ? supplied : 0;
                if (Double.compare(supplied, weight) != 0) setProbability.accept(entry, weight);
                if (maximum == null || weight > 0) candidates.add(new Candidate<>(entry, weight));
            }
        }
        work(budget, candidates.size());
        shuffle(candidates, random);
        List<Candidate<E>> available = new ArrayList<>(candidates);
        int guarantee = Math.min(Math.max(minimum, 0), available.size());
        for (int count = 0; count < guarantee; count++) {
            Candidate<E> selected = available.remove(choose(available, random));
            setProbability.accept(selected.entry(), 1);
        }
        for (Candidate<E> candidate : candidates) {
            List<T> batch = Objects.requireNonNull(generate.apply(candidate.entry()), "batch");
            if (batch.isEmpty() || maximum != null && retained >= maximum) continue;
            accepted.accept(batch);
            result.addAll(batch);
            retained++;
        }
        return result;
    }

    private static void work(GenerationBudget budget, long count) {
        if (budget != null) budget.work(count);
    }

    private static <E> int choose(List<Candidate<E>> candidates, RandomGenerator random) {
        List<Integer> infinite = new ArrayList<>();
        double largest = 0;
        for (int index = 0; index < candidates.size(); index++) {
            double weight = candidates.get(index).weight();
            if (Double.isInfinite(weight)) infinite.add(index);
            else largest = Math.max(largest, weight);
        }
        if (!infinite.isEmpty()) return infinite.get(random.nextInt(infinite.size()));
        if (largest == 0) return random.nextInt(candidates.size());
        double total = 0;
        for (Candidate<E> candidate : candidates) total += candidate.weight() / largest;
        double draw = random.nextDouble(total);
        int lastPositive = 0;
        for (int index = 0; index < candidates.size(); index++) {
            double weight = candidates.get(index).weight() / largest;
            if (weight > 0) lastPositive = index;
            draw -= weight;
            if (draw < 0) return index;
        }
        return lastPositive;
    }

    private static <E> void shuffle(List<E> values, RandomGenerator random) {
        for (int index = values.size() - 1; index > 0; index--) {
            int selected = random.nextInt(index + 1);
            E prior = values.set(index, values.get(selected));
            values.set(selected, prior);
        }
    }

    private record Candidate<E>(E entry, double weight) {}
}

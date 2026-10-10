package dev.itemloom.paper.action;

import java.util.random.RandomGenerator;

/** Arithmetic for one affected unit; inventory ownership stays with the caller. */
final class DurabilityOutcome {
    private DurabilityOutcome() {}

    record Change(int remaining, int count, Integer displayedDamage, boolean exhausted) {}

    static int effectiveDamage(int requested, int unbreaking, RandomGenerator random) {
        if (unbreaking <= 0) return requested;
        double probability = 1 / (unbreaking + 1d);
        int result = 0;
        for (int opportunity = 0; opportunity < requested; opportunity++)
            if (random.nextDouble() < probability) result++;
        return result;
    }

    static Change change(
            int current,
            int maximum,
            int ordinaryMaximum,
            int effective,
            boolean breaks,
            boolean removeDirectly) {
        if (maximum <= 0)
            throw new IllegalArgumentException("Non-positive custom durability maximum");
        if (effective <= 0)
            throw new IllegalArgumentException("Expected positive effective damage");
        if (effective < current) {
            int remaining = current - effective;
            return new Change(
                    remaining,
                    1,
                    (int) (ordinaryMaximum * (1 - remaining / (double) maximum)),
                    false);
        }
        if (breaks) return new Change(current, removeDirectly ? 0 : 1, null, true);
        return new Change(0, 1, Math.max(0, ordinaryMaximum - 1), true);
    }
}

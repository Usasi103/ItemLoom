package dev.itemloom.paper.action;

import java.util.random.RandomGenerator;

final class DurabilityOutcome {
    private DurabilityOutcome() {}

    static int effectiveDamage(int requested, int unbreaking, RandomGenerator random) {
        if (unbreaking <= 0) return requested;
        double probability = 1 / (unbreaking + 1d);
        int applied = 0;
        for (int trial = 0; trial < requested; trial++) {
            if (random.nextDouble() < probability) applied++;
        }
        return applied;
    }

    static Change change(
            int current,
            int maximum,
            int ordinaryMaximum,
            int effective,
            boolean breaks,
            boolean removeDirectly) {
        if (maximum <= 0 || effective <= 0) {
            throw new IllegalArgumentException("Durability maximum and damage must be positive");
        }
        if (effective < current) {
            int remaining = current - effective;
            int displayed = (int) (ordinaryMaximum * (1 - remaining / (double) maximum));
            return new Change(remaining, 1, displayed, false);
        }
        if (breaks) return new Change(current, removeDirectly ? 0 : 1, null, true);
        return new Change(0, 1, Math.max(0, ordinaryMaximum - 1), true);
    }

    record Change(int remaining, int count, Integer displayedDamage, boolean exhausted) {}
}

package dev.itemloom.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class GenerationContext {
    public static final class Key<T> {
        private final Class<T> type;

        public Key(Class<T> type) {
            this.type = Objects.requireNonNull(type, "type");
        }
    }

    private final Map<String, String> rolls;
    private final RandomGenerator random;
    private final Map<Key<?>, Object> values = new IdentityHashMap<>();
    private final List<String> evaluating = new ArrayList<>();

    public GenerationContext(Map<String, String> savedRolls, RandomGenerator random) {
        this(savedRolls, random, false);
    }

    private GenerationContext(
            Map<String, String> savedRolls, RandomGenerator random, boolean share) {
        Objects.requireNonNull(savedRolls, "savedRolls");
        this.rolls = share ? savedRolls : new LinkedHashMap<>(savedRolls);
        this.random = Objects.requireNonNull(random, "random");
    }

    public static GenerationContext sharingRolls(
            Map<String, String> cache, RandomGenerator random) {
        return new GenerationContext(cache, random, true);
    }

    public RandomGenerator random() {
        return random;
    }

    public <T> void put(Key<T> key, T value) {
        values.put(Objects.requireNonNull(key, "key"), key.type.cast(value));
    }

    public <T> T get(Key<T> key) {
        return key.type.cast(values.get(key));
    }

    public Map<String, String> rolls() {
        return rolls;
    }

    public Map<String, String> savedRolls() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(rolls));
    }

    public String resolve(String name, Supplier<String> evaluation) {
        return resolve(name, evaluation, true);
    }

    public String resolve(String name, Supplier<String> evaluation, boolean cache) {
        Objects.requireNonNull(name, "name");
        if (rolls.containsKey(name)) {
            return rolls.get(name);
        }
        if (evaluating.contains(name)) {
            List<String> cycle = new ArrayList<>(evaluating);
            cycle.add(name);
            throw new IllegalArgumentException(
                    "Circular item expression: " + String.join(" -> ", cycle));
        }
        evaluating.add(name);
        try {
            String value = Objects.requireNonNull(evaluation, "evaluation").get();
            if (cache && value != null) {
                rolls.put(name, value);
            }
            return value;
        } finally {
            evaluating.remove(evaluating.size() - 1);
        }
    }
}

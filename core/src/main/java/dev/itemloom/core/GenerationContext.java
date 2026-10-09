package dev.itemloom.core;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** One generation's state. Never shared between requests or kept in a compiled recipe. */
public final class GenerationContext {
    private final Map<String, String> rolls;
    private final Map<Key<?>, Object> attributes = new HashMap<>();
    private final Deque<String> resolving = new ArrayDeque<>();
    private final RandomGenerator random;

    /** Keys use identity: separate integrations cannot accidentally alias equal display names. */
    public static final class Key<T> {
        private final Class<T> type;

        public Key(Class<T> type) {
            this.type = Objects.requireNonNull(type);
        }
    }

    public GenerationContext(Map<String, String> savedRolls, RandomGenerator random) {
        this(savedRolls, random, false);
    }

    private GenerationContext(
            Map<String, String> savedRolls, RandomGenerator random, boolean share) {
        this.rolls =
                share
                        ? Objects.requireNonNull(savedRolls)
                        : new LinkedHashMap<>(Objects.requireNonNull(savedRolls));
        this.random = Objects.requireNonNull(random);
    }

    /** Explicit caller-owned cache. The caller must serialize access for the whole generation. */
    public static GenerationContext sharingRolls(
            Map<String, String> cache, RandomGenerator random) {
        return new GenerationContext(cache, random, true);
    }

    public RandomGenerator random() {
        return random;
    }

    public <T> void put(Key<T> key, T value) {
        attributes.put(key, key.type.cast(value));
    }

    public <T> T get(Key<T> key) {
        return key.type.cast(attributes.get(key));
    }

    /** Mutable request-local view for configuration languages with explicit cache mutation. */
    public Map<String, String> rolls() {
        return rolls;
    }

    public Map<String, String> savedRolls() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(rolls));
    }

    /** Resolve a named value once, with a useful dependency path for recursive definitions. */
    public String resolve(String name, Supplier<String> evaluation) {
        return resolve(name, evaluation, true);
    }

    /** Cycle detection also applies to languages that explicitly disable named-value caching. */
    public String resolve(String name, Supplier<String> evaluation, boolean cache) {
        if (rolls.containsKey(name)) return rolls.get(name);
        if (resolving.contains(name)) {
            throw new IllegalArgumentException(
                    "Cyclic value: " + String.join(" -> ", resolving) + " -> " + name);
        }
        resolving.addLast(name);
        try {
            String result = evaluation.get();
            if (cache && result != null) rolls.put(name, result);
            return result;
        } finally {
            resolving.removeLast();
        }
    }
}

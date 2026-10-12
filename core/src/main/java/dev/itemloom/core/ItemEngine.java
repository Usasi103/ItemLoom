package dev.itemloom.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ItemEngine<T> {
    public record Catalog<T>(long revision, Map<String, ItemRecipe<T>> recipes) {
        public Catalog {
            Map<String, ItemRecipe<T>> copy = new LinkedHashMap<>();
            Objects.requireNonNull(recipes, "recipes")
                    .forEach(
                            (id, recipe) -> {
                                if (id == null || id.isBlank()) {
                                    throw new IllegalArgumentException("Item id cannot be blank");
                                }
                                copy.put(id, Objects.requireNonNull(recipe, "recipe"));
                            });
            recipes = Collections.unmodifiableMap(copy);
        }
    }

    public record Generated<T>(String id, long revision, T item, Map<String, String> savedRolls) {
        public Generated {
            Objects.requireNonNull(item, "item");
            savedRolls =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(Objects.requireNonNull(savedRolls, "savedRolls")));
        }
    }

    private final AtomicReference<Catalog<T>> installed =
            new AtomicReference<>(new Catalog<>(0, Map.of()));

    public Catalog<T> catalog() {
        return installed.get();
    }

    public void install(Catalog<T> candidate) {
        Objects.requireNonNull(candidate, "candidate");
        while (true) {
            Catalog<T> previous = installed.get();
            if (candidate.revision() <= previous.revision()) {
                throw new IllegalArgumentException("Catalog revision must advance");
            }
            if (installed.compareAndSet(previous, candidate)) {
                return;
            }
        }
    }

    public Set<String> ids() {
        return catalog().recipes().keySet();
    }

    public Generated<T> generate(String id, GenerationContext context) {
        Catalog<T> captured = catalog();
        ItemRecipe<T> recipe = captured.recipes().get(id);
        if (recipe == null) {
            throw new IllegalArgumentException("Unknown item: " + id);
        }
        T item = recipe.create(Objects.requireNonNull(context, "context"));
        return new Generated<>(id, captured.revision(), item, context.savedRolls());
    }
}

package dev.itemloom.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** A platform-independent catalog. A generation observes exactly one installed revision. */
public final class ItemEngine<T> {
    public record Catalog<T>(long revision, Map<String, ItemRecipe<T>> recipes) {
        public Catalog {
            LinkedHashMap<String, ItemRecipe<T>> copy = new LinkedHashMap<>();
            recipes.forEach(
                    (id, recipe) -> {
                        if (id == null || id.isBlank())
                            throw new IllegalArgumentException("Blank item id");
                        copy.put(id, Objects.requireNonNull(recipe, id));
                    });
            recipes = Collections.unmodifiableMap(copy);
        }
    }

    public record Generated<T>(String id, long revision, T item, Map<String, String> savedRolls) {
        public Generated {
            Objects.requireNonNull(item, "Recipe returned no item");
            savedRolls = Collections.unmodifiableMap(new LinkedHashMap<>(savedRolls));
        }
    }

    private final AtomicReference<Catalog<T>> active =
            new AtomicReference<>(new Catalog<>(0, Map.of()));

    public Catalog<T> catalog() {
        return active.get();
    }

    /** Install only fully prepared candidates; failed configuration reads never reach this method. */
    public void install(Catalog<T> candidate) {
        Objects.requireNonNull(candidate);
        while (true) {
            Catalog<T> previous = active.get();
            if (candidate.revision() <= previous.revision()) {
                throw new IllegalArgumentException("Catalog revision must advance");
            }
            if (active.compareAndSet(previous, candidate)) return;
        }
    }

    public Set<String> ids() {
        return active.get().recipes().keySet();
    }

    public Generated<T> generate(String id, GenerationContext context) {
        Catalog<T> snapshot = active.get();
        ItemRecipe<T> recipe = snapshot.recipes().get(id);
        if (recipe == null) throw new IllegalArgumentException("Unknown item: " + id);
        T result = recipe.create(Objects.requireNonNull(context));
        return new Generated<>(id, snapshot.revision(), result, context.savedRolls());
    }
}

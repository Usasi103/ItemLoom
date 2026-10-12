package dev.itemloom.paper;

import dev.itemloom.api.ItemContext;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.api.ItemProvider;
import dev.itemloom.api.ProviderRegistration;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.core.ItemRecipe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Owns provider registrations while publishing only immutable registration maps to readers. */
final class ItemProviders {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern LOCAL_ID = Pattern.compile("[A-Za-z0-9_./-]+");
    private volatile Map<String, Registration> published = immutable(Map.of());
    private final Set<String> generating = new HashSet<>();
    private boolean closed;

    ProviderRegistration register(
            Plugin owner, String namespace, ItemProvider definition, Set<String> configured) {
        ItemsService.requireThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(definition, "definition");
        if (closed) {
            throw new IllegalStateException("Provider registry is closed");
        }
        if (!owner.isEnabled()) {
            throw new IllegalArgumentException("Provider owner must be enabled");
        }
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid provider namespace: " + namespace);
        }
        if (published.containsKey(namespace)) {
            throw new IllegalArgumentException(
                    "Provider namespace is already registered: " + namespace);
        }
        for (String id : definition.items().keySet()) {
            validateLocal(id);
            if (configured.contains(namespace + ":" + id)) {
                throw new IllegalArgumentException(
                        "Item conflicts with configured item: " + namespace + ":" + id);
            }
        }
        for (var group : definition.groups().entrySet()) {
            validateLocal(group.getKey());
            for (String member : group.getValue()) {
                if (!definition.items().containsKey(member)) {
                    throw new IllegalArgumentException(
                            "Unknown member " + member + " in group " + group.getKey());
                }
            }
        }
        Registration registration = new Registration(owner, namespace, definition);
        Map<String, Registration> next = new LinkedHashMap<>(published);
        next.put(namespace, registration);
        published = immutable(next);
        return registration;
    }

    Set<String> ids(boolean groups) {
        Map<String, Registration> captured = published;
        Set<String> result = new LinkedHashSet<>();
        captured.forEach(
                (namespace, registration) -> {
                    Set<String> local =
                            groups
                                    ? registration.definition.groups().keySet()
                                    : registration.definition.items().keySet();
                    for (String id : local) {
                        result.add(namespace + ":" + id);
                    }
                });
        return Set.copyOf(result);
    }

    void validate(Set<String> configured) {
        for (String id : ids(false)) {
            if (configured.contains(id)) {
                throw new IllegalArgumentException("Item conflicts with external provider: " + id);
            }
        }
    }

    boolean contains(String id) {
        Qualified name = Qualified.parse(id);
        if (name == null) {
            return false;
        }
        Registration registration = published.get(name.namespace());
        return registration != null && registration.definition.items().containsKey(name.local());
    }

    ItemStack create(String id, OfflinePlayer viewer, Map<String, String> parameters) {
        ItemsService.requireThread();
        Qualified name = Qualified.parse(id);
        Registration registration = name == null ? null : published.get(name.namespace());
        ItemRecipe<ItemStack> recipe =
                registration == null ? null : registration.definition.items().get(name.local());
        if (recipe == null) {
            throw new IllegalArgumentException("Unknown provider item: " + id);
        }
        registration.ensureActive();
        if (!generating.add(id)) {
            throw new IllegalArgumentException("Recursive provider item: " + id);
        }
        try {
            GenerationContext context =
                    new GenerationContext(
                            parameters == null ? Map.of() : parameters,
                            ThreadLocalRandom.current());
            context.put(ItemContext.VIEWER, viewer);
            ItemStack supplied =
                    Objects.requireNonNull(recipe.create(context), "Provider returned null item");
            registration.ensureActive();
            ItemGenerateEvent event =
                    new ItemGenerateEvent(id, viewer, context.rolls(), supplied.clone());
            Bukkit.getPluginManager().callEvent(event);
            registration.ensureActive();
            return event.getItem().clone();
        } finally {
            generating.remove(id);
        }
    }

    List<ItemStack> group(String id, OfflinePlayer viewer, Map<String, String> parameters) {
        ItemsService.requireThread();
        Qualified name = Qualified.parse(id);
        Registration registration = name == null ? null : published.get(name.namespace());
        List<String> members =
                registration == null ? null : registration.definition.groups().get(name.local());
        if (members == null) {
            throw new IllegalArgumentException("Unknown provider group: " + id);
        }
        registration.ensureActive();
        List<ItemStack> result = new ArrayList<>(members.size());
        for (String member : members) {
            registration.ensureActive();
            result.add(create(name.namespace() + ":" + member, viewer, parameters));
        }
        registration.ensureActive();
        return result;
    }

    void remove(Plugin owner) {
        ItemsService.requireThread();
        Map<String, Registration> next = new LinkedHashMap<>(published);
        if (next.values().removeIf(registration -> registration.owner == owner)) {
            published = immutable(next);
        }
    }

    void close() {
        ItemsService.requireThread();
        if (!closed) {
            closed = true;
            published = immutable(Map.of());
        }
    }

    Object revision() {
        return published;
    }

    private static void validateLocal(String id) {
        if (id == null || !LOCAL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid local provider id: " + id);
        }
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private record Qualified(String namespace, String local) {
        private static Qualified parse(String id) {
            if (id == null) {
                return null;
            }
            int separator = id.indexOf(':');
            return separator <= 0 || separator == id.length() - 1
                    ? null
                    : new Qualified(id.substring(0, separator), id.substring(separator + 1));
        }
    }

    private final class Registration implements ProviderRegistration {
        private final Plugin owner;
        private final String namespace;
        private final ItemProvider definition;

        private Registration(Plugin owner, String namespace, ItemProvider definition) {
            this.owner = owner;
            this.namespace = namespace;
            this.definition = definition;
        }

        private void ensureActive() {
            if (closed || published.get(namespace) != this || !owner.isEnabled()) {
                throw new IllegalStateException("Provider is no longer active: " + namespace);
            }
        }

        @Override
        public void close() {
            ItemsService.requireThread();
            if (published.get(namespace) == this) {
                Map<String, Registration> next = new LinkedHashMap<>(published);
                next.remove(namespace);
                published = immutable(next);
            }
        }
    }
}

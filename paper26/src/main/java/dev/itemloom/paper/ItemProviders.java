package dev.itemloom.paper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.itemloom.api.ItemContext;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.api.ItemProvider;
import dev.itemloom.api.ProviderRegistration;
import dev.itemloom.core.GenerationContext;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Immutable registration publications; live callbacks and ownership changes are main-thread only. */
final class ItemProviders {
    private volatile Map<String, Registration> providers = Map.of();
    private final Set<String> generating = new HashSet<>();

    ProviderRegistration register(
            Plugin owner, String namespace, ItemProvider provider, Set<String> configured) {
        ItemsService.requireThread();
        if (!owner.isEnabled()) throw new IllegalArgumentException("Provider owner is disabled");
        if (namespace == null || !namespace.matches("[a-z0-9_.-]+"))
            throw new IllegalArgumentException("Invalid provider namespace");
        if (providers.containsKey(namespace))
            throw new IllegalArgumentException(
                    "Provider namespace already registered: " + namespace);
        provider.items().keySet().forEach(ItemProviders::localId);
        provider.groups()
                .forEach(
                        (id, members) -> {
                            localId(id);
                            for (String member : members)
                                if (!provider.items().containsKey(member))
                                    throw new IllegalArgumentException(
                                            "Unknown group member: " + member);
                        });
        Registration registration = new Registration(owner, namespace, provider);
        for (String id : provider.items().keySet())
            if (configured.contains(namespace + ':' + id))
                throw new IllegalArgumentException(
                        "Configured item already exists: " + namespace + ':' + id);
        Map<String, Registration> next = new LinkedHashMap<>(providers);
        next.put(namespace, registration);
        providers = Map.copyOf(next);
        return registration;
    }

    Set<String> ids(boolean groups) {
        Set<String> result = new LinkedHashSet<>();
        providers.forEach(
                (namespace, provider) ->
                        (groups ? provider.definition.groups() : provider.definition.items())
                                .keySet()
                                .forEach(id -> result.add(namespace + ':' + id)));
        return Set.copyOf(result);
    }

    void validate(Set<String> configured) {
        for (String id : ids(false))
            if (configured.contains(id))
                throw new IllegalArgumentException("Item conflicts with external provider: " + id);
    }

    boolean contains(String id) {
        Registration registration = find(id);
        return registration != null && registration.definition.items().containsKey(local(id));
    }

    ItemStack create(String id, OfflinePlayer viewer, Map<String, String> parameters) {
        Registration registration = find(id);
        if (registration == null || !registration.definition.items().containsKey(local(id)))
            throw new IllegalArgumentException("Unknown provider item: " + id);
        return create(registration, id, viewer, parameters);
    }

    private ItemStack create(
            Registration registration,
            String id,
            OfflinePlayer viewer,
            Map<String, String> parameters) {
        registration.ensureActive();
        if (!generating.add(id))
            throw new IllegalStateException("Recursive provider generation: " + id);
        try {
            GenerationContext context =
                    new GenerationContext(
                            parameters == null ? Map.of() : parameters,
                            java.util.concurrent.ThreadLocalRandom.current());
            context.put(ItemContext.VIEWER, viewer);
            ItemStack result =
                    java.util.Objects.requireNonNull(
                                    registration.definition.items().get(local(id)).create(context),
                                    "Provider returned null")
                            .clone();
            registration.ensureActive();
            ItemGenerateEvent event = new ItemGenerateEvent(id, viewer, context.rolls(), result);
            Bukkit.getPluginManager().callEvent(event);
            registration.ensureActive();
            return event.getItem().clone();
        } finally {
            generating.remove(id);
        }
    }

    List<ItemStack> group(String id, OfflinePlayer viewer, Map<String, String> parameters) {
        Registration registration = find(id);
        List<String> members =
                registration == null ? null : registration.definition.groups().get(local(id));
        if (members == null) throw new IllegalArgumentException("Unknown provider group: " + id);
        List<ItemStack> result = new ArrayList<>();
        for (String member : members)
            result.add(
                    create(
                            registration,
                            registration.namespace + ':' + member,
                            viewer,
                            parameters));
        return result;
    }

    void remove(Plugin owner) {
        List.copyOf(providers.values()).stream()
                .filter(p -> p.owner == owner)
                .forEach(Registration::close);
    }

    void close() {
        providers = Map.of();
    }

    Object revision() {
        return providers;
    }

    private Registration find(String id) {
        int colon = id == null ? -1 : id.indexOf(':');
        return colon < 0 ? null : providers.get(id.substring(0, colon));
    }

    private static String local(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static void localId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_./-]+"))
            throw new IllegalArgumentException("Invalid local provider ID: " + id);
    }

    private final class Registration implements ProviderRegistration {
        final Plugin owner;
        final String namespace;
        final ItemProvider definition;

        Registration(Plugin owner, String namespace, ItemProvider definition) {
            this.owner = owner;
            this.namespace = namespace;
            this.definition = definition;
        }

        void ensureActive() {
            if (!owner.isEnabled() || providers.get(namespace) != this)
                throw new IllegalStateException(
                        "Provider registration is no longer active: " + namespace);
        }

        @Override
        public void close() {
            ItemsService.requireThread();
            if (providers.get(namespace) != this) return;
            Map<String, Registration> next = new LinkedHashMap<>(providers);
            next.remove(namespace);
            providers = Map.copyOf(next);
        }
    }
}

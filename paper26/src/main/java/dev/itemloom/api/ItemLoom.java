package dev.itemloom.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.core.ActionFlow;
import java.util.concurrent.CompletionStage;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Obtain through Bukkit's ServicesManager. Generation and migration run on the server thread. */
public interface ItemLoom {
    Set<String> ids();

    ItemStack create(String id, OfflinePlayer viewer, Map<String, String> savedRolls);

    Set<String> packIds();

    java.util.List<ItemStack> createPack(
            String id, OfflinePlayer viewer, Map<String, String> savedRolls);

    Optional<ItemIdentity> identify(ItemStack item);

    ItemStack migrate(ItemStack original);

    CompletionStage<ActionFlow.Result> runFunction(
            String id, Object caster, Map<String, Object> parameters);

    /** Register a snapshot of a provider. IDs are namespace:localId; duplicates fail without replacement. */
    ProviderRegistration registerProvider(Plugin owner, String namespace, ItemProvider provider);

    Set<String> groupIds();

    java.util.List<ItemStack> createGroup(
            String id, OfflinePlayer viewer, Map<String, String> savedRolls);

    /** Persist the current state, not its random/script recipe. Path is relative to Items/. */
    SaveResult save(ItemStack item, String id, String path, boolean replace);

    enum SaveResult {
        SUCCESS,
        AIR,
        CONFLICT
    }
}

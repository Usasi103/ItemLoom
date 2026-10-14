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

    /**
     * Keep-list entry that preserves the whole {@code PublicBukkitValues} compound of the original
     * item, the same effect as NI's {@code PublicBukkitValues} protect path.
     */
    String KEEP_ALL_PDC = "*";

    /**
     * Rebuilds an existing ItemLoom item with some of its roll values replaced. The rolls are the
     * identity's roll values (NI section names or native roll names). {@code overrides} sets the
     * value of each listed roll, adds rolls the item did not have, and removes a roll whose value
     * is {@code null} (the same rule as NI's rebuild). A null map changes nothing.
     *
     * <p>The replacement is generated from the item's definition with every current roll locked:
     * the unchanged rolls keep their values and the overridden rolls take their new values, so
     * unrelated random results do not change. The result then takes these values from the
     * original:
     * <ul>
     *   <li>the amount;
     *   <li>custom durability or charge (NI {@code durability}/{@code charge}), clamped to the
     *       new maximum, and dropped when the new definition has no maximum;
     *   <li>vanilla damage, so a damaged vanilla-durability item stays damaged; with custom
     *       durability the vanilla damage follows the custom value;
     *   <li>the enchantment and stored-enchantment sets, copied exactly, so an enchantment the
     *       new definition would add is not applied and an empty set stays empty;
     *   <li>the generator's protected NBT and components (its {@code options.update.protect} and
     *       {@code options.update.protect-components} lists, read whether or not update is
     *       enabled);
     *   <li>PDC entries named in {@code keepPdcKeys}. A key is the full {@code namespace:key}
     *       string; pass {@link #KEEP_ALL_PDC} to keep the whole PDC. A named key absent from the
     *       original is removed. Unlisted PDC entries come from the new generation.
     * </ul>
     *
     * <p>{@code ItemGenerateEvent} fires once during generation, before the preserved state is
     * applied. Listeners may edit lore, name and components; anything the list above copies from
     * the original is restored afterwards.
     *
     * <p>The original stack is never modified. Failures return an {@link ItemRebuildResult} with
     * a status and message instead of throwing; the only exception is the thread check below.
     *
     * @param item the item to rebuild; null or air returns
     *     {@link ItemRebuildResult.Status#EMPTY_ITEM}
     * @param viewer the player used for placeholders and events; may be null
     * @param overrides roll values to set, or null values to remove; null means no changes
     * @param keepPdcKeys PDC keys to copy from the original; null or empty means none
     * @throws IllegalStateException when called off the server thread
     */
    ItemRebuildResult rebuild(
            ItemStack item,
            OfflinePlayer viewer,
            Map<String, String> overrides,
            Set<String> keepPdcKeys);

    enum SaveResult {
        SUCCESS,
        AIR,
        CONFLICT
    }
}

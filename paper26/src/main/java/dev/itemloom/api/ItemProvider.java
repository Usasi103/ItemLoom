package dev.itemloom.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.itemloom.core.ItemRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * Independent generators and ordered groups of local IDs. Duplicate group entries roll separately.
 * Callbacks run on the main thread with a fresh GenerationContext; ItemContext.VIEWER holds the viewer.
 * Returned stacks are copied before dispatching ItemGenerateEvent. Providers own their item identity
 * and persistent data: returning vanilla or another plugin's item does not add a ItemLoom identity.
 */
public record ItemProvider(
        Map<String, ItemRecipe<ItemStack>> items, Map<String, List<String>> groups) {
    public ItemProvider {
        items = Map.copyOf(items);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        groups.forEach((id, members) -> copy.put(id, List.copyOf(members)));
        groups = Map.copyOf(copy);
    }

    public ItemProvider(Map<String, ItemRecipe<ItemStack>> items) {
        this(items, Map.of());
    }
}

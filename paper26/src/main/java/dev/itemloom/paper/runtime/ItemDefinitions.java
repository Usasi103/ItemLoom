package dev.itemloom.paper.runtime;

import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import java.util.Map;

public interface ItemDefinitions {
    ItemPublication publication();

    ItemStack create(String id, OfflinePlayer viewer, Map<String, String> rolls);

    /**
     * Applies this format's protected NBT and components to a freshly generated replacement,
     * using the original stack as the source of the protected values. Formats without protection
     * return the replacement unchanged.
     */
    default ItemStack protectRebuild(String id, ItemStack before, ItemStack generated) {
        return generated;
    }
}

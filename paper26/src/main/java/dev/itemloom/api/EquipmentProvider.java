package dev.itemloom.api;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Main-thread equipment view. Return only slots owned by this provider; never mutate input items. */
@FunctionalInterface
public interface EquipmentProvider {
    Map<String, ItemStack> equipment(Player viewer);

    /** All providers are consulted for every slot; any false result excludes the item. */
    default boolean eligible(Player viewer, String slot, ItemStack item) {
        return true;
    }
}

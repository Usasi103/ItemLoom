package dev.itemloom.paper.runtime;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface MaintenanceAccess {
    boolean active();

    MaintenanceOptions options();

    Integer updateHash(String id);

    void update(Player player, ItemStack item, boolean force, boolean sendMessage);

    String itemName(ItemStack item);

    boolean expirationAllowed(Player player, ItemStack item);
}

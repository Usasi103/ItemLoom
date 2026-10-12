package dev.itemloom.paper.runtime;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface DurabilityCallbacks {
    boolean active();

    void broken(Player player);

    void returnLater(Player player, ItemStack remainder);
}

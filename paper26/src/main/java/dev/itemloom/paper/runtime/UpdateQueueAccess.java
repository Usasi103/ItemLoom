package dev.itemloom.paper.runtime;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface UpdateQueueAccess {
    boolean hasUpdates();

    boolean update(Player viewer, ItemStack item);
}

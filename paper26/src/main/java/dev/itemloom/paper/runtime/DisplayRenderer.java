package dev.itemloom.paper.runtime;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

@FunctionalInterface
public interface DisplayRenderer {
    void apply(Player viewer, ItemStack detachedCopy);
}

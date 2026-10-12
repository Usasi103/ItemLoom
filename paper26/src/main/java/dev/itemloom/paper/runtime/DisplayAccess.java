package dev.itemloom.paper.runtime;

import org.bukkit.inventory.ItemStack;

public interface DisplayAccess {
    boolean active();

    DisplayPublication publication();

    boolean hasItemPlaceholders(net.minecraft.world.item.ItemStack item);

    void parseItemPlaceholders(ItemStack detachedCopy);
}

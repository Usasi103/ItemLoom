package dev.itemloom.paper.runtime;

import dev.itemloom.api.ItemLoom;
import org.bukkit.inventory.ItemStack;

public interface ItemPersistence {
    ItemLoom.SaveResult save(ItemStack item, String id, String path, boolean replace);

    boolean owns(ItemDefinitions format);
}

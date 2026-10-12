package dev.itemloom.paper.runtime;

import dev.itemloom.paper.display.ProofItemCopies;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

public final class ItemCopies {
    private ItemCopies() {}

    public static ItemStack detached(ItemStack source) {
        return CraftItemStack.asCraftMirror(ProofItemCopies.copy(CraftItemStack.unwrap(source)));
    }
}

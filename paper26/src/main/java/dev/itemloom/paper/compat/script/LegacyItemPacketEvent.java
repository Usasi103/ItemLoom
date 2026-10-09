package dev.itemloom.paper.compat.script;

import dev.itemloom.api.ItemDisplayEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Script alias only; the packet path dispatches the same independent event on the main thread. */
public final class LegacyItemPacketEvent extends ItemDisplayEvent {
    public LegacyItemPacketEvent(Player player, ItemStack itemStack) {
        super(player, itemStack, !Bukkit.isPrimaryThread());
    }

    public Player getPlayer() {
        return getViewer();
    }

    public ItemStack getItemStack() {
        return getItem();
    }

    @Override
    public String getEventName() {
        return "ItemPacketEvent";
    }

    public boolean call() {
        Bukkit.getPluginManager().callEvent(this);
        return true;
    }
}

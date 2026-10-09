package dev.itemloom.paper.compat.script;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** The NI script event contract, implemented entirely by ItemLoom. */
public final class LegacyItemExpirationEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final OfflinePlayer player;
    private final ItemStack itemStack;
    private final LegacyItemInfo itemInfo;
    private boolean cancelled;

    public LegacyItemExpirationEvent(
            OfflinePlayer player, ItemStack itemStack, LegacyItemInfo itemInfo) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.itemStack = Objects.requireNonNull(itemStack);
        this.itemInfo = Objects.requireNonNull(itemInfo);
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public LegacyItemInfo getItemInfo() {
        return itemInfo;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean value) {
        cancelled = value;
    }

    public boolean call() {
        Bukkit.getPluginManager().callEvent(this);
        return !cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

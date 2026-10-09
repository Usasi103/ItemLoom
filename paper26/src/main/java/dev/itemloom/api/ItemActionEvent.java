package dev.itemloom.api;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Synchronous trigger gate, fired after reserving the cooldown and before item consumption. */
public class ItemActionEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ItemStack itemStack;
    private final String itemId;
    private final String triggerKey;
    private boolean cancelled;

    public ItemActionEvent(Player player, ItemStack itemStack, String itemId, String type) {
        this(player, itemStack, itemId, type, false);
    }

    /** Allows language adapters to represent explicitly constructed asynchronous events. */
    protected ItemActionEvent(
            Player player, ItemStack itemStack, String itemId, String type, boolean asynchronous) {
        super(asynchronous);
        this.player = Objects.requireNonNull(player, "player");
        this.itemStack = Objects.requireNonNull(itemStack, "itemStack");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.triggerKey = Objects.requireNonNull(type, "type");
    }

    public Player getPlayer() {
        return player;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public String getItemId() {
        return itemId;
    }

    public String getTriggerKey() {
        return triggerKey;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

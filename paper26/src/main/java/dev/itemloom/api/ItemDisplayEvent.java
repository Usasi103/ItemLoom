package dev.itemloom.api;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Main-thread display hook for survival/adventure inventory packets. The item is a detached copy;
 * listeners must retain its type and count. Canonical inventory data is never modified by this hook.
 */
public class ItemDisplayEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player viewer;
    private final ItemStack item;

    public ItemDisplayEvent(Player viewer, ItemStack item) {
        this(viewer, item, false);
    }

    protected ItemDisplayEvent(Player viewer, ItemStack item, boolean asynchronous) {
        super(asynchronous);
        this.viewer = Objects.requireNonNull(viewer);
        this.item = Objects.requireNonNull(item);
    }

    public Player getViewer() {
        return viewer;
    }

    public ItemStack getItem() {
        return item;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

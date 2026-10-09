package dev.itemloom.api;

import java.util.Map;
import java.util.Objects;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Synchronous generation hook, independent of the format used to define the item. */
public class ItemGenerateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String id;
    private final OfflinePlayer viewer;
    private final Map<String, String> savedRolls;
    private ItemStack item;

    public ItemGenerateEvent(
            String id, OfflinePlayer viewer, Map<String, String> savedRolls, ItemStack item) {
        this(id, viewer, savedRolls, item, false);
    }

    /** Language adapters may represent explicitly constructed asynchronous events. */
    protected ItemGenerateEvent(
            String id,
            OfflinePlayer viewer,
            Map<String, String> savedRolls,
            ItemStack item,
            boolean asynchronous) {
        super(asynchronous);
        this.id = Objects.requireNonNull(id, "id");
        this.viewer = viewer;
        this.savedRolls = Objects.requireNonNull(savedRolls, "savedRolls");
        this.item = Objects.requireNonNull(item);
    }

    public String getId() {
        return id;
    }

    public OfflinePlayer getViewer() {
        return viewer;
    }

    public Map<String, String> getSavedRolls() {
        return savedRolls;
    }

    public ItemStack getItem() {
        return item;
    }

    public void setItem(ItemStack item) {
        this.item = Objects.requireNonNull(item);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

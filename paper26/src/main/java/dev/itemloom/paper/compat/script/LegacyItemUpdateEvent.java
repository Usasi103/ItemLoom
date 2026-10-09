package dev.itemloom.paper.compat.script;

import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Script event aliases with independent Java types and the historical two update gates. */
public final class LegacyItemUpdateEvent {
    private LegacyItemUpdateEvent() {}

    public static final class PreGenerate extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final OfflinePlayer player;
        private final ItemStack oldItem;
        private final Map<String, String> data;
        private final LegacyItemGenerator item;
        private boolean cancelled;

        public PreGenerate(
                OfflinePlayer player,
                ItemStack oldItem,
                Map<String, String> data,
                LegacyItemGenerator item) {
            super(!Bukkit.isPrimaryThread());
            this.player = player;
            this.oldItem = Objects.requireNonNull(oldItem);
            this.data = Objects.requireNonNull(data);
            this.item = Objects.requireNonNull(item);
        }

        public OfflinePlayer getPlayer() {
            return player;
        }

        public ItemStack getOldItem() {
            return oldItem;
        }

        public Map<String, String> getData() {
            return data;
        }

        public LegacyItemGenerator getItem() {
            return item;
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

    public static final class PostGenerate extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final OfflinePlayer player;
        private final ItemStack oldItem;
        private final ItemStack newItem;
        private boolean cancelled;

        public PostGenerate(OfflinePlayer player, ItemStack oldItem, ItemStack newItem) {
            super(!Bukkit.isPrimaryThread());
            this.player = player;
            this.oldItem = Objects.requireNonNull(oldItem);
            this.newItem = Objects.requireNonNull(newItem);
        }

        public OfflinePlayer getPlayer() {
            return player;
        }

        public ItemStack getOldItem() {
            return oldItem;
        }

        public ItemStack getNewItem() {
            return newItem;
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
}

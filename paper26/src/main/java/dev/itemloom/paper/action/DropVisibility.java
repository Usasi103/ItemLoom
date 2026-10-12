package dev.itemloom.paper.action;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.itemloom.paper.ItemsService;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class DropVisibility implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Map<UUID, Visibility> watched = new HashMap<>();
    private boolean closed;

    public DropVisibility(Plugin plugin) {
        this.plugin = plugin;
        ItemsService.requireThread();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void start() {
        ItemsService.requireThread();
        if (!closed)
            for (var world : Bukkit.getWorlds())
                for (Item item : world.getEntitiesByClass(Item.class)) refresh(item);
    }

    public void tick() {
        ItemsService.requireThread();
        if (closed) return;
        for (Visibility state : new ArrayList<>(watched.values())) {
            if (!state.item.isValid()) forget(state);
            else refresh(state.item);
        }
    }

    public void refresh(Item item) {
        ItemsService.requireThread();
        if (closed) return;
        String owner = DropOwnership.owner(item);
        var tags = item.getScoreboardTags();
        boolean hidden = owner != null && tags.contains("NI-Hide");
        boolean byDefault = item.isVisibleByDefault();
        Visibility state = watched.get(item.getUniqueId());
        if (state == null) {
            if (owner == null
                    && !tags.contains("NI-Hide")
                    && !tags.contains("ItemLoom")
                    && !tags.contains("NeigeItems")) return;
            state = new Visibility(item);
            watched.put(item.getUniqueId(), state);
        } else if (state.retiring
                || state.initialized
                        && Objects.equals(state.owner, owner)
                        && state.hidden == hidden
                        && state.byDefault == byDefault) return;
        state.owner = owner;
        state.hidden = hidden;
        state.byDefault = byDefault;
        state.initialized = true;
        for (Player player : new ArrayList<>(state.overrides.values())) {
            if (!hidden || player.getName().equals(owner) || !byDefault) release(state, player);
        }
        if (hidden && !state.retiring) {
            for (Player player : new ArrayList<>(item.getTrackedBy())) {
                if (!player.getName().equals(owner)) conceal(state, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void track(PlayerTrackEntityEvent event) {
        ItemsService.requireThread();
        if (!(event.getEntity() instanceof Item item)) return;
        refresh(item);
        Visibility state = watched.get(item.getUniqueId());
        if (state == null) return;
        if (state.retiring) {
            event.setCancelled(true);
            unpair(item, event.getPlayer());
        } else if (state.hidden && !event.getPlayer().getName().equals(state.owner)) {
            event.setCancelled(true);
            conceal(state, event.getPlayer());
        }
    }

    private void conceal(Visibility state, Player player) {
        if (state.retiring) {
            unpair(state.item, player);
            return;
        }
        if (state.item.isVisibleByDefault()
                && state.overrides.get(player.getUniqueId()) != player) {
            state.overrides.put(player.getUniqueId(), player);
            player.hideEntity(plugin, state.item);
        }
        unpair(state.item, player);
    }

    private static void unpair(Item item, Player player) {
        if (item instanceof CraftItem craft && player instanceof CraftPlayer viewer) {
            var tracked = craft.getHandle().moonrise$getTrackedEntity();
            if (tracked != null) tracked.removePlayer(viewer.getHandle());
        }
    }

    private void release(Visibility state, Player player) {
        if (state.overrides.get(player.getUniqueId()) != player) return;
        state.overrides.remove(player.getUniqueId());
        if (state.item.isVisibleByDefault()) player.showEntity(plugin, state.item);
    }

    private void forget(Visibility state) {
        if (state.retiring) return;
        state.retiring = true;
        for (Player player : new ArrayList<>(state.overrides.values())) release(state, player);
        watched.remove(state.item.getUniqueId(), state);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void added(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Item item) refresh(item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void removed(EntityRemoveFromWorldEvent event) {
        ItemsService.requireThread();
        Visibility state = watched.get(event.getEntity().getUniqueId());
        if (state != null) forget(state);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void changed(DropOwnership.Changed event) {
        refresh(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        ItemsService.requireThread();
        for (Visibility state : watched.values()) {
            UUID id = event.getPlayer().getUniqueId();
            if (state.overrides.get(id) == event.getPlayer()) state.overrides.remove(id);
        }
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        for (Visibility state : new ArrayList<>(watched.values())) forget(state);
    }

    private static final class Visibility {
        final Item item;
        final Map<UUID, Player> overrides = new HashMap<>();
        String owner;
        boolean hidden;
        boolean byDefault;
        boolean initialized;
        boolean retiring;

        Visibility(Item item) {
            this.item = item;
        }
    }
}

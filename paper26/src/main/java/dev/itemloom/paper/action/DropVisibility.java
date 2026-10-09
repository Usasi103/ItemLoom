package dev.itemloom.paper.action;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import dev.itemloom.paper.ItemsService;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Owner-only drops use Paper's tracking gate, not a metadata packet listener.
 * Only this plugin's hide overrides are removed; visibleByDefault is never changed.
 */
public final class DropVisibility implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Map<UUID, Watched> watched = new HashMap<>();
    private boolean closed;

    private static final class Watched {
        final Item item;
        final Map<UUID, Player> hidden = new HashMap<>();
        String owner;
        boolean hide, visibleByDefault, retiring;

        Watched(Item item) {
            this.item = item;
        }
    }

    public DropVisibility(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Called once after registration. Chunk loads and new entities are handled by events. */
    public void start() {
        ItemsService.requireThread();
        Bukkit.getWorlds()
                .forEach(world -> world.getEntitiesByClass(Item.class).forEach(this::refresh));
    }

    /** Checks only known drops, every ten ticks; raw scoreboard-tag changes have no Bukkit event. */
    public void tick() {
        ItemsService.requireThread();
        if (closed) return;
        for (Watched entry : List.copyOf(watched.values())) {
            if (!entry.item.isValid()) forget(entry);
            else refresh(entry.item);
        }
    }

    public void refresh(Item item) {
        ItemsService.requireThread();
        if (closed) return;
        String owner = DropOwnership.owner(item);
        var tags = item.getScoreboardTags();
        boolean hide = owner != null && tags.contains("NI-Hide");
        Watched entry = watched.get(item.getUniqueId());
        if (entry != null && entry.retiring) return;
        if (entry == null) {
            if (owner == null
                    && !tags.contains("NI-Hide")
                    && !tags.contains("ItemLoom")
                    && !tags.contains("NeigeItems")) return;
            entry = new Watched(item);
            // Publish before calling visibility APIs, which synchronously invoke plugin events.
            watched.put(item.getUniqueId(), entry);
        } else if (Objects.equals(entry.owner, owner)
                && entry.hide == hide
                && entry.visibleByDefault == item.isVisibleByDefault()) return;
        entry.owner = owner;
        entry.hide = hide;
        entry.visibleByDefault = item.isVisibleByDefault();
        for (Player player : List.copyOf(entry.hidden.values())) {
            if (!hide || player.getName().equals(owner) || !entry.visibleByDefault)
                release(entry, player);
        }
        // Existing viewers must lose tracking immediately after an owner/hide change. Future
        // viewers go through track(), so no drops × all-online-players sweep is needed.
        if (hide)
            for (Player player : item.getTrackedBy()) {
                if (!player.getName().equals(owner)) conceal(entry, player);
            }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void track(PlayerTrackEntityEvent event) {
        if (closed || !(event.getEntity() instanceof Item item)) return;
        refresh(item);
        Watched entry = watched.get(item.getUniqueId());
        if (entry != null && entry.retiring) {
            event.setCancelled(true);
            untrack(item, event.getPlayer());
            return;
        }
        if (entry == null || !entry.hide || event.getPlayer().getName().equals(entry.owner)) return;
        event.setCancelled(true);
        conceal(entry, event.getPlayer());
    }

    private void conceal(Watched entry, Player player) {
        if (entry.item.isVisibleByDefault()) {
            entry.hidden.put(player.getUniqueId(), player);
            player.hideEntity(plugin, entry.item);
        }
        // Paper 26.2 adds seenBy BEFORE the cancellable tracking event, and cancellation
        // alone leaves it there. Remove it as well, including entities hidden by default
        // which another plugin has explicitly shown. Never revoke that plugin's grant.
        untrack(entry.item, player);
    }

    private static void untrack(Item item, Player player) {
        var tracker = ((CraftEntity) item).getHandle().moonrise$getTrackedEntity();
        if (tracker != null && player instanceof CraftPlayer craft)
            tracker.removePlayer(craft.getHandle());
    }

    private void release(Watched entry, Player player) {
        if (entry.hidden.remove(player.getUniqueId()) == null) return;
        // Toggling visibleByDefault resets ALL overrides inside Paper. Calling showEntity
        // while false would create a new grant, so only undo hides while the default is true.
        if (entry.item.isVisibleByDefault()) player.showEntity(plugin, entry.item);
    }

    private void forget(Watched entry) {
        entry.retiring = true;
        for (Player player : List.copyOf(entry.hidden.values())) release(entry, player);
        watched.remove(entry.item.getUniqueId(), entry);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void added(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Item item) refresh(item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void removed(EntityRemoveFromWorldEvent event) {
        Watched entry = watched.get(event.getEntity().getUniqueId());
        if (entry != null) forget(entry);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void changed(DropOwnership.Changed event) {
        refresh(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        for (Watched entry : watched.values()) {
            // Identity matters: a late event from an old session must not affect a new one.
            UUID id = event.getPlayer().getUniqueId();
            if (entry.hidden.get(id) == event.getPlayer()) entry.hidden.remove(id);
        }
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        for (Watched entry : List.copyOf(watched.values())) forget(entry);
    }
}

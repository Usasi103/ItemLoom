package dev.itemloom.paper.sx;

import dev.itemloom.paper.runtime.UpdateQueueAccess;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/** Event-fed inventory work, bound to the revision capability captured for each batch. */
public final class SxMaintenance implements Listener {
    private final Supplier<UpdateQueueAccess> current;
    private final Logger logger;
    private final Set<UUID> pending = new LinkedHashSet<>();
    private int ticks;
    private boolean closed;
    private long lastWarning;
    private boolean warned;

    public SxMaintenance(Supplier<UpdateQueueAccess> current, Logger logger) {
        this.current = current;
        this.logger = logger;
    }

    public void refresh() {
        pending.clear();
        if (enabled()) {
            Bukkit.getOnlinePlayers().forEach(player -> pending.add(player.getUniqueId()));
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        enqueue(event.getPlayer());
    }

    @EventHandler
    public void held(PlayerItemHeldEvent event) {
        enqueue(event.getPlayer());
    }

    @EventHandler
    public void open(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            enqueue(player);
        }
    }

    private void enqueue(Player player) {
        if (enabled()) {
            pending.add(player.getUniqueId());
        }
    }

    private boolean enabled() {
        UpdateQueueAccess catalog = closed ? null : current.get();
        return catalog != null && catalog.hasUpdates();
    }

    public void tick() {
        UpdateQueueAccess owner = closed ? null : current.get();
        if (owner == null || !owner.hasUpdates()) {
            pending.clear();
            return;
        }
        if (++ticks < 20) {
            return;
        }
        ticks = 0;
        var batch = new ArrayList<>(pending);
        pending.clear();
        for (UUID id : batch) {
            if (current.get() != owner) {
                return;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            for (ItemStack item : player.getInventory().getContents()) {
                if (closed || current.get() != owner) {
                    return;
                }
                try {
                    owner.update(player, item);
                } catch (RuntimeException error) {
                    long now = System.nanoTime();
                    if (!warned || now - lastWarning >= 10_000_000_000L) {
                        logger.log(Level.WARNING, "SX automatic item update failed", error);
                        lastWarning = now;
                        warned = true;
                    }
                }
            }
        }
    }

    public void close() {
        closed = true;
        pending.clear();
        HandlerList.unregisterAll(this);
    }
}

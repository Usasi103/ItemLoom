package dev.itemloom.paper.sx;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** SX updates are queued by inventory activity, rather than polling every slot every tick. */
public final class SxMaintenance implements Listener {
    private final Supplier<SxCatalog> current;
    private final Logger logger;
    private final Set<UUID> pending = new HashSet<>();
    private int ticks;
    private long lastWarning;

    public SxMaintenance(Supplier<SxCatalog> current, Logger logger) {
        this.current = current;
        this.logger = logger;
    }

    private void queue(Player player) {
        SxCatalog catalog = current.get();
        if (catalog != null && catalog.hasUpdates()) pending.add(player.getUniqueId());
    }

    public void refresh() {
        pending.clear();
        Bukkit.getOnlinePlayers().forEach(this::queue);
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        queue(event.getPlayer());
    }

    @EventHandler
    public void held(PlayerItemHeldEvent event) {
        queue(event.getPlayer());
    }

    @EventHandler
    public void open(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) queue(player);
    }

    public void tick() {
        if (++ticks < 20) return;
        ticks = 0;
        SxCatalog catalog = current.get();
        if (catalog == null || !catalog.hasUpdates()) {
            pending.clear();
            return;
        }
        Set<UUID> batch = Set.copyOf(pending);
        pending.clear();
        for (UUID id : batch) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            for (var item : player.getInventory().getContents()) {
                if (current.get() != catalog) return;
                try {
                    catalog.update(player, item);
                } catch (RuntimeException error) {
                    long now = System.nanoTime();
                    if (now - lastWarning >= 10_000_000_000L) {
                        lastWarning = now;
                        logger.warning("SX item update failed: " + error.getMessage());
                    }
                }
            }
        }
    }

    public void close() {
        pending.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }
}

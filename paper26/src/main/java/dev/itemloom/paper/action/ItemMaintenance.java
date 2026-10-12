package dev.itemloom.paper.action;

import dev.itemloom.paper.runtime.DurabilityCallbacks;
import dev.itemloom.paper.runtime.ItemCopies;
import dev.itemloom.paper.runtime.MaintenanceAccess;
import dev.itemloom.paper.runtime.MaintenanceOptions;
import dev.itemloom.paper.runtime.ScalarItemState;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Revision-owned maintenance. Construction prepares resources; activation installs listeners. */
public final class ItemMaintenance implements AutoCloseable, Listener {
    private final MaintenanceAccess items;
    private final MaintenanceOptions options;
    private final ItemDurabilityService durability;
    private final JavaPlugin plugin;
    private final Clock clock;
    private final Map<UUID, Long> scanned = new HashMap<>();
    private boolean activated;
    private boolean closed;
    private long lastWarning;
    private boolean warned;
    private int suppressed;

    public ItemMaintenance(
            MaintenanceAccess items, DurabilityCallbacks callbacks, JavaPlugin plugin) {
        this(items, callbacks, plugin, Clock.systemUTC());
    }

    public ItemMaintenance(
            MaintenanceAccess items,
            DurabilityCallbacks callbacks,
            JavaPlugin plugin,
            Clock clock) {
        this.items = Objects.requireNonNull(items, "items");
        this.options = Objects.requireNonNull(items.options(), "options");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.durability = new ItemDurabilityService(callbacks, plugin);
    }

    public boolean active() {
        return !closed && items.active();
    }

    public ItemDurabilityService durability() {
        return durability;
    }

    public void activate() {
        if (activated || !active()) {
            return;
        }
        activated = true;
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            durability.activate();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public boolean check(Player player, ItemStack item) {
        return check(player, item, false);
    }

    public boolean check(Player player, ItemStack item, boolean synchronizeDurability) {
        if (!active()) {
            return false;
        }
        ScalarItemState.State state = ScalarItemState.read(item);
        if (state == null) {
            return true;
        }
        if (clock.millis() >= state.expiresAt()) {
            ItemStack before = ItemCopies.detached(item);
            boolean expires = items.expirationAllowed(player, item);
            if (!active()) {
                return false;
            }
            if (expires) {
                if (item.equals(before)) {
                    String name = items.itemName(item);
                    if (!active()) {
                        return false;
                    }
                    if (item.equals(before)) {
                        item.setAmount(0);
                        item.setType(Material.AIR);
                        String message = options.expirationMessage();
                        if (player != null && message != null && !message.isEmpty()) {
                            player.sendMessage(message.replace("{itemName}", name));
                        }
                    }
                }
                return active();
            }
            state = ScalarItemState.read(item);
            if (state == null) {
                return true;
            }
        }
        Integer hash = items.updateHash(state.id());
        if (hash != null && state.updateHash() != null && !hash.equals(state.updateHash())) {
            items.update(player, item, false, true);
            if (!active()) {
                return false;
            }
        }
        if (synchronizeDurability && options.forceDurabilitySync()) {
            durability.synchronize(item);
        }
        return active();
    }

    public void scan(Player player, Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize() && active(); slot++) {
            try {
                if (!check(player, inventory.getItem(slot))) {
                    return;
                }
            } catch (RuntimeException failure) {
                warn(failure);
            }
        }
    }

    public boolean checkInventory(Player player, Inventory inventory) {
        if (!active() || !options.checkOpenedInventory()) {
            return false;
        }
        long now = clock.millis();
        Long previous = scanned.get(player.getUniqueId());
        if (previous != null && now >= previous && now - previous < 1000) {
            return false;
        }
        scanned.put(player.getUniqueId(), now);
        scan(player, inventory);
        return active();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            checkInventory(player, event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        scanned.remove(event.getPlayer().getUniqueId());
    }

    private void warn(RuntimeException failure) {
        long now = clock.millis();
        if (!warned || now < lastWarning || now - lastWarning >= 10000) {
            String suffix =
                    suppressed == 0 ? "" : " (" + suppressed + " similar failures suppressed)";
            plugin.getLogger().warning("Item maintenance failed: " + failure + suffix);
            lastWarning = now;
            warned = true;
            suppressed = 0;
        } else {
            suppressed++;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        HandlerList.unregisterAll(this);
        durability.close();
        scanned.clear();
    }
}

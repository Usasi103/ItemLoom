package dev.itemloom.paper.action;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemExpirationEvent;
import dev.itemloom.paper.compat.script.LegacyItemInfo;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
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

/** Revision-owned maintenance. Construction never exposes a candidate catalog to live events. */
public final class ItemMaintenance implements Listener, AutoCloseable {
    private static final long WARNING_INTERVAL = 10_000_000_000L;
    private final NiCatalog catalog;
    private final JavaPlugin plugin;
    private final Clock clock;
    private final ItemDurabilityService durability;
    private final Map<UUID, Long> lastInventoryChecks = new HashMap<>();
    private final boolean checkInventory, forceSync;
    private final String expirationMessage;
    private boolean registered, closed;
    private long lastWarning = Long.MIN_VALUE, suppressedFailures;

    public ItemMaintenance(NiCatalog catalog, JavaPlugin plugin) {
        this(catalog, plugin, Clock.systemUTC());
    }

    public ItemMaintenance(NiCatalog catalog, JavaPlugin plugin, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog);
        this.plugin = Objects.requireNonNull(plugin);
        this.clock = Objects.requireNonNull(clock);
        checkInventory = catalog.input().settings().bool("ItemCheck.checkInventory", true);
        forceSync = catalog.input().settings().bool("ItemDurability.forceSync", false);
        expirationMessage = catalog.input().settings().string("Messages.itemExpirationMessage");
        durability = new ItemDurabilityService(catalog, plugin, this::active);
    }

    public boolean active() {
        return !closed && catalog.active();
    }

    public ItemDurabilityService durability() {
        return durability;
    }

    /** Call only after the new catalog has been installed successfully. */
    public void activate() {
        ItemsService.requireThread();
        if (!active()) throw new IllegalStateException("Cannot activate closed item maintenance");
        if (registered) return;
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            Bukkit.getPluginManager().registerEvents(durability, plugin);
            registered = true;
        } catch (RuntimeException error) {
            HandlerList.unregisterAll(this);
            HandlerList.unregisterAll(durability);
            throw error;
        }
    }

    /** Drop/open paths use this overload; only the inventory ticker requests forceSync. */
    public boolean check(Player player, ItemStack item) {
        return check(player, item, false);
    }

    /** Returns false when a callback closed this revision, so a caller can stop its old batch. */
    public boolean check(Player player, ItemStack item, boolean synchronizeDurability) {
        ItemsService.requireThread();
        if (!active()) return false;
        Snapshot state = inspect(item);
        if (state == null) return true;
        if (clock.millis() >= state.expiration) {
            ItemStack before = item.clone();
            LegacyItemInfo info = LegacyItemInfo.inspect(item);
            if (info == null) return active();
            boolean expire = new LegacyItemExpirationEvent(player, item, info).call();
            if (!active()) return false;
            if (expire) {
                // A listener may replace/edit the item; never delete a now-unrelated stack.
                if (!item.equals(before)) return true;
                String name = catalog.itemName(item);
                item.setAmount(0);
                if (player != null && expirationMessage != null && !expirationMessage.isEmpty())
                    player.sendMessage(expirationMessage.replace("{itemName}", name));
                return true;
            }
            // A cancelled expiration still permits update, using the listener's current item.
            state = inspect(item);
            if (state == null) return true;
        }
        Integer currentHash = catalog.registry().updateHash(state.id);
        if (currentHash != null && state.hash != null && !currentHash.equals(state.hash)) {
            catalog.items().update(player, item, false, true);
            if (!active()) return false;
        }
        if (synchronizeDurability && forceSync) durability.synchronize(item);
        return active();
    }

    /** Full opened-container scan; an invalid slot cannot prevent maintenance of later slots. */
    public void scan(Player player, Inventory inventory) {
        ItemsService.requireThread();
        for (int slot = 0; active() && slot < inventory.getSize(); slot++) {
            try {
                if (!check(player, inventory.getItem(slot))) return;
            } catch (RuntimeException error) {
                warn(player, slot, error);
            }
        }
    }

    public boolean checkInventory(Player player, Inventory inventory) {
        ItemsService.requireThread();
        if (!active() || !checkInventory) return false;
        long now = clock.millis();
        Long last = lastInventoryChecks.get(player.getUniqueId());
        if (last != null && now >= last && now - last < 1000) return false;
        lastInventoryChecks.put(player.getUniqueId(), now);
        scan(player, inventory);
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player)
            checkInventory(player, event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        lastInventoryChecks.remove(event.getPlayer().getUniqueId());
    }

    /** Only scalar values leave this method. getUnsafe's immutable component tree is never edited. */
    private static Snapshot inspect(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        var data = CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag custom = data.getUnsafe();
        CompoundTag properties;
        String id;
        Tag raw = custom.get(ItemStateCodec.KEY);
        if (raw != null) {
            if (!(raw instanceof CompoundTag state) || state.getInt("schema").orElse(-1) != 1)
                throw new IllegalArgumentException("Malformed or unsupported item state envelope");
            id = state.getString("id").orElse(null);
            properties = state.getCompound("properties").orElse(null);
            if (id == null || properties == null)
                throw new IllegalArgumentException("Incomplete item state envelope");
        } else {
            properties = custom.getCompound("NeigeItems").orElse(null);
            if (properties == null) return null;
            id = properties.getString("id").orElse(null);
            if (id == null) return null;
        }
        return new Snapshot(
                id,
                properties.getInt("hashCode").orElse(null),
                properties.getLong("itemTime").orElse(Long.MAX_VALUE));
    }

    private record Snapshot(String id, Integer hash, long expiration) {}

    private void warn(Player player, int slot, RuntimeException error) {
        long now = System.nanoTime();
        if (lastWarning != Long.MIN_VALUE && now - lastWarning < WARNING_INTERVAL) {
            suppressedFailures++;
            return;
        }
        String extra =
                suppressedFailures == 0
                        ? ""
                        : " (" + suppressedFailures + " intervening failures suppressed)";
        suppressedFailures = 0;
        lastWarning = now;
        plugin.getLogger()
                .log(
                        Level.WARNING,
                        "Item maintenance failed for player "
                                + player.getUniqueId()
                                + " at slot "
                                + slot
                                + extra,
                        error);
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(durability);
        durability.close();
        lastInventoryChecks.clear();
    }
}

package dev.itemloom.paper.compare;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Everything that can change what a player has equipped queues a snapshot rebuild for the next
 * tick (after the change has been applied); the rebuild resends the inventory only when the
 * snapshot really changed, so cancelled or unrelated events cost one cheap rebuild and nothing
 * else. MONITOR priority, cancelled events included: a cancelled click may still have been
 * rewritten by another plugin.
 */
public final class ComparisonListener implements Listener {
    private final EquipmentComparison snapshots;

    public ComparisonListener(EquipmentComparison snapshots) {
        this.snapshots = snapshots;
    }

    /** First armor index in {@link org.bukkit.inventory.PlayerInventory} (36-39 armor, 40 off hand). */
    private static final int FIRST_EQUIPMENT_SLOT = 36;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        snapshots.schedule(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        snapshots.schedule(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        snapshots.schedule(event.getPlayer());
    }

    /** Only the equipment slots and the selected hotbar slot matter. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        Player player = event.getPlayer();
        int slot = event.getSlot();
        if (slot >= FIRST_EQUIPMENT_SLOT || slot == player.getInventory().getHeldItemSlot()) {
            snapshots.schedule(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        snapshots.schedule(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            snapshots.schedule(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        schedule(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent event) {
        schedule(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        schedule(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        snapshots.schedule(event.getPlayer());
    }

    private void schedule(HumanEntity entity) {
        if (entity instanceof Player player) {
            snapshots.schedule(player);
        }
    }
}

package dev.itemloom.paper.runtime;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/** Installed once; every event captures the currently published event capability. */
public final class RuntimeEventRouter implements Listener {
    private final Supplier<RuntimeEvents> current;

    public RuntimeEventRouter(Supplier<RuntimeEvents> current) {
        this.current = current;
    }

    private void dispatch(Consumer<RuntimeEvents> event) {
        RuntimeEvents captured = current.get();
        if (captured != null) {
            event.accept(captured);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void interact(PlayerInteractEvent event) {
        dispatch(actions -> actions.interact(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        dispatch(actions -> actions.consume(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        dispatch(actions -> actions.drop(event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        dispatch(actions -> actions.pickup(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        dispatch(actions -> actions.click(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent event) {
        dispatch(actions -> actions.shoot(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        dispatch(actions -> actions.attack(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damaged(EntityDamageEvent event) {
        dispatch(actions -> actions.damaged(event));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void killed(EntityDamageByEntityEvent event) {
        dispatch(actions -> actions.killed(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        dispatch(actions -> actions.broken(event));
    }
}

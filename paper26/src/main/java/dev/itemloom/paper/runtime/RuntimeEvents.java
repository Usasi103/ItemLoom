package dev.itemloom.paper.runtime;

import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

public interface RuntimeEvents {
    void interact(PlayerInteractEvent event);

    void consume(PlayerItemConsumeEvent event);

    void drop(PlayerDropItemEvent event);

    void pickup(EntityPickupItemEvent event);

    void click(InventoryClickEvent event);

    void shoot(EntityShootBowEvent event);

    void attack(EntityDamageByEntityEvent event);

    void damaged(EntityDamageEvent event);

    void killed(EntityDamageByEntityEvent event);

    void broken(BlockBreakEvent event);

    void tick();

    void flushReturns(Player player);
}

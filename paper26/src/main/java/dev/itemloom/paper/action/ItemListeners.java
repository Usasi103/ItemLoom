package dev.itemloom.paper.action;

import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class ItemListeners implements Listener {
    private static final List<EquipmentSlot> EQUIPMENT =
            List.of(
                    EquipmentSlot.HAND,
                    EquipmentSlot.OFF_HAND,
                    EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS,
                    EquipmentSlot.FEET);
    private final Supplier<ItemTriggers> current;
    private final Supplier<ItemMaintenance> maintenance;
    private final Logger logger;
    private final Predicate<PlayerInteractEvent> bags;
    private long lastWarning;
    private long suppressed;

    public ItemListeners(Supplier<ItemTriggers> current, Logger logger) {
        this(current, () -> null, logger, event -> false);
    }

    public ItemListeners(
            Supplier<ItemTriggers> current, Supplier<ItemMaintenance> maintenance, Logger logger) {
        this(current, maintenance, logger, event -> false);
    }

    public ItemListeners(
            Supplier<ItemTriggers> current,
            Supplier<ItemMaintenance> maintenance,
            Logger logger,
            Predicate<PlayerInteractEvent> bags) {
        this.current = current;
        this.maintenance = maintenance;
        this.logger = logger;
        this.bags = bags;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) return;
        ItemTriggers triggers = current.get();
        ItemMaintenance upkeep = maintenance.get();
        if (triggers == null
                || !usable(triggers, upkeep, event.getPlayer(), event.getItem(), event)) return;
        if (bags.test(event)) return;
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        triggers.interact(
                event.getPlayer(),
                event.getItem(),
                event,
                TriggerSource.equipment(event.getPlayer(), hand));
        if (current.get() == triggers && !event.isCancelled() && upkeep != null)
            upkeep.durability().igniteTnt(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers != null
                && usable(triggers, maintenance.get(), event.getPlayer(), event.getItem(), event))
            triggers.eat(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null) return;
        Player player = event.getPlayer();
        ItemMaintenance upkeep = maintenance.get();
        if (upkeep != null) {
            TriggerSource initial = TriggerSource.dropped(player, event.getItemDrop());
            if (!initial.accepts(event.getItemDrop().getItemStack())) return;
            ItemStack candidate = initial.candidate();
            if (!upkeep.check(player, candidate)
                    || current.get() != triggers
                    || !initial.unchanged()) return;
            ItemStack actual = initial.commit(candidate);
            initial.synchronizeEntity(actual);
            if (initial.entityEmpty()) {
                event.setCancelled(true);
                return;
            }
        }
        TriggerSource source = TriggerSource.dropped(player, event.getItemDrop());
        triggers.handle(
                "drop",
                player,
                event.getItemDrop().getItemStack(),
                event,
                false,
                true,
                true,
                source);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemTriggers triggers = current.get();
        if (triggers != null)
            triggers.handle(
                    "pick",
                    player,
                    event.getItem().getItemStack(),
                    event,
                    false,
                    true,
                    false,
                    TriggerSource.dropped(player, event.getItem()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemTriggers triggers = current.get();
        if (triggers == null) return;
        triggers.handle(
                "click",
                player,
                event.getCursor(),
                event,
                true,
                true,
                true,
                TriggerSource.cursor(player, event.getView()));
        if (!event.isCancelled() && current.get() == triggers) {
            TriggerSource clicked =
                    TriggerSource.clicked(player, event.getView(), event.getRawSlot());
            if (clicked != null)
                triggers.handle(
                        "beclicked",
                        player,
                        event.getCurrentItem(),
                        event,
                        true,
                        true,
                        true,
                        clicked);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemTriggers triggers = current.get();
        ItemMaintenance upkeep = maintenance.get();
        if (triggers == null) return;
        if (usable(triggers, upkeep, player, event.getBow(), event)) {
            EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
            triggers.handle(
                    "shoot_bow",
                    player,
                    event.getBow(),
                    event,
                    false,
                    true,
                    false,
                    TriggerSource.equipment(player, hand));
        }
        if (!event.isCancelled()
                && current.get() == triggers
                && usable(triggers, upkeep, player, event.getConsumable(), event)) {
            triggers.handle(
                    "shoot_arrow",
                    player,
                    event.getConsumable(),
                    event,
                    false,
                    true,
                    false,
                    TriggerSource.eventStack(player, event::getConsumable));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player player)) return;
        ItemTriggers triggers = current.get();
        if (triggers == null) return;
        ItemStack main = player.getInventory().getItemInMainHand();
        if (triggers.itemId(main) == null
                || !usable(triggers, maintenance.get(), player, main, event)) return;
        for (EquipmentSlot slot : EQUIPMENT)
            equipment(
                    triggers,
                    slot == EquipmentSlot.HAND ? "damage" : "damage_" + suffix(slot),
                    player,
                    slot,
                    event,
                    true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemTriggers triggers = current.get();
        if (triggers == null) return;
        for (EquipmentSlot slot : EQUIPMENT)
            equipment(triggers, "damaged_" + suffix(slot), player, slot, event, true);
        if (!event.isCancelled()
                && event instanceof EntityDamageByEntityEvent
                && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) != 0) {
            EquipmentSlot hand =
                    player.getInventory().getItemInMainHand().getType() == Material.SHIELD
                            ? EquipmentSlot.HAND
                            : EquipmentSlot.OFF_HAND;
            ItemStack shield = player.getInventory().getItem(hand);
            if (shield.getType() == Material.SHIELD
                    && usable(triggers, maintenance.get(), player, shield, event)) {
                equipment(triggers, "blocking", player, hand, event, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void killed(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)
                || event.getFinalDamage() < victim.getHealth()) return;
        Player player = null;
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamager() instanceof Player attacker) player = attacker;
        else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) player = shooter;
        ItemTriggers triggers = current.get();
        if (player == null || triggers == null) return;
        for (EquipmentSlot slot : EQUIPMENT)
            equipment(triggers, "kill_" + suffix(slot), player, slot, event, false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (usable(triggers, maintenance.get(), player, item, event))
            triggers.handle(
                    "break_block",
                    player,
                    item,
                    event,
                    false,
                    true,
                    true,
                    TriggerSource.equipment(player, EquipmentSlot.HAND));
    }

    private static void equipment(
            ItemTriggers triggers,
            String type,
            Player player,
            EquipmentSlot slot,
            Event event,
            boolean consume) {
        TriggerSource source = TriggerSource.equipment(player, slot);
        triggers.handle(type, player, source.original, event, false, false, consume, source);
    }

    private boolean usable(
            ItemTriggers triggers,
            ItemMaintenance upkeep,
            Player player,
            ItemStack item,
            Cancellable event) {
        if (upkeep != null) return upkeep.durability().usable(player, item, event);
        if (item == null || item.isEmpty()) return false;
        CompoundTag custom = NmsItems.customData(item);
        CompoundTag envelope = custom.getCompound(ItemStateCodec.KEY).orElse(null);
        CompoundTag properties;
        if (envelope == null || envelope.isEmpty()) {
            properties = custom.getCompound("NeigeItems").orElse(null);
            if (properties == null || !properties.contains("id")) return true;
        } else properties = envelope.getCompound("properties").orElseGet(CompoundTag::new);
        if (properties.getInt("durability").orElse(-1) != 0) return true;
        event.setCancelled(true);
        triggers.broken(player);
        return false;
    }

    public void tick() {
        tick(Bukkit.getOnlinePlayers());
    }

    public void tick(Iterable<? extends Player> players) {
        ItemsService.requireThread();
        ItemTriggers triggers = current.get();
        ItemMaintenance upkeep = maintenance.get();
        if (triggers == null || upkeep == null && !triggers.hasKeyPrefix("tick_")) return;
        for (Player player : players) {
            for (int slot = 0; slot <= 40; slot++) {
                try {
                    if (current.get() != triggers || upkeep != null && !upkeep.active()) return;
                    ItemStack item = player.getInventory().getItem(slot);
                    if (item == null || item.isEmpty()) continue;
                    if (upkeep != null) {
                        if (!upkeep.check(player, item, true)) return;
                        if (current.get() != triggers || !upkeep.active()) return;
                        item = player.getInventory().getItem(slot);
                        if (item == null || item.isEmpty()) continue;
                    }
                    if (triggers.hasTickSlot(slot)) triggers.tick("tick_" + slot, player, item);
                    String equipment = tickEquipment(player, slot);
                    if (equipment != null && triggers.hasTickEquipment(equipment))
                        triggers.tick(equipment, player, item);
                } catch (RuntimeException failure) {
                    report(failure);
                }
            }
        }
    }

    private static String tickEquipment(Player player, int slot) {
        if (slot == player.getInventory().getHeldItemSlot()) return "tick_hand";
        return switch (slot) {
            case 40 -> "tick_offhand";
            case 39 -> "tick_head";
            case 38 -> "tick_chest";
            case 37 -> "tick_legs";
            case 36 -> "tick_feet";
            default -> null;
        };
    }

    private static String suffix(EquipmentSlot slot) {
        return slot == EquipmentSlot.OFF_HAND
                ? "offhand"
                : slot.name().toLowerCase(java.util.Locale.ROOT);
    }

    private void report(RuntimeException failure) {
        long now = System.nanoTime();
        if (lastWarning != 0 && now - lastWarning < 10_000_000_000L) {
            suppressed++;
            return;
        }
        logger.log(Level.WARNING, "Item inventory tick failed; suppressed=" + suppressed, failure);
        lastWarning = now;
        suppressed = 0;
    }
}

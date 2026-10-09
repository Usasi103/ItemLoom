package dev.itemloom.paper.action;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Reads and commits real inventory handles only on the Paper server thread. */
public final class ItemListeners implements Listener {
    private static final EquipmentSlot[] EQUIPMENT = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    };
    private static final String[] SUFFIX = {"hand", "offhand", "head", "chest", "legs", "feet"};
    private static final String[] SLOT_KEYS =
            java.util.stream.IntStream.rangeClosed(0, 40)
                    .mapToObj(slot -> "tick_" + slot)
                    .toArray(String[]::new);
    private static final long TICK_WARNING_INTERVAL = 10_000_000_000L;
    private final Supplier<ItemTriggers> current;
    private final Supplier<ItemMaintenance> maintenance;
    private final Logger logger;
    private final java.util.function.Predicate<PlayerInteractEvent> bags;
    private long lastTickWarning = Long.MIN_VALUE;
    private long suppressedTickFailures;

    public ItemListeners(Supplier<ItemTriggers> current, Logger logger) {
        this(current, () -> null, logger);
    }

    public ItemListeners(
            Supplier<ItemTriggers> current, Supplier<ItemMaintenance> maintenance, Logger logger) {
        this(current, maintenance, logger, event -> false);
    }

    public ItemListeners(
            Supplier<ItemTriggers> current,
            Supplier<ItemMaintenance> maintenance,
            Logger logger,
            java.util.function.Predicate<PlayerInteractEvent> bags) {
        this.current = Objects.requireNonNull(current, "current");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.bags = Objects.requireNonNull(bags, "bags");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) return;
        ItemTriggers triggers = current.get();
        if (triggers != null && usable(triggers, event.getItem(), event.getPlayer(), event)) {
            if (bags.test(event)) return;
            triggers.interact(
                    event.getPlayer(),
                    event.getItem(),
                    event,
                    TriggerSource.equipment(
                            event.getPlayer(),
                            event.getHand() == null ? EquipmentSlot.HAND : event.getHand()));
            ItemMaintenance upkeep = maintenance.get();
            if (current.get() == triggers && upkeep != null && !event.isCancelled())
                upkeep.durability().igniteTnt(event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null) return;
        ItemStack item = event.getItem();
        if (!usable(triggers, item, event.getPlayer(), event)) return;
        triggers.eat(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null) return;
        ItemMaintenance upkeep = maintenance.get();
        if (upkeep != null) {
            TriggerSource maintained =
                    TriggerSource.dropped(event.getPlayer(), event.getItemDrop());
            if (!maintained.accepts(maintained.original)) return;
            ItemStack candidate = maintained.candidate();
            if (!upkeep.check(event.getPlayer(), candidate)
                    || current.get() != triggers
                    || !maintained.unchanged()) return;
            ItemStack actual = maintained.commit(candidate);
            maintained.synchronizeEntity(actual);
            if (maintained.entityEmpty()) {
                event.setCancelled(true);
                return;
            }
        }
        TriggerSource source = TriggerSource.dropped(event.getPlayer(), event.getItemDrop());
        if (source.entityEmpty()) {
            event.getItemDrop().remove();
            event.setCancelled(true);
            return;
        }
        triggers.handle(
                "drop", event.getPlayer(), source.original, event, false, true, true, source);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null || !(event.getEntity() instanceof Player player)) return;
        TriggerSource source = TriggerSource.dropped(player, event.getItem());
        triggers.handle("pick", player, source.original, event, false, true, false, source);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null || !(event.getWhoClicked() instanceof Player player)) return;
        TriggerSource cursor = TriggerSource.cursor(player, event.getView());
        triggers.handle("click", player, cursor.original, event, true, true, true, cursor);
        if (!event.isCancelled() && current.get() == triggers) {
            TriggerSource clicked =
                    TriggerSource.clicked(player, event.getView(), event.getRawSlot());
            if (clicked != null)
                triggers.handle(
                        "beclicked", player, clicked.original, event, true, true, true, clicked);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null || !(event.getEntity() instanceof Player player)) return;
        if (usable(triggers, event.getBow(), player, event))
            triggers.handle(
                    "shoot_bow",
                    player,
                    event.getBow(),
                    event,
                    false,
                    true,
                    false,
                    TriggerSource.equipment(player, event.getHand()));
        if (!event.isCancelled()
                && current.get() == triggers
                && usable(triggers, event.getConsumable(), player, event))
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player player)) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (triggers.itemId(hand) == null || !usable(triggers, hand, player, event)) return;
        for (int i = 0; i < EQUIPMENT.length; i++) {
            String type = i == 0 ? "damage" : "damage_" + SUFFIX[i];
            TriggerSource source = TriggerSource.equipment(player, EQUIPMENT[i]);
            triggers.handle(type, player, source.original, event, false, false, true, source);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damaged(EntityDamageEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null || !(event.getEntity() instanceof Player player)) return;
        for (int i = 0; i < EQUIPMENT.length; i++) {
            TriggerSource source = TriggerSource.equipment(player, EQUIPMENT[i]);
            triggers.handle(
                    "damaged_" + SUFFIX[i],
                    player,
                    source.original,
                    event,
                    false,
                    false,
                    true,
                    source);
        }
        if (!event.isCancelled()
                && event instanceof EntityDamageByEntityEvent
                && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) != 0) {
            TriggerSource source = TriggerSource.equipment(player, EquipmentSlot.HAND);
            if (source.original == null || source.original.getType() != Material.SHIELD)
                source = TriggerSource.equipment(player, EquipmentSlot.OFF_HAND);
            ItemStack shield = source.original;
            if (shield != null
                    && shield.getType() == Material.SHIELD
                    && usable(triggers, shield, player, event))
                triggers.handle("blocking", player, shield, event, false, false, true, source);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void killed(EntityDamageByEntityEvent event) {
        ItemTriggers triggers = current.get();
        if (triggers == null
                || !(event.getEntity() instanceof LivingEntity victim)
                || event.getFinalDamage() < victim.getHealth()) return;
        Object attacker =
                event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                                && event.getDamager() instanceof Projectile projectile
                        ? projectile.getShooter()
                        : event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                                ? event.getDamager()
                                : null;
        if (!(attacker instanceof Player player)) return;
        for (int i = 0; i < EQUIPMENT.length; i++) {
            TriggerSource source = TriggerSource.equipment(player, EQUIPMENT[i]);
            triggers.handle(
                    "kill_" + SUFFIX[i],
                    player,
                    source.original,
                    event,
                    false,
                    false,
                    false,
                    source);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        ItemTriggers triggers = current.get();
        TriggerSource source = TriggerSource.equipment(event.getPlayer(), EquipmentSlot.HAND);
        if (triggers != null && usable(triggers, source.original, event.getPlayer(), event))
            triggers.handle(
                    "break_block",
                    event.getPlayer(),
                    source.original,
                    event,
                    false,
                    true,
                    true,
                    source);
    }

    public void tick() {
        tick(org.bukkit.Bukkit.getOnlinePlayers());
    }

    /** Scans a supplied player batch; one malformed slot cannot starve later slots or players. */
    public void tick(Iterable<? extends Player> players) {
        if (!org.bukkit.Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item scan requires the server thread");
        ItemTriggers triggers = current.get();
        ItemMaintenance upkeep = maintenance.get();
        if (triggers == null) return;
        boolean hasTick = triggers.hasKeyPrefix("tick_");
        if (upkeep == null && !hasTick) return;
        for (Player player : players) {
            for (int slot = 0; slot <= 40; slot++) {
                try {
                    if (current.get() != triggers || (upkeep != null && !upkeep.active())) return;
                    ItemStack item = player.getInventory().getItem(slot);
                    if (item == null || item.isEmpty()) continue;
                    if (upkeep != null && !upkeep.check(player, item, true)) return;
                    if (current.get() != triggers) return;
                    if (!hasTick) continue;
                    if (upkeep != null) item = player.getInventory().getItem(slot);
                    String key = SLOT_KEYS[slot];
                    String equipment =
                            slot == player.getInventory().getHeldItemSlot()
                                    ? "tick_hand"
                                    : switch (slot) {
                                        case 40 -> "tick_offhand";
                                        case 39 -> "tick_head";
                                        case 38 -> "tick_chest";
                                        case 37 -> "tick_legs";
                                        case 36 -> "tick_feet";
                                        default -> null;
                                    };
                    boolean numericTick = triggers.hasTickSlot(slot),
                            equipmentTick = triggers.hasTickEquipment(equipment);
                    if (numericTick) triggers.tick(key, player, item);
                    if (equipmentTick) triggers.tick(equipment, player, item);
                } catch (RuntimeException error) {
                    warnTickFailure(player, slot, error);
                }
            }
        }
    }

    private void warnTickFailure(Player player, int slot, RuntimeException error) {
        long now = System.nanoTime();
        if (lastTickWarning != Long.MIN_VALUE && now - lastTickWarning < TICK_WARNING_INTERVAL) {
            suppressedTickFailures++;
            return;
        }
        lastTickWarning = now;
        String suppressed =
                suppressedTickFailures == 0
                        ? ""
                        : " (" + suppressedTickFailures + " intervening failures suppressed)";
        suppressedTickFailures = 0;
        logger.log(
                Level.WARNING,
                "ItemActions tick failed for player "
                        + player.getUniqueId()
                        + " at slot "
                        + slot
                        + suppressed,
                error);
    }

    private boolean usable(
            ItemTriggers triggers,
            ItemStack item,
            Player player,
            org.bukkit.event.Cancellable event) {
        ItemMaintenance upkeep = maintenance.get();
        if (upkeep != null) return upkeep.durability().usable(player, item, event);
        if (item == null || item.isEmpty()) return false;
        var custom = NmsItems.customData(item);
        var state = custom.getCompoundOrEmpty(ItemStateCodec.KEY);
        var properties =
                state.isEmpty()
                        ? custom.getCompoundOrEmpty("NeigeItems")
                        : state.getCompoundOrEmpty("properties");
        if (state.isEmpty() && !properties.contains("id")) return true;
        if (properties.getInt("durability").orElse(-1) != 0) return true;
        event.setCancelled(true);
        triggers.broken(player);
        return false;
    }
}

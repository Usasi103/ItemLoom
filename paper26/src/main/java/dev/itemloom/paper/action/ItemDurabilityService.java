package dev.itemloom.paper.action;

import dev.itemloom.paper.nms.NmsItems;
import dev.itemloom.paper.runtime.DurabilityCallbacks;
import dev.itemloom.paper.runtime.ItemCopies;
import dev.itemloom.paper.runtime.ScalarItemState;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.component.DataComponents;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Commits a damaged unit before transferring ownership of a stacked remainder. */
public final class ItemDurabilityService implements Listener, AutoCloseable {
    public enum DamageResult {
        VANILLA,
        BREAK,
        BROKEN_ITEM,
        ZERO_DAMAGE,
        SUCCESS,
        INVALID_DAMAGE
    }

    private final DurabilityCallbacks callbacks;
    private final JavaPlugin plugin;
    private final Set<BukkitRunnable> refreshes = new HashSet<>();
    private boolean activated;
    private boolean closed;

    public ItemDurabilityService(DurabilityCallbacks callbacks, JavaPlugin plugin) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void activate() {
        if (!activated && active()) {
            activated = true;
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    private boolean active() {
        return !closed && callbacks.active();
    }

    public boolean usable(Player player, ItemStack item, Cancellable event) {
        if (!active() || item == null || item.isEmpty()) {
            return false;
        }
        ScalarItemState.State state = ScalarItemState.read(item);
        if (state == null || state.durability() == null || state.durability() != 0) {
            return true;
        }
        if (event != null) {
            event.setCancelled(true);
        }
        callbacks.broken(player);
        return false;
    }

    public void synchronize(ItemStack item) {
        if (!active()) {
            return;
        }
        ScalarItemState.State state = ScalarItemState.read(item);
        if (state == null || state.durability() == null) {
            return;
        }
        int ordinary = maximumDamage(item);
        if (ordinary <= 0) {
            return;
        }
        int capacity = capacity(state);
        int display = editorDamage(ordinary, state.durability(), capacity);
        if (display != damageValue(item)) {
            setDamage(item, display);
        }
    }

    public DamageResult damage(Player player, ItemStack item, int damage) {
        return damage(player, item, damage, true, null);
    }

    public DamageResult damage(
            Player player,
            ItemStack item,
            int damage,
            boolean breakItem,
            PlayerItemDamageEvent event) {
        if (damage < 0) {
            return DamageResult.INVALID_DAMAGE;
        }
        if (damage == 0) {
            return DamageResult.ZERO_DAMAGE;
        }
        if (!active()) {
            return DamageResult.VANILLA;
        }
        ScalarItemState.State state = ScalarItemState.read(item);
        if (state == null || state.durability() == null) {
            return DamageResult.VANILLA;
        }
        int current = state.durability();
        if (current == 0) {
            if (event != null) {
                event.setCancelled(true);
            }
            return DamageResult.BROKEN_ITEM;
        }
        int capacity = capacity(state);
        int effective = damage;
        if (event == null) {
            int unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING);
            if (unbreaking > 0) {
                effective = 0;
                for (int opportunity = 0; opportunity < damage; opportunity++) {
                    if (ThreadLocalRandom.current().nextLong((long) unbreaking + 1) == 0) {
                        effective++;
                    }
                }
            }
        }
        if (effective == 0) {
            return DamageResult.ZERO_DAMAGE;
        }
        int ordinary = maximumDamage(item);
        int previousDisplay = damageValue(item);
        boolean exhausted = effective >= current;
        int remaining = exhausted ? 0 : current - effective;
        boolean physicalBreak = exhausted && state.breaks();
        boolean removeNow = physicalBreak && breakItem && event == null;
        int desiredDisplay =
                exhausted
                        ? Math.max(0, ordinary - 1)
                        : (int) Math.floor(ordinary * (1.0 - (double) remaining / capacity));
        ItemStack original = ItemCopies.detached(item);
        ItemStack remainder = original.getAmount() > 1 ? ItemCopies.detached(original) : null;
        if (remainder != null) {
            remainder.setAmount(original.getAmount() - 1);
        }
        ItemStack target =
                physicalBreak && !removeNow
                        ? ItemCopies.detached(original)
                        : ScalarItemState.durability(ItemCopies.detached(original), remaining);
        target.setAmount(removeNow ? 0 : 1);
        if (event == null && !physicalBreak && ordinary > 0) {
            setDamage(target, desiredDisplay);
        }
        NmsItems.replace(item, target);
        if (remainder != null) {
            callbacks.returnLater(player, remainder);
        }
        if (event != null) {
            event.setDamage(
                    physicalBreak
                            ? ordinary - previousDisplay + 1
                            : desiredDisplay - previousDisplay);
        }
        if (exhausted) {
            callbacks.broken(player);
            if (player != null) {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                if (removeNow && original.getType() != Material.FIRE_CHARGE) {
                    player.incrementStatistic(Statistic.BREAK_ITEM, original.getType());
                }
            }
        }
        return physicalBreak ? DamageResult.BREAK : DamageResult.SUCCESS;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damageGate(PlayerItemDamageEvent event) {
        usable(event.getPlayer(), event.getItem(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void itemDamage(PlayerItemDamageEvent event) {
        damage(event.getPlayer(), event.getItem(), event.getDamage(), true, event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) {
        usable(event.getPlayer(), event.getPlayer().getInventory().getItem(event.getHand()), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void mend(PlayerItemMendEvent event) {
        if (!active()) {
            return;
        }
        ItemStack item = event.getItem();
        ScalarItemState.State state = ScalarItemState.read(item);
        if (state == null || state.durability() == null) {
            return;
        }
        int maximum = capacity(state);
        int repaired =
                (int)
                        Math.max(
                                0,
                                Math.min(
                                        (long) maximum,
                                        (long) state.durability() + event.getRepairAmount()));
        ItemStack target = ScalarItemState.durability(ItemCopies.detached(item), repaired);
        int ordinary = maximumDamage(target);
        if (ordinary > 0) {
            setDamage(target, editorDamage(ordinary, repaired, maximum));
        }
        NmsItems.replace(item, target);
        event.setCancelled(true);
        event.getExperienceOrb()
                .setExperience(
                        Math.max(
                                0,
                                event.getExperienceOrb().getExperience()
                                        - event.getRepairAmount() / 2));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void anvil(PrepareAnvilEvent event) {
        if (!active()) {
            return;
        }
        ItemStack before = event.getInventory().getItem(0);
        ItemStack result = event.getResult();
        ScalarItemState.State previous = ScalarItemState.read(before);
        ScalarItemState.State proposed = ScalarItemState.read(result);
        if (previous == null
                || proposed == null
                || previous.durability() == null
                || !previous.id().equals(proposed.id())
                || !Objects.equals(previous.durability(), proposed.durability())
                || !Objects.equals(previous.maximumDurability(), proposed.maximumDurability())) {
            return;
        }
        int difference = damageValue(before) - damageValue(result);
        if (difference == 0) {
            return;
        }
        int capacity = capacity(previous);
        int remaining =
                (int)
                        Math.max(
                                0,
                                Math.min(
                                        (long) capacity,
                                        (long) previous.durability() + difference));
        ItemStack target = ScalarItemState.durability(ItemCopies.detached(result), remaining);
        int ordinary = maximumDamage(target);
        if (ordinary > 0) {
            setDamage(target, editorDamage(ordinary, remaining, capacity));
        }
        event.setResult(target);
    }

    public void igniteTnt(PlayerInteractEvent event) {
        if (!active()
                || event.isCancelled()
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.TNT
                || event.getPlayer().getGameMode() == GameMode.CREATIVE
                || event.getItem() == null
                || event.getItem().getType() != Material.FIRE_CHARGE) {
            return;
        }
        DamageResult result = damage(event.getPlayer(), event.getItem(), 1, false, null);
        if (result == DamageResult.BROKEN_ITEM) {
            event.setCancelled(true);
        } else if (result == DamageResult.SUCCESS || result == DamageResult.ZERO_DAMAGE) {
            event.getItem().setAmount(event.getItem().getAmount() + 1);
            Player player = event.getPlayer();
            BukkitRunnable task =
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            refreshes.remove(this);
                            if (active() && player.isOnline()) {
                                player.updateInventory();
                            }
                        }
                    };
            task.runTask(plugin);
            refreshes.add(task);
        }
    }

    private static int capacity(ScalarItemState.State state) {
        int value = state.maximumDurability() == null ? 0 : state.maximumDurability();
        if (value <= 0) {
            throw new IllegalArgumentException("Custom durability maximum must be positive");
        }
        return value;
    }

    private static int maximumDamage(ItemStack item) {
        return CraftItemStack.unwrap(item).getOrDefault(DataComponents.MAX_DAMAGE, 0);
    }

    private static int damageValue(ItemStack item) {
        return CraftItemStack.unwrap(item).getOrDefault(DataComponents.DAMAGE, 0);
    }

    private static void setDamage(ItemStack item, int damage) {
        var copy = CraftItemStack.unwrap(item).copy();
        copy.set(DataComponents.DAMAGE, damage);
        NmsItems.replace(item, CraftItemStack.asCraftMirror(copy));
    }

    private static int editorDamage(int ordinary, int remaining, int maximum) {
        short quantized = (short) (int) (ordinary * (1.0 - (double) remaining / maximum));
        int display = remaining < maximum && quantized <= 0 ? 1 : quantized;
        return remaining > 0 && display >= ordinary ? (short) (display - 1) : display;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        HandlerList.unregisterAll(this);
        for (BukkitRunnable task : refreshes) {
            task.cancel();
        }
        refreshes.clear();
    }
}

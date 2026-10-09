package dev.itemloom.paper.action;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemEditorManager;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
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
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Custom durability is an inventory mutation, never a client-bound display or charge deduction. */
public final class ItemDurabilityService implements Listener, AutoCloseable {
    public enum DamageResult {
        VANILLA,
        BREAK,
        BROKEN_ITEM,
        ZERO_DAMAGE,
        SUCCESS,
        INVALID_DAMAGE
    }

    private final NiCatalog catalog;
    private final JavaPlugin plugin;
    private final BooleanSupplier active;
    private final Set<BukkitTask> refreshTasks = new HashSet<>();

    ItemDurabilityService(NiCatalog catalog, JavaPlugin plugin, BooleanSupplier active) {
        this.catalog = Objects.requireNonNull(catalog);
        this.plugin = Objects.requireNonNull(plugin);
        this.active = Objects.requireNonNull(active);
    }

    public boolean usable(Player player, ItemStack item, Cancellable event) {
        ItemsService.requireThread();
        if (!active.getAsBoolean() || item == null || item.isEmpty()) return false;
        State state = read(item);
        if (state == null || state.current != 0) return true;
        event.setCancelled(true);
        catalog.triggers().broken(player);
        return false;
    }

    /** Returns without copying custom data unless the displayed damage actually differs. */
    public void synchronize(ItemStack item) {
        ItemsService.requireThread();
        if (!active.getAsBoolean()) return;
        State state = read(item);
        if (state == null || maximumDamage(item) <= 0) return;
        requireMaximum(state);
        int expected = LegacyItemEditorManager.checkDurability(item, state.current, state.maximum);
        var handle = CraftItemStack.unwrap(item);
        if (handle.getOrDefault(DataComponents.DAMAGE, 0) != expected)
            handle.set(DataComponents.DAMAGE, expected);
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
        ItemsService.requireThread();
        if (!active.getAsBoolean())
            throw new IllegalStateException("Item durability revision has closed");
        if (damage < 0) return DamageResult.INVALID_DAMAGE;
        if (damage == 0) return DamageResult.ZERO_DAMAGE;
        State state = read(item);
        if (state == null) return DamageResult.VANILLA;
        requireMaximum(state);
        if (state.current == 0) {
            if (event != null) event.setCancelled(true);
            return DamageResult.BROKEN_ITEM;
        }

        int realDamage = damage;
        if (event == null) {
            int unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING);
            if (unbreaking > 0) {
                for (int i = 0; i < damage; i++)
                    if (ThreadLocalRandom.current().nextLong((long) unbreaking + 1) != 0)
                        realDamage--;
            }
        }
        if (realDamage == 0) return DamageResult.ZERO_DAMAGE;

        // Finish the owned candidate before splitting or publishing a returned remainder.
        ItemStack prepared = item.clone();
        ItemStack remainder = item.getAmount() > 1 ? item.clone() : null;
        if (remainder != null) remainder.setAmount(item.getAmount() - 1);
        prepared.setAmount(1);
        Material material = item.getType();
        boolean exhausted = realDamage >= state.current;
        boolean broken = exhausted && state.breakItem;
        Integer eventDamage = null;
        if (broken) {
            if (breakItem) {
                if (event == null) prepared.setAmount(0);
                else eventDamage = maximumDamage(item) - damageValue(item) + 1;
            }
        } else {
            int remaining = exhausted ? 0 : state.current - realDamage;
            writeCurrent(prepared, state, remaining);
            int expected =
                    exhausted
                            ? Math.max(0, maximumDamage(item) - 1)
                            : (int)
                                    (maximumDamage(item)
                                            * (1 - (double) remaining / state.maximum));
            if (event != null) eventDamage = expected - damageValue(item);
            else if (maximumDamage(prepared) > 0)
                CraftItemStack.unwrap(prepared).set(DataComponents.DAMAGE, expected);
        }
        NmsItems.replace(item, prepared);
        if (eventDamage != null) event.setDamage(eventDamage);
        if (remainder != null) catalog.triggers().returnLater(player, remainder);
        if (exhausted) {
            // Vanilla's lethal damage path records its own statistic after the event returns.
            if (broken && event == null && material != Material.FIRE_CHARGE)
                player.incrementStatistic(Statistic.BREAK_ITEM, material);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            catalog.triggers().broken(player);
        }
        return broken ? DamageResult.BREAK : DamageResult.SUCCESS;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damageGate(PlayerItemDamageEvent event) {
        usable(event.getPlayer(), event.getItem(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void itemDamage(PlayerItemDamageEvent event) {
        if (active.getAsBoolean())
            damage(event.getPlayer(), event.getItem(), event.getDamage(), true, event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void mend(PlayerItemMendEvent event) {
        ItemsService.requireThread();
        if (!active.getAsBoolean()) return;
        ItemStack item = event.getItem();
        State state = read(item);
        if (state == null) return;
        requireMaximum(state);
        ItemStack prepared = item.clone();
        int remaining = clamp((long) state.current + event.getRepairAmount(), state.maximum);
        repair(prepared, state, remaining);
        NmsItems.replace(item, prepared);
        event.setCancelled(true);
        var orb = event.getExperienceOrb();
        orb.setExperience(Math.max(0, orb.getExperience() - event.getRepairAmount() / 2));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void anvil(PrepareAnvilEvent event) {
        ItemsService.requireThread();
        if (!active.getAsBoolean()) return;
        ItemStack original = event.getInventory().getItem(0), result = event.getResult();
        State before = read(original), after = read(result);
        if (before == null
                || after == null
                || !before.id.equals(after.id)
                || before.current != after.current
                || before.maximum != after.maximum
                || damageValue(original) == damageValue(result)) return;
        requireMaximum(after);
        ItemStack prepared = result.clone();
        int remaining =
                clamp(
                        (long) after.current + damageValue(original) - damageValue(result),
                        after.maximum);
        repair(prepared, after, remaining);
        event.setResult(prepared);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) {
        usable(event.getPlayer(), event.getPlayer().getInventory().getItem(event.getHand()), event);
    }

    /** The action listener calls this after its non-cancelled interaction actions. */
    @SuppressWarnings("deprecation")
    public void igniteTnt(PlayerInteractEvent event) {
        ItemsService.requireThread();
        if (!active.getAsBoolean()
                || event.isCancelled()
                || event.getPlayer().getGameMode() == GameMode.CREATIVE
                || event.getItem() == null
                || event.getItem().getType() != Material.FIRE_CHARGE
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.TNT
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        DamageResult result = damage(player, item, 1, false, null);
        if (result == DamageResult.BROKEN_ITEM) {
            event.setCancelled(true);
            catalog.triggers().broken(player);
        } else if (result != DamageResult.VANILLA && result != DamageResult.BREAK) {
            // Vanilla consumes one charge after this callback; compensate only a surviving item.
            item.setAmount(item.getAmount() + 1);
            BukkitTask[] scheduled = new BukkitTask[1];
            scheduled[0] =
                    Bukkit.getScheduler()
                            .runTask(
                                    plugin,
                                    () -> {
                                        refreshTasks.remove(scheduled[0]);
                                        if (active.getAsBoolean() && player.isOnline())
                                            player.updateInventory();
                                    });
            refreshTasks.add(scheduled[0]);
        }
    }

    private static void repair(ItemStack item, State state, int remaining) {
        if (remaining == 0 && state.breakItem) item.setAmount(0);
        else {
            writeCurrent(item, state, remaining);
            if (maximumDamage(item) > 0)
                CraftItemStack.unwrap(item)
                        .set(
                                DataComponents.DAMAGE,
                                (int)
                                        LegacyItemEditorManager.checkDurability(
                                                item, remaining, state.maximum));
        }
    }

    private static int clamp(long value, int maximum) {
        return (int) Math.max(0, Math.min(value, maximum));
    }

    private static int maximumDamage(ItemStack item) {
        return CraftItemStack.unwrap(item).getOrDefault(DataComponents.MAX_DAMAGE, 0);
    }

    private static int damageValue(ItemStack item) {
        return CraftItemStack.unwrap(item).getOrDefault(DataComponents.DAMAGE, 0);
    }

    private static void requireMaximum(State state) {
        if (state.maximum <= 0)
            throw new IllegalArgumentException(
                    "Non-positive custom durability maximum for " + state.id);
    }

    /** No borrowed tag escapes: the hot gate projects only immutable scalar values. */
    private static State read(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        var data = CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag custom = data.getUnsafe(), properties;
        Tag raw = custom.get(ItemStateCodec.KEY);
        boolean modern = raw != null;
        String id;
        if (modern) {
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
        Integer durability = properties.getInt("durability").orElse(null);
        return durability == null
                ? null
                : new State(
                        id,
                        modern,
                        durability,
                        properties.getInt("maxDurability").orElse(0),
                        properties.getBoolean("itemBreak").orElse(true));
    }

    /** Called only for a detached construction copy; preserve every unrelated custom-data field. */
    private static void writeCurrent(ItemStack item, State state, int current) {
        var handle = CraftItemStack.unwrap(item);
        var data = handle.get(DataComponents.CUSTOM_DATA);
        if (data == null) throw new IllegalStateException("Custom durability disappeared");
        CompoundTag copy = data.copyTag();
        CompoundTag properties =
                state.modern
                        ? copy.getCompoundOrEmpty(ItemStateCodec.KEY)
                                .getCompoundOrEmpty("properties")
                        : copy.getCompoundOrEmpty("NeigeItems");
        properties.putInt("durability", current);
        handle.set(DataComponents.CUSTOM_DATA, CustomData.of(copy));
    }

    private record State(String id, boolean modern, int current, int maximum, boolean breakItem) {}

    @Override
    public void close() {
        ItemsService.requireThread();
        refreshTasks.forEach(BukkitTask::cancel);
        refreshTasks.clear();
    }
}

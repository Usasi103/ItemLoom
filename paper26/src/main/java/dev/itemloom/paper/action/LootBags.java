package dev.itemloom.paper.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.integration.MythicLootTables;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Bag consumption authorizes a fixed set of rewards; no generation happens after commit. */
public final class LootBags implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ItemsService items;
    private final Supplier<LootBagConfig> current;
    private final ReturnLedger ledger;
    private final ItemStateCodec codec = new ItemStateCodec();
    private final Set<UUID> opening = new HashSet<>();
    private final Map<UUID, Integer> cooldowns = new HashMap<>();
    private boolean closed;

    public LootBags(
            JavaPlugin plugin,
            ItemsService items,
            PlayerActionState players,
            Supplier<LootBagConfig> current) {
        this.plugin = plugin;
        this.items = items;
        this.current = current;
        ledger = players.returns(plugin);
    }

    /** Claims the right-click before the generic action listener, including disabled/legacy bags. */
    public boolean interact(PlayerInteractEvent event) {
        ItemsService.requireThread();
        if (closed
                || event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
        LootBagConfig config = current.get();
        if (config == null || config.bags().isEmpty()) return false;
        String id = identify(event.getItem(), config);
        if (id == null) return false;
        boolean denied = event.useItemInHand() == org.bukkit.event.Event.Result.DENY;
        event.setCancelled(true);
        // Main hand only, matching the old CE bags. Air clicks can be pre-cancelled by vanilla
        // while useItemInHand is DEFAULT, so isCancelled alone is not an authorization test.
        if (denied || event.getHand() != EquipmentSlot.HAND) return true;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        LootBagConfig.Bag bag = config.bags().get(id);
        if (!bag.enabled()) {
            message(player, "奖励列表尚未配置，战利品袋不会消耗。", NamedTextColor.YELLOW);
            return true;
        }
        int tick = Bukkit.getCurrentTick();
        Integer until = cooldowns.get(playerId);
        if ((until != null && tick - until < 0) || !opening.add(playerId)) return true;
        // Failed/empty generators share the throttle too; holding right-click must not
        // execute an expensive missing table or script on every packet.
        cooldowns.put(playerId, tick + bag.cooldown());
        UUID transaction = UUID.randomUUID();
        List<ReturnLedger.Entry> pending = new ArrayList<>();
        boolean committed = false;
        ledger.begin(transaction);
        try {
            TriggerSource source = TriggerSource.equipment(player, EquipmentSlot.HAND);
            if (!source.accepts(source.original) || !source.candidate().equals(event.getItem()))
                return true;
            ItemStack before = source.candidate();
            Object revision = items.placeholderRevision(), providers = items.providerRevision();
            List<ItemStack> rewards = generate(bag, config, player);
            if (closed
                    || current.get() != config
                    || revision != items.placeholderRevision()
                    || providers != items.providerRevision()
                    || !source.unchanged()) return true;
            if (rewards.isEmpty()) {
                message(player, "本次列表没有生成物品，战利品袋未消耗。", NamedTextColor.YELLOW);
                return true;
            }
            ItemStack after = before.clone();
            after.setAmount(before.getAmount() - 1);
            var snapshot = ReturnLedger.SourceSnapshot.capture(before, after);
            for (ItemStack reward : rewards) {
                var entry =
                        ledger.registerPrepared(
                                transaction, player, reward, "loot-bag:" + id, snapshot);
                pending.add(entry);
            }
            try {
                source.commit(after);
            } catch (RuntimeException failure) {
                pending.forEach(entry -> ledger.sourceUnknown(entry, failure));
                throw failure;
            }
            committed = true;
            pending.forEach(ledger::ready);
        } catch (RuntimeException failure) {
            plugin.getLogger()
                    .log(Level.WARNING, "Loot bag " + id + " failed for " + playerId, failure);
            message(player, "开袋失败，请联系管理员检查；不会自动重试发奖。", NamedTextColor.RED);
        } finally {
            if (!committed) pending.forEach(ledger::abort);
            ledger.end(transaction);
            // The committed obligation belongs to the service ledger across reload and quit.
            // Uncertain delivery is quarantined there; it is never rerolled or blindly retried.
            if (committed) {
                pending.forEach(ledger::deliver);
                if (!closed) {
                    boolean uncertain = pending.stream().anyMatch(entry -> entry.outcomeUnknown);
                    message(
                            player,
                            uncertain ? "战利品袋已开启，部分奖励交付异常，请联系管理员。" : "已开启战利品袋。",
                            uncertain ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
                }
            }
            opening.remove(playerId);
        }
        return true;
    }

    private String identify(ItemStack item, LootBagConfig config) {
        if (item == null || item.isEmpty()) return null;
        String id = codec.readId(item);
        if (id != null) return config.bags().containsKey(id) ? id : null;
        // Explicit compatibility marker for imported bags without an item identity.
        if (item.getType() != Material.PAPER || config.legacyIds().isEmpty()) return null;
        String legacy =
                NmsItems.customData(item)
                        .getCompoundOrEmpty("itemloom")
                        .getString("loot_bag")
                        .orElse(null);
        return legacy == null ? null : config.legacyIds().get(legacy);
    }

    private List<ItemStack> generate(LootBagConfig.Bag bag, LootBagConfig config, Player player) {
        List<ItemStack> result = new ArrayList<>();
        if (bag.pack() != null)
            append(
                    result,
                    items.createPack(
                            bag.pack(),
                            player,
                            Map.of(),
                            new dev.itemloom.core.GenerationBudget(4096, 4096, 256)));
        else if (bag.mythic() != null)
            append(result, MythicLootTables.generate(bag.mythic(), player));
        else {
            LootBagConfig.Table table =
                    bag.loot() != null ? bag.loot() : config.tables().get(bag.table());
            int rolls = table.rolls().roll();
            for (int roll = 0; roll < rolls; roll++) {
                int point = ThreadLocalRandom.current().nextInt(table.weight());
                LootBagConfig.Entry selected = null;
                for (var entry : table.entries()) {
                    point -= entry.weight();
                    if (point < 0) {
                        selected = entry;
                        break;
                    }
                }
                if (selected == null)
                    throw new IllegalStateException("Loot table selection failed");
                ItemStack item;
                if (selected.item().startsWith("minecraft:")) {
                    Material type = Material.matchMaterial(selected.item());
                    if (type == null || !type.isItem() || type.isAir())
                        throw new IllegalArgumentException(
                                "Invalid vanilla reward: " + selected.item());
                    item = new ItemStack(type);
                } else item = items.create(selected.item(), player, selected.data());
                if (item == null || item.isEmpty())
                    throw new IllegalStateException(
                            "Reward generator returned no item: " + selected.item());
                item = item.clone();
                item.setAmount(selected.amount().roll());
                append(result, List.of(item));
            }
        }
        return result;
    }

    private static void append(List<ItemStack> result, List<ItemStack> additions) {
        int total = result.stream().mapToInt(ItemStack::getAmount).sum();
        for (ItemStack item : additions) {
            if (item == null) throw new IllegalArgumentException("Null loot reward");
            if (item.isEmpty()) continue;
            total = Math.addExact(total, item.getAmount());
            if (total > 4096) throw new IllegalArgumentException("Bag rewards exceed 4096 items");
            int count = item.getAmount(), maximum = Math.max(1, item.getMaxStackSize());
            while (count > 0) {
                if (result.size() >= 256)
                    throw new IllegalArgumentException("Bag rewards exceed 256 stacks");
                ItemStack copy = item.clone();
                copy.setAmount(Math.min(count, maximum));
                result.add(copy);
                count -= copy.getAmount();
            }
        }
    }

    private static void message(Player player, String text, NamedTextColor color) {
        player.sendActionBar(Component.text(text, color));
    }

    public void quit(UUID player) {
        cooldowns.remove(player);
    }

    @Override
    public void close() {
        closed = true;
        cooldowns.clear();
    }
}

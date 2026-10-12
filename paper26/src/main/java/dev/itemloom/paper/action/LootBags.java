package dev.itemloom.paper.action;

import dev.itemloom.core.GenerationBudget;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.integration.MythicLootTables;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
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
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class LootBags implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ItemsService items;
    private final Supplier<LootBagConfig> configuration;
    private final ReturnLedger ledger;
    private final ItemStateCodec codec = new ItemStateCodec();
    private final Set<UUID> opening = new HashSet<>();
    private final Map<UUID, Integer> cooldowns = new HashMap<>();
    private boolean closed;

    public LootBags(
            JavaPlugin plugin,
            ItemsService items,
            PlayerActionState players,
            Supplier<LootBagConfig> configuration) {
        this.plugin = plugin;
        this.items = items;
        this.configuration = configuration;
        ledger = players.returns(plugin);
    }

    public boolean interact(PlayerInteractEvent event) {
        ItemsService.requireThread();
        if (closed
                || event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
        LootBagConfig config = configuration.get();
        if (config == null || config.bags().isEmpty()) return false;
        ItemStack supplied = event.getItem();
        if (supplied == null || supplied.isEmpty()) return false;
        String id = codec.readId(supplied);
        if (id == null && supplied.getType() == Material.PAPER) {
            String marker =
                    NmsItems.customData(supplied)
                            .getCompound("itemloom")
                            .flatMap(tag -> tag.getString("loot_bag"))
                            .orElse(null);
            if (marker != null) id = config.legacyIds().get(marker);
        }
        if (id == null) return false;
        LootBagConfig.Bag bag = config.bags().get(id);
        if (bag == null) return false;
        boolean denied = event.useItemInHand() == Event.Result.DENY;
        event.setCancelled(true);
        if (denied || event.getHand() == EquipmentSlot.OFF_HAND) return true;
        Player player = event.getPlayer();
        if (!bag.enabled()) {
            player.sendActionBar("§e奖励列表尚未配置，战利品袋不会消耗。");
            return true;
        }
        UUID playerId = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        Integer until = cooldowns.get(playerId);
        if (opening.contains(playerId) || until != null && tick - until < 0) return true;
        cooldowns.put(playerId, tick + bag.cooldown());
        opening.add(playerId);
        UUID owner = UUID.randomUUID();
        List<ReturnLedger.Entry> rewards = new ArrayList<>();
        boolean leased = false;
        boolean committed = false;
        try {
            ledger.begin(owner);
            leased = true;
            TriggerSource source = TriggerSource.equipment(player, EquipmentSlot.HAND);
            if (!source.accepts(source.original)
                    || !java.util.Objects.equals(source.candidate(), supplied)) return true;
            ItemStack before = source.candidate();
            Object placeholders = items.placeholderRevision();
            Object providers = items.providerRevision();
            List<ItemStack> generated = generate(bag, config, player);
            if (closed
                    || configuration.get() != config
                    || items.placeholderRevision() != placeholders
                    || items.providerRevision() != providers
                    || !source.unchanged()) return true;
            if (generated.isEmpty()) {
                player.sendActionBar("§e本次列表没有生成物品，战利品袋未消耗。");
                return true;
            }
            ItemStack prepared = before.clone();
            prepared.setAmount(before.getAmount() - 1);
            ReturnLedger.SourceSnapshot snapshots =
                    ReturnLedger.SourceSnapshot.capture(before, prepared);
            for (ItemStack reward : generated)
                rewards.add(
                        ledger.registerPrepared(
                                owner, player, reward, "loot-bag:" + id, snapshots));
            try {
                source.commit(prepared);
            } catch (RuntimeException failure) {
                for (ReturnLedger.Entry entry : rewards) ledger.sourceUnknown(entry, failure);
                throw failure;
            }
            committed = true;
            for (ReturnLedger.Entry entry : rewards) ledger.ready(entry);
        } catch (RuntimeException failure) {
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "Loot bag " + id + " failed for " + player.getName(),
                            failure);
            player.sendActionBar("§c开袋失败，请联系管理员检查；不会自动重试发奖。");
        } finally {
            if (!committed) for (ReturnLedger.Entry entry : rewards) ledger.abort(entry);
            if (leased) ledger.end(owner);
            if (committed) {
                for (ReturnLedger.Entry entry : rewards) {
                    ledger.deliver(entry);
                    ledger.schedule(entry);
                }
                if (!closed) {
                    boolean unknown = rewards.stream().anyMatch(entry -> entry.outcomeUnknown);
                    player.sendActionBar(unknown ? "§e战利品袋已开启，部分奖励交付异常，请联系管理员。" : "§a已开启战利品袋。");
                }
            }
            opening.remove(playerId);
        }
        return true;
    }

    private List<ItemStack> generate(LootBagConfig.Bag bag, LootBagConfig config, Player player) {
        RewardBuffer rewards = new RewardBuffer();
        if (bag.pack() != null) {
            for (ItemStack item :
                    items.createPack(
                            bag.pack(), player, Map.of(), new GenerationBudget(4096, 4096, 256)))
                rewards.append(item);
        } else if (bag.mythic() != null) {
            for (ItemStack item : MythicLootTables.generate(bag.mythic(), player))
                rewards.append(item);
        } else {
            LootBagConfig.Table table =
                    bag.table() == null ? bag.loot() : config.tables().get(bag.table());
            int rolls = table.rolls().roll();
            for (int roll = 0; roll < rolls; roll++) {
                int draw = ThreadLocalRandom.current().nextInt(table.weight());
                LootBagConfig.Entry selected = null;
                for (LootBagConfig.Entry entry : table.entries()) {
                    draw -= entry.weight();
                    if (draw < 0) {
                        selected = entry;
                        break;
                    }
                }
                if (selected == null)
                    throw new IllegalStateException("Loot weights did not select an entry");
                ItemStack reward;
                if (selected.item().startsWith("minecraft:")) {
                    Material material = Material.matchMaterial(selected.item());
                    if (material == null || !material.isItem() || material.isAir())
                        throw new IllegalArgumentException(
                                "Invalid vanilla reward " + selected.item());
                    reward = new ItemStack(material);
                } else reward = items.create(selected.item(), player, selected.data());
                if (reward == null || reward.isEmpty())
                    throw new IllegalArgumentException("Empty reward " + selected.item());
                reward = reward.clone();
                reward.setAmount(selected.amount().roll());
                rewards.append(reward);
            }
        }
        return rewards.stacks;
    }

    public void quit(UUID player) {
        cooldowns.remove(player);
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        closed = true;
        cooldowns.clear();
    }

    private static final class RewardBuffer {
        final List<ItemStack> stacks = new ArrayList<>();
        int count;

        void append(ItemStack supplied) {
            if (supplied == null) throw new IllegalArgumentException("Null loot reward");
            if (supplied.isEmpty()) return;
            count = Math.addExact(count, supplied.getAmount());
            if (count > 4096) throw new IllegalArgumentException("Loot exceeds 4096 items");
            int remaining = supplied.getAmount();
            int maximum = Math.max(1, supplied.getMaxStackSize());
            while (remaining > 0) {
                if (stacks.size() >= 256)
                    throw new IllegalArgumentException("Loot exceeds 256 stacks");
                ItemStack portion = supplied.clone();
                portion.setAmount(Math.min(maximum, remaining));
                stacks.add(portion);
                remaining -= portion.getAmount();
            }
        }
    }
}

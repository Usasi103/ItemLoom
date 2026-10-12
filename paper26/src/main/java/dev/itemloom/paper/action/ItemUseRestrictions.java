package dev.itemloom.paper.action;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.paper.nms.ItemStateCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

public final class ItemUseRestrictions implements Listener {
    private final Supplier<Map<String, Integer>> policy;
    private final ItemStateCodec codec = new ItemStateCodec();

    public ItemUseRestrictions(Supplier<Map<String, Integer>> policy) {
        this.policy = policy;
    }

    public static Map<String, Integer> read(NiConfig settings) {
        Object source = settings.get("ItemLoom.UsageRestrictions");
        if (source == null) return Map.of();
        if (!(source instanceof Map<?, ?> items)) throw invalid("expected mapping");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var item : items.entrySet()) {
            if (!(item.getKey() instanceof String id)
                    || id.isBlank()
                    || !(item.getValue() instanceof Map<?, ?> flags)) {
                throw invalid("expected nonblank item ID and flag mapping");
            }
            int mask = 0;
            for (var flag : flags.entrySet()) {
                int bit =
                        switch (String.valueOf(flag.getKey())) {
                            case "crafting" -> 1;
                            case "enchanting" -> 2;
                            case "placement" -> 4;
                            default -> throw invalid(id + ": unknown flag " + flag.getKey());
                        };
                if (!(flag.getValue() instanceof Boolean allowed))
                    throw invalid(id + ": expected boolean for " + flag.getKey());
                if (!allowed) mask |= bit;
            }
            if (mask != 0) result.put(id, mask);
        }
        return Map.copyOf(result);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("ItemLoom.UsageRestrictions: " + reason);
    }

    private boolean blocked(ItemStack item, int bit) {
        Map<String, Integer> rules = policy.get();
        if (rules == null || rules.isEmpty() || item == null || item.isEmpty()) return false;
        String id = codec.readId(item);
        return id != null && (rules.getOrDefault(id, 0) & bit) != 0;
    }

    private boolean crafting(ItemStack[] items) {
        for (ItemStack item : items) if (blocked(item, 1)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepareCraft(PrepareItemCraftEvent event) {
        if (crafting(event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void craft(CraftItemEvent event) {
        if (crafting(event.getInventory().getMatrix())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void crafter(CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof Crafter crafter
                && crafting(crafter.getInventory().getContents())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prepareEnchant(PrepareItemEnchantEvent event) {
        if (blocked(event.getItem(), 2)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void enchant(EnchantItemEvent event) {
        if (blocked(event.getItem(), 2)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (blocked(event.getItemInHand(), 4)) event.setCancelled(true);
    }
}

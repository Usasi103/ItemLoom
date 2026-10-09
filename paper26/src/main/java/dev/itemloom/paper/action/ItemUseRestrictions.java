package dev.itemloom.paper.action;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.paper.nms.ItemStateCodec;
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

/** Opt-in restrictions on actual vanilla operations, not inventory insertion or generic GUI clicks. */
public final class ItemUseRestrictions implements Listener {
    private static final int CRAFTING = 1, ENCHANTING = 2, PLACEMENT = 4;
    private final Supplier<Map<String, Integer>> current;
    private final ItemStateCodec codec = new ItemStateCodec();

    public ItemUseRestrictions(Supplier<Map<String, Integer>> current) {
        this.current = current;
    }

    public static Map<String, Integer> read(NiConfig settings) {
        Object value = settings.get("ItemLoom.UsageRestrictions");
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> rules))
            throw new IllegalArgumentException(
                    "ItemLoom.UsageRestrictions must be an item-ID mapping");
        Map<String, Integer> result = new LinkedHashMap<>();
        rules.forEach(
                (id, options) -> {
                    if (!(id instanceof String key)
                            || key.isBlank()
                            || !(options instanceof Map<?, ?> flags))
                        throw new IllegalArgumentException("Invalid usage restriction: " + id);
                    int mask = 0;
                    for (var entry : flags.entrySet()) {
                        int flag =
                                switch (entry.getKey().toString()) {
                                    case "crafting" -> CRAFTING;
                                    case "enchanting" -> ENCHANTING;
                                    case "placement" -> PLACEMENT;
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "Unknown usage restriction: " + entry.getKey());
                                };
                        if (!(entry.getValue() instanceof Boolean allowed))
                            throw new IllegalArgumentException(
                                    "Usage restriction must be true/false: "
                                            + id
                                            + '/'
                                            + entry.getKey());
                        if (!allowed) mask |= flag;
                    }
                    if (mask != 0) result.put(key, mask);
                });
        return Map.copyOf(result);
    }

    private boolean blocked(ItemStack item, int operation, Map<String, Integer> rules) {
        if (rules.isEmpty() || item == null || item.isEmpty()) return false;
        String id = codec.readId(item);
        return id != null && (rules.getOrDefault(id, 0) & operation) != 0;
    }

    private boolean crafting(ItemStack[] matrix) {
        Map<String, Integer> rules = current.get();
        if (rules.isEmpty()) return false;
        for (ItemStack item : matrix) if (blocked(item, CRAFTING, rules)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepareCraft(PrepareItemCraftEvent event) {
        if (!current.get().isEmpty() && crafting(event.getInventory().getMatrix()))
            event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void craft(CraftItemEvent event) {
        if (!current.get().isEmpty() && crafting(event.getInventory().getMatrix()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void crafter(CrafterCraftEvent event) {
        if (current.get().isEmpty()) return;
        if (event.getBlock().getState(false) instanceof Crafter crafter
                && crafting(crafter.getInventory().getContents())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prepareEnchant(PrepareItemEnchantEvent event) {
        if (blocked(event.getItem(), ENCHANTING, current.get())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void enchant(EnchantItemEvent event) {
        if (blocked(event.getItem(), ENCHANTING, current.get())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (blocked(event.getItemInHand(), PLACEMENT, current.get())) event.setCancelled(true);
    }
}

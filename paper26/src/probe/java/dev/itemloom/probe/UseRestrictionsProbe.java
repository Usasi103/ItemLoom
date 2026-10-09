package dev.itemloom.probe;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.ItemUseRestrictions;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.view.EnchantmentView;
import org.bukkit.plugin.java.JavaPlugin;

/** Real events and crafter block, controlled view/matrix; does not simulate client packets. */
final class UseRestrictionsProbe {
    static Map<String, Object> run(JavaPlugin plugin) {
        List<String> checks = new ArrayList<>();
        AtomicReference<Map<String, Integer>> policy = new AtomicReference<>(Map.of());
        var listener = new ItemUseRestrictions(policy::get);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        org.bukkit.block.BlockState oldBlock = null;
        try {
            ItemStack restricted = item(false), legacy = item(true);
            var player = ProbePlayer.create("UseRestrictions");
            var block = Bukkit.getWorlds().getFirst().getSpawnLocation().add(20, 8, 20).getBlock();
            oldBlock = block.getState();
            BlockPlaceEvent allowed =
                    new BlockPlaceEvent(
                            block, oldBlock, block, restricted, player, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(allowed);
            check(!allowed.isCancelled(), "absent configuration leaves original behavior", checks);
            policy.set(
                    ItemUseRestrictions.read(
                            new NiConfig(
                                    Map.of(
                                            "ItemLoom",
                                            Map.of(
                                                    "UsageRestrictions",
                                                    Map.of(
                                                            "blocked",
                                                            Map.of(
                                                                    "crafting",
                                                                    false,
                                                                    "enchanting",
                                                                    false,
                                                                    "placement",
                                                                    false)))))));
            var recipe =
                    new ShapelessRecipe(
                                    new NamespacedKey(plugin, "use_probe"),
                                    new ItemStack(Material.STONE))
                            .addIngredient(Material.STONE);
            for (int size : new int[] {4, 9}) {
                ItemStack[] matrix = new ItemStack[size], result = {new ItemStack(Material.STONE)};
                CraftingInventory inventory =
                        (CraftingInventory)
                                Proxy.newProxyInstance(
                                        CraftingInventory.class.getClassLoader(),
                                        new Class<?>[] {CraftingInventory.class},
                                        (self, method, args) ->
                                                switch (method.getName()) {
                                                    case "getMatrix", "getContents" -> matrix;
                                                    case "getResult" -> result[0];
                                                    case "setResult" -> {
                                                        result[0] = (ItemStack) args[0];
                                                        yield null;
                                                    }
                                                    case "getRecipe" -> recipe;
                                                    case "getItem" -> result[0];
                                                    case "getViewers" -> List.of(player);
                                                    case "getType" ->
                                                            size == 4
                                                                    ? InventoryType.CRAFTING
                                                                    : InventoryType.WORKBENCH;
                                                    default -> null;
                                                });
                InventoryView view = view(player, inventory, InventoryView.class);
                // The final matrix is checked regardless of whether insertion used cursor, drag or
                // hotbar.
                matrix[size - 1] = restricted;
                Bukkit.getPluginManager()
                        .callEvent(new PrepareItemCraftEvent(inventory, view, false));
                check(
                        result[0] == null,
                        size + "-slot preparation clears restricted recipe result",
                        checks);
                for (ClickType click :
                        List.of(
                                ClickType.LEFT,
                                ClickType.SHIFT_LEFT,
                                ClickType.NUMBER_KEY,
                                ClickType.SWAP_OFFHAND)) {
                    CraftItemEvent event =
                            new CraftItemEvent(
                                    recipe,
                                    view,
                                    InventoryType.SlotType.RESULT,
                                    0,
                                    click,
                                    InventoryAction.PICKUP_ALL,
                                    0);
                    Bukkit.getPluginManager().callEvent(event);
                    check(
                            event.isCancelled(),
                            size + "-slot result extraction blocked: " + click,
                            checks);
                }
                matrix[size - 1] = new ItemStack(Material.STONE);
                result[0] = new ItemStack(Material.STONE);
                Bukkit.getPluginManager()
                        .callEvent(new PrepareItemCraftEvent(inventory, view, false));
                check(result[0] != null, size + "-slot vanilla ingredients unaffected", checks);
                InventoryDragEvent drag =
                        new InventoryDragEvent(
                                view,
                                new ItemStack(Material.AIR),
                                restricted,
                                false,
                                Map.of(1, restricted));
                Bukkit.getPluginManager().callEvent(drag);
                check(
                        !drag.isCancelled(),
                        "restriction does not cancel inventory drag itself (matrix checked at use)",
                        checks);
            }
            for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                var event =
                        new BlockPlaceEvent(
                                block,
                                oldBlock,
                                block,
                                hand == EquipmentSlot.HAND ? restricted : legacy,
                                player,
                                true,
                                hand);
                Bukkit.getPluginManager().callEvent(event);
                check(event.isCancelled(), "modern/legacy placement blocked for " + hand, checks);
            }
            var enchanting =
                    view(
                            player,
                            Bukkit.createInventory(null, InventoryType.ENCHANTING),
                            EnchantmentView.class);
            var prepare =
                    new PrepareItemEnchantEvent(
                            player,
                            enchanting,
                            block,
                            restricted,
                            new org.bukkit.enchantments.EnchantmentOffer[3],
                            15);
            Bukkit.getPluginManager().callEvent(prepare);
            check(prepare.isCancelled(), "enchant preview blocked", checks);
            var enchant =
                    new EnchantItemEvent(
                            player,
                            enchanting,
                            block,
                            restricted,
                            3,
                            new java.util.HashMap<>(),
                            Enchantment.UNBREAKING,
                            1,
                            0);
            Bukkit.getPluginManager().callEvent(enchant);
            check(enchant.isCancelled(), "enchant commit rechecks current policy", checks);
            block.setType(Material.CRAFTER, false);
            ((Crafter) block.getState()).getInventory().setItem(0, restricted);
            var automatic = new CrafterCraftEvent(block, recipe, new ItemStack(Material.STONE));
            Bukkit.getPluginManager().callEvent(automatic);
            check(
                    automatic.isCancelled(),
                    "actual crafter block inventory prevents restricted crafting",
                    checks);
            var chest = view(player, Bukkit.createInventory(null, 27), InventoryView.class);
            var click =
                    new InventoryClickEvent(
                            chest,
                            InventoryType.SlotType.CONTAINER,
                            0,
                            ClickType.NUMBER_KEY,
                            InventoryAction.HOTBAR_SWAP,
                            0);
            Bukkit.getPluginManager().callEvent(click);
            check(!click.isCancelled(), "generic chest/menu clicks are unaffected", checks);
            policy.set(Map.of());
            var released =
                    new BlockPlaceEvent(
                            block,
                            oldBlock,
                            block,
                            restricted,
                            player,
                            true,
                            EquipmentSlot.OFF_HAND);
            Bukkit.getPluginManager().callEvent(released);
            check(
                    !released.isCancelled(),
                    "policy replacement immediately permits previously blocked items",
                    checks);
            boolean invalid = false;
            try {
                ItemUseRestrictions.read(
                        new NiConfig(
                                Map.of(
                                        "ItemLoom",
                                        Map.of(
                                                "UsageRestrictions",
                                                Map.of("blocked", Map.of("placement", "false"))))));
            } catch (IllegalArgumentException expected) {
                invalid = true;
            }
            check(invalid, "invalid boolean fails policy preparation", checks);
            return Map.of(
                    "passed",
                    true,
                    "checks",
                    checks,
                    "limits",
                    "Synthetic Paper event dispatch and inventory views; actual crafter block restored after test. No client crafting/drag packets or external menu plugins.");
        } finally {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            if (oldBlock != null) oldBlock.update(true, false);
        }
    }

    private static ItemStack item(boolean legacy) {
        CompoundTag data = new CompoundTag();
        if (legacy) {
            CompoundTag value = new CompoundTag();
            value.putString("id", "blocked");
            value.putString("data", "{}");
            data.put("NeigeItems", value);
        } else
            data.put(
                    ItemStateCodec.KEY,
                    new ItemStateCodec()
                            .encode(new ItemIdentity("blocked", Map.of()), new CompoundTag()));
        return NmsItems.withCustomData(new ItemStack(Material.STONE), data);
    }

    @SuppressWarnings("unchecked")
    private static <T extends InventoryView> T view(
            org.bukkit.entity.Player player,
            org.bukkit.inventory.Inventory inventory,
            Class<T> type) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (self, method, args) ->
                                switch (method.getName()) {
                                    case "getTopInventory" -> inventory;
                                    case "getBottomInventory" -> player.getInventory();
                                    case "getInventory" -> inventory;
                                    case "getPlayer" -> player;
                                    case "getType" -> inventory.getType();
                                    case "getCursor", "getItem" -> new ItemStack(Material.AIR);
                                    case "convertSlot" -> args[0];
                                    case "getSlotType" -> InventoryType.SlotType.CONTAINER;
                                    default -> null;
                                });
    }

    private static void check(boolean result, String label, List<String> checks) {
        if (!result) throw new AssertionError(label);
        checks.add(label);
    }
}

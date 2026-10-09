package dev.itemloom.probe;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.ItemDurabilityService.DamageResult;
import dev.itemloom.paper.action.ItemListeners;
import dev.itemloom.paper.action.ItemMaintenance;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiPaperRecipe;
import dev.itemloom.paper.compat.script.LegacyItemEditorManager;
import dev.itemloom.paper.compat.script.LegacyItemExpirationEvent;
import dev.itemloom.paper.compat.script.LegacyItemUpdateEvent;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.java.JavaPlugin;

/** In-memory inventories and synthetic events; never edits configuration or connected players. */
final class ItemMaintenanceProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final List<String> ENABLED =
            List.of(
                    "abyss_walker_blade",
                    "division_legendary_sword",
                    "division_epic_chestplate",
                    "division_common_sword",
                    "division_rolled_chestplate");
    private static final List<String> DISABLED =
            List.of(
                    "preview_enchant_empty",
                    "preview_enchant_one",
                    "preview_enchant_two",
                    "preview_enchant_four");

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        Checks checks = new Checks();
        checks.group(
                "tick-update",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        ticks(checks, f);
                    }
                });
        checks.group(
                "container",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        container(checks, f);
                    }
                });
        checks.group(
                "container-disabled",
                () -> {
                    try (Fixture f = new Fixture(plugin, false, false)) {
                        Inventory inventory = Bukkit.createInventory(null, 54);
                        inventory.setItem(53, f.old(ENABLED.getFirst()));
                        checks.that(
                                !f.maintenance.checkInventory(f.player, inventory)
                                        && inventory.getItem(53).getType()
                                                == Material.DIAMOND_SWORD,
                                "open-container setting prevents its scan");
                        f.player.getInventory().setItem(40, f.old(ENABLED.getFirst()));
                        f.listeners.tick(List.of(f.player));
                        checks.that(
                                f.player.getInventory().getItem(40).getType()
                                        == Material.IRON_SWORD,
                                "open-container setting does not disable player maintenance");
                    }
                });
        checks.group(
                "expiration",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        expiration(checks, f);
                    }
                });
        checks.group(
                "drop",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        drops(checks, f);
                    }
                });
        checks.group(
                "force-sync",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, true)) {
                        forceSync(checks, f);
                    }
                });
        checks.group(
                "damage",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        damage(checks, f);
                    }
                });
        checks.group(
                "mend-anvil",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        repairs(checks, f);
                    }
                });
        checks.group(
                "tnt",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        tnt(checks, f);
                    }
                });
        checks.group(
                "lifecycle",
                () -> {
                    try (Fixture f = new Fixture(plugin, true, false)) {
                        lifecycle(checks, f);
                    }
                });
        CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        Fixture deferred;
        try {
            deferred = new Fixture(plugin, true, false);
        } catch (Throwable error) {
            checks.failures.put("next-tick-return", error.toString());
            return CompletableFuture.completedFuture(result(checks));
        }
        try {
            ItemStack stacked = deferred.durable(10, false, 4);
            deferred.player.getInventory().setItem(0, stacked);
            deferred.maintenance.durability().damage(deferred.player, stacked, 2);
            Bukkit.getScheduler()
                    .runTaskLater(
                            plugin,
                            () -> {
                                try {
                                    checks.group(
                                            "next-tick-return",
                                            () -> {
                                                int total =
                                                        Arrays.stream(
                                                                        deferred.player
                                                                                .getInventory()
                                                                                .getContents())
                                                                .filter(
                                                                        item ->
                                                                                item != null
                                                                                        && !item
                                                                                                .isEmpty())
                                                                .mapToInt(ItemStack::getAmount)
                                                                .sum();
                                                int undamaged =
                                                        Arrays.stream(
                                                                        deferred.player
                                                                                .getInventory()
                                                                                .getContents())
                                                                .filter(
                                                                        item ->
                                                                                item != null
                                                                                        && !item
                                                                                                .isEmpty())
                                                                .filter(
                                                                        item ->
                                                                                properties(item)
                                                                                                .getInt(
                                                                                                        "durability")
                                                                                                .orElse(
                                                                                                        -1)
                                                                                        == 10)
                                                                .mapToInt(ItemStack::getAmount)
                                                                .sum();
                                                checks.that(
                                                        total == 4
                                                                && undamaged == 3
                                                                && stacked.getAmount() == 1,
                                                        "scheduled return restores exactly the undamaged remainder on the next tick");
                                            });
                                } finally {
                                    try {
                                        deferred.close();
                                    } catch (Throwable error) {
                                        checks.failures.put(
                                                "next-tick-return-close", error.toString());
                                    }
                                    completion.complete(result(checks));
                                }
                            },
                            2);
        } catch (Throwable error) {
            checks.failures.put("next-tick-return", error.toString());
            try {
                deferred.close();
            } catch (Throwable closeError) {
                checks.failures.put("next-tick-return-close", closeError.toString());
            }
            completion.complete(result(checks));
        }
        return completion;
    }

    private static Map<String, Object> result(Checks checks) {
        return Map.of(
                "passed",
                checks.failures.isEmpty(),
                "assertions",
                checks.count,
                "checks",
                checks.passed,
                "failures",
                checks.failures);
    }

    private static void ticks(Checks c, Fixture f) {
        c.that(!f.catalog.triggers().hasKeyPrefix("tick_"), "fixture has no tick actions");
        for (int i = 0; i < ENABLED.size(); i++)
            f.player.getInventory().setItem(i, f.old(ENABLED.get(i)));
        for (int i = 0; i < DISABLED.size(); i++)
            f.player.getInventory().setItem(10 + i, f.old(DISABLED.get(i)));
        f.player.getInventory().setItem(40, f.old(ENABLED.getFirst()));
        f.listeners.tick(List.of(f.player));
        for (int i = 0; i < ENABLED.size(); i++) {
            ItemStack item = f.player.getInventory().getItem(i);
            c.that(
                    item.getType() == Material.IRON_SWORD
                            && properties(item).getInt("hashCode").orElseThrow()
                                    == f.hash(ENABLED.get(i)),
                    "enabled definition updates without tick actions: " + ENABLED.get(i));
            c.that(
                    CODEC.read(item).orElseThrow().rolls().get("saved").equals("roll-value")
                            && item.getAmount() == 3
                            && properties(item).getInt("durability").orElseThrow() == 80
                            && properties(item).getInt("charge").orElseThrow() == 13,
                    "update retains amount, rolls, custom durability and charge: "
                            + ENABLED.get(i));
        }
        for (int i = 0; i < DISABLED.size(); i++)
            c.that(
                    f.player.getInventory().getItem(10 + i).getType() == Material.DIAMOND_SWORD,
                    "disabled definition stays unchanged: " + DISABLED.get(i));
        c.that(
                f.player.getInventory().getItem(40).getType() == Material.IRON_SWORD,
                "maintenance reaches offhand slot 40");
        int count = f.updates;
        f.listeners.tick(List.of(f.player));
        c.that(f.updates == count, "matching hashes do not regenerate on the next tick");
        ItemStack missingHash = f.old(ENABLED.getFirst());
        edit(missingHash, tag -> tag.remove("hashCode"));
        byte[] before = missingHash.serializeAsBytes();
        f.maintenance.check(f.player, missingHash);
        c.that(
                Arrays.equals(before, missingHash.serializeAsBytes()),
                "missing hash follows the legacy default-to-current rule");
        ItemStack malformedRolls = f.old(DISABLED.getFirst());
        var tag = NmsItems.customData(malformedRolls);
        tag.getCompoundOrEmpty(ItemStateCodec.KEY).putString("rolls", "not-a-compound");
        malformedRolls = NmsItems.withCustomData(malformedRolls, tag);
        before = malformedRolls.serializeAsBytes();
        f.maintenance.check(f.player, malformedRolls);
        c.that(
                Arrays.equals(before, malformedRolls.serializeAsBytes()),
                "disabled update fast path never parses saved rolls");
        f.player.getInventory().setItem(0, corrupt());
        f.player.getInventory().setItem(1, f.old(ENABLED.getFirst()));
        f.listeners.tick(List.of(f.player));
        c.that(
                f.player.getInventory().getItem(1).getType() == Material.IRON_SWORD,
                "malformed slot cannot starve the next tick slot");
    }

    private static void container(Checks c, Fixture f) {
        Inventory inventory = Bukkit.createInventory(null, 54);
        inventory.setItem(0, corrupt());
        inventory.setItem(53, f.old(ENABLED.getFirst()));
        c.that(
                f.maintenance.checkInventory(f.player, inventory),
                "first container scan is allowed");
        c.that(
                inventory.getItem(53).getType() == Material.IRON_SWORD,
                "bad first slot does not prevent last container slot update");
        inventory.setItem(53, f.old(ENABLED.getFirst()));
        c.that(
                !f.maintenance.checkInventory(f.player, inventory)
                        && inventory.getItem(53).getType() == Material.DIAMOND_SWORD,
                "reopening within 1000 ms is throttled");
        f.clock.now += 999;
        c.that(
                !f.maintenance.checkInventory(f.player, inventory),
                "999 ms still respects container cooldown");
        f.clock.now++;
        c.that(
                f.maintenance.checkInventory(f.player, inventory)
                        && inventory.getItem(53).getType() == Material.IRON_SWORD,
                "1000 ms permits the next scan");
    }

    private static void expiration(Checks c, Fixture f) {
        ItemStack item = f.old(ENABLED.getFirst());
        edit(item, tag -> tag.putLong("itemTime", f.clock.now));
        // Real Craft inventory/NMS aliasing proves the whole stack is removed, not just a wrapper.
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, item);
        ItemStack mirror = inventory.getItem(0);
        var handle = CraftItemStack.unwrap(mirror);
        f.maintenance.check(f.player, mirror);
        c.that(
                handle.isEmpty()
                        && (inventory.getItem(0) == null || inventory.getItem(0).isEmpty()),
                "expiration at the exact timestamp clears all stacked inventory items");
        c.that(
                f.expirations == 1 && f.updates == 0,
                "expiration precedes update and emits one compatibility event");
        item = f.old(ENABLED.getFirst());
        edit(item, tag -> tag.putLong("itemTime", f.clock.now - 1));
        f.expirationEffect = event -> event.setCancelled(true);
        f.maintenance.check(f.player, item);
        c.that(
                item.getType() == Material.IRON_SWORD && item.getAmount() == 3 && f.updates == 1,
                "cancelling expiration still allows automatic update");
        item = f.old(ENABLED.getFirst());
        edit(item, tag -> tag.putLong("itemTime", f.clock.now + 1));
        int calls = f.expirations;
        f.maintenance.check(f.player, item);
        c.that(f.expirations == calls, "future timestamp does not fire expiration");
        item = f.old(ENABLED.getFirst());
        edit(item, tag -> tag.putLong("itemTime", f.clock.now));
        f.expirationEffect = event -> event.getItemStack().setAmount(2);
        f.maintenance.check(f.player, item);
        c.that(
                item.getAmount() == 2,
                "expiration callback replacement is not removed using stale state");
    }

    private static void drops(Checks c, Fixture f) {
        Drop drop = new Drop(f.old(ENABLED.getFirst()));
        PlayerDropItemEvent event = new PlayerDropItemEvent(f.player, drop.entity);
        f.listeners.drop(event);
        c.that(
                drop.stack.getType() == Material.IRON_SWORD
                        && !drop.removed
                        && !event.isCancelled(),
                "drop path updates and writes its item back to the entity");
        drop = new Drop(f.old(ENABLED.getFirst()));
        edit(drop.stack, tag -> tag.putLong("itemTime", f.clock.now));
        event = new PlayerDropItemEvent(f.player, drop.entity);
        f.listeners.drop(event);
        c.that(
                drop.removed && event.isCancelled(),
                "expired dropped stack removes the entity and cancels the event");

        Drop replaced = new Drop(f.old(ENABLED.getFirst()));
        edit(replaced.stack, tag -> tag.putLong("itemTime", f.clock.now));
        var previousHandle = CraftItemStack.unwrap(replaced.stack);
        f.expirationEffect =
                expiration -> replaced.entity.setItemStack(replaced.entity.getItemStack().clone());
        event = new PlayerDropItemEvent(f.player, replaced.entity);
        f.listeners.drop(event);
        c.that(
                CraftItemStack.unwrap(replaced.stack) != previousHandle
                        && replaced.stack.getAmount() == 3
                        && !replaced.removed
                        && !event.isCancelled(),
                "equal-valued replacement entity handle cannot inherit a pending expiration commit");

        Drop swapped = new Drop(f.old(ENABLED.getFirst()));
        edit(swapped.stack, tag -> tag.putLong("itemTime", f.clock.now));
        f.expirationEffect =
                expiration -> {
                    f.player.getInventory().setItem(8, swapped.entity.getItemStack());
                    swapped.entity.setItemStack(new ItemStack(Material.DIAMOND, 2));
                };
        event = new PlayerDropItemEvent(f.player, swapped.entity);
        f.listeners.drop(event);
        c.that(
                swapped.stack.getType() == Material.DIAMOND
                        && swapped.stack.getAmount() == 2
                        && f.player.getInventory().getItem(8).getAmount() == 3
                        && !swapped.removed,
                "maintenance callback source movement and entity replacement survive without stale writeback");
    }

    private static void forceSync(Checks c, Fixture f) {
        ItemStack item = f.old(DISABLED.getFirst());
        CraftItemStack.unwrap(item).set(DataComponents.DAMAGE, 7);
        f.maintenance.check(f.player, item);
        c.that(damageValue(item) == 7, "open/drop check does not force-sync displayed durability");
        f.maintenance.check(f.player, item, true);
        c.that(
                damageValue(item) == LegacyItemEditorManager.checkDurability(item, 80, 100),
                "tick requested force-sync uses configured custom durability");
        var custom = CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        f.maintenance.check(f.player, item, true);
        c.that(
                CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA) == custom,
                "stable force-sync does not replace custom data");
        ItemStack updated = f.old(ENABLED.getFirst());
        f.maintenance.check(f.player, updated, true);
        c.that(
                damageValue(updated) == LegacyItemEditorManager.checkDurability(updated, 80, 100),
                "force-sync reads the regenerated material and current properties");
    }

    private static void damage(Checks c, Fixture f) {
        var service = f.maintenance.durability();
        ItemStack item = f.durable(20, false, 1);
        byte[] before = item.serializeAsBytes();
        c.that(
                service.damage(f.player, item, -1) == DamageResult.INVALID_DAMAGE
                        && service.damage(f.player, item, 0) == DamageResult.ZERO_DAMAGE
                        && Arrays.equals(before, item.serializeAsBytes()),
                "non-positive damage does not mutate items");
        c.that(
                service.damage(f.player, new ItemStack(Material.IRON_SWORD), 1)
                        == DamageResult.VANILLA,
                "ordinary vanilla item bypasses custom damage");
        item = f.durable(0, false, 1);
        PlayerItemDamageEvent event = new PlayerItemDamageEvent(f.player, item, 3);
        service.damageGate(event);
        c.that(event.isCancelled(), "zero custom durability blocks the vanilla damage event");
        f.player.getInventory().setItem(EquipmentSlot.OFF_HAND, item);
        var entityEvent = new PlayerInteractEntityEvent(f.player, f.player, EquipmentSlot.OFF_HAND);
        service.interactEntity(entityEvent);
        c.that(entityEvent.isCancelled(), "right-click entity gate checks the actual offhand");
        item = f.durable(80, false, 1);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1000);
        CraftItemStack.unwrap(item).set(DataComponents.REPAIR_COST, 17);
        CraftItemStack.unwrap(item).set(DataComponents.CUSTOM_NAME, Component.literal("kept-name"));
        event = new PlayerItemDamageEvent(f.player, item, 5);
        service.itemDamage(event);
        int expected = (int) (item.getType().getMaxDurability() * (1 - 75d / 100));
        c.that(
                properties(item).getInt("durability").orElseThrow() == 75
                        && event.getDamage() == expected,
                "damage event applies its post-unbreaking damage exactly once and converts display delta");
        c.that(
                CraftItemStack.unwrap(item).get(DataComponents.REPAIR_COST) == 17
                        && CraftItemStack.unwrap(item)
                                .get(DataComponents.CUSTOM_NAME)
                                .getString()
                                .equals("kept-name")
                        && NmsItems.customData(item)
                                .getString("probe:unknown")
                                .orElseThrow()
                                .equals("preserved"),
                "custom damage preserves unrelated components and data");
        item = f.durable(2, false, 1);
        event = new PlayerItemDamageEvent(f.player, item, 5);
        service.itemDamage(event);
        c.that(
                !item.isEmpty()
                        && properties(item).getInt("durability").orElseThrow() == 0
                        && event.getDamage() == item.getType().getMaxDurability() - 1,
                "itemBreak false leaves an unusable item short of vanilla break");
        item = f.durable(2, true, 1);
        event = new PlayerItemDamageEvent(f.player, item, 5);
        service.itemDamage(event);
        c.that(
                event.getDamage() > item.getType().getMaxDurability(),
                "itemBreak true delegates a lethal vanilla event damage");
        item = f.durable(10, false, 4);
        f.player.getInventory().setItem(0, item);
        service.damage(f.player, item, 2);
        c.that(
                item.getAmount() == 1 && properties(item).getInt("durability").orElseThrow() == 8,
                "custom damage affects one item in a stacked tool");
        f.catalog.triggers().flushReturns(f.player);
        c.that(
                Arrays.stream(f.player.getInventory().getContents())
                                .filter(stack -> stack != null && !stack.isEmpty())
                                .filter(
                                        stack ->
                                                properties(stack).getInt("durability").orElse(-1)
                                                        == 10)
                                .mapToInt(ItemStack::getAmount)
                                .sum()
                        == 3,
                "undamaged stacked remainder is returned without loss or duplicate");
    }

    private static void repairs(Checks c, Fixture f) {
        ItemStack item = f.durable(95, false, 1);
        CraftItemStack.unwrap(item).set(DataComponents.REPAIR_COST, 8);
        int[] xp = {9};
        ExperienceOrb orb =
                proxy(
                        ExperienceOrb.class,
                        (method, args) ->
                                switch (method) {
                                    case "getExperience" -> xp[0];
                                    case "setExperience" -> {
                                        xp[0] = (int) args[0];
                                        yield null;
                                    }
                                    default -> null;
                                });
        var mend = new PlayerItemMendEvent(f.player, item, EquipmentSlot.HAND, orb, 8);
        f.maintenance.durability().mend(mend);
        c.that(
                mend.isCancelled()
                        && xp[0] == 5
                        && properties(item).getInt("durability").orElseThrow() == 100,
                "mending cancels vanilla repair, spends repairAmount/2 XP, and clamps custom durability");
        c.that(
                damageValue(item) == 0
                        && CraftItemStack.unwrap(item).get(DataComponents.REPAIR_COST) == 8,
                "mending synchronizes display and preserves unrelated components");
        ItemStack original = f.durable(60, false, 1);
        CraftItemStack.unwrap(original).set(DataComponents.DAMAGE, 100);
        ItemStack result = original.clone();
        CraftItemStack.unwrap(result).set(DataComponents.DAMAGE, 90);
        var anvil = anvil(f.player, original, result);
        f.maintenance.durability().anvil(anvil);
        c.that(
                properties(anvil.getResult()).getInt("durability").orElseThrow() == 70
                        && properties(original).getInt("durability").orElseThrow() == 60,
                "anvil repair adds vanilla damage difference only to the result");
        result = original.clone();
        edit(result, tag -> tag.putInt("durability", 40));
        CraftItemStack.unwrap(result).set(DataComponents.DAMAGE, 90);
        anvil = anvil(f.player, original, result);
        f.maintenance.durability().anvil(anvil);
        c.that(
                properties(anvil.getResult()).getInt("durability").orElseThrow() == 40,
                "another plugin's custom repair prevents duplicate anvil correction");
        ItemStack legacy = f.legacyDurable(30);
        f.maintenance.durability().damage(f.player, legacy, 3);
        c.that(
                NmsItems.customData(legacy)
                                .getCompoundOrEmpty("NeigeItems")
                                .getInt("durability")
                                .orElseThrow()
                        == 27,
                "unmigrated NI durability is still editable");
    }

    private static void tnt(Checks c, Fixture f) {
        ItemStack fire = f.durable(3, false, 1);
        fire.setType(Material.FIRE_CHARGE);
        Block block =
                proxy(
                        Block.class,
                        (method, args) -> method.equals("getType") ? Material.TNT : null);
        var event =
                new PlayerInteractEvent(
                        f.player,
                        Action.RIGHT_CLICK_BLOCK,
                        fire,
                        block,
                        BlockFace.UP,
                        EquipmentSlot.HAND);
        f.maintenance.durability().igniteTnt(event);
        c.that(
                !event.isCancelled()
                        && properties(fire).getInt("durability").orElseThrow() == 2
                        && fire.getAmount() == 2,
                "TNT ignition deducts one durability and compensates vanilla charge consumption");
        fire.setAmount(fire.getAmount() - 1);
        c.that(
                fire.getAmount() == 1,
                "simulated vanilla charge consumption conserves the surviving item");
        f.player.setGameMode(GameMode.CREATIVE);
        f.maintenance.durability().igniteTnt(event);
        c.that(
                properties(fire).getInt("durability").orElseThrow() == 2,
                "creative ignition does not deduct custom durability");
    }

    private static void lifecycle(Checks c, Fixture f) {
        c.that(
                Arrays.stream(
                                org.bukkit.event.player.PlayerItemDamageEvent.getHandlerList()
                                        .getRegisteredListeners())
                        .noneMatch(
                                listener -> listener.getListener() == f.maintenance.durability()),
                "candidate construction registers no durability listeners");
        f.maintenance.activate();
        f.maintenance.activate();
        c.that(
                Arrays.stream(
                                        org.bukkit.event.player.PlayerItemDamageEvent
                                                .getHandlerList()
                                                .getRegisteredListeners())
                                .filter(
                                        listener ->
                                                listener.getListener()
                                                        == f.maintenance.durability())
                                .count()
                        == 2,
                "activation is idempotent and registers the two damage priorities");
        ItemStack item = f.old(ENABLED.getFirst());
        edit(item, tag -> tag.putLong("itemTime", f.clock.now));
        byte[] before = item.serializeAsBytes();
        f.expirationEffect = event -> f.catalog.close();
        c.that(
                !f.maintenance.check(f.player, item)
                        && Arrays.equals(before, item.serializeAsBytes()),
                "expiration callback closing revision prevents stale deletion or update");
        f.maintenance.close();
        c.that(
                Arrays.stream(
                                org.bukkit.event.player.PlayerItemDamageEvent.getHandlerList()
                                        .getRegisteredListeners())
                        .noneMatch(
                                listener -> listener.getListener() == f.maintenance.durability()),
                "close unregisters durability listeners");
    }

    private static PrepareAnvilEvent anvil(Player player, ItemStack original, ItemStack result) {
        AnvilInventory inventory =
                proxy(
                        AnvilInventory.class,
                        (method, args) -> method.equals("getItem") ? original : null);
        AnvilView view =
                proxy(
                        AnvilView.class,
                        (method, args) ->
                                switch (method) {
                                    case "getTopInventory" -> inventory;
                                    case "getPlayer" -> player;
                                    default -> null;
                                });
        return new PrepareAnvilEvent(view, result);
    }

    private static int damageValue(ItemStack item) {
        return CraftItemStack.unwrap(item).getOrDefault(DataComponents.DAMAGE, 0);
    }

    private static CompoundTag properties(ItemStack item) {
        return NmsItems.customData(item)
                .getCompoundOrEmpty(ItemStateCodec.KEY)
                .getCompoundOrEmpty("properties");
    }

    private static void edit(ItemStack item, Consumer<CompoundTag> change) {
        CompoundTag tag = NmsItems.customData(item);
        change.accept(tag.getCompoundOrEmpty(ItemStateCodec.KEY).getCompoundOrEmpty("properties"));
        CraftItemStack.unwrap(item).set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static ItemStack corrupt() {
        CompoundTag tag = new CompoundTag();
        tag.putString(ItemStateCodec.KEY, "bad-envelope");
        return NmsItems.withCustomData(new ItemStack(Material.STONE), tag);
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Invocation handler) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (self, method, args) -> {
                            if (method.getName().equals("hashCode"))
                                return System.identityHashCode(self);
                            if (method.getName().equals("equals")) return self == args[0];
                            if (method.getName().equals("toString"))
                                return "MaintenanceProbe[" + type.getSimpleName() + "]";
                            Object value = handler.call(method.getName(), args);
                            if (value != null
                                    || !method.getReturnType().isPrimitive()
                                    || method.getReturnType() == void.class) return value;
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == long.class) return 0L;
                            if (method.getReturnType() == double.class) return 0d;
                            if (method.getReturnType() == float.class) return 0f;
                            return 0;
                        }));
    }

    private static final class Drop {
        ItemStack stack;
        boolean removed;
        final java.util.UUID id = java.util.UUID.randomUUID();
        final Item entity =
                proxy(
                        Item.class,
                        (method, args) ->
                                switch (method) {
                                    // Match real CraftItem: fresh wrappers share the current NMS
                                    // handle, whereas
                                    // setters copy the supplied value into a replacement handle.
                                    case "getItemStack" ->
                                            CraftItemStack.asCraftMirror(
                                                    CraftItemStack.unwrap(stack));
                                    case "setItemStack" -> {
                                        stack =
                                                CraftItemStack.asCraftMirror(
                                                        CraftItemStack.asNMSCopy(
                                                                (ItemStack) args[0]));
                                        yield null;
                                    }
                                    case "remove" -> {
                                        removed = true;
                                        yield null;
                                    }
                                    case "isValid" -> !removed;
                                    case "isDead" -> removed;
                                    case "getUniqueId" -> id;
                                    default -> null;
                                });

        Drop(ItemStack stack) {
            this.stack = CraftItemStack.asCraftMirror(CraftItemStack.asNMSCopy(stack));
        }
    }

    private static final class MutableClock extends Clock {
        long now = 2_000_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Player player = ProbePlayer.create("ItemMaintenanceProbe");
        final PlayerActionState state = new PlayerActionState();
        final Listener listener = new Listener() {};
        final MutableClock clock = new MutableClock();
        final NiCatalog catalog;
        final ItemMaintenance maintenance;
        final ItemListeners listeners;
        int updates, expirations;
        Consumer<LegacyItemExpirationEvent> expirationEffect;

        Fixture(JavaPlugin plugin, boolean checkInventory, boolean forceSync) {
            state.join(player.getUniqueId());
            Map<String, NiRepository.Definition> definitions = new LinkedHashMap<>();
            for (String id :
                    java.util.stream.Stream.concat(ENABLED.stream(), DISABLED.stream()).toList()) {
                NiConfig config =
                        NiYaml.read(
                                "material: IRON_SWORD\noptions:\n  durability: 100\n  charge: 20\n  update: {enable: "
                                        + ENABLED.contains(id)
                                        + "}\n",
                                "memory/maintenance.yml");
                definitions.put(
                        id, new NiRepository.Definition(id, "memory/maintenance.yml", config));
            }
            NiConfig settings =
                    new NiConfig(
                            Map.of(
                                    "ItemCheck",
                                    Map.of("checkInventory", checkInventory),
                                    "ItemDurability",
                                    Map.of("forceSync", forceSync)));
            var input =
                    new NiRepository.Input(
                            settings,
                            definitions,
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());
            try {
                catalog = new NiCatalog(1, input, plugin, (viewer, text) -> null, state);
            } catch (Throwable error) {
                state.close();
                throw error;
            }
            maintenance = new ItemMaintenance(catalog, plugin, clock);
            listeners = new ItemListeners(catalog::triggers, () -> maintenance, plugin.getLogger());
            Bukkit.getPluginManager()
                    .registerEvent(
                            LegacyItemUpdateEvent.PreGenerate.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, raw) -> {
                                if (((LegacyItemUpdateEvent.PreGenerate) raw).getPlayer() == player)
                                    updates++;
                            },
                            plugin);
            Bukkit.getPluginManager()
                    .registerEvent(
                            LegacyItemExpirationEvent.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, raw) -> {
                                var event = (LegacyItemExpirationEvent) raw;
                                if (event.getPlayer() != player) return;
                                expirations++;
                                if (expirationEffect != null) expirationEffect.accept(event);
                            },
                            plugin);
        }

        int hash(String id) {
            return ((NiPaperRecipe) catalog.catalog().recipes().get(id)).definitionHash();
        }

        ItemStack old(String id) {
            CompoundTag properties = new CompoundTag();
            properties.putInt("hashCode", hash(id) ^ 0x40000000);
            properties.putInt("durability", 80);
            properties.putInt("maxDurability", 100);
            properties.putInt("charge", 13);
            properties.putInt("maxCharge", 20);
            properties.putBoolean("itemBreak", false);
            return CODEC.write(
                    new ItemStack(Material.DIAMOND_SWORD, 3),
                    new ItemIdentity(id, Map.of("saved", "roll-value")),
                    properties);
        }

        ItemStack durable(int current, boolean breakItem, int amount) {
            ItemStack item = old(DISABLED.getFirst());
            item.setType(Material.IRON_SWORD);
            item.setAmount(amount);
            edit(
                    item,
                    tag -> {
                        tag.putInt("durability", current);
                        tag.putBoolean("itemBreak", breakItem);
                    });
            CompoundTag custom = NmsItems.customData(item);
            custom.putString("probe:unknown", "preserved");
            CraftItemStack.unwrap(item).set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
            return item;
        }

        ItemStack legacyDurable(int current) {
            CompoundTag root = new CompoundTag(), old = new CompoundTag();
            old.putString("id", DISABLED.getFirst());
            old.putString("data", "{}");
            old.putInt("durability", current);
            old.putInt("maxDurability", 100);
            root.put("NeigeItems", old);
            return NmsItems.withCustomData(new ItemStack(Material.IRON_SWORD), root);
        }

        @Override
        public void close() {
            HandlerList.unregisterAll(listener);
            try {
                maintenance.close();
                catalog.close();
            } finally {
                state.close();
            }
        }
    }

    private static final class Checks {
        int count;
        String group;
        final List<String> passed = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        void group(String name, Runnable test) {
            group = name;
            try {
                test.run();
            } catch (Throwable error) {
                failures.put(name, error.toString());
            }
        }

        void that(boolean condition, String message) {
            count++;
            if (!condition) throw new AssertionError(message);
            passed.add(group + ": " + message);
        }
    }
}

package dev.itemloom.paper.action;

import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.LegacyItemPack;
import dev.itemloom.paper.compat.script.LegacyScheduler;
import dev.itemloom.paper.integration.OptionalItemIntegrations;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.entity.item.ItemEntity;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public final class ItemDrops {
    private final NiItemOperations items;
    private final LegacyScheduler scheduler;
    private final OptionalItemIntegrations integrations;

    public ItemDrops(
            NiItemOperations items,
            LegacyScheduler scheduler,
            OptionalItemIntegrations integrations) {
        this.items = items;
        this.scheduler = scheduler;
        this.integrations = integrations;
    }

    public CompletableFuture<Item> drop(Location location, ItemStack item, Entity trigger) {
        Location target = location.clone();
        return scheduler.callSyncStrict(() -> spawn(target, item, trigger, null, null, false));
    }

    public CompletableFuture<Item> drop(
            Location location,
            ItemStack item,
            Entity trigger,
            LegacyNbt.Compound tag,
            LegacyNbt.Compound properties) {
        Location target = location.clone();
        return scheduler.callSyncStrict(() -> spawn(target, item, trigger, tag, properties, true));
    }

    public CompletableFuture<Item> drop(
            Location location, ItemStack item, Entity trigger, LegacyNbt.Compound tag) {
        Location target = location.clone();
        return scheduler.callSyncStrict(() -> spawn(target, item, trigger, tag, null, false));
    }

    public CompletableFuture<List<Item>> amount(
            Location location, ItemStack item, Integer total, Entity trigger) {
        Location target = location.clone();
        return scheduler.callSyncStrict(
                () -> {
                    items.ensureActive();
                    int amount = total == null ? 1 : total;
                    if (amount <= 0) return List.of();
                    List<Item> spawned = new ArrayList<>();
                    for (ItemStack portion : LegacyItemPack.split(item, amount)) {
                        Item accepted = spawn(target, portion, trigger, null, null, false);
                        if (accepted != null) spawned.add(accepted);
                    }
                    return spawned;
                });
    }

    public CompletableFuture<List<Item>> list(
            List<? extends ItemStack> requested,
            Location location,
            Entity trigger,
            String horizontal,
            String vertical,
            String mode) {
        Location target = location.clone();
        List<ItemStack> snapshot = new ArrayList<>(requested);
        return scheduler.callSyncStrict(
                () -> {
                    items.ensureActive();
                    var random = ThreadLocalRandom.current();
                    DropMotion motion = DropMotion.prepare(horizontal, vertical, mode, random);
                    List<Item> spawned = new ArrayList<>();
                    for (int index = 0; index < snapshot.size(); index++) {
                        Item accepted =
                                spawn(target, snapshot.get(index), trigger, null, null, false);
                        if (accepted != null) {
                            if (motion != null) {
                                DropMotion.Velocity velocity =
                                        motion.at(index, snapshot.size(), random);
                                accepted.setVelocity(
                                        new Vector(velocity.x(), velocity.y(), velocity.z()));
                            }
                            spawned.add(accepted);
                        }
                    }
                    return spawned;
                });
    }

    private Item spawn(
            Location location,
            ItemStack stack,
            Entity trigger,
            LegacyNbt.Compound suppliedTag,
            LegacyNbt.Compound suppliedProperties,
            boolean explicit) {
        items.ensureActive();
        LegacyNbtItemStack view = new LegacyNbtItemStack(stack);
        LegacyNbt.Compound tag = suppliedTag == null ? view.getOrCreateTag() : suppliedTag;
        LegacyNbt.Compound properties =
                explicit ? suppliedProperties : tag.getCompoundOrNull("NeigeItems");
        String owner = properties == null ? null : properties.getStringOrNull("owner");
        boolean hide = properties != null && properties.getBoolean("hide", false);
        String skill = properties == null ? null : properties.getStringOrNull("dropSkill");
        if (owner != null) properties.remove("owner");
        if (!(stack instanceof CraftItemStack)) tag.saveTo(stack);
        if (location.getWorld() == null) return null;
        var world = ((CraftWorld) location.getWorld()).getHandle();
        ItemEntity entity =
                new ItemEntity(
                        world,
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        CraftItemStack.asNMSCopy(stack));
        entity.setDefaultPickUpDelay();
        Item result = (Item) entity.getBukkitEntity();
        DropOwnership.assign(result, owner, hide, items.catalog().plugin());
        if (skill != null) {
            var mythic = integrations.mythic();
            if (mythic != null) mythic.castSkill(result, skill, trigger);
        }
        items.ensureActive();
        if (!world.addFreshEntity(entity, SpawnReason.CUSTOM)) return null;
        return result;
    }
}

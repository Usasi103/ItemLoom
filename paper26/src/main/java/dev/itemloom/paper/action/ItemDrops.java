package dev.itemloom.paper.action;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.LegacyItemPack;
import dev.itemloom.paper.compat.script.LegacyScheduler;
import dev.itemloom.paper.integration.OptionalItemIntegrations;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** Revision-owned drops; all live item reads, mutation and entity creation run on the server thread. */
public final class ItemDrops {
    private final NiItemOperations owner;
    private final LegacyScheduler scheduler;
    private final OptionalItemIntegrations integrations;

    public ItemDrops(
            NiItemOperations owner,
            LegacyScheduler scheduler,
            OptionalItemIntegrations integrations) {
        this.owner = owner;
        this.scheduler = scheduler;
        this.integrations = integrations;
    }

    public CompletableFuture<Item> drop(Location location, ItemStack stack, Entity trigger) {
        Location at = location.clone();
        return scheduler.callSyncStrict(() -> single(at, stack, trigger, null, null, false, null));
    }

    public CompletableFuture<Item> drop(
            Location location,
            ItemStack stack,
            Entity trigger,
            LegacyNbt.Compound tag,
            LegacyNbt.Compound properties) {
        Location at = location.clone();
        return scheduler.callSyncStrict(
                () -> single(at, stack, trigger, tag, properties, true, null));
    }

    public CompletableFuture<Item> drop(
            Location location, ItemStack stack, Entity trigger, LegacyNbt.Compound tag) {
        Location at = location.clone();
        return scheduler.callSyncStrict(() -> single(at, stack, trigger, tag, null, false, null));
    }

    public CompletableFuture<List<Item>> amount(
            Location location, ItemStack stack, Integer amount, Entity trigger) {
        Location at = location.clone();
        return scheduler.callSyncStrict(
                () -> {
                    owner.ensureActive();
                    List<Item> result = new ArrayList<>();
                    int total = amount == null ? 1 : amount;
                    if (total <= 0) return result;
                    // Prepare each independent copy before a drop removes the owner from its
                    // source.
                    for (ItemStack split : LegacyItemPack.split(stack, total)) {
                        Item dropped = single(at, split, trigger, null, null, false, null);
                        if (dropped != null) result.add(dropped);
                    }
                    return result;
                });
    }

    public CompletableFuture<List<Item>> list(
            List<? extends ItemStack> stacks,
            Location location,
            Entity trigger,
            String x,
            String y,
            String angle) {
        Location at = location.clone();
        List<ItemStack> requested = new ArrayList<>(stacks);
        return scheduler.callSyncStrict(
                () -> {
                    owner.ensureActive();
                    var random = ThreadLocalRandom.current();
                    DropMotion motion = DropMotion.prepare(x, y, angle, random);
                    List<Item> accepted = new ArrayList<>();
                    for (int index = 0; index < requested.size(); index++) {
                        Vector velocity = null;
                        if (motion != null) {
                            var value = motion.at(index, requested.size(), random);
                            velocity = new Vector(value.x(), value.y(), value.z());
                        }
                        Item entity =
                                single(
                                        at,
                                        requested.get(index),
                                        trigger,
                                        null,
                                        null,
                                        false,
                                        velocity);
                        if (entity != null) accepted.add(entity);
                    }
                    return accepted;
                });
    }

    private Item single(
            Location location,
            ItemStack stack,
            Entity trigger,
            LegacyNbt.Compound suppliedTag,
            LegacyNbt.Compound suppliedProperties,
            boolean explicit,
            Vector velocity) {
        owner.ensureActive();
        LegacyNbt.Compound tag =
                suppliedTag == null ? new LegacyNbtItemStack(stack).getOrCreateTag() : suppliedTag;
        LegacyNbt.Compound properties =
                explicit ? suppliedProperties : tag.getCompound("NeigeItems");
        String name = properties == null ? null : properties.getString("owner");
        boolean hide = properties != null && properties.getBoolean("hide", false);
        String skill = properties == null ? null : properties.getString("dropSkill");
        if (name != null) {
            properties.remove("owner");
            if (!(stack instanceof org.bukkit.craftbukkit.inventory.CraftItemStack))
                tag.saveTo(stack);
        }
        if (location.getWorld() == null) return null;
        var world = ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle();
        var entity =
                new net.minecraft.world.entity.item.ItemEntity(
                        world,
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(stack));
        entity.setDefaultPickUpDelay();
        Item result = (Item) entity.getBukkitEntity();
        DropOwnership.assign(result, name, hide, owner.catalog().plugin());
        if (skill != null) {
            var mythic = integrations.mythic();
            if (mythic != null) mythic.castSkill(result, skill, trigger);
        }
        owner.ensureActive();
        // isValid also depends on chunk tracking, so it cannot report spawn acceptance.
        // Use Paper's actual NMS insertion result, including ItemSpawnEvent cancellation.
        if (!world.addFreshEntity(
                entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM)) return null;
        if (velocity != null) result.setVelocity(velocity);
        return result;
    }
}

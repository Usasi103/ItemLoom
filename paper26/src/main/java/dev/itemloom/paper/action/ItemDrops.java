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
        List<ItemStack> entries = new ArrayList<>(stacks);
        return scheduler.callSyncStrict(
                () -> {
                    owner.ensureActive();
                    boolean fancy = x != null && y != null && angle != null;
                    double offsetX = fancy ? offset(x) : 0, offsetY = fancy ? offset(y) : 0;
                    List<Item> result = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) {
                        Vector velocity = fancy ? new Vector(offsetX, offsetY, 0) : null;
                        if (fancy && (angle.equals("round") || angle.equals("random"))) {
                            double phase = Math.PI * 2 * i / entries.size();
                            // Keep NI's two independent angles for random mode.
                            double cos =
                                    Math.cos(
                                            angle.equals("random")
                                                    ? Math.PI
                                                            * 2
                                                            * ThreadLocalRandom.current()
                                                                    .nextDouble()
                                                    : phase);
                            double sin =
                                    Math.sin(
                                            angle.equals("random")
                                                    ? Math.PI
                                                            * 2
                                                            * ThreadLocalRandom.current()
                                                                    .nextDouble()
                                                    : phase);
                            velocity.setX(cos * offsetX).setZ(-sin * offsetX);
                        }
                        Item dropped =
                                single(at, entries.get(i), trigger, null, null, false, velocity);
                        if (dropped != null) result.add(dropped);
                    }
                    return result;
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

    private static double offset(String text) {
        int separator = text.indexOf('-');
        if (separator < 0) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException invalid) {
                return 0.1;
            }
        }
        double min, max;
        try {
            min = Double.parseDouble(text.substring(0, separator));
            max = Double.parseDouble(text.substring(separator + 1));
        } catch (NumberFormatException invalid) {
            return 0.1;
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
    }
}

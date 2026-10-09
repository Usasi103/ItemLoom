package dev.itemloom.paper.action;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** One physical event source; equal values in a replacement handle never inherit its ownership. */
final class TriggerSource {
    record PlayerSlot(UUID player, int slot) {}

    private record Cursor(UUID player) {}

    private record Dropped(UUID entity) {}

    private record EventStack(UUID player, Identity stack) {}

    private record ContainerSlot(Identity container, int slot) {}

    private record Identity(Object value) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && identity.value == value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }

    final Player player;
    final Object key;
    final ItemStack original;
    private ItemStack snapshot;
    private final Supplier<ItemStack> read;
    private final Consumer<ItemStack> write;
    private final BooleanSupplier valid;
    private final Item entity;
    private final Object handle;

    private TriggerSource(
            Player player,
            Object key,
            Supplier<ItemStack> read,
            Consumer<ItemStack> write,
            BooleanSupplier valid,
            Item entity) {
        this.player = player;
        this.key = key;
        this.read = read;
        this.write = write;
        this.valid = valid;
        this.entity = entity;
        original = read.get();
        handle = reference(original);
    }

    static TriggerSource equipment(Player player, EquipmentSlot equipment) {
        PlayerInventory inventory = player.getInventory();
        int selected = inventory.getHeldItemSlot();
        int slot =
                switch (equipment) {
                    case HAND -> selected;
                    case OFF_HAND -> 40;
                    case HEAD -> 39;
                    case CHEST -> 38;
                    case LEGS -> 37;
                    case FEET -> 36;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported player equipment source: " + equipment);
                };
        return new TriggerSource(
                player,
                new PlayerSlot(player.getUniqueId(), slot),
                () -> inventory.getItem(slot),
                value -> inventory.setItem(slot, value),
                () -> equipment != EquipmentSlot.HAND || inventory.getHeldItemSlot() == selected,
                null);
    }

    static TriggerSource cursor(Player player, InventoryView view) {
        BooleanSupplier visible = sameView(player, view);
        return new TriggerSource(
                player,
                new Cursor(player.getUniqueId()),
                view::getCursor,
                view::setCursor,
                visible,
                null);
    }

    static TriggerSource clicked(Player player, InventoryView view, int rawSlot) {
        Inventory inventory = view.getInventory(rawSlot);
        if (inventory == null) return null;
        int slot = view.convertSlot(rawSlot);
        Object key =
                inventory instanceof PlayerInventory
                        ? new PlayerSlot(player.getUniqueId(), slot)
                        : new ContainerSlot(
                                new Identity(
                                        inventory instanceof CraftInventory craft
                                                ? craft.getInventory()
                                                : inventory),
                                slot);
        return new TriggerSource(
                player,
                key,
                () -> inventory.getItem(slot),
                value -> inventory.setItem(slot, value),
                sameView(player, view),
                null);
    }

    static TriggerSource dropped(Player player, Item entity) {
        // A PlayerDropItemEvent can precede world insertion, so isValid() is not a liveness test.
        return new TriggerSource(
                player,
                new Dropped(entity.getUniqueId()),
                entity::getItemStack,
                entity::setItemStack,
                () -> !entity.isDead(),
                entity);
    }

    /** Projectile consumables can already be detached by vanilla; their event owns that handle. */
    static TriggerSource eventStack(Player player, Supplier<ItemStack> read) {
        ItemStack item = read.get();
        return new TriggerSource(
                player,
                new EventStack(player.getUniqueId(), new Identity(reference(item))),
                read,
                value -> NmsItems.replace(item, value),
                () -> true,
                null);
    }

    private static BooleanSupplier sameView(Player player, InventoryView view) {
        if (player instanceof CraftHumanEntity craft) {
            var container = craft.getHandle().containerMenu;
            return () ->
                    craft.getHandle().containerMenu == container
                            && container.getBukkitView() == view;
        }
        return () -> player.getOpenInventory() == view;
    }

    boolean accepts(ItemStack supplied) {
        if (reference(supplied) != handle
                || !same(original, supplied)
                || !player.isOnline()
                || !valid.getAsBoolean()) return false;
        ItemStack current = read.get();
        if (reference(current) != handle || !same(original, current)) return false;
        // Most equipment slots have no matching trigger. Snapshot only after finding one,
        // immediately before entering callbacks, rather than cloning NBT on every event.
        snapshot = empty(original) ? null : original.clone();
        return true;
    }

    boolean unchanged() {
        if (!player.isOnline() || !valid.getAsBoolean()) return false;
        ItemStack current = read.get();
        return reference(current) == handle && same(snapshot, current);
    }

    ItemStack candidate() {
        return snapshot == null ? null : snapshot.clone();
    }

    ItemStack commit(ItemStack candidate) {
        // Inventory/cursor mirrors are also held by the vanilla event caller. Retain that
        // NMS identity when possible so its later durability/use logic sees the committed value.
        if (entity == null && original instanceof CraftItemStack)
            NmsItems.replace(original, candidate);
        else write.accept(candidate);
        ItemStack actual = read.get();
        if (!same(candidate, actual))
            throw new IllegalStateException(
                    "Item trigger source did not retain its prepared value");
        return actual;
    }

    /** Refresh entity metadata from its current owned value, never from a pre-script snapshot. */
    void synchronizeEntity(ItemStack committed) {
        if (entity == null || !valid.getAsBoolean()) return;
        ItemStack current = read.get();
        if (reference(current) != reference(committed)) return;
        if (empty(current)) entity.remove();
        else write.accept(current);
    }

    boolean entityEmpty() {
        return entity != null && (!valid.getAsBoolean() || empty(read.get()));
    }

    private static Object reference(ItemStack item) {
        return item instanceof CraftItemStack ? CraftItemStack.unwrap(item) : item;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return empty(a) ? empty(b) : a.equals(b);
    }
}

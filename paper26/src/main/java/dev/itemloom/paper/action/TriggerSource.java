package dev.itemloom.paper.action;

import dev.itemloom.paper.nms.NmsItems;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

final class TriggerSource {
    final Player player;
    final Object key;
    final ItemStack original;
    private final Object reference;
    private final Supplier<ItemStack> read;
    private final Consumer<ItemStack> write;
    private final BooleanSupplier valid;
    private final boolean preserveHandle;
    private final Item entity;
    private ItemStack snapshot;
    private Object committed;
    private boolean committedEmpty;

    private TriggerSource(
            Player player,
            Object key,
            Supplier<ItemStack> read,
            Consumer<ItemStack> write,
            BooleanSupplier valid,
            boolean preserveHandle,
            Item entity) {
        this.player = player;
        this.key = key;
        this.read = read;
        this.write = write;
        this.valid = valid;
        this.preserveHandle = preserveHandle;
        this.entity = entity;
        original = read.get();
        reference = identity(original);
    }

    static TriggerSource equipment(Player player, EquipmentSlot equipment) {
        int selected = player.getInventory().getHeldItemSlot();
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
                                    "Unsupported equipment slot: " + equipment);
                };
        return new TriggerSource(
                player,
                new PlayerSlot(player.getUniqueId(), slot),
                () -> player.getInventory().getItem(slot),
                value -> player.getInventory().setItem(slot, value),
                () ->
                        equipment != EquipmentSlot.HAND
                                || player.getInventory().getHeldItemSlot() == selected,
                true,
                null);
    }

    static TriggerSource cursor(Player player, InventoryView view) {
        BooleanSupplier valid = viewGuard(player, view);
        return new TriggerSource(
                player,
                new EndpointKey(view, -1),
                view::getCursor,
                view::setCursor,
                valid,
                true,
                null);
    }

    static TriggerSource clicked(Player player, InventoryView view, int raw) {
        Inventory inventory = view.getInventory(raw);
        if (inventory == null) return null;
        int slot = view.convertSlot(raw);
        Object key =
                inventory == player.getInventory() || inventory.equals(player.getInventory())
                        ? new PlayerSlot(player.getUniqueId(), slot)
                        : new EndpointKey(
                                inventory instanceof CraftInventory craft
                                        ? craft.getInventory()
                                        : inventory,
                                slot);
        return new TriggerSource(
                player,
                key,
                () -> inventory.getItem(slot),
                value -> inventory.setItem(slot, value),
                viewGuard(player, view),
                true,
                null);
    }

    static TriggerSource dropped(Player player, Item item) {
        return new TriggerSource(
                player,
                item.getUniqueId(),
                item::getItemStack,
                item::setItemStack,
                () -> !item.isDead(),
                false,
                item);
    }

    static TriggerSource eventStack(Player player, Supplier<ItemStack> read) {
        ItemStack original = read.get();
        return new TriggerSource(
                player,
                new EndpointKey(identity(original), player.getUniqueId()),
                read,
                value -> NmsItems.replace(original, value),
                () -> true,
                true,
                null);
    }

    private static BooleanSupplier viewGuard(Player player, InventoryView view) {
        Object menu = player instanceof CraftPlayer craft ? craft.getHandle().containerMenu : null;
        return () ->
                player.getOpenInventory() == view
                        && (!(player instanceof CraftPlayer craft)
                                || craft.getHandle().containerMenu == menu);
    }

    boolean accepts(ItemStack supplied) {
        if (!player.isOnline() || !valid.getAsBoolean() || identity(supplied) != reference)
            return false;
        ItemStack current = read.get();
        if (identity(current) != reference
                || !Objects.equals(current, original)
                || !Objects.equals(supplied, original)) return false;
        snapshot = original == null ? null : original.clone();
        return true;
    }

    boolean unchanged() {
        if (!player.isOnline() || !valid.getAsBoolean()) return false;
        ItemStack current = read.get();
        return identity(current) == reference && Objects.equals(current, snapshot);
    }

    ItemStack candidate() {
        return snapshot == null ? null : snapshot.clone();
    }

    boolean ownsCommitted(ItemStack value) {
        if (!player.isOnline() || !valid.getAsBoolean()) return false;
        ItemStack current = read.get();
        return committedEmpty
                ? empty(current)
                : identity(current) == committed && identity(value) == committed;
    }

    ItemStack commit(ItemStack prepared) {
        return commit(prepared, false);
    }

    ItemStack commit(ItemStack prepared, boolean useSetter) {
        if (!unchanged())
            throw new IllegalStateException("Physical item source changed before commit");
        if (!useSetter && preserveHandle && original instanceof CraftItemStack)
            NmsItems.replace(original, prepared);
        else write.accept(prepared);
        ItemStack current = read.get();
        if (!(empty(current) && empty(prepared)) && !Objects.equals(current, prepared)) {
            throw new IllegalStateException(
                    "Physical item source did not retain the prepared value");
        }
        committed = identity(current);
        committedEmpty = empty(current);
        return current;
    }

    void synchronizeEntity(ItemStack value) {
        if (entity == null || entity.isDead()) return;
        ItemStack current = read.get();
        if (committedEmpty && empty(current)) entity.remove();
        else if (identity(current) == committed && identity(value) == committed)
            entity.setItemStack(current);
    }

    boolean entityEmpty() {
        return entity != null && (entity.isDead() || empty(read.get()));
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    private static Object identity(ItemStack stack) {
        return stack instanceof CraftItemStack craft ? craft.handle : stack;
    }

    record PlayerSlot(UUID player, int slot) {}

    private static final class EndpointKey {
        final Object object;
        final Object slot;

        EndpointKey(Object object, Object slot) {
            this.object = object;
            this.slot = slot;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(object) * 31 + slot.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EndpointKey key
                    && object == key.object
                    && slot.equals(key.slot);
        }
    }
}

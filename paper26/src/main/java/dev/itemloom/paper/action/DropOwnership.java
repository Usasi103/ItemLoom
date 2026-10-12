package dev.itemloom.paper.action;

import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.runtime.DropOwnershipPolicy;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Drop ownership stores a durable scalar owner and retains the legacy metadata mirror. */
public final class DropOwnership implements Listener {
    private static final NamespacedKey OWNER = new NamespacedKey("itemloom", "drop_owner");
    private final Supplier<DropOwnershipPolicy> policy;

    public DropOwnership(Supplier<DropOwnershipPolicy> policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public static void assign(Item item, String owner, boolean hide, Plugin plugin) {
        ItemsService.requireThread();
        item.addScoreboardTag("ItemLoom");
        item.addScoreboardTag("NeigeItems");
        if (hide) {
            item.addScoreboardTag("NI-Hide");
        }
        if (owner != null) {
            setOwner(item, owner, plugin);
        }
    }

    public static void setOwner(Item item, String owner, Plugin plugin) {
        ItemsService.requireThread();
        if (owner == null) {
            item.getPersistentDataContainer().remove(OWNER);
            item.removeMetadata("NI-Owner", plugin);
        } else {
            item.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, owner);
            item.setMetadata("NI-Owner", new FixedMetadataValue(plugin, owner));
        }
        Bukkit.getPluginManager().callEvent(new Changed(item));
    }

    public static String owner(Item item) {
        String persistent = item.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
        if (persistent != null) {
            return persistent;
        }
        for (MetadataValue value : item.getMetadata("NI-Owner")) {
            if (value.value() instanceof String owner) {
                return owner;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void pickup(EntityPickupItemEvent event) {
        String owner = owner(event.getItem());
        if (owner == null
                || event.getEntity() instanceof Player player && owner.equals(player.getName())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getEntity() instanceof Player player)
                || event.getItem().getScoreboardTags().contains("NI-Hide")) {
            return;
        }
        DropOwnershipPolicy current = policy.get();
        if (current == null || current.invalidOwnerMessage() == null) {
            return;
        }
        String message = current.invalidOwnerMessage().replace("{name}", owner);
        if ("message".equals(current.messageType())) {
            player.sendMessage(message);
        } else if ("actionbar".equals(current.messageType())) {
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(message));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void merge(ItemMergeEvent event) {
        Item source = event.getEntity();
        Item target = event.getTarget();
        if (!Objects.equals(owner(source), owner(target))
                || source.getScoreboardTags().contains("NI-Hide")
                        != target.getScoreboardTags().contains("NI-Hide")) {
            event.setCancelled(true);
        }
    }

    public static final class Changed extends EntityEvent {
        private static final HandlerList HANDLERS = new HandlerList();

        private Changed(Item item) {
            super(item);
        }

        @Override
        public Item getEntity() {
            return (Item) super.getEntity();
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}

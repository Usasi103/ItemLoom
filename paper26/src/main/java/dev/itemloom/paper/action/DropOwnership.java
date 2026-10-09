package dev.itemloom.paper.action;

import java.util.Objects;
import java.util.function.Supplier;
import dev.itemloom.compat.ni.NiConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Entity ownership survives chunk saves; old metadata is accepted for existing drops. */
public final class DropOwnership implements Listener {
    private static final NamespacedKey OWNER = new NamespacedKey("itemloom", "drop_owner");
    private final Supplier<NiConfig> settings;

    public DropOwnership(Supplier<NiConfig> settings) {
        this.settings = settings;
    }

    public static void assign(Item item, String owner, boolean hide, Plugin plugin) {
        dev.itemloom.paper.ItemsService.requireThread();
        item.addScoreboardTag("ItemLoom");
        // Script-facing markers remain part of the configuration compatibility surface.
        item.addScoreboardTag("NeigeItems");
        if (hide) item.addScoreboardTag("NI-Hide");
        if (owner != null) setOwner(item, owner, plugin);
    }

    public static void setOwner(Item item, String owner, Plugin plugin) {
        dev.itemloom.paper.ItemsService.requireThread();
        if (owner == null) {
            item.getPersistentDataContainer().remove(OWNER);
            item.removeMetadata("NI-Owner", plugin);
        } else {
            item.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, owner);
            item.setMetadata("NI-Owner", new FixedMetadataValue(plugin, owner));
        }
        new Changed(item).callEvent();
    }

    /** Internal main-thread notification also covers the old script metadata setter. */
    public static final class Changed extends org.bukkit.event.entity.EntityEvent {
        private static final org.bukkit.event.HandlerList HANDLERS =
                new org.bukkit.event.HandlerList();

        private Changed(Item item) {
            super(item);
        }

        @Override
        public Item getEntity() {
            return (Item) entity;
        }

        @Override
        public org.bukkit.event.HandlerList getHandlers() {
            return HANDLERS;
        }

        public static org.bukkit.event.HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static String owner(Item item) {
        dev.itemloom.paper.ItemsService.requireThread();
        String saved = item.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
        if (saved != null) return saved;
        for (var metadata : item.getMetadata("NI-Owner"))
            if (metadata.value() instanceof String value) return value;
        return null;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void pickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        String owner = owner(event.getItem());
        if (owner == null || player.getName().equals(owner)) return;
        event.setCancelled(true);
        if (event.getItem().getScoreboardTags().contains("NI-Hide")) return;
        NiConfig config = settings.get();
        if (config == null) return;
        String message = config.string("Messages.invalidOwnerMessage", null);
        if (message == null) return;
        message = message.replace("{name}", owner);
        switch (config.string("ItemOwner.messageType", "")) {
            case "message" -> player.sendMessage(message);
            case "actionbar" -> player.sendActionBar(message);
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void merge(ItemMergeEvent event) {
        Item from = event.getEntity(), into = event.getTarget();
        if (!Objects.equals(owner(from), owner(into))
                || from.getScoreboardTags().contains("NI-Hide")
                        != into.getScoreboardTags().contains("NI-Hide")) {
            event.setCancelled(true);
        }
    }
}

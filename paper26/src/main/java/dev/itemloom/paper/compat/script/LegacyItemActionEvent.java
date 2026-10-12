package dev.itemloom.paper.compat.script;

import dev.itemloom.api.ItemActionEvent;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class LegacyItemActionEvent extends ItemActionEvent {
    private final LegacyItemInfo itemInfo;
    private final LegacyItemActionType type;
    private final LegacyActionTrigger trigger;

    public LegacyItemActionEvent(
            Player player,
            ItemStack itemStack,
            LegacyItemInfo itemInfo,
            LegacyItemActionType type,
            LegacyActionTrigger trigger) {
        super(
                player,
                itemStack,
                Objects.requireNonNull(itemInfo, "itemInfo").getId(),
                Objects.requireNonNull(type, "type").getType(),
                !Bukkit.isPrimaryThread());
        this.itemInfo = itemInfo;
        this.type = type;
        this.trigger = Objects.requireNonNull(trigger, "trigger");
    }

    public LegacyItemInfo getItemInfo() {
        return itemInfo;
    }

    public LegacyItemActionType getType() {
        return type;
    }

    public LegacyActionTrigger getTrigger() {
        return trigger;
    }

    @Override
    public String getEventName() {
        return "ItemActionEvent";
    }

    public boolean call() {
        Bukkit.getPluginManager().callEvent(this);
        return !isCancelled();
    }
}

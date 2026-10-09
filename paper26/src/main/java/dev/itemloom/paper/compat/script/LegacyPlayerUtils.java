package dev.itemloom.paper.compat.script;

import java.util.Objects;
import dev.itemloom.paper.action.PlayerActionState;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.Metadatable;
import org.bukkit.plugin.Plugin;

/**
 * The metadata and cooldown subset of the old script PlayerUtils namespace.
 * Alias an instance of this adapter; no static plugin singleton or old-package class is required.
 */
public final class LegacyPlayerUtils {
    public final LegacyPlayerUtils INSTANCE = this;
    private final Plugin plugin;
    private final PlayerActionState state;

    public LegacyPlayerUtils(Plugin plugin, PlayerActionState state) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.state = Objects.requireNonNull(state, "state");
    }

    public boolean hasMetadataEZ(Player player, String key) {
        return state.hasMetadata(player.getUniqueId(), key);
    }

    public Object getMetadataEZ(Player player, String key, Object fallback) {
        return state.getMetadata(player.getUniqueId(), key, fallback);
    }

    public void setMetadataEZ(Player player, String key, Object value) {
        state.setMetadata(player.getUniqueId(), key, value);
    }

    /** One snapshot prevents async combo readers racing a main-thread append/reset. */
    public java.util.List<?> comboSnapshot(Player player, String group) {
        Object value =
                state.metadataIfAbsent(
                        player.getUniqueId(), "Combo-" + group, java.util.ArrayList::new);
        if (value == null) return java.util.List.of();
        if (!(value instanceof java.util.List<?> history))
            throw new IllegalStateException("Combo metadata is not a list: " + group);
        synchronized (history) {
            return new java.util.ArrayList<>(history);
        }
    }

    /** The Player overload uses private session data; other targets use Bukkit metadata. */
    public Object getMetadataEZ(Metadatable target, String key, Object fallback) {
        if (!state.active()) return fallback;
        if (target instanceof org.bukkit.entity.Item item && key.equals("NI-Owner")) {
            String owner = dev.itemloom.paper.action.DropOwnership.owner(item);
            return owner == null ? fallback : owner;
        }
        return target.hasMetadata(key) ? target.getMetadata(key).getFirst().value() : fallback;
    }

    public void setMetadataEZ(Metadatable target, String key, Object value) {
        if (!state.active()) return;
        if (target instanceof org.bukkit.entity.Item item && key.equals("NI-Owner")) {
            if (value != null && !(value instanceof String))
                throw new IllegalArgumentException("NI-Owner must be a player name or null");
            dev.itemloom.paper.action.DropOwnership.setOwner(item, (String) value, plugin);
        } else target.setMetadata(key, new FixedMetadataValue(plugin, value));
    }

    public long checkCooldown(Player player, String key, long cooldown) {
        return state.checkCooldown(player.getUniqueId(), key, cooldown);
    }

    public long getCooldown(Player player, String key) {
        return state.getCooldown(player.getUniqueId(), key);
    }

    public void setCooldown(Player player, String key, long cooldown) {
        state.setCooldown(player.getUniqueId(), key, cooldown);
    }
}

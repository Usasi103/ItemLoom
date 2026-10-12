package dev.itemloom.paper.compat.script;

import dev.itemloom.paper.action.DropOwnership;
import dev.itemloom.paper.action.PlayerActionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.Metadatable;
import org.bukkit.plugin.Plugin;

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

    public List<?> comboSnapshot(Player player, String group) {
        Object value =
                state.metadataIfAbsent(player.getUniqueId(), "Combo-" + group, ArrayList::new);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list))
            throw new IllegalStateException("Invalid combo history: " + group);
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public Object getMetadataEZ(Metadatable target, String key, Object fallback) {
        if (!state.active()) return fallback;
        if (target instanceof Item item && "NI-Owner".equals(key)) {
            String owner = DropOwnership.owner(item);
            return owner == null ? fallback : owner;
        }
        return target.hasMetadata(key) ? target.getMetadata(key).getFirst().value() : fallback;
    }

    public void setMetadataEZ(Metadatable target, String key, Object value) {
        if (!state.active()) return;
        if (target instanceof Item item && "NI-Owner".equals(key)) {
            if (value != null && !(value instanceof String)) {
                throw new IllegalArgumentException("NI-Owner must be a string or null");
            }
            DropOwnership.setOwner(item, (String) value, plugin);
            return;
        }
        target.setMetadata(key, new FixedMetadataValue(plugin, value));
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

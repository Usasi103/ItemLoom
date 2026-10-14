package dev.itemloom.paper.integration;

import dev.itemloom.paper.ItemsService;
import java.util.ArrayList;
import java.util.List;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/** Loaded only when PlaceholderAPI is enabled; requests never access a live inventory. */
public final class ComparisonExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final ItemsService items;
    private final String identifier;

    private ComparisonExpansion(JavaPlugin plugin, ItemsService items, String identifier) {
        this.plugin = plugin;
        this.items = items;
        this.identifier = identifier;
    }

    public static Runnable install(JavaPlugin plugin, ItemsService items) {
        List<ComparisonExpansion> installed = new ArrayList<>();
        for (String id : List.of("itemloom", "gearcompare")) {
            ComparisonExpansion expansion = new ComparisonExpansion(plugin, items, id);
            if (expansion.register()) installed.add(expansion);
            else
                plugin.getLogger()
                        .warning("Could not register comparison placeholder identifier " + id);
        }
        return () -> installed.forEach(PlaceholderExpansion::unregister);
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return "ItemLoom";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String parameters) {
        return items.comparisonPlaceholder(
                player == null ? null : player.getUniqueId(), parameters);
    }
}

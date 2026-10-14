package dev.itemloom.paper;

import dev.itemloom.api.ItemLoom;
import dev.itemloom.paper.command.ItemCommands;
import dev.itemloom.paper.integration.MythicDrops;
import dev.itemloom.paper.integration.NiPapiExpansion;
import dev.itemloom.paper.integration.PapiBridge;
import dev.itemloom.paper.integration.ComparisonExpansion;
import dev.itemloom.paper.integration.CraftEngineSource;
import dev.keystone.Keystone;
import dev.keystone.storage.StorageWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Platform entry owns startup order and guarantees cleanup after partial initialization. */
public final class ItemLoomPlugin extends JavaPlugin {
    private ItemsService items;
    private ItemCommands commands;
    private AutoCloseable mythic;
    private Runnable closePlaceholders;
    private Runnable closeComparisonPlaceholders;
    private AutoCloseable craftEngine;
    private boolean stopped;

    @Override
    public void onLoad() {
        Keystone.init(this);
    }

    @Override
    public void onEnable() {
        stopped = false;
        try {
            Path own = getDataFolder().toPath();
            Files.createDirectories(own);
            Path sibling = own.resolveSibling("NeigeItems");
            Path input =
                    !Files.isDirectory(own.resolve("Items"))
                                    && !Files.isDirectory(own.resolve("SX-Item"))
                                    && Files.isDirectory(sibling.resolve("Items"))
                            ? sibling
                            : own;
            boolean papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
            BiFunction<Object, String, String> placeholders =
                    papi ? new PapiBridge() : (viewer, text) -> null;
            items = new ItemsService(this, input, placeholders);
            if (papi) {
                closePlaceholders = NiPapiExpansion.install(this, items);
            }
            if (!items.reload(Bukkit.getConsoleSender())) {
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            if (papi) closeComparisonPlaceholders = ComparisonExpansion.install(this, items);
            craftEngine = CraftEngineSource.install(this, items);
            Bukkit.getServicesManager()
                    .register(ItemLoom.class, items, this, ServicePriority.Normal);
            mythic = MythicDrops.install(this, items);
            commands = new ItemCommands(this, items);
            commands.register();
            Bukkit.getScheduler().runTask(this, items::serverEnabled);
            getLogger().info("Loaded " + items.ids().size() + " items from " + input);
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "ItemLoom initialization failed", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            close(commands, "command jobs");
            commands = null;
            Bukkit.getServicesManager().unregisterAll(this);
            Bukkit.getScheduler().cancelTasks(this);
            close(mythic, "Mythic integration");
            close(craftEngine, "CraftEngine item source");
            craftEngine = null;
            if (closeComparisonPlaceholders != null) {
                closeComparisonPlaceholders.run();
                closeComparisonPlaceholders = null;
            }
            mythic = null;
            if (closePlaceholders != null) {
                try {
                    closePlaceholders.run();
                } catch (RuntimeException failure) {
                    getLogger()
                            .log(Level.WARNING, "Could not close placeholder integration", failure);
                } finally {
                    closePlaceholders = null;
                }
            }
        } finally {
            try {
                close(items, "item runtime");
                items = null;
            } finally {
                StorageWriter.shutdown();
            }
        }
    }

    private void close(AutoCloseable resource, String description) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception failure) {
            getLogger().log(Level.WARNING, "Could not close " + description, failure);
        }
    }
}

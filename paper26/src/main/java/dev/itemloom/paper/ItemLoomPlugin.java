package dev.itemloom.paper;

import dev.keystone.Keystone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.paper.integration.PapiBridge;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemLoomPlugin extends JavaPlugin {
    private ItemsService items;
    private Runnable closePlaceholders = () -> {};
    private AutoCloseable closeMythic = () -> {};
    private dev.itemloom.paper.command.ItemCommands commands;

    @Override
    public void onLoad() {
        Keystone.init(this);
    }

    @Override
    public void onEnable() {
        try {
            Path own = getDataFolder().toPath();
            Path legacy = own.resolveSibling("NeigeItems");
            Path input =
                    !Files.isDirectory(own.resolve("Items"))
                                    && !Files.isDirectory(own.resolve("SX-Item"))
                                    && Files.isDirectory(legacy.resolve("Items"))
                            ? legacy
                            : own;
            Files.createDirectories(own);
            BiFunction<Object, String, String> placeholders = (player, value) -> null;
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
                placeholders = new PapiBridge();
            items = new ItemsService(this, input, placeholders);
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
                closePlaceholders =
                        dev.itemloom.paper.integration.NiPapiExpansion.install(this, items);
            if (!items.reload(getServer().getConsoleSender())) {
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getServer()
                    .getServicesManager()
                    .register(ItemLoom.class, items, this, ServicePriority.Normal);
            closeMythic = dev.itemloom.paper.integration.MythicDrops.install(this, items);
            commands = new dev.itemloom.paper.command.ItemCommands(this, items);
            commands.register();
            getServer().getScheduler().runTask(this, items::serverEnabled);
            getLogger()
                    .info("ItemLoom loaded " + items.ids().size() + " definitions from " + input);
        } catch (Exception error) {
            getLogger()
                    .log(java.util.logging.Level.SEVERE, "ItemLoom initialization failed", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (commands != null) commands.close();
        getServer().getServicesManager().unregisterAll(this);
        getServer().getScheduler().cancelTasks(this);
        try {
            try {
                try {
                    closeMythic.close();
                } catch (Exception error) {
                    getLogger()
                            .log(
                                    java.util.logging.Level.WARNING,
                                    "Mythic bridge shutdown failed",
                                    error);
                }
                closePlaceholders.run();
            } finally {
                if (items != null) items.close();
            }
        } finally {
            dev.keystone.storage.StorageWriter.shutdown();
        }
    }
}

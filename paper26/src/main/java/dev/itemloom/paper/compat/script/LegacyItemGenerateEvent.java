package dev.itemloom.paper.compat.script;

import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.paper.compat.NiConfigViews;
import dev.itemloom.paper.compat.NiPaperRecipe;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class LegacyItemGenerateEvent extends ItemGenerateEvent {
    private final LegacyConfigReader configSection;
    private final Supplier<ConfigurationSection> sections;

    public LegacyItemGenerateEvent(
            String id,
            OfflinePlayer player,
            ItemStack itemStack,
            Map<String, String> cache,
            LegacyConfigReader configSection,
            ConfigurationSection sections) {
        this(id, player, itemStack, cache, configSection, () -> sections);
    }

    private LegacyItemGenerateEvent(
            String id,
            OfflinePlayer player,
            ItemStack itemStack,
            Map<String, String> cache,
            LegacyConfigReader configSection,
            Supplier<ConfigurationSection> sections) {
        super(id, player, cache, itemStack, !Bukkit.isPrimaryThread());
        this.configSection = Objects.requireNonNull(configSection, "configSection");
        this.sections = Objects.requireNonNull(sections, "sections");
    }

    public static LegacyItemGenerateEvent fromGenerated(
            String id,
            OfflinePlayer player,
            Map<String, String> cache,
            NiPaperRecipe.Generated generated) {
        return new LegacyItemGenerateEvent(
                id,
                player,
                generated.item(),
                cache,
                LegacyConfigReader.parse(NiConfigViews.map(generated.expanded())),
                generated.sections());
    }

    public OfflinePlayer getPlayer() {
        return getViewer();
    }

    public ItemStack getItemStack() {
        return getItem();
    }

    public void setItemStack(ItemStack itemStack) {
        setItem(itemStack);
    }

    public Map<String, String> getCache() {
        return getSavedRolls();
    }

    public LegacyConfigReader getConfigSection() {
        return configSection;
    }

    public ConfigurationSection getSections() {
        return sections.get();
    }

    @Override
    public String getEventName() {
        return "ItemGenerateEvent";
    }

    public boolean call() {
        Bukkit.getPluginManager().callEvent(this);
        return true;
    }
}

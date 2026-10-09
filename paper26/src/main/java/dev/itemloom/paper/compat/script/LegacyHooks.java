package dev.itemloom.paper.compat.script;

import java.util.function.BiFunction;
import dev.itemloom.compat.ni.action.NiActionText;
import dev.itemloom.paper.integration.OptionalItemIntegrations;
import org.bukkit.OfflinePlayer;

/** The small hook surface used by NI's bundled action functions. */
public final class LegacyHooks {
    public final LegacyHooks INSTANCE = this;
    private final BiFunction<Object, String, String> placeholders;
    private final OptionalItemIntegrations integrations;
    private final dev.itemloom.paper.integration.OptionalItemSources itemSources;

    public LegacyHooks(BiFunction<Object, String, String> placeholders) {
        this(placeholders, new OptionalItemIntegrations());
    }

    public LegacyHooks(
            BiFunction<Object, String, String> placeholders,
            OptionalItemIntegrations integrations) {
        this(placeholders, integrations, new dev.itemloom.paper.integration.OptionalItemSources());
    }

    public LegacyHooks(
            BiFunction<Object, String, String> placeholders,
            OptionalItemIntegrations integrations,
            dev.itemloom.paper.integration.OptionalItemSources itemSources) {
        this.placeholders = java.util.Objects.requireNonNull(placeholders);
        this.integrations = java.util.Objects.requireNonNull(integrations);
        this.itemSources = java.util.Objects.requireNonNull(itemSources);
    }

    public org.bukkit.inventory.ItemStack getHookedItem(String id) {
        return itemSources.getHookedItem(id);
    }

    public String papi(OfflinePlayer player, String text) {
        if (text == null) return null;
        // Use the same percent scanner as the action frontend; plain angle brackets remain literal.
        return NiActionText.replacePlaceholders(text, token -> placeholders.apply(player, token));
    }

    public String papiColor(OfflinePlayer player, String text) {
        return papi(player, org.bukkit.ChatColor.translateAlternateColorCodes('&', text));
    }

    public OptionalItemIntegrations.Mythic getMythicMobsHooker() {
        return integrations.mythic();
    }

    public OptionalItemIntegrations.Vault getVaultHooker() {
        return integrations.vault();
    }
}

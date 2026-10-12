package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.action.NiActionText;
import dev.itemloom.paper.integration.OptionalItemIntegrations;
import dev.itemloom.paper.integration.OptionalItemSources;
import java.util.Objects;
import java.util.function.BiFunction;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

public final class LegacyHooks {
    public final LegacyHooks INSTANCE = this;
    private final BiFunction<Object, String, String> placeholders;
    private final OptionalItemIntegrations integrations;
    private final OptionalItemSources itemSources;

    public LegacyHooks(BiFunction<Object, String, String> placeholders) {
        this(placeholders, new OptionalItemIntegrations());
    }

    public LegacyHooks(
            BiFunction<Object, String, String> placeholders,
            OptionalItemIntegrations integrations) {
        this(placeholders, integrations, new OptionalItemSources());
    }

    public LegacyHooks(
            BiFunction<Object, String, String> placeholders,
            OptionalItemIntegrations integrations,
            OptionalItemSources itemSources) {
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.integrations = Objects.requireNonNull(integrations, "integrations");
        this.itemSources = Objects.requireNonNull(itemSources, "itemSources");
    }

    public ItemStack getHookedItem(String id) {
        return itemSources.getHookedItem(id);
    }

    public String papi(OfflinePlayer player, String text) {
        return text == null
                ? null
                : NiActionText.replacePlaceholders(
                        text, token -> placeholders.apply(player, token));
    }

    public String papiColor(OfflinePlayer player, String text) {
        return papi(player, ChatColor.translateAlternateColorCodes('&', text));
    }

    public OptionalItemIntegrations.Mythic getMythicMobsHooker() {
        return integrations.mythic();
    }

    public OptionalItemIntegrations.Vault getVaultHooker() {
        return integrations.vault();
    }
}

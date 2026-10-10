/*
 * ItemBridge integration customization, 2026-10-10.
 * Based on the MIT-licensed discovery contract of ItemBridge 1.0.32,
 * copyright (c) 2025 jhqwqmc. See vendor/itembridge/LICENSE and README.md.
 */
package cn.gtemc.itembridge.hook;

import cn.gtemc.itembridge.api.Provider;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** ItemLoom's included ItemBridge discovery policy; all item operations stay in ItemBridge. */
public final class HookHelper {
    private HookHelper() {}

    public static Map<String, Provider<ItemStack, Player>> getSupportedPlugins(
            Consumer<String> onSuccess,
            BiConsumer<String, Throwable> onFailure,
            Predicate<Plugin> filter) {
        Map<String, Provider<ItemStack, Player>> providers = new LinkedHashMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            try {
                if (filter != null && !filter.test(plugin)) continue;
                Provider<ItemStack, Player> provider = provider(plugin);
                if (provider == null) continue;
                providers.put(provider.plugin(), provider);
                if (onSuccess != null) onSuccess.accept(plugin.getName());
            } catch (RuntimeException | LinkageError error) {
                if (onFailure != null) onFailure.accept(plugin.getName(), error);
            }
        }
        return providers;
    }

    // Package-private ItemBridge factories are accessed at compile time, within the shaded
    // dependency's own package. No reflective access or removed-provider class literal is used.
    private static Provider<ItemStack, Player> provider(Plugin plugin) {
        return switch (plugin.getName()) {
            case "CraftEngine" ->
                    CraftEngineProvider.Check.conflictCheck(plugin)
                            ? CraftEngineProvider.INSTANCE
                            : null;
            case "Nexo" -> NexoProvider.Check.conflictCheck(plugin) ? NexoProvider.INSTANCE : null;
            case "Oraxen" ->
                    OraxenProvider.Check.conflictCheck(plugin) ? OraxenProvider.INSTANCE : null;
            case "Nova" -> NovaProvider.Check.conflictCheck(plugin) ? NovaProvider.INSTANCE : null;
            case "MythicMobs" ->
                    MythicMobsProvider.Check.conflictCheck(plugin)
                            ? MythicMobsProvider.INSTANCE
                            : null;
            case "EcoArmor",
                    "EcoCrates",
                    "EcoItems",
                    "EcoMobs",
                    "EcoPets",
                    "EcoScrolls",
                    "Reforges",
                    "StatTrackers",
                    "Talismans" ->
                    EcoProvider.Check.conflictCheck(plugin)
                            ? new EcoProvider(plugin.getName().toLowerCase(Locale.ROOT))
                            : null;
            case "HMCCosmetics" ->
                    HMCCosmeticsProvider.Check.conflictCheck(plugin)
                            ? HMCCosmeticsProvider.INSTANCE
                            : null;
            case "Sertraline" ->
                    SertralineProvider.Check.conflictCheck(plugin)
                            ? SertralineProvider.INSTANCE
                            : null;
            case "MMOItems" ->
                    MMOItemsProvider.Check.conflictCheck(plugin) ? MMOItemsProvider.INSTANCE : null;
            case "CustomFishing" ->
                    CustomFishingProvider.Check.conflictCheck(plugin)
                            ? CustomFishingProvider.INSTANCE
                            : null;
            case "ItemsAdder" ->
                    ItemsAdderProvider.Check.conflictCheck(plugin)
                            ? ItemsAdderProvider.INSTANCE
                            : null;
            case "Zaphkiel" ->
                    ZaphkielProvider.Check.conflictCheck(plugin) ? ZaphkielProvider.INSTANCE : null;
            case "Slimefun" ->
                    SlimefunProvider.Check.conflictCheck(plugin) ? SlimefunProvider.INSTANCE : null;
            case "HeadDatabase" ->
                    HeadDatabaseProvider.Check.conflictCheck(plugin)
                            ? HeadDatabaseProvider.INSTANCE
                            : null;
            case "ExecutableItems" ->
                    ExecutableItemsProvider.Check.conflictCheck(plugin)
                            ? ExecutableItemsProvider.INSTANCE
                            : null;
            case "AzureFlow" ->
                    AzureFlowProvider.Check.conflictCheck(plugin)
                            ? AzureFlowProvider.INSTANCE
                            : null;
            case "MagicGem" ->
                    MagicGemProvider.Check.conflictCheck(plugin) ? MagicGemProvider.INSTANCE : null;
            case "PxRpg" ->
                    PxRpgProvider.Check.conflictCheck(plugin) ? PxRpgProvider.INSTANCE : null;
            case "Ratziel" ->
                    RatzielProvider.Check.conflictCheck(plugin) ? RatzielProvider.INSTANCE : null;
            case "Baikiruto" ->
                    BaikirutoProvider.Check.conflictCheck(plugin)
                            ? BaikirutoProvider.INSTANCE
                            : null;
            case "DragonArmourers" ->
                    DragonArmourersProvider.Check.conflictCheck(plugin)
                            ? DragonArmourersProvider.INSTANCE
                            : null;
            case "CrazyVouchers" ->
                    CrazyVouchersProvider.Check.conflictCheck(plugin)
                            ? CrazyVouchersProvider.INSTANCE
                            : null;
            case "ExecutableBlocks" ->
                    ExecutableBlocksProvider.Check.conflictCheck(plugin)
                            ? ExecutableBlocksProvider.INSTANCE
                            : null;
            case "ItemsXL" ->
                    ItemsXLProvider.Check.conflictCheck(plugin) ? ItemsXLProvider.INSTANCE : null;
            case "AdvancedItems" ->
                    AdvancedItemsProvider.Check.conflictCheck(plugin)
                            ? AdvancedItemsProvider.INSTANCE
                            : null;
            case "CustomCrafting" ->
                    CustomCraftingProvider.Check.conflictCheck(plugin)
                            ? CustomCraftingProvider.INSTANCE
                            : null;
            case "ItemEdit" ->
                    ItemEditProvider.Check.conflictCheck(plugin) ? ItemEditProvider.INSTANCE : null;
            case "EmakiItem" ->
                    EmakiItemProvider.Check.conflictCheck(plugin)
                            ? EmakiItemProvider.INSTANCE
                            : null;
            case "BreweryX" ->
                    BreweryXProvider.Check.conflictCheck(plugin) ? BreweryXProvider.INSTANCE : null;
            default -> null;
        };
    }
}

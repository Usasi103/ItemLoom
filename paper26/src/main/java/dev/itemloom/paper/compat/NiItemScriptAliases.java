package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.script.*;
import dev.itemloom.paper.action.PaperActions;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.*;
import java.util.*;
import java.util.function.BiFunction;
import jdk.dynalink.beans.StaticClass;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class NiItemScriptAliases {
    private NiItemScriptAliases() {}

    private static void alias(LegacyScriptBindings bindings, String name, Object value) {
        bindings.alias("pers.neige.neigeitems." + name, value);
    }

    private static void type(LegacyScriptBindings bindings, String name, Class<?> value) {
        alias(bindings, name, StaticClass.forClass(value));
    }

    static void install(
            LegacyScriptBindings bindings,
            NiItemOperations items,
            PaperActions actions,
            BiFunction<Object, String, String> placeholders) {
        LegacyItemManager manager = new LegacyItemManager(items);
        alias(bindings, "manager.ItemManager", manager);
        bindings.global("ItemManager", manager);
        type(bindings, "manager.ItemManager$SaveResult", LegacyItemManager.SaveResult.class);
        alias(bindings, "manager.ItemConfigManager", LegacyItemConstructors.configManager(items));
        alias(bindings, "manager.ItemPackManager", items.catalog().packs());
        bindings.global("ItemPackManager", items.catalog().packs());
        alias(bindings, "item.ItemPack", LegacyItemConstructors.pack(items));
        alias(bindings, "item.ItemPack$ItemInfo", LegacyItemConstructors.packItemInfo(items));
        alias(bindings, "item.ItemConfig", LegacyItemConstructors.config(items));
        alias(bindings, "item.ItemGenerator", LegacyItemConstructors.generator(items));
        type(bindings, "event.ItemGenerateEvent", LegacyItemGenerateEvent.class);
        type(bindings, "event.ItemPacketEvent", LegacyItemPacketEvent.class);
        alias(bindings, "item.ItemPlaceholder", items.catalog().itemPlaceholders());
        type(bindings, "item.ItemPlaceholder$ParseResult", LegacyItemPlaceholder.ParseResult.class);
        type(bindings, "event.ItemExpirationEvent", LegacyItemExpirationEvent.class);
        type(bindings, "event.ItemUpdateEvent", LegacyItemUpdateEvent.class);
        type(
                bindings,
                "event.ItemUpdateEvent$PreGenerate",
                LegacyItemUpdateEvent.PreGenerate.class);
        type(
                bindings,
                "event.ItemUpdateEvent$PostGenerate",
                LegacyItemUpdateEvent.PostGenerate.class);
        LegacyItemEditorManager editors =
                new LegacyItemEditorManager(
                        new LegacyItemEditorManager.Host() {
                            @Override
                            public Material material(String name) {
                                return NiItemAppearance.material(name);
                            }

                            @Override
                            public String papi(Player player, String text) {
                                return placeholders.apply(player, text);
                            }

                            @Override
                            public String section(Player player, ItemStack item, String text) {
                                var context = items.catalog().actionContext(player, null);
                                return context.invoke(
                                        () ->
                                                LegacySectionUtils.parseItemSection(
                                                        text,
                                                        item,
                                                        new LegacyNbtItemStack(item)
                                                                .getOrCreateTag(),
                                                        player));
                            }

                            @Override
                            public boolean refresh(
                                    Player player,
                                    ItemStack item,
                                    List<String> remove,
                                    Map<String, String> overrides,
                                    Integer amount) {
                                return items.refresh(player, item, remove, overrides, amount);
                            }

                            @Override
                            public boolean rebuild(
                                    Player player, ItemStack item, Map<String, String> overrides) {
                                return items.rebuild(item, player, overrides, null);
                            }
                        });
        alias(bindings, "manager.ItemEditorManager", editors);
        bindings.global("ItemEditorManager", editors);
        actions.editors(editors);
        type(bindings, "utils.function.TriFunction", LegacyItemEditorManager.Editor.class);
    }
}

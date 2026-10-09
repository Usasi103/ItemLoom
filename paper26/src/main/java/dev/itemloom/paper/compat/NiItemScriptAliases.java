package dev.itemloom.paper.compat;

import java.util.function.BiFunction;
import jdk.dynalink.beans.StaticClass;
import dev.itemloom.compat.ni.script.LegacyScriptBindings;
import dev.itemloom.compat.ni.script.LegacySectionUtils;
import dev.itemloom.paper.action.PaperActions;
import dev.itemloom.paper.compat.script.LegacyItemEditorManager;
import dev.itemloom.paper.compat.script.LegacyItemConstructors;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.compat.script.LegacyItemUpdateEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Revision-owned wiring of script names; no NI classes are loaded or installed. */
final class NiItemScriptAliases {
    private NiItemScriptAliases() {}

    static void install(
            LegacyScriptBindings bindings,
            NiItemOperations items,
            PaperActions actions,
            BiFunction<Object, String, String> placeholders) {
        var manager = new LegacyItemManager(items);
        bindings.alias("pers.neige.neigeitems.manager.ItemManager", manager);
        bindings.alias(
                "pers.neige.neigeitems.manager.ItemManager$SaveResult",
                StaticClass.forClass(LegacyItemManager.SaveResult.class));
        bindings.alias(
                "pers.neige.neigeitems.manager.ItemConfigManager",
                LegacyItemConstructors.configManager(items));
        bindings.global("ItemManager", manager);
        bindings.alias("pers.neige.neigeitems.manager.ItemPackManager", items.catalog().packs());
        bindings.global("ItemPackManager", items.catalog().packs());
        bindings.alias("pers.neige.neigeitems.item.ItemPack", LegacyItemConstructors.pack(items));
        bindings.alias(
                "pers.neige.neigeitems.item.ItemPack$ItemInfo",
                LegacyItemConstructors.packItemInfo(items));
        bindings.alias(
                "pers.neige.neigeitems.item.ItemConfig", LegacyItemConstructors.config(items));
        bindings.alias(
                "pers.neige.neigeitems.item.ItemGenerator",
                LegacyItemConstructors.generator(items));
        bindings.alias(
                "pers.neige.neigeitems.event.ItemGenerateEvent",
                StaticClass.forClass(
                        dev.itemloom.paper.compat.script.LegacyItemGenerateEvent.class));
        bindings.alias(
                "pers.neige.neigeitems.event.ItemPacketEvent",
                StaticClass.forClass(dev.itemloom.paper.compat.script.LegacyItemPacketEvent.class));
        bindings.alias(
                "pers.neige.neigeitems.item.ItemPlaceholder", items.catalog().itemPlaceholders());
        bindings.alias(
                "pers.neige.neigeitems.item.ItemPlaceholder$ParseResult",
                StaticClass.forClass(
                        dev.itemloom.paper.compat.script.LegacyItemPlaceholder.ParseResult.class));
        bindings.alias(
                "pers.neige.neigeitems.event.ItemExpirationEvent",
                StaticClass.forClass(
                        dev.itemloom.paper.compat.script.LegacyItemExpirationEvent.class));
        bindings.alias(
                "pers.neige.neigeitems.event.ItemUpdateEvent",
                StaticClass.forClass(LegacyItemUpdateEvent.class));
        bindings.alias(
                "pers.neige.neigeitems.event.ItemUpdateEvent$PreGenerate",
                StaticClass.forClass(LegacyItemUpdateEvent.PreGenerate.class));
        bindings.alias(
                "pers.neige.neigeitems.event.ItemUpdateEvent$PostGenerate",
                StaticClass.forClass(LegacyItemUpdateEvent.PostGenerate.class));
        var editors =
                new LegacyItemEditorManager(
                        new LegacyItemEditorManager.Host() {
                            public Material material(String text) {
                                return NiItemAppearance.material(text);
                            }

                            public String papi(Player player, String text) {
                                return placeholders.apply(player, text);
                            }

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

                            public boolean refresh(
                                    Player player,
                                    ItemStack item,
                                    java.util.List<String> remove,
                                    java.util.Map<String, String> overrides,
                                    Integer amount) {
                                return items.refresh(player, item, remove, overrides, amount);
                            }

                            public boolean rebuild(
                                    Player player,
                                    ItemStack item,
                                    java.util.Map<String, String> overrides) {
                                return items.rebuild(item, player, overrides, null);
                            }
                        });
        bindings.alias("pers.neige.neigeitems.manager.ItemEditorManager", editors);
        bindings.global("ItemEditorManager", editors);
        bindings.alias(
                "pers.neige.neigeitems.utils.function.TriFunction",
                StaticClass.forClass(LegacyItemEditorManager.Editor.class));
        actions.editors(editors);
    }
}

package dev.itemloom.probe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.ni.*;
import dev.itemloom.compat.ni.action.*;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.NiTranslations;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;

final class ItemNodeProbe {
    @SuppressWarnings("deprecation")
    static Map<String, Object> run(Plugin reference) throws Exception {
        if (reference == null || !reference.isEnabled())
            throw new IllegalStateException("NI reference is not loaded");
        ClassLoader loader = reference.getClass().getClassLoader();
        Class<?> managerClass = loader.loadClass("pers.neige.neigeitems.manager.ActionManager");
        Object manager = managerClass.getField("INSTANCE").get(null);
        Class<?> contextClass = loader.loadClass("pers.neige.neigeitems.action.ActionContext");
        Class<?> keyClass = loader.loadClass("pers.neige.neigeitems.action.ContextKey");
        Object itemKey =
                loader.loadClass("pers.neige.neigeitems.action.ContextKeys")
                        .getField("ITEM_STACK")
                        .get(null);
        var parse = managerClass.getMethod("parseNode", String.class, contextClass);
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 3);
        var meta = sword.getItemMeta();
        meta.setDisplayName("§aProbe Sword");
        meta.setLore(List.of("§7first", "§bsecond"));
        ((Damageable) meta).setDamage(7);
        sword.setItemMeta(meta);
        CompoundTag old = new CompoundTag();
        old.putString("id", "probe-sword");
        old.putString("data", "{\"saved\":\"roll\",\"empty\":null}");
        old.putInt("charge", 5);
        CompoundTag custom = new CompoundTag();
        custom.put("NeigeItems", old);
        custom.putString("a.b", "literal-dot");
        custom.putInt("integer", 17);
        sword = NmsItems.withCustomData(sword, custom);
        CompoundTag compoundRolls = new CompoundTag();
        compoundRolls.putString("saved", "roll");
        compoundRolls.putInt("number", 17);
        old.put("data", compoundRolls);
        custom.put("NeigeItems", old);
        ItemStack compoundSword = NmsItems.withCustomData(sword, custom);
        List<ItemStack> items =
                List.of(
                        new ItemStack(Material.STONE),
                        sword,
                        compoundSword,
                        new ItemStack(Material.WRITTEN_BOOK));
        NiTranslations translations = new NiTranslations("zh_cn", reference.getLogger());
        NiEvaluation.Host host =
                new NiEvaluation.Host() {
                    public String placeholder(Object viewer, String text) {
                        return null;
                    }

                    public String itemValue(String type, String text) {
                        return NiItemNodes.value(
                                type, text, NiActionContext.currentOrNull(), translations);
                    }

                    public void check(Object input, NiEvaluation evaluation, String value) {
                        throw new IllegalStateException("Unexpected check node");
                    }
                };
        Map<String, Object> differences = new LinkedHashMap<>();
        int checked = 0;
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            for (ItemStack before : items) {
                ItemStack after = new NiItemMigration().convert(before);
                for (String text :
                        List.of(
                                "<amount::>",
                                "<damage::>",
                                "<type::>",
                                "<item_id::>",
                                "<name::>",
                                "<lore_size::>",
                                "<whole_lore::>",
                                "<lore::0>",
                                "<lore::1>",
                                "<lore::-1_fallback>",
                                "<lore::9_fallback>",
                                "<lore::bad>",
                                "<data::saved>",
                                "<data::missing_fallback>",
                                "<data::empty_fallback>",
                                "<nbt::NeigeItems.id>",
                                "<nbt::NeigeItems.charge>",
                                "<nbt::missing_fallback>",
                                "<nbt::integer>",
                                "<nbt::a\\.b>",
                                "<nbt::NeigeItems.data.saved_fallback>")) {
                    Object builder = contextClass.getMethod("builder").invoke(null);
                    builder.getClass()
                            .getMethod("with", keyClass, Object.class)
                            .invoke(builder, itemKey, before);
                    Object oldContext = builder.getClass().getMethod("build").invoke(builder);
                    NiActionContext context =
                            new NiActionContext(
                                    new NiEvaluation(
                                            new GenerationContext(Map.of(), new Random(1)),
                                            null,
                                            null,
                                            NiEvaluation.Mode.ACTION,
                                            new NiNodes(),
                                            scripts,
                                            host),
                                    null,
                                    Map.of(),
                                    () -> true);
                    context.set(NiContextKeys.ITEM_STACK, after);
                    Object expected = parse.invoke(manager, text, oldContext),
                            actual = context.parse(text);
                    checked++;
                    if (!java.util.Objects.equals(expected, actual))
                        differences.put(
                                "item:" + checked,
                                Map.of(
                                        "material",
                                        before.getType().toString(),
                                        "text",
                                        text,
                                        "expected",
                                        expected,
                                        "actual",
                                        actual));
                }
            }
        }
        return Map.of("checked", checked, "differences", differences);
    }
}

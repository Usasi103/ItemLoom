package dev.itemloom.paper.compat;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.LegacyItemGenerator;
import dev.itemloom.paper.compat.script.LegacyItemInfo;
import dev.itemloom.paper.compat.script.LegacyItemUpdateEvent;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** NI regeneration rules, confined to a single independent catalog revision. */
public final class NiItemOperations {
    private final NiCatalog catalog;

    public NiItemOperations(NiCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
    }

    public NiCatalog catalog() {
        return catalog;
    }

    public void ensureActive() {
        ItemsService.requireThread();
        if (!catalog.active() || catalog.catalog() == null)
            throw new IllegalStateException("Item catalog is not ready or has closed");
    }

    /** The old script API returns null for missing IDs and fills its caller's mutable cache. */
    public ItemStack create(String id, OfflinePlayer player, Map<String, String> data) {
        ensureActive();
        LegacyItemGenerator generator = catalog.registry().generators().get(id);
        return generator == null ? null : create(generator, player, data);
    }

    public ItemStack create(
            LegacyItemGenerator generator, OfflinePlayer player, Map<String, String> data) {
        ensureActive();
        try {
            return catalog.generate(generator, player, data, true);
        } catch (NiPaperRecipe.InvalidMaterialException invalid) {
            return null;
        }
    }

    public boolean rebuild(
            ItemStack item,
            OfflinePlayer player,
            Map<String, String> sections,
            List<String> protectNbt) {
        ensureActive();
        if (item == null || item.isEmpty()) return false;
        ItemStack before = item.clone();
        LegacyItemInfo info = LegacyItemInfo.inspect(before);
        if (info == null) return true;
        Map<String, String> data = info.getData();
        overrides(data, sections);
        ItemStack generated = create(info.getId(), player, data);
        if (generated != null)
            finish(item, before, prepare(info, generated, protectNbt, List.of()));
        return true;
    }

    public boolean rebuild(
            ItemStack item,
            OfflinePlayer player,
            List<String> protectSections,
            List<String> protectNbt) {
        ensureActive();
        if (item == null || item.isEmpty()) return false;
        ItemStack before = item.clone();
        LegacyItemInfo info = LegacyItemInfo.inspect(before);
        if (info == null) return true;
        Map<String, String> data = new LinkedHashMap<>();
        for (String key : protectSections) {
            String value = info.getData().get(key);
            if (value != null) data.put(key, value);
        }
        ItemStack generated = create(info.getId(), player, data);
        if (generated != null)
            finish(item, before, prepare(info, generated, protectNbt, List.of()));
        return true;
    }

    public boolean refresh(
            Player player,
            ItemStack item,
            List<String> remove,
            Map<String, String> changes,
            Integer amount) {
        ensureActive();
        if (item == null || item.isEmpty()) return false;
        ItemStack before = item.clone();
        ItemStack prepared = before.clone();
        LegacyItemInfo info = LegacyItemInfo.inspect(before);
        if (info != null) {
            Map<String, String> data = info.getData();
            if (remove != null) remove.forEach(data::remove);
            overrides(data, changes);
            ItemStack generated = create(info.getId(), player, data);
            if (generated != null) {
                LegacyNbt.Compound tag = new LegacyNbt.Compound(NiItemNodes.legacyData(generated));
                // NI 26.2 refresh deliberately leaves display and other components on the old
                // stack; rebuild/update are the operations that replace all components.
                if (tag.getCompound("NeigeItems") != null) {
                    preserveState(info.getNeigeItems(), tag);
                    new LegacyNbtItemStack(prepared).setTag(tag);
                }
                prepared.setType(generated.getType());
            }
        }
        ItemStack remainder = null;
        if (amount != null && Math.max(1, amount) < before.getAmount()) {
            if (player == null)
                throw new IllegalArgumentException("Splitting an item requires a player");
            remainder = before.clone();
            remainder.setAmount(before.getAmount() - Math.max(1, amount));
            prepared.setAmount(Math.max(1, amount));
        }
        finish(item, before, prepared);
        if (remainder != null) catalog.triggers().returnLater(player, remainder);
        return true;
    }

    public void update(Player player, ItemStack item, boolean force, boolean sendMessage) {
        ensureActive();
        if (item == null || item.isEmpty()) return;
        ItemStack before = item.clone();
        LegacyItemInfo info = LegacyItemInfo.inspect(before);
        if (info == null) return;
        LegacyItemGenerator generator = catalog.registry().generators().get(info.getId());
        if (generator == null) return;
        NiPaperRecipe recipe = generator.compiledRecipe();
        NiConfig options = recipe.definition().definition().section("options.update");
        if (options == null || !options.bool("enable", false)) return;
        if (!force
                && info.getNeigeItems().getInt("hashCode", recipe.definitionHash())
                        == recipe.definitionHash()) return;

        Map<String, String> data = info.getData();
        var context = catalog.actionContext(player, null);
        context.set(NiContextKeys.SECTIONS, LegacyConfigReader.parse(generator.getSections()));
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, Object> cache = (Map) data;
        context.set(NiContextKeys.SECTION_CACHE, cache);
        Map<String, String> changes = new HashMap<>();
        Map<String, String> configured = generator.getRebuildData();
        if (configured != null) {
            // Do not mutate the shared cache until all expressions have observed old values.
            configured.forEach(
                    (key, value) -> changes.put(context.parse(key), context.parse(value)));
        }
        List<String> remove = new ArrayList<>();
        for (String key : options.strings("refresh")) remove.add(context.parse(key));
        data.putAll(changes);
        remove.forEach(data::remove);

        ensureUnchanged(item, before);
        var pre = new LegacyItemUpdateEvent.PreGenerate(player, item, data, generator);
        if (!pre.call()) return;
        ensureUnchanged(item, before);
        ItemStack generated = pre.getItem().getItemStack(player, pre.getData());
        if (generated == null) return;
        var post = new LegacyItemUpdateEvent.PostGenerate(player, item, generated);
        if (!post.call()) return;
        ItemStack prepared =
                prepare(
                        info,
                        post.getNewItem(),
                        options.strings("protect"),
                        options.strings("protect-components"));
        finish(item, before, prepared);
        if (sendMessage && player != null) sendUpdateMessage(player, before);
    }

    private void sendUpdateMessage(Player player, ItemStack before) {
        String message = catalog.input().settings().string("Messages.legacyItemUpdateMessage");
        if (message == null || message.isEmpty()) return;
        player.sendMessage(message.replace("{name}", catalog.itemName(before)));
    }

    private static void overrides(Map<String, String> data, Map<String, String> changes) {
        if (changes != null)
            changes.forEach(
                    (key, value) -> {
                        if (value == null) data.remove(key);
                        else data.put(key, value);
                    });
    }

    private static ItemStack prepare(
            LegacyItemInfo old,
            ItemStack generated,
            List<String> protectNbt,
            List<String> protectComponents) {
        ItemStack result = generated.clone();
        if (result.isEmpty()) {
            result.setAmount(0);
            return result;
        }
        LegacyNbt.Compound tag = new LegacyNbt.Compound(NiItemNodes.legacyData(result));
        preserveState(old.getNeigeItems(), tag);
        if (protectNbt != null)
            for (String key : protectNbt) {
                LegacyNbt value = old.getItemTag().getDeep(key);
                if (value != null) tag.putDeep(key, value);
            }
        var target = CraftItemStack.unwrap(result);
        var previous = CraftItemStack.asNMSCopy(old.getItemStack());
        for (String key : protectComponents) {
            Identifier id = Identifier.tryParse(key);
            DataComponentType<?> type =
                    id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
            if (type != null) preserveComponent(target, previous, type);
        }
        // As in NI, protected custom_data is followed by the regenerated/protected NBT tree.
        new LegacyNbtItemStack(result).setTag(tag);
        result.setAmount(old.getItemStack().getAmount());
        return result;
    }

    private static <T> void preserveComponent(
            net.minecraft.world.item.ItemStack target,
            net.minecraft.world.item.ItemStack old,
            DataComponentType<T> type) {
        T value = old.get(type);
        if (value == null) target.remove(type);
        else target.set(type, value);
    }

    private static void preserveState(LegacyNbt.Compound previous, LegacyNbt.Compound tag) {
        var destination = tag.getCompound("NeigeItems");
        if (destination != null)
            LegacyStateRules.captureNumericState(previous::containsKey, previous::getInt)
                    .applyTo(destination::putInt);
    }

    private void ensureUnchanged(ItemStack item, ItemStack before) {
        ensureActive();
        if (!item.equals(before))
            throw new ConcurrentModificationException("Item changed during regeneration");
    }

    private void finish(ItemStack item, ItemStack before, ItemStack prepared) {
        ensureUnchanged(item, before);
        NmsItems.replace(item, prepared);
    }
}

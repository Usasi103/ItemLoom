package dev.itemloom.paper.compat;

import dev.itemloom.compat.ni.action.*;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.script.*;
import dev.itemloom.paper.nms.NmsItems;
import java.util.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Prepares replacements off the live stack and commits after the last callback fence. */
public final class NiItemOperations {
    private final NiCatalog catalog;
    private final NiItemMigration migration = new NiItemMigration();

    public NiItemOperations(NiCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public NiCatalog catalog() {
        return catalog;
    }

    public void ensureActive() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item operations require the main thread");
        if (!catalog.active() || catalog.catalog() == null)
            throw new IllegalStateException("Item catalog is closed");
    }

    public ItemStack create(String id, OfflinePlayer player, Map<String, String> rolls) {
        ensureActive();
        return create(catalog.registry().generators().get(id), player, rolls);
    }

    public ItemStack create(
            LegacyItemGenerator generator, OfflinePlayer player, Map<String, String> rolls) {
        ensureActive();
        if (generator == null) return null;
        if (!generator.ownedBy(this))
            throw new IllegalArgumentException("Generator belongs to another revision");
        try {
            return catalog.generate(generator, player, rolls, true);
        } catch (NiPaperRecipe.InvalidMaterialException invalid) {
            return null;
        }
    }

    public boolean rebuild(
            ItemStack item,
            OfflinePlayer player,
            Map<String, String> overrides,
            List<String> protectNBT) {
        ensureActive();
        if (item == null || item.isEmpty()) return false;
        ItemStack before = item.clone();
        LegacyItemInfo old = LegacyItemInfo.inspect(before);
        if (old == null) return true;
        Map<String, String> rolls = new LinkedHashMap<>(old.getData());
        change(rolls, overrides);
        return rebuild(item, before, old, player, rolls, protectNBT);
    }

    public boolean rebuild(
            ItemStack item,
            OfflinePlayer player,
            List<String> protectSections,
            List<String> protectNBT) {
        ensureActive();
        if (item == null || item.isEmpty()) return false;
        ItemStack before = item.clone();
        LegacyItemInfo old = LegacyItemInfo.inspect(before);
        if (old == null) return true;
        Map<String, String> rolls = new LinkedHashMap<>();
        if (protectSections != null)
            for (String key : protectSections) {
                String value = old.getData().get(key);
                if (value != null) rolls.put(key, value);
            }
        return rebuild(item, before, old, player, rolls, protectNBT);
    }

    private boolean rebuild(
            ItemStack item,
            ItemStack before,
            LegacyItemInfo old,
            OfflinePlayer player,
            Map<String, String> rolls,
            List<String> protectedPaths) {
        ItemStack generated = create(old.getId(), player, rolls);
        if (generated == null) return true;
        commit(item, before, replacement(before, old, generated, protectedPaths, null));
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
        int quantity =
                amount == null
                        ? before.getAmount()
                        : Math.min(before.getAmount(), Math.max(1, amount));
        boolean split = quantity < before.getAmount();
        if (split && player == null)
            throw new IllegalArgumentException("A player is required to return the remainder");
        LegacyItemInfo old = LegacyItemInfo.inspect(before);
        ItemStack prepared = before.clone();
        if (old != null) {
            Map<String, String> rolls = new LinkedHashMap<>(old.getData());
            if (remove != null) remove.forEach(rolls::remove);
            change(rolls, changes);
            ItemStack generated = create(old.getId(), player, rolls);
            if (generated != null) {
                if (generated.isEmpty()) prepared.setAmount(0);
                else {
                    prepared.setType(generated.getType());
                    LegacyNbt.Compound nbt =
                            new LegacyNbt.Compound(NiItemNodes.legacyData(generated));
                    preserveState(old.getNeigeItems(), nbt);
                    prepared = NmsItems.withCustomData(prepared, migration.convert(nbt.copyTag()));
                }
            }
        }
        if (!prepared.isEmpty()) prepared.setAmount(quantity);
        ItemStack remainder = null;
        if (split) {
            remainder = before.clone();
            remainder.setAmount(before.getAmount() - quantity);
        }
        commit(item, before, prepared);
        if (remainder != null) catalog.triggers().returnLater(player, remainder);
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void update(Player player, ItemStack item, boolean force, boolean message) {
        ensureActive();
        if (item == null || item.isEmpty()) return;
        ItemStack before = item.clone();
        LegacyItemInfo old = LegacyItemInfo.inspect(before);
        if (old == null) return;
        LegacyItemGenerator generator = catalog.registry().generators().get(old.getId());
        if (generator == null || !generator.getUpdate()) return;
        int hash = generator.getHashCode();
        if (!force && old.getNeigeItems().getInt("hashCode", hash) == hash) return;
        Map<String, String> oldRolls = new LinkedHashMap<>(old.getData());
        NiActionContext context = catalog.actionContext(player, null);
        context.set(NiContextKeys.SECTIONS, LegacyConfigReader.parse(generator.getSections()));
        context.set(NiContextKeys.SECTION_CACHE, (Map) oldRolls);
        Map<String, String> changes = new LinkedHashMap<>();
        Map<String, String> configured = generator.getRebuildData();
        if (configured != null) {
            configured.forEach(
                    (key, value) -> changes.put(context.parse(key), context.parse(value)));
        }
        List<String> removals = new ArrayList<>();
        for (String key : generator.getRefreshData()) removals.add(context.parse(key));
        Map<String, String> rolls = oldRolls;
        rolls.putAll(changes);
        removals.forEach(rolls::remove);
        fence(item, before);
        LegacyItemUpdateEvent.PreGenerate pre =
                new LegacyItemUpdateEvent.PreGenerate(player, item, rolls, generator);
        pre.call();
        if (pre.isCancelled()) return;
        fence(item, before);
        ItemStack generated = create(pre.getItem(), player, pre.getData());
        if (generated == null) return;
        LegacyItemUpdateEvent.PostGenerate post =
                new LegacyItemUpdateEvent.PostGenerate(player, item, generated);
        post.call();
        if (post.isCancelled()) return;
        ItemStack prepared =
                replacement(
                        before,
                        old,
                        post.getNewItem(),
                        generator.getProtectNBT(),
                        generator.getProtectComponents());
        commit(item, before, prepared);
        if (message && player != null) {
            String text = catalog.input().settings().string("Messages.legacyItemUpdateMessage", "");
            if (!text.isEmpty())
                player.sendMessage(text.replace("{name}", catalog.itemName(before)));
        }
    }

    private ItemStack replacement(
            ItemStack before,
            LegacyItemInfo old,
            ItemStack generated,
            List<String> paths,
            List<String> components) {
        ItemStack prepared = Objects.requireNonNull(generated, "Generated item").clone();
        if (prepared.isEmpty()) {
            prepared.setAmount(0);
            return prepared;
        }
        LegacyNbt.Compound nbt = new LegacyNbt.Compound(NiItemNodes.legacyData(prepared));
        if (paths != null)
            for (String path : paths) {
                LegacyNbt value = old.getItemTag().getDeep(path);
                if (value != null) nbt.putDeep(path, value.clone());
            }
        preserveState(old.getNeigeItems(), nbt);
        if (components != null && !components.isEmpty()) {
            var source = CraftItemStack.asNMSCopy(before);
            var target = CraftItemStack.asNMSCopy(prepared);
            for (String key : components) {
                Identifier id = key == null ? null : Identifier.tryParse(key);
                DataComponentType<?> type =
                        id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
                if (type != null) copyComponent(source, target, type);
            }
            prepared = CraftItemStack.asBukkitCopy(target);
        }
        prepared = NmsItems.withCustomData(prepared, migration.convert(nbt.copyTag()));
        prepared.setAmount(before.getAmount());
        return prepared;
    }

    private static <T> void copyComponent(
            net.minecraft.world.item.ItemStack source,
            net.minecraft.world.item.ItemStack target,
            DataComponentType<T> type) {
        T value = source.get(type);
        if (value == null) target.remove(type);
        else target.set(type, value);
    }

    private static void preserveState(LegacyNbt.Compound source, LegacyNbt.Compound data) {
        LegacyNbt.Compound destination = data.getCompoundOrNull("NeigeItems");
        if (destination == null) return;
        LegacyStateRules.captureNumericState(key -> source.contains(key, 99), source::getInt)
                .applyTo(destination::putInt);
    }

    private static void change(Map<String, String> rolls, Map<String, String> changes) {
        if (changes != null)
            changes.forEach(
                    (key, value) -> {
                        if (value == null) rolls.remove(key);
                        else rolls.put(key, value);
                    });
    }

    private void fence(ItemStack item, ItemStack before) {
        ensureActive();
        if (!item.equals(before))
            throw new ConcurrentModificationException(
                    "Item changed while its replacement was being prepared");
    }

    private void commit(ItemStack item, ItemStack before, ItemStack prepared) {
        fence(item, before);
        NmsItems.replace(item, prepared);
    }
}

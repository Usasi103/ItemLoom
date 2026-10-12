package dev.itemloom.paper.compat.script;

import com.google.gson.*;
import dev.itemloom.compat.ni.NiTagValues;
import dev.itemloom.compat.ni.action.*;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.*;
import dev.itemloom.paper.compat.nbt.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

public final class LegacyItemUtils {
    public final LegacyItemUtils INSTANCE = this;
    private final Function<ItemStack, String> names;
    private final NiItemOperations operations;

    public LegacyItemUtils(Function<ItemStack, String> names) {
        this(names, null);
    }

    public LegacyItemUtils(Function<ItemStack, String> names, NiItemOperations operations) {
        this.names = names;
        this.operations = operations;
    }

    private NiItemOperations owner() {
        if (operations == null)
            throw new IllegalStateException("Item generation and drops require a catalog");
        return operations;
    }

    public ArrayList<ItemStack> getItems(ItemStack item, Integer amount) {
        ItemsService.requireThread();
        return LegacyItemPack.split(item, amount);
    }

    public ArrayList<ItemStack> loadItems(String line) {
        return loadItems(line, (OfflinePlayer) null);
    }

    public ArrayList<ItemStack> loadItems(String line, OfflinePlayer viewer) {
        return LegacyItemPack.ItemInfo.load(owner(), line, viewer);
    }

    public ArrayList<ItemStack> loadItems(List<String> lines) {
        return loadItems(lines, (OfflinePlayer) null);
    }

    public ArrayList<ItemStack> loadItems(List<String> lines, OfflinePlayer viewer) {
        ArrayList<ItemStack> result = new ArrayList<>();
        loadItems(result, lines, viewer, null, null, false);
        return result;
    }

    public void loadItems(ArrayList<ItemStack> into, String line) {
        loadItems(into, line, null);
    }

    public void loadItems(ArrayList<ItemStack> into, String line, OfflinePlayer viewer) {
        into.addAll(loadItems(line, viewer));
    }

    public void loadItems(ArrayList<ItemStack> into, List<String> lines) {
        loadItems(into, lines, null);
    }

    public void loadItems(ArrayList<ItemStack> into, List<String> lines, OfflinePlayer viewer) {
        loadItems(into, lines, viewer, null, null);
    }

    public void loadItems(
            ArrayList<ItemStack> into,
            List<String> lines,
            OfflinePlayer viewer,
            Map<String, String> cache,
            ConfigurationSection sections) {
        loadItems(into, lines, viewer, cache, sections, true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void loadItems(
            ArrayList<ItemStack> into,
            List<String> lines,
            OfflinePlayer viewer,
            Map<String, String> cache,
            ConfigurationSection sections,
            boolean parse) {
        NiItemOperations owner = owner();
        owner.ensureActive();
        NiActionContext context = null;
        if (parse) {
            context = owner.catalog().actionContext(viewer, null);
            context.set(
                    NiContextKeys.SECTIONS,
                    sections == null ? null : new LegacyConfigReader.BukkitReader(sections));
            context.set(NiContextKeys.SECTION_CACHE, (Map) cache);
        }
        for (String raw : lines) {
            String expanded = parse ? context.parse(raw) : raw;
            for (String line : expanded.split("\n", -1))
                into.addAll(LegacyItemPack.ItemInfo.load(owner, line, viewer));
        }
    }

    public CompletableFuture<Item> dropNiItem(Location at, ItemStack item) {
        return dropNiItem(at, item, null);
    }

    public CompletableFuture<Item> dropNiItem(Location at, ItemStack item, Entity trigger) {
        return owner().catalog().drops().drop(at, item, trigger);
    }

    public CompletableFuture<Item> dropNiItem(
            Location at, ItemStack item, Entity trigger, LegacyNbt.Compound tag) {
        return owner().catalog().drops().drop(at, item, trigger, tag);
    }

    public CompletableFuture<Item> dropNiItem(
            Location at,
            ItemStack item,
            Entity trigger,
            LegacyNbt.Compound tag,
            LegacyNbt.Compound properties) {
        return owner().catalog().drops().drop(at, item, trigger, tag, properties);
    }

    public void dropNiItems(Location at, ItemStack item, Integer amount) {
        dropNiItems(at, item, amount, null);
    }

    public void dropNiItems(Location at, ItemStack item, Integer amount, Entity trigger) {
        observe(owner().catalog().drops().amount(at, item, amount, trigger));
    }

    public void dropItems(List<? extends ItemStack> items, Location at) {
        dropItems(items, at, null);
    }

    public void dropItems(List<? extends ItemStack> items, Location at, Entity trigger) {
        dropItems(items, at, trigger, null);
    }

    public void dropItems(List<? extends ItemStack> items, Location at, Entity trigger, String x) {
        dropItems(items, at, trigger, x, null);
    }

    public void dropItems(
            List<? extends ItemStack> items, Location at, Entity trigger, String x, String y) {
        dropItems(items, at, trigger, x, y, null);
    }

    public void dropItems(
            List<? extends ItemStack> items,
            Location at,
            Entity trigger,
            String x,
            String y,
            String angle) {
        observe(owner().catalog().drops().list(items, at, trigger, x, y, angle));
    }

    private void observe(CompletableFuture<?> future) {
        if (future.isDone() && !future.isCancelled()) {
            future.join();
            return;
        }
        future.whenComplete(
                (value, error) -> {
                    if (error != null && !future.isCancelled())
                        owner().catalog()
                                .plugin()
                                .getLogger()
                                .log(
                                        Level.SEVERE,
                                        "Item drop failed; delivery may be partial and will not be retried",
                                        error);
                });
    }

    public String getItemId(ItemStack item) {
        return NiItemNodes.itemId(item);
    }

    public String getName(ItemStack item) {
        return names.apply(item);
    }

    public int getDamage(ItemStack item) {
        if (item == null || item.getType().isAir()) return -1;
        return item.getItemMeta() instanceof Damageable meta ? meta.getDamage() : 0;
    }

    public LegacyNbt.Compound getNbt(ItemStack item) {
        return new LegacyNbtItemStack(item).getOrCreateTag();
    }

    public LegacyNbt.Compound getDirectTag(ItemStack item) {
        return new LegacyNbtItemStack(item).getDirectTag();
    }

    public LegacyItemInfo isNiItem(ItemStack item) {
        return LegacyItemInfo.inspect(item);
    }

    public ItemStack copy(ItemStack item) {
        return item.clone();
    }

    public void saveToSafe(LegacyNbt.Compound tag, ItemStack item) {
        if (item != null && !item.isEmpty() && !(item instanceof CraftItemStack)) tag.saveTo(item);
    }

    public Object toValue(LegacyNbt value) {
        return LegacyNbt.toValue(value);
    }

    public LegacyNbt toNbt(Object value) {
        return LegacyNbt.of(NiTagValues.decode(value));
    }

    public String sectionValue(
            String key, String parameters, ItemStack item, Object raw, Map<String, String> data) {
        LegacyNbt.Compound tag =
                raw instanceof LegacyNbt.Compound compound
                        ? compound
                        : raw instanceof CompoundTag compound
                                ? new LegacyNbt.Compound(compound)
                                : null;
        return switch (key) {
            case "nbt" -> {
                LegacyNbt value = tag == null ? null : tag.getDeep(parameters);
                yield value == null ? null : value.getAsString();
            }
            case "data" -> {
                if (data != null) yield data.get(parameters);
                LegacyNbt value = tag == null ? null : tag.get("NeigeItems.data");
                if (!(value instanceof LegacyNbt.StringValue)) yield null;
                JsonElement member =
                        JsonParser.parseString(value.getAsString())
                                .getAsJsonObject()
                                .get(parameters);
                yield member == null || member.isJsonNull() ? null : member.getAsString();
            }
            case "amount" -> String.valueOf(item.getAmount());
            case "type" -> item.getType().toString();
            case "name" -> names.apply(item);
            case "damage" -> String.valueOf(getDamage(item));
            default -> null;
        };
    }
}

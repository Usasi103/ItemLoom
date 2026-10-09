package dev.itemloom.paper.compat.script;

import java.util.Map;
import java.util.function.Function;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/** Script-facing item helpers operate on independent identity and direct 26.2 NBT views. */
public final class LegacyItemUtils {
    public final LegacyItemUtils INSTANCE = this;
    private final Function<ItemStack, String> names;
    private final dev.itemloom.paper.compat.NiItemOperations operations;

    public LegacyItemUtils(Function<ItemStack, String> names) {
        this(names, null);
    }

    public LegacyItemUtils(
            Function<ItemStack, String> names,
            dev.itemloom.paper.compat.NiItemOperations operations) {
        this.names = names;
        this.operations = operations;
    }

    public java.util.ArrayList<ItemStack> getItems(ItemStack item, Integer amount) {
        dev.itemloom.paper.ItemsService.requireThread();
        return LegacyItemPack.split(item, amount);
    }

    public java.util.ArrayList<ItemStack> loadItems(String line) {
        return loadItems(line, null);
    }

    public java.util.ArrayList<ItemStack> loadItems(String line, org.bukkit.OfflinePlayer viewer) {
        return LegacyItemPack.ItemInfo.load(owner(), line, viewer);
    }

    public java.util.ArrayList<ItemStack> loadItems(java.util.List<String> lines) {
        return loadItems(lines, null);
    }

    public java.util.ArrayList<ItemStack> loadItems(
            java.util.List<String> lines, org.bukkit.OfflinePlayer viewer) {
        var result = new java.util.ArrayList<ItemStack>();
        loadItems(result, lines, viewer, null, null, false);
        return result;
    }

    public void loadItems(java.util.ArrayList<ItemStack> into, String line) {
        loadItems(into, line, null);
    }

    public void loadItems(
            java.util.ArrayList<ItemStack> into, String line, org.bukkit.OfflinePlayer viewer) {
        into.addAll(loadItems(line, viewer));
    }

    public void loadItems(java.util.ArrayList<ItemStack> into, java.util.List<String> lines) {
        loadItems(into, lines, null);
    }

    public void loadItems(
            java.util.ArrayList<ItemStack> into,
            java.util.List<String> lines,
            org.bukkit.OfflinePlayer viewer) {
        loadItems(into, lines, viewer, null, null, true);
    }

    public void loadItems(
            java.util.ArrayList<ItemStack> into,
            java.util.List<String> lines,
            org.bukkit.OfflinePlayer viewer,
            Map<String, String> cache,
            org.bukkit.configuration.ConfigurationSection sections) {
        loadItems(into, lines, viewer, cache, sections, true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void loadItems(
            java.util.ArrayList<ItemStack> into,
            java.util.List<String> lines,
            org.bukkit.OfflinePlayer viewer,
            Map<String, String> cache,
            org.bukkit.configuration.ConfigurationSection sections,
            boolean parse) {
        var owner = owner();
        owner.ensureActive();
        var context = parse ? owner.catalog().actionContext(viewer, null) : null;
        if (context != null) {
            context.set(
                    dev.itemloom.compat.ni.action.NiContextKeys.SECTIONS,
                    sections == null
                            ? null
                            : new dev.itemloom.compat.ni.script.LegacyConfigReader.BukkitReader(
                                    sections));
            context.set(dev.itemloom.compat.ni.action.NiContextKeys.SECTION_CACHE, (Map) cache);
        }
        for (String raw : lines)
            for (String line : (parse ? context.parse(raw) : raw).split("\n", -1))
                loadItems(into, line, viewer);
    }

    public java.util.concurrent.CompletableFuture<org.bukkit.entity.Item> dropNiItem(
            org.bukkit.Location at, ItemStack item) {
        return dropNiItem(at, item, null);
    }

    public java.util.concurrent.CompletableFuture<org.bukkit.entity.Item> dropNiItem(
            org.bukkit.Location at, ItemStack item, org.bukkit.entity.Entity trigger) {
        return dropService().drop(at, item, trigger);
    }

    public java.util.concurrent.CompletableFuture<org.bukkit.entity.Item> dropNiItem(
            org.bukkit.Location at,
            ItemStack item,
            org.bukkit.entity.Entity trigger,
            LegacyNbt.Compound tag) {
        return dropService().drop(at, item, trigger, tag);
    }

    public java.util.concurrent.CompletableFuture<org.bukkit.entity.Item> dropNiItem(
            org.bukkit.Location at,
            ItemStack item,
            org.bukkit.entity.Entity trigger,
            LegacyNbt.Compound tag,
            LegacyNbt.Compound properties) {
        return dropService().drop(at, item, trigger, tag, properties);
    }

    public void dropNiItems(org.bukkit.Location at, ItemStack item, Integer amount) {
        dropNiItems(at, item, amount, null);
    }

    public void dropNiItems(
            org.bukkit.Location at,
            ItemStack item,
            Integer amount,
            org.bukkit.entity.Entity trigger) {
        observe(dropService().amount(at, item, amount, trigger));
    }

    public void dropItems(java.util.List<? extends ItemStack> items, org.bukkit.Location at) {
        dropItems(items, at, null);
    }

    public void dropItems(
            java.util.List<? extends ItemStack> items,
            org.bukkit.Location at,
            org.bukkit.entity.Entity trigger) {
        dropItems(items, at, trigger, null);
    }

    public void dropItems(
            java.util.List<? extends ItemStack> items,
            org.bukkit.Location at,
            org.bukkit.entity.Entity trigger,
            String x) {
        dropItems(items, at, trigger, x, null);
    }

    public void dropItems(
            java.util.List<? extends ItemStack> items,
            org.bukkit.Location at,
            org.bukkit.entity.Entity trigger,
            String x,
            String y) {
        dropItems(items, at, trigger, x, y, null);
    }

    public void dropItems(
            java.util.List<? extends ItemStack> items,
            org.bukkit.Location at,
            org.bukkit.entity.Entity trigger,
            String x,
            String y,
            String angle) {
        observe(dropService().list(items, at, trigger, x, y, angle));
    }

    private dev.itemloom.paper.action.ItemDrops dropService() {
        return owner().catalog().drops();
    }

    private dev.itemloom.paper.compat.NiItemOperations owner() {
        if (operations == null)
            throw new IllegalStateException("Item generation and drops require a catalog");
        return operations;
    }

    private static void observe(java.util.concurrent.CompletableFuture<?> future) {
        if (future.isDone() && !future.isCancelled()) {
            future.join();
            return;
        }
        future.whenComplete(
                (value, error) -> {
                    if (error != null && !future.isCancelled())
                        org.bukkit.Bukkit.getLogger()
                                .log(
                                        java.util.logging.Level.SEVERE,
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
        return item == null || item.getType().isAir()
                ? -1
                : item.getItemMeta() instanceof Damageable damage ? damage.getDamage() : 0;
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
        if (item != null
                && !item.isEmpty()
                && !(item instanceof org.bukkit.craftbukkit.inventory.CraftItemStack))
            tag.saveTo(item);
    }

    public Object toValue(LegacyNbt value) {
        return value.toValue();
    }

    public LegacyNbt toNbt(Object value) {
        return LegacyNbt.of(dev.itemloom.compat.ni.NiTagValues.decode(value));
    }

    public String sectionValue(
            String key, String parameters, ItemStack item, Object raw, Map<String, String> data) {
        LegacyNbt.Compound tag =
                raw instanceof LegacyNbt.Compound compound
                        ? compound
                        : raw instanceof net.minecraft.nbt.CompoundTag compound
                                ? (LegacyNbt.Compound) LegacyNbt.wrap(compound)
                                : null;
        return switch (key) {
            case "nbt" -> {
                LegacyNbt value = tag == null ? null : tag.getDeep(parameters);
                yield value == null ? null : value.getAsString();
            }
            case "data" -> {
                // The historical parseItemSection fallback reads this literal dotted key.
                if (data != null) yield data.get(parameters);
                LegacyNbt value = tag == null ? null : tag.get("NeigeItems.data");
                if (!(value instanceof LegacyNbt.StringValue)) yield null;
                com.google.gson.JsonObject values =
                        com.google.gson.JsonParser.parseString((String) value.toValue())
                                .getAsJsonObject();
                var saved = values.get(parameters);
                yield saved == null || saved.isJsonNull() ? null : saved.getAsString();
            }
            case "amount" -> Integer.toString(item.getAmount());
            case "type" -> item.getType().toString();
            case "name" -> getName(item);
            case "damage" -> Integer.toString(getDamage(item));
            default -> null;
        };
    }
}

package dev.itemloom.paper.compat.nbt;

import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** A legacy custom-data view that keeps the independent storage envelope on the actual item. */
public final class LegacyNbtItemStack implements Comparable<LegacyNbtItemStack>, Cloneable {
    private static final String LEGACY_KEY = "NeigeItems";
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final NiItemMigration MIGRATION = new NiItemMigration();
    private static final Set<String> MODELED_STATE =
            Set.of("schema", "id", "rolls", "null_rolls", "properties", "compat_ni_rolls");
    private static final java.util.Comparator<LegacyNbtItemStack> ORDER =
            dev.itemloom.paper.compat.LegacyStateRules.itemOrder(
                    value -> value.item.getType().ordinal(),
                    value -> value.item.getAmount(),
                    value -> value.item.getDurability(),
                    LegacyNbtItemStack::getTag,
                    java.util.Comparator.naturalOrder());
    private final ItemStack item;

    public LegacyNbtItemStack(ItemStack item) {
        this.item = Objects.requireNonNull(item, "item");
    }

    public LegacyNbt.Compound getTag() {
        synchronized (item) {
            if (CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA) == null) return null;
            return liveView();
        }
    }

    public LegacyNbt.Compound getDirectTag() {
        return getTag();
    }

    public LegacyNbt.Compound getOrCreateTag() {
        synchronized (item) {
            if (CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA) == null)
                install(new CompoundTag());
            return liveView();
        }
    }

    private LegacyNbt.Compound liveView() {
        CompoundTag original = NmsItems.customData(item);
        checkExistingState(original);
        return LegacyNbt.linked(NiItemNodes.legacyData(item), item, this::commit);
    }

    private void commit(CompoundTag previous, CompoundTag candidate) {
        synchronized (item) {
            CompoundTag current = NmsItems.customData(item);
            checkExistingState(current);
            CompoundTag projection = NiItemNodes.legacyData(item);
            CompoundTag merged =
                    (CompoundTag) merge(previous, candidate, projection, "custom_data");
            CompoundTag translated = translate(merged, current);
            if (!translated.equals(current)) install(translated);
        }
    }

    /** Replaces custom_data as requested by the caller; every other item component is retained. */
    public void setTag(LegacyNbt.Compound compound) {
        CompoundTag candidate = Objects.requireNonNull(compound, "compound").copyTag();
        synchronized (item) {
            CompoundTag current = NmsItems.customData(item);
            checkExistingState(current);
            install(translate(candidate, current));
        }
    }

    private static void checkExistingState(CompoundTag current) {
        CODEC.read(current); // Unknown/corrupt schema is never silently overwritten by a script.
        if (current.contains(ItemStateCodec.KEY) && current.contains(LEGACY_KEY))
            MIGRATION.convert(current);
    }

    private static CompoundTag translate(CompoundTag projection, CompoundTag current) {
        CompoundTag candidate = projection.copy();
        if (!candidate.contains(LEGACY_KEY)) {
            CODEC.read(candidate);
            return candidate; // Removing the complete legacy record explicitly removes identity.
        }
        if (MIGRATION.read(candidate).isEmpty())
            throw new IllegalArgumentException("Legacy item metadata must contain a valid id");
        if (candidate.contains(ItemStateCodec.KEY)) {
            if (!CODEC.read(candidate).equals(MIGRATION.read(candidate))) {
                throw new IllegalArgumentException("Conflicting modern and legacy item identity");
            }
            candidate.remove(ItemStateCodec.KEY);
        }
        CompoundTag converted = MIGRATION.convert(candidate);
        CompoundTag convertedState = converted.getCompoundOrEmpty(ItemStateCodec.KEY);
        CompoundTag state = current.getCompoundOrEmpty(ItemStateCodec.KEY).copy();
        // Preserve fields a future independent runtime may add; replace all modeled fields so
        // removed null rolls and a compound-to-JSON data change cannot leave stale projections.
        MODELED_STATE.forEach(state::remove);
        convertedState
                .entrySet()
                .forEach(entry -> state.put(entry.getKey(), entry.getValue().copy()));
        converted.put(ItemStateCodec.KEY, state);
        CODEC.read(converted);
        return converted;
    }

    /** Merge only this handle's edits, preserving independent changes to other custom-data keys. */
    private static Tag merge(Tag before, Tag changed, Tag current, String path) {
        if (Objects.equals(before, changed)) return copy(current);
        if (before instanceof CompoundTag oldMap
                && changed instanceof CompoundTag newMap
                && current instanceof CompoundTag currentMap) {
            CompoundTag result = currentMap.copy();
            Set<String> keys = new LinkedHashSet<>(oldMap.keySet());
            keys.addAll(newMap.keySet());
            for (String key : keys) {
                Tag value =
                        merge(
                                oldMap.get(key),
                                newMap.get(key),
                                currentMap.get(key),
                                path + "." + key);
                if (value == null) result.remove(key);
                else result.put(key, value);
            }
            return result;
        }
        if (!Objects.equals(current, before) && !Objects.equals(current, changed)) {
            throw new ConcurrentModificationException(
                    "Item NBT changed through another handle: " + path);
        }
        return copy(changed);
    }

    private static Tag copy(Tag tag) {
        return tag == null ? null : tag.copy();
    }

    private void install(CompoundTag data) {
        var handle = CraftItemStack.unwrap(item);
        if (handle.isEmpty())
            throw new IllegalArgumentException("Cannot attach custom data to an empty item");
        // The sole live write occurs after all parsing/identity/conflict checks succeed. Do not
        // round-trip ItemMeta, which could discard components absent from its Bukkit model.
        handle.set(DataComponents.CUSTOM_DATA, CustomData.of(data.copy()));
    }

    public ItemStack asItemStack() {
        return item;
    }

    public ItemStack asCopy() {
        return item.clone();
    }

    public ItemStack asBukkitCopy() {
        return CraftItemStack.asBukkitCopy(CraftItemStack.unwrap(item));
    }

    public ItemStack asCraftCopy() {
        return CraftItemStack.asCraftCopy(item);
    }

    public boolean isBukkitItemStack() {
        return !(item instanceof CraftItemStack);
    }

    public boolean isCraftItemStack() {
        return item instanceof CraftItemStack;
    }

    public void saveTo(ItemStack receiver) {
        LegacyNbt.Compound tag = getTag();
        new LegacyNbtItemStack(receiver).setTag(tag == null ? new LegacyNbt.Compound() : tag);
    }

    @Override
    public LegacyNbtItemStack clone() {
        return new LegacyNbtItemStack(item.clone());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LegacyNbtItemStack value && item.equals(value.item);
    }

    @Override
    public int hashCode() {
        return item.hashCode();
    }

    @Override
    public int compareTo(LegacyNbtItemStack other) {
        return ORDER.compare(this, other);
    }
}

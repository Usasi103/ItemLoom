package dev.itemloom.paper.compat.nbt;

import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.nms.ItemStateCodec;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Keeps the original stack and reconciles each script view's changes with its current data. */
public final class LegacyNbtItemStack implements Comparable<LegacyNbtItemStack>, Cloneable {
    private static final String LEGACY_KEY = "NeigeItems";
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final NiItemMigration MIGRATION = new NiItemMigration();
    private static final Set<String> MODELED_FIELDS =
            Set.of("schema", "id", "rolls", "null_rolls", "properties", "compat_ni_rolls");
    private final ItemStack item;

    public LegacyNbtItemStack(ItemStack item) {
        this.item = Objects.requireNonNull(item, "item");
    }

    public LegacyNbt.Compound getTag() {
        synchronized (item) {
            CompoundTag projection = projection();
            return projection == null ? null : LegacyNbt.Compound.live(projection, this);
        }
    }

    public LegacyNbt.Compound getDirectTag() {
        return getTag();
    }

    public LegacyNbt.Compound getOrCreateTag() {
        synchronized (item) {
            LegacyNbt.Compound current = getTag();
            if (current != null) {
                return current;
            }
            write(new CompoundTag());
            return getTag();
        }
    }

    public void setTag(LegacyNbt.Compound tag) {
        CompoundTag candidate = Objects.requireNonNull(tag, "tag").copyTag();
        synchronized (item) {
            CompoundTag current = raw();
            validateExisting(current);
            write(translate(candidate, current));
        }
    }

    public ItemStack asItemStack() {
        return item;
    }

    public ItemStack asCopy() {
        return item.clone();
    }

    public ItemStack asBukkitCopy() {
        return CraftItemStack.asBukkitCopy(CraftItemStack.asNMSCopy(item));
    }

    public ItemStack asCraftCopy() {
        return CraftItemStack.asCraftCopy(item);
    }

    public boolean isBukkitItemStack() {
        return !isCraftItemStack();
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
        return new LegacyNbtItemStack(asCopy());
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof LegacyNbtItemStack other && item.equals(other.item);
    }

    @Override
    public int hashCode() {
        return item.hashCode();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int compareTo(LegacyNbtItemStack other) {
        int materials = Integer.compare(item.getType().ordinal(), other.item.getType().ordinal());
        if (materials != 0) {
            return materials;
        }
        int amounts = Integer.compare(item.getAmount(), other.item.getAmount());
        if (amounts != 0) {
            return amounts;
        }
        int durability = Short.compare(item.getDurability(), other.item.getDurability());
        if (durability != 0) {
            return durability;
        }
        LegacyNbt.Compound left = getTag();
        LegacyNbt.Compound right = other.getTag();
        if (left == null) {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }

    boolean sameTarget(ItemStack target) {
        return item == target || CraftItemStack.unwrap(item) == CraftItemStack.unwrap(target);
    }

    void commit(CompoundTag before, CompoundTag desired) {
        synchronized (item) {
            CompoundTag currentRaw = raw();
            validateExisting(currentRaw);
            CompoundTag currentView = projection();
            Tag merged = merge(before, desired, currentView, "");
            if (merged == null) {
                return;
            }
            CompoundTag translated = translate((CompoundTag) merged, currentRaw);
            if (!translated.equals(currentRaw)) {
                write(translated);
            }
        }
    }

    private CompoundTag raw() {
        CustomData data = CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    private CompoundTag projection() {
        CompoundTag current = raw();
        if (current == null) {
            return null;
        }
        validateExisting(current);
        return NiItemNodes.legacyData(item);
    }

    private static void validateExisting(CompoundTag current) {
        if (current == null) {
            return;
        }
        CODEC.read(current);
        if (current.contains(ItemStateCodec.KEY) && current.contains(LEGACY_KEY)) {
            MIGRATION.convert(current);
        }
    }

    private void write(CompoundTag tag) {
        var handle = CraftItemStack.unwrap(item);
        if (handle.isEmpty()) {
            throw new IllegalArgumentException("An empty item cannot hold custom data");
        }
        handle.set(DataComponents.CUSTOM_DATA, CustomData.of(tag.copy()));
    }

    private static CompoundTag translate(CompoundTag projection, CompoundTag previous) {
        CompoundTag candidate = projection.copy();
        var nativeIdentity = CODEC.read(candidate);
        Tag legacy = candidate.get(LEGACY_KEY);
        if (legacy != null) {
            if (!(legacy instanceof CompoundTag old)
                    || !(old.get("id") instanceof StringTag)
                    || old.getStringOr("id", "").isBlank()) {
                throw new IllegalArgumentException("NeigeItems requires a nonblank string id");
            }
            var identity =
                    MIGRATION
                            .read(candidate)
                            .orElseThrow(
                                    () -> new IllegalArgumentException("Missing legacy identity"));
            if (nativeIdentity.isPresent() && !nativeIdentity.get().equals(identity)) {
                throw new IllegalArgumentException("Conflicting native and legacy item identities");
            }
            candidate.remove(ItemStateCodec.KEY);
            candidate = MIGRATION.convert(candidate);
            if (previous != null
                    && previous.get(ItemStateCodec.KEY) instanceof CompoundTag existing) {
                CODEC.read(previous);
                CompoundTag combined = existing.copy();
                MODELED_FIELDS.forEach(combined::remove);
                CompoundTag converted = candidate.getCompoundOrEmpty(ItemStateCodec.KEY);
                for (String key : converted.keySet()) {
                    combined.put(key, converted.get(key).copy());
                }
                candidate.put(ItemStateCodec.KEY, combined);
            }
        }
        CODEC.read(candidate);
        return candidate;
    }

    private static Tag merge(Tag before, Tag desired, Tag current, String path) {
        if (Objects.equals(before, desired)) {
            return copy(current);
        }
        if (before instanceof CompoundTag old
                && desired instanceof CompoundTag next
                && current instanceof CompoundTag live) {
            CompoundTag result = live.copy();
            Set<String> keys = new LinkedHashSet<>(old.keySet());
            keys.addAll(next.keySet());
            for (String key : keys) {
                String nestedPath = path.isEmpty() ? key : path + "." + key;
                Tag merged = merge(old.get(key), next.get(key), live.get(key), nestedPath);
                if (merged == null) {
                    result.remove(key);
                } else {
                    result.put(key, merged);
                }
            }
            return result;
        }
        if (!Objects.equals(current, before) && !Objects.equals(current, desired)) {
            throw new ConcurrentModificationException(
                    "NBT changed at " + (path.isEmpty() ? "<root>" : path));
        }
        return copy(desired);
    }

    private static Tag copy(Tag value) {
        return value == null ? null : value.copy();
    }
}

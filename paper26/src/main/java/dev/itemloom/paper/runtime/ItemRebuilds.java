package dev.itemloom.paper.runtime;

import dev.itemloom.api.ItemLoom;
import dev.itemloom.api.ItemRebuildResult;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.ItemDurabilityService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Rules behind {@code ItemLoom#rebuild}: validation, roll merging and runtime-state carry-over. */
public final class ItemRebuilds {
    static final String PDC_ROOT = "PublicBukkitValues";

    private ItemRebuilds() {}

    /**
     * A failure when the item is not an ItemLoom item or its id is not defined; otherwise empty.
     */
    public static Optional<ItemRebuildResult> rejection(
            Optional<ItemIdentity> identity, Set<String> definedIds) {
        if (identity.isEmpty()) {
            return Optional.of(
                    ItemRebuildResult.failed(
                            ItemRebuildResult.Status.NOT_ITEMLOOM,
                            "Item has no ItemLoom identity",
                            null));
        }
        String id = identity.get().id();
        if (!definedIds.contains(id)) {
            return Optional.of(
                    ItemRebuildResult.failed(
                            ItemRebuildResult.Status.UNKNOWN_ID,
                            "Item id is not defined: " + id,
                            null));
        }
        return Optional.empty();
    }

    /**
     * Current rolls with the overrides applied. A null override value removes that roll; a roll
     * that is absent from the current identity is added.
     *
     * @throws IllegalArgumentException when an override name is null
     */
    public static Map<String, String> mergeRolls(
            Map<String, String> current, Map<String, String> overrides) {
        Map<String, String> rolls = new LinkedHashMap<>(current);
        if (overrides == null) {
            return rolls;
        }
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("Override roll name cannot be null");
            }
            if (entry.getValue() == null) {
                rolls.remove(entry.getKey());
            } else {
                rolls.put(entry.getKey(), entry.getValue());
            }
        }
        return rolls;
    }

    /**
     * Returns the candidate custom data with the PDC entries named by {@code keys} taken from
     * {@code before}. An entry absent from {@code before} is absent from the result. Entries not
     * listed keep the candidate's values.
     */
    public static CompoundTag keepPdc(CompoundTag before, CompoundTag candidate, Set<String> keys) {
        CompoundTag result = candidate.copy();
        if (keys == null || keys.isEmpty()) {
            return result;
        }
        if (keys.contains(ItemLoom.KEEP_ALL_PDC)) {
            Tag whole = before.get(PDC_ROOT);
            if (whole == null) {
                result.remove(PDC_ROOT);
            } else {
                result.put(PDC_ROOT, whole.copy());
            }
            return result;
        }
        CompoundTag previous = before.getCompoundOrEmpty(PDC_ROOT);
        CompoundTag next = result.getCompoundOrEmpty(PDC_ROOT).copy();
        for (String key : keys) {
            Tag value = previous.get(key);
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, value.copy());
            }
        }
        if (next.isEmpty()) {
            result.remove(PDC_ROOT);
        } else {
            result.put(PDC_ROOT, next);
        }
        return result;
    }

    /**
     * Applies the runtime state of {@code before} to a generated {@code candidate}: amount, vanilla
     * enchantments and stored enchantments, vanilla damage, and the PDC keys named in
     * {@code keepPdcKeys}. The candidate's lore and other definition-driven components are kept.
     * Custom durability is applied by the format before this step, so the candidate already
     * holds it.
     */
    public static ItemStack preserveRuntime(
            ItemStack before, ItemStack candidate, Set<String> keepPdcKeys) {
        ItemStack prepared = candidate.clone();
        prepared.setAmount(before.getAmount());
        var source = CraftItemStack.asNMSCopy(before);
        var target = CraftItemStack.asNMSCopy(prepared);
        copyComponent(source, target, DataComponents.ENCHANTMENTS);
        copyComponent(source, target, DataComponents.STORED_ENCHANTMENTS);
        target.set(
                DataComponents.CUSTOM_DATA,
                CustomData.of(keepPdc(customData(source), customData(target), keepPdcKeys)));
        int ordinary = target.getOrDefault(DataComponents.MAX_DAMAGE, 0);
        if (ordinary > 0) {
            ItemStack staged = CraftItemStack.asBukkitCopy(target);
            target.set(DataComponents.DAMAGE, damageFor(source, staged, ordinary));
        }
        return CraftItemStack.asBukkitCopy(target);
    }

    /**
     * Custom durability is displayed through vanilla damage, as maintenance does. Without custom
     * durability the original vanilla damage carries over (SX durability is vanilla damage),
     * clamped so the rebuilt item cannot break.
     */
    private static int damageFor(
            net.minecraft.world.item.ItemStack source, ItemStack staged, int ordinary) {
        ScalarItemState.State state = ScalarItemState.read(staged);
        if (state != null
                && state.durability() != null
                && state.maximumDurability() != null
                && state.maximumDurability() > 0) {
            return ItemDurabilityService.editorDamage(
                    ordinary, state.durability(), state.maximumDurability());
        }
        int previous = source.getOrDefault(DataComponents.DAMAGE, 0);
        return Math.clamp(previous, 0, ordinary - 1);
    }

    private static CompoundTag customData(net.minecraft.world.item.ItemStack item) {
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static <T> void copyComponent(
            net.minecraft.world.item.ItemStack source,
            net.minecraft.world.item.ItemStack target,
            DataComponentType<T> type) {
        T value = source.get(type);
        if (value == null) {
            target.remove(type);
        } else {
            target.set(type, value);
        }
    }
}

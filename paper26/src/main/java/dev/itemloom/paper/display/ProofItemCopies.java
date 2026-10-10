package dev.itemloom.paper.display;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.component.UseRemainder;
import org.bukkit.craftbukkit.CraftRegistry;
import net.minecraft.world.item.component.SulfurCubeContent;

/**
 * ItemStack copies own their component map, but NMS text and NBT values also expose mutable state.
 * Copy those values explicitly without serializing the whole item and normalizing its components.
 */
public final class ProofItemCopies {
    // These component APIs expose mutable collections or values beneath their records.
    // Round-trip only the component payload so absent/default item components and the two
    // independent lore channels are not normalized by a whole-ItemStack serialization.
    private static final List<DataComponentType<?>> MUTABLE_PAYLOADS =
            List.of(
                    DataComponents.CAN_PLACE_ON,
                    DataComponents.CAN_BREAK,
                    DataComponents.WRITABLE_BOOK_CONTENT,
                    DataComponents.BLOCK_STATE,
                    DataComponents.MAP_DECORATIONS,
                    DataComponents.DEBUG_STICK_STATE,
                    DataComponents.BANNER_PATTERNS,
                    DataComponents.SUSPICIOUS_STEW_EFFECTS,
                    DataComponents.TOOL,
                    DataComponents.BLOCKS_ATTACKS,
                    DataComponents.ATTRIBUTE_MODIFIERS,
                    DataComponents.CONSUMABLE,
                    DataComponents.DEATH_PROTECTION,
                    DataComponents.BEES,
                    DataComponents.POTION_CONTENTS,
                    DataComponents.ENCHANTMENTS,
                    DataComponents.STORED_ENCHANTMENTS);

    private ProofItemCopies() {}

    public static ItemStack copy(ItemStack source) {
        return copy(source, 0, new int[] {DisplayLedger.MAX_NESTED_ITEMS});
    }

    private static ItemStack copy(ItemStack source, int depth, int[] remaining) {
        if (depth > DisplayLedger.MAX_NESTING || --remaining[0] < 0)
            throw new IllegalArgumentException("Display item exceeds nesting or item budget");
        // copy(true) deliberately preserves an internal null-item representation for EMPTY;
        // only Minecraft's shared sentinel is valid for ordinary empty-slot operations.
        if (source.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = source.copy(true);
        for (var type : MUTABLE_PAYLOADS) copyPayload(source, result, type);
        var data = source.get(DataComponents.CUSTOM_DATA);
        if (data != null) result.set(DataComponents.CUSTOM_DATA, CustomData.of(data.copyTag()));
        var bucket = source.get(DataComponents.BUCKET_ENTITY_DATA);
        if (bucket != null)
            result.set(DataComponents.BUCKET_ENTITY_DATA, CustomData.of(bucket.copyTag()));
        var entity = source.get(DataComponents.ENTITY_DATA);
        if (entity != null)
            result.set(
                    DataComponents.ENTITY_DATA,
                    TypedEntityData.of(entity.type(), entity.copyTagWithoutId()));
        var blockEntity = source.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntity != null)
            result.set(
                    DataComponents.BLOCK_ENTITY_DATA,
                    TypedEntityData.of(blockEntity.type(), blockEntity.copyTagWithoutId()));
        var name = source.get(DataComponents.CUSTOM_NAME);
        if (name != null) result.set(DataComponents.CUSTOM_NAME, text(name));
        var itemName = source.get(DataComponents.ITEM_NAME);
        if (itemName != null) result.set(DataComponents.ITEM_NAME, text(itemName));
        var lore = source.get(DataComponents.LORE);
        if (lore != null)
            result.set(
                    DataComponents.LORE,
                    new ItemLore(
                            lore.lines().stream().map(ProofItemCopies::text).toList(),
                            lore.styledLines().stream().map(ProofItemCopies::text).toList()));
        var book = source.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (book != null)
            result.set(
                    DataComponents.WRITTEN_BOOK_CONTENT,
                    book.withReplacedPages(
                            book.pages().stream()
                                    .map(page -> page.map(ProofItemCopies::text))
                                    .toList()));
        var tooltip = source.get(DataComponents.TOOLTIP_DISPLAY);
        if (tooltip != null)
            result.set(
                    DataComponents.TOOLTIP_DISPLAY,
                    new TooltipDisplay(
                            tooltip.hideTooltip(),
                            new LinkedHashSet<>(tooltip.hiddenComponents())));
        var model = source.get(DataComponents.CUSTOM_MODEL_DATA);
        if (model != null)
            result.set(
                    DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(
                            new ArrayList<>(model.floats()), new ArrayList<>(model.flags()),
                            new ArrayList<>(model.strings()), new ArrayList<>(model.colors())));
        var explosion = source.get(DataComponents.FIREWORK_EXPLOSION);
        if (explosion != null) result.set(DataComponents.FIREWORK_EXPLOSION, explosion(explosion));
        var fireworks = source.get(DataComponents.FIREWORKS);
        if (fireworks != null)
            result.set(
                    DataComponents.FIREWORKS,
                    new Fireworks(
                            fireworks.flightDuration(),
                            fireworks.explosions().stream()
                                    .map(ProofItemCopies::explosion)
                                    .toList()));
        var container = source.get(DataComponents.CONTAINER);
        if (container != null) {
            List<ItemStack> contents = new ArrayList<>();
            for (var slot : container.items)
                contents.add(
                        slot.isPresent()
                                ? copy(slot.get().create(), depth + 1, remaining)
                                : ItemStack.EMPTY);
            result.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        }
        var bundle = source.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            var copied = new BundleContents(templates(bundle.items(), depth, remaining));
            if (bundle.getSelectedItemIndex() != BundleContents.NO_SELECTED_ITEM_INDEX) {
                var selection = new BundleContents.Mutable(copied);
                selection.toggleSelectedItem(bundle.getSelectedItemIndex());
                copied = selection.toImmutable();
            }
            result.set(DataComponents.BUNDLE_CONTENTS, copied);
        }
        var projectiles = source.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles != null)
            result.set(
                    DataComponents.CHARGED_PROJECTILES,
                    new ChargedProjectiles(templates(projectiles.items(), depth, remaining)));
        var remainder = source.get(DataComponents.USE_REMAINDER);
        if (remainder != null)
            result.set(
                    DataComponents.USE_REMAINDER,
                    new UseRemainder(
                            ItemStackTemplate.fromNonEmptyStack(
                                    copy(remainder.convertInto().create(), depth + 1, remaining))));
        var sulfur = source.get(DataComponents.SULFUR_CUBE_CONTENT);
        if (sulfur != null)
            result.set(
                    DataComponents.SULFUR_CUBE_CONTENT,
                    new SulfurCubeContent(
                            ItemStackTemplate.fromNonEmptyStack(
                                    copy(
                                            sulfur.absorbedBlockItemStack().create(),
                                            depth + 1,
                                            remaining))));
        return result;
    }

    private static List<ItemStackTemplate> templates(
            List<ItemStackTemplate> sources, int depth, int[] remaining) {
        List<ItemStackTemplate> copies = new ArrayList<>(sources.size());
        for (var item : sources)
            copies.add(
                    ItemStackTemplate.fromNonEmptyStack(copy(item.create(), depth + 1, remaining)));
        return List.copyOf(copies);
    }

    private static Component text(Component source) {
        var buffer = Unpooled.buffer();
        try {
            // A Component.copy() still shares mutable siblings, translation arguments and hover
            // text.
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buffer, source);
            return ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buffer).copy();
        } finally {
            buffer.release();
        }
    }

    private static FireworkExplosion explosion(FireworkExplosion source) {
        return new FireworkExplosion(
                source.shape(),
                new IntArrayList(source.colors()),
                new IntArrayList(source.fadeColors()),
                source.hasTrail(),
                source.hasTwinkle());
    }

    private static <T> void copyPayload(
            ItemStack source, ItemStack result, DataComponentType<T> type) {
        T value = source.get(type);
        if (value == null) return;
        var ops = CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        var codec = type.codecOrThrow();
        var encoded = codec.encodeStart(ops, value).getOrThrow().copy();
        result.set(type, codec.parse(ops, encoded).getOrThrow());
    }
}

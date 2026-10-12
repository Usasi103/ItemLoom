package dev.itemloom.paper.display;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.*;
import org.bukkit.craftbukkit.CraftRegistry;

/** Owns mutable component payloads without normalizing a stack's component patch. */
public final class ProofItemCopies {
    private static final List<DataComponentType<?>> CODEC_COPIES =
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
        return new Copier().stack(source, 0);
    }

    private static final class Copier {
        private int visited;

        ItemStack stack(ItemStack source, int depth) {
            if (depth > DisplayLedger.MAX_NESTING || ++visited > DisplayLedger.MAX_NESTED_ITEMS) {
                throw new IllegalArgumentException("Item snapshot exceeds nesting budget");
            }
            if (source.isEmpty()) return ItemStack.EMPTY;
            ItemStack result = source.copy(true);
            for (var type : CODEC_COPIES) detachCodec(result, type);
            var custom = source.get(DataComponents.CUSTOM_DATA);
            if (custom != null)
                result.set(DataComponents.CUSTOM_DATA, CustomData.of(custom.copyTag()));
            var bucket = source.get(DataComponents.BUCKET_ENTITY_DATA);
            if (bucket != null)
                result.set(DataComponents.BUCKET_ENTITY_DATA, CustomData.of(bucket.copyTag()));
            var entity = source.get(DataComponents.ENTITY_DATA);
            if (entity != null)
                result.set(
                        DataComponents.ENTITY_DATA,
                        TypedEntityData.of(entity.type(), entity.copyTagWithoutId()));
            var block = source.get(DataComponents.BLOCK_ENTITY_DATA);
            if (block != null)
                result.set(
                        DataComponents.BLOCK_ENTITY_DATA,
                        TypedEntityData.of(block.type(), block.copyTagWithoutId()));
            var customName = source.get(DataComponents.CUSTOM_NAME);
            if (customName != null) result.set(DataComponents.CUSTOM_NAME, text(customName));
            var itemName = source.get(DataComponents.ITEM_NAME);
            if (itemName != null) result.set(DataComponents.ITEM_NAME, text(itemName));
            var lore = source.get(DataComponents.LORE);
            if (lore != null)
                result.set(
                        DataComponents.LORE,
                        new ItemLore(texts(lore.lines()), texts(lore.styledLines())));
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
            if (explosion != null)
                result.set(DataComponents.FIREWORK_EXPLOSION, explosion(explosion));
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
                var slots = new ArrayList<ItemStack>();
                for (var slot : container.items)
                    slots.add(
                            stack(
                                    slot.map(ItemStackTemplate::create).orElse(ItemStack.EMPTY),
                                    depth + 1));
                result.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
            }
            var bundle = source.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null) {
                var detached = new BundleContents(templates(bundle.items(), depth + 1));
                if (bundle.getSelectedItemIndex() != BundleContents.NO_SELECTED_ITEM_INDEX) {
                    var selected = new BundleContents.Mutable(detached);
                    selected.toggleSelectedItem(bundle.getSelectedItemIndex());
                    detached = selected.toImmutable();
                }
                result.set(DataComponents.BUNDLE_CONTENTS, detached);
            }
            var projectiles = source.get(DataComponents.CHARGED_PROJECTILES);
            if (projectiles != null)
                result.set(
                        DataComponents.CHARGED_PROJECTILES,
                        new ChargedProjectiles(templates(projectiles.items(), depth + 1)));
            var remainder = source.get(DataComponents.USE_REMAINDER);
            if (remainder != null)
                result.set(
                        DataComponents.USE_REMAINDER,
                        new UseRemainder(template(remainder.convertInto(), depth + 1)));
            var sulfur = source.get(DataComponents.SULFUR_CUBE_CONTENT);
            if (sulfur != null)
                result.set(
                        DataComponents.SULFUR_CUBE_CONTENT,
                        new SulfurCubeContent(
                                template(sulfur.absorbedBlockItemStack(), depth + 1)));
            return result;
        }

        private ItemStackTemplate template(ItemStackTemplate input, int depth) {
            return ItemStackTemplate.fromStack(stack(input.create(), depth));
        }

        private List<ItemStackTemplate> templates(List<ItemStackTemplate> input, int depth) {
            var copies = new ArrayList<ItemStackTemplate>();
            for (var value : input) copies.add(template(value, depth));
            return copies;
        }
    }

    private static <T> void detachCodec(ItemStack item, DataComponentType<T> type) {
        T value = item.get(type);
        if (value == null) return;
        var ops = CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        var codec = type.codecOrThrow();
        var encoded =
                codec.encodeStart(ops, value).getOrThrow(IllegalArgumentException::new).copy();
        item.set(type, codec.parse(ops, encoded).getOrThrow(IllegalArgumentException::new));
    }

    private static Component text(Component input) {
        var buffer = Unpooled.buffer();
        try {
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buffer, input);
            return ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static List<Component> texts(List<Component> input) {
        return input.stream().map(ProofItemCopies::text).toList();
    }

    private static FireworkExplosion explosion(FireworkExplosion value) {
        return new FireworkExplosion(
                value.shape(),
                new IntArrayList(value.colors()),
                new IntArrayList(value.fadeColors()),
                value.hasTrail(),
                value.hasTwinkle());
    }
}

package dev.itemloom.paper.display;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Connection-local evidence of successfully delivered canonical and display items. */
public final class DisplayLedger {
    public static final String MARKER = "itemloom:display";
    static final int MAX_NESTING = 63;
    static final int MAX_NESTED_ITEMS = 2048;
    private static final int MAX_ORIGINALS = 512;
    private static final int MAX_APPEARANCES = 4;
    private static final int MAX_PENDING = 2048;

    public record Sent(ItemStack original, ItemStack display, String token) {
        public Sent {
            original = ProofItemCopies.copy(original);
            display = ProofItemCopies.copy(display);
        }

        @Override
        public ItemStack original() {
            return ProofItemCopies.copy(original);
        }

        @Override
        public ItemStack display() {
            return ProofItemCopies.copy(display);
        }
    }

    private record Key(ItemStack value) {
        static Key retained(ItemStack value) {
            ItemStack owned = ProofItemCopies.copy(value);
            owned.setCount(1);
            return new Key(owned);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && ItemStack.isSameItemSameComponents(value, key.value);
        }
    }

    private record Pair(Key original, Key visible) {}

    private record Permit(Pair pair, ItemStack marked, String token) {
        boolean matches(Sent sent) {
            return pair.original.equals(new Key(sent.original))
                    && ItemStack.isSameItemSameComponents(marked, sent.display);
        }
    }

    private final LinkedHashMap<Pair, Permit> waiting = new LinkedHashMap<>();
    private final Map<String, Permit> waitingTokens = new LinkedHashMap<>();
    private final LinkedHashMap<Key, LinkedHashMap<Key, Permit>> delivered = new LinkedHashMap<>();
    private final Map<String, Permit> deliveredTokens = new LinkedHashMap<>();

    public static Sent canonical(ItemStack item) {
        return new Sent(item, item, null);
    }

    public synchronized Sent prepare(ItemStack original, ItemStack appearance) {
        if (!clean(original) || !clean(appearance) || !ItemStack.isSameItem(original, appearance)) {
            throw new IllegalArgumentException(
                    "Display proof requires unmarked nonempty items of the same type");
        }
        Pair query = new Pair(new Key(original), new Key(appearance));
        Permit permit = waiting.get(query);
        if (permit == null) {
            var known = delivered.get(query.original);
            if (known != null) permit = known.get(query.visible);
        }
        if (permit == null) {
            Pair owned = new Pair(Key.retained(original), Key.retained(appearance));
            String token = UUID.randomUUID().toString();
            ItemStack marked = ProofItemCopies.copy(owned.visible.value);
            var old = marked.get(DataComponents.CUSTOM_DATA);
            CompoundTag data = old == null ? new CompoundTag() : old.copyTag();
            data.putString(MARKER, token);
            marked.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            permit = new Permit(owned, marked, token);
            waiting.put(owned, permit);
            waitingTokens.put(token, permit);
            while (waiting.size() > MAX_PENDING) {
                var expired = waiting.pollFirstEntry().getValue();
                waitingTokens.remove(expired.token);
            }
        }
        ItemStack display = ProofItemCopies.copy(permit.marked);
        display.setCount(appearance.getCount());
        return new Sent(original, display, permit.token);
    }

    public synchronized void commit(Sent sent) {
        if (sent.token == null) {
            if (clean(sent.original)
                    && ItemStack.isSameItemSameComponents(sent.original, sent.display)) {
                remember(Key.retained(sent.original));
            }
            return;
        }
        Permit permit = waitingTokens.get(sent.token);
        if (permit == null) permit = deliveredTokens.get(sent.token);
        if (permit == null || !permit.matches(sent)) return;
        waiting.remove(permit.pair);
        waitingTokens.remove(permit.token);
        var appearances = remember(permit.pair.original);
        appearances.remove(permit.pair.visible);
        appearances.put(permit.pair.visible, permit);
        deliveredTokens.put(permit.token, permit);
        while (appearances.size() > MAX_APPEARANCES) {
            deliveredTokens.remove(appearances.pollFirstEntry().getValue().token);
        }
    }

    private LinkedHashMap<Key, Permit> remember(Key original) {
        var values = delivered.remove(original);
        if (values == null) values = new LinkedHashMap<>();
        delivered.put(original, values);
        while (delivered.size() > MAX_ORIGINALS) {
            for (var permit : delivered.pollFirstEntry().getValue().values())
                deliveredTokens.remove(permit.token);
        }
        return values;
    }

    public synchronized void clear() {
        waiting.clear();
        waitingTokens.clear();
        delivered.clear();
        deliveredTokens.clear();
    }

    public synchronized int size() {
        return delivered.size();
    }

    public static boolean marked(ItemStack item) {
        return hasMarker(item);
    }

    private static boolean hasMarker(DataComponentGetter item) {
        var data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARKER);
    }

    private static boolean clean(ItemStack item) {
        return item != null && !item.isEmpty() && !marked(item) && !hasNestedMarker(item);
    }

    private record Visit(DataComponentGetter item, int depth) {}

    public static boolean hasNestedMarker(ItemStack item) {
        try {
            var queue = new ArrayDeque<Visit>();
            queue.add(new Visit(item, 0));
            int visits = 0;
            while (!queue.isEmpty()) {
                Visit current = queue.removeFirst();
                if (++visits > MAX_NESTED_ITEMS || current.depth > MAX_NESTING) return true;
                if (current.depth != 0 && hasMarker(current.item)) return true;
                int childDepth = current.depth + 1;
                var container = current.item.get(DataComponents.CONTAINER);
                if (container != null) {
                    for (var child : container.items) {
                        if (!enqueue(
                                queue,
                                child.isPresent() ? child.get() : ItemStack.EMPTY,
                                childDepth,
                                visits)) return true;
                    }
                }
                var bundle = current.item.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null)
                    for (var child : bundle.items())
                        if (!enqueue(queue, child, childDepth, visits)) return true;
                var projectiles = current.item.get(DataComponents.CHARGED_PROJECTILES);
                if (projectiles != null)
                    for (var child : projectiles.items())
                        if (!enqueue(queue, child, childDepth, visits)) return true;
                var remainder = current.item.get(DataComponents.USE_REMAINDER);
                if (remainder != null
                        && !enqueue(queue, remainder.convertInto(), childDepth, visits))
                    return true;
                var sulfur = current.item.get(DataComponents.SULFUR_CUBE_CONTENT);
                if (sulfur != null
                        && !enqueue(queue, sulfur.absorbedBlockItemStack(), childDepth, visits))
                    return true;
            }
            return false;
        } catch (RuntimeException malformed) {
            return true;
        }
    }

    private static boolean enqueue(
            ArrayDeque<Visit> queue, DataComponentGetter item, int depth, int visits) {
        if (depth > MAX_NESTING || visits + queue.size() >= MAX_NESTED_ITEMS) return false;
        queue.addLast(new Visit(item, depth));
        return true;
    }

    public synchronized ItemStack restore(ItemStack received) {
        if (received == null || received.isEmpty() || hasNestedMarker(received)) return null;
        var data = received.get(DataComponents.CUSTOM_DATA);
        if (data == null || !(data.getUnsafe().get(MARKER) instanceof StringTag token)) return null;
        Permit permit = deliveredTokens.get(token.value());
        if (permit == null || !ItemStack.isSameItemSameComponents(received, permit.marked))
            return null;
        var result = ProofItemCopies.copy(permit.pair.original.value);
        result.setCount(received.getCount());
        return result;
    }

    public synchronized boolean knownOriginal(ItemStack item) {
        return clean(item) && delivered.containsKey(new Key(item));
    }

    public HashedStack acceptSent(HashedStack claim) {
        HashedStack snapshot = claim;
        if (claim instanceof HashedStack.ActualItem actual) {
            snapshot =
                    new HashedStack.ActualItem(
                            actual.item(),
                            actual.count(),
                            new HashedPatchMap(
                                    Map.copyOf(actual.components().addedComponents()),
                                    Set.copyOf(actual.components().removedComponents())));
        }
        HashedStack retained = snapshot;
        return (actual, hashes) -> {
            if (retained.matches(actual, hashes)) return true;
            synchronized (DisplayLedger.this) {
                if (!clean(actual)) return false;
                var appearances = delivered.get(new Key(actual));
                if (appearances == null) return false;
                for (Permit permit : appearances.values()) {
                    var candidate = permit.marked.copyWithCount(actual.getCount());
                    if (retained.matches(candidate, hashes)) return true;
                }
                return false;
            }
        };
    }
}

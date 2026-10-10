package dev.itemloom.paper.display;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Connection-local evidence. Preparing work and authorizing a successful write are separate steps. */
public final class DisplayLedger {
    public static final String MARKER = "itemloom:display";
    private static final int MAX_IDENTITIES = 512;
    private static final int MAX_APPEARANCES = 4;
    private static final int MAX_PREPARATIONS = 2048;
    static final int MAX_NESTING = 63;
    static final int MAX_NESTED_ITEMS = 2048;

    /** Snapshots on both boundaries: packet consumers cannot mutate the evidence carried to commit. */
    public record Sent(ItemStack original, ItemStack display, String token) {
        public Sent {
            original = ProofItemCopies.copy(Objects.requireNonNull(original));
            display = ProofItemCopies.copy(Objects.requireNonNull(display));
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

    /** The stack is privately owned whenever this key is retained in a map. Count is not identity. */
    private record Identity(ItemStack stack) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity
                    && ItemStack.isSameItemSameComponents(stack, identity.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }

    private record Pair(Identity original, Identity appearance) {}

    private record Appearance(Pair pair, ItemStack marked, String token) {
        boolean describes(Sent sent) {
            return token.equals(sent.token)
                    && ItemStack.isSameItemSameComponents(pair.original.stack, sent.original)
                    && ItemStack.isSameItemSameComponents(marked, sent.display);
        }
    }

    private static final class Authorization {
        final LinkedHashMap<Identity, Appearance> appearances = new LinkedHashMap<>();
    }

    private final LinkedHashMap<Identity, Authorization> authorized = new LinkedHashMap<>();
    private final Map<String, Appearance> sentTokens = new HashMap<>();
    private final LinkedHashMap<Pair, Appearance> preparing = new LinkedHashMap<>();
    private final Map<String, Appearance> preparedTokens = new HashMap<>();

    public DisplayLedger() {}

    public static Sent canonical(ItemStack stack) {
        return new Sent(stack, stack, null);
    }

    /** The rendering service enforces outbound count equality; identities deliberately ignore count. */
    public synchronized Sent prepare(ItemStack original, ItemStack display) {
        Objects.requireNonNull(original);
        Objects.requireNonNull(display);
        if (original.isEmpty()
                || display.isEmpty()
                || original.getItem() != display.getItem()
                || marked(original)
                || marked(display)
                || hasNestedMarker(original)
                || hasNestedMarker(display))
            throw new IllegalArgumentException(
                    "Display proof requires unmarked items of the same type");

        Pair lookup = new Pair(new Identity(original), new Identity(display));
        Appearance appearance = preparing.get(lookup);
        if (appearance == null) {
            Authorization existing = authorized.get(lookup.original);
            if (existing != null) appearance = existing.appearances.get(lookup.appearance);
        }
        if (appearance == null) {
            Pair owned =
                    new Pair(
                            new Identity(ProofItemCopies.copy(original).copyWithCount(1)),
                            new Identity(ProofItemCopies.copy(display).copyWithCount(1)));
            String token = UUID.randomUUID().toString();
            ItemStack marked = ProofItemCopies.copy(owned.appearance.stack);
            CustomData.update(
                    DataComponents.CUSTOM_DATA, marked, tag -> tag.putString(MARKER, token));
            appearance = new Appearance(owned, marked, token);
            preparing.put(owned, appearance);
            preparedTokens.put(token, appearance);
            while (preparing.size() > MAX_PREPARATIONS) {
                Appearance expired = preparing.pollFirstEntry().getValue();
                preparedTokens.remove(expired.token);
            }
        }
        return new Sent(
                original, appearance.marked.copyWithCount(display.getCount()), appearance.token);
    }

    /** Called only by the successful-write callback. Unknown, expired and foreign work is ignored. */
    public synchronized void commit(Sent sent) {
        Objects.requireNonNull(sent);
        if (sent.token == null) {
            if (!sent.original.isEmpty()
                    && !marked(sent.original)
                    && !hasNestedMarker(sent.original)
                    && ItemStack.isSameItemSameComponents(sent.original, sent.display))
                remember(new Identity(ProofItemCopies.copy(sent.original).copyWithCount(1)));
            return;
        }

        Appearance appearance = preparedTokens.get(sent.token);
        if (appearance == null) appearance = sentTokens.get(sent.token);
        if (appearance == null || !appearance.describes(sent)) return;
        preparing.remove(appearance.pair);
        preparedTokens.remove(appearance.token);
        Authorization group = remember(appearance.pair.original);
        group.appearances.remove(appearance.pair.appearance);
        group.appearances.put(appearance.pair.appearance, appearance);
        sentTokens.put(appearance.token, appearance);
        while (group.appearances.size() > MAX_APPEARANCES) {
            Appearance expired = group.appearances.pollFirstEntry().getValue();
            sentTokens.remove(expired.token);
        }
    }

    private Authorization remember(Identity identity) {
        Authorization group = authorized.remove(identity);
        if (group == null) group = new Authorization();
        authorized.put(identity, group);
        while (authorized.size() > MAX_IDENTITIES) {
            Authorization expired = authorized.pollFirstEntry().getValue();
            expired.appearances.values().forEach(value -> sentTokens.remove(value.token));
        }
        return group;
    }

    public synchronized void clear() {
        authorized.clear();
        sentTokens.clear();
        preparing.clear();
        preparedTokens.clear();
    }

    public synchronized int size() {
        return authorized.size();
    }

    public static boolean marked(ItemStack stack) {
        return hasMarker(stack);
    }

    private static boolean hasMarker(DataComponentGetter item) {
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARKER);
    }

    private record Child(DataComponentGetter item, int depth) {}

    /** Iterative traversal bounds both memory and work, including cyclic/malformed component graphs. */
    public static boolean hasNestedMarker(ItemStack stack) {
        ArrayDeque<Child> work = new ArrayDeque<>();
        work.add(new Child(stack, 0));
        int visited = 0;
        try {
            while (!work.isEmpty()) {
                Child child = work.removeLast();
                if (++visited > MAX_NESTED_ITEMS || child.depth > MAX_NESTING) return true;
                if (child.depth != 0 && hasMarker(child.item)) return true;
                int depth = child.depth + 1;
                var container = child.item.get(DataComponents.CONTAINER);
                if (container != null)
                    for (var item : container.nonEmptyItems()) {
                        if (work.size() + visited >= MAX_NESTED_ITEMS) return true;
                        work.add(new Child(item, depth));
                    }
                var bundle = child.item.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null)
                    for (var item : bundle.items()) {
                        if (work.size() + visited >= MAX_NESTED_ITEMS) return true;
                        work.add(new Child(item, depth));
                    }
                var projectiles = child.item.get(DataComponents.CHARGED_PROJECTILES);
                if (projectiles != null)
                    for (var item : projectiles.items()) {
                        if (work.size() + visited >= MAX_NESTED_ITEMS) return true;
                        work.add(new Child(item, depth));
                    }
                var remainder = child.item.get(DataComponents.USE_REMAINDER);
                if (remainder != null) {
                    if (work.size() + visited >= MAX_NESTED_ITEMS) return true;
                    work.add(new Child(remainder.convertInto(), depth));
                }
                var sulfur = child.item.get(DataComponents.SULFUR_CUBE_CONTENT);
                if (sulfur != null) {
                    if (work.size() + visited >= MAX_NESTED_ITEMS) return true;
                    work.add(new Child(sulfur.absorbedBlockItemStack(), depth));
                }
            }
            return false;
        } catch (RuntimeException malformed) {
            return true;
        }
    }

    public synchronized ItemStack restore(ItemStack received) {
        if (received.isEmpty() || hasNestedMarker(received)) return null;
        CustomData data = received.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String token = data.getUnsafe().getString(MARKER).orElse(null);
        Appearance appearance = sentTokens.get(token);
        if (appearance == null || !ItemStack.isSameItemSameComponents(received, appearance.marked))
            return null;
        return ProofItemCopies.copy(appearance.pair.original.stack)
                .copyWithCount(received.getCount());
    }

    public synchronized boolean knownOriginal(ItemStack stack) {
        return !stack.isEmpty()
                && !marked(stack)
                && !hasNestedMarker(stack)
                && authorized.containsKey(new Identity(stack));
    }

    public HashedStack acceptSent(HashedStack received) {
        Objects.requireNonNull(received);
        // ActualItem is the incoming wire representation; its record contains mutable collections.
        HashedStack claim = received;
        if (received instanceof HashedStack.ActualItem item)
            claim =
                    new HashedStack.ActualItem(
                            item.item(),
                            item.count(),
                            new HashedPatchMap(
                                    Map.copyOf(item.components().addedComponents()),
                                    Set.copyOf(item.components().removedComponents())));
        HashedStack detached = claim;
        return (actual, hashes) ->
                detached.matches(actual, hashes) || matchesAppearance(detached, actual, hashes);
    }

    private synchronized boolean matchesAppearance(
            HashedStack received, ItemStack actual, HashedPatchMap.HashGenerator hashes) {
        if (actual.isEmpty() || marked(actual) || hasNestedMarker(actual)) return false;
        Authorization group = authorized.get(new Identity(actual));
        if (group == null) return false;
        for (Appearance appearance : group.appearances.values())
            if (received.matches(
                    ProofItemCopies.copy(appearance.marked).copyWithCount(actual.getCount()),
                    hashes)) return true;
        return false;
    }
}

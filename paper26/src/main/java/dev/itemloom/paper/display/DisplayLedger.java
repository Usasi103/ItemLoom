package dev.itemloom.paper.display;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Connection-local proofs of copies successfully sent to this client; no script runs under its lock. */
public final class DisplayLedger {
    public static final String MARKER = "itemloom:display";
    private static final int ORIGINALS = 512, VARIANTS = 4;

    private record Key(Item item, DataComponentPatch patch) {
        static Key of(ItemStack stack) {
            return new Key(stack.getItem(), stack.getComponentsPatch());
        }
    }

    public record Sent(ItemStack original, ItemStack display, String token) {}

    private record Entry(Sent sent, ItemStack unmarked) {}

    private record PreparedKey(Key original, Key display) {}

    private final LinkedHashMap<Key, List<Entry>> entries = new LinkedHashMap<>();
    private final Map<String, Sent> tokens = new java.util.HashMap<>();
    // Reservations share tokens across packets rendered before write completion. They confer no
    // authority.
    private final LinkedHashMap<PreparedKey, Sent> prepared = new LinkedHashMap<>();

    public static Sent canonical(ItemStack original) {
        return new Sent(original.copyWithCount(1), original.copyWithCount(1), "");
    }

    /** Preparation does not authorize a return. commit is called only after a successful write. */
    public synchronized Sent prepare(ItemStack original, ItemStack display) {
        for (Entry entry : snapshot(original)) {
            Sent previous = entry.sent();
            if (previous.token().isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(entry.unmarked(), display))
                return new Sent(
                        original.copyWithCount(1),
                        previous.display().copyWithCount(display.getCount()),
                        previous.token());
        }
        var key = new PreparedKey(Key.of(original), Key.of(display));
        Sent pending = prepared.remove(key);
        if (pending != null) {
            prepared.put(key, pending);
            return new Sent(
                    pending.original().copy(),
                    pending.display().copyWithCount(display.getCount()),
                    pending.token());
        }
        String token = UUID.randomUUID().toString();
        var clean = display.copyWithCount(1);
        CustomData.update(DataComponents.CUSTOM_DATA, clean, tag -> tag.putString(MARKER, token));
        var frozen = new Sent(original.copyWithCount(1), clean, token);
        prepared.put(key, frozen);
        while (prepared.size() > ORIGINALS * VARIANTS) prepared.pollFirstEntry();
        return new Sent(frozen.original().copy(), clean.copyWithCount(display.getCount()), token);
    }

    public synchronized void commit(Sent sent) {
        Key key = Key.of(sent.original());
        var values = new ArrayList<>(entries.getOrDefault(key, List.of()));
        values.removeIf(value -> value.sent().token().equals(sent.token()));
        Sent frozen =
                new Sent(
                        sent.original().copyWithCount(1),
                        sent.display().copyWithCount(1),
                        sent.token());
        ItemStack unmarked = null;
        if (!sent.token().isEmpty()) {
            // Derive once from the detached committed proof, so edits to a returned Sent cannot
            // poison comparison state.
            unmarked = frozen.display().copy();
            CustomData.update(DataComponents.CUSTOM_DATA, unmarked, tag -> tag.remove(MARKER));
            var preparedKey = new PreparedKey(key, Key.of(unmarked));
            Sent pending = prepared.get(preparedKey);
            if (pending != null && pending.token().equals(sent.token()))
                prepared.remove(preparedKey);
        }
        values.add(new Entry(frozen, unmarked));
        if (!frozen.token().isEmpty()) tokens.put(frozen.token(), frozen);
        while (values.size() > VARIANTS) tokens.remove(values.removeFirst().sent().token());
        entries.remove(key);
        entries.put(key, List.copyOf(values));
        while (entries.size() > ORIGINALS)
            entries.pollFirstEntry()
                    .getValue()
                    .forEach(value -> tokens.remove(value.sent().token()));
    }

    private synchronized List<Entry> snapshot(ItemStack original) {
        return entries.getOrDefault(Key.of(original), List.of());
    }

    public synchronized void clear() {
        entries.clear();
        tokens.clear();
        prepared.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    public static boolean marked(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(MARKER);
    }

    /** Nested items are not display-transformed. A client must not smuggle a display copy inside a container. */
    public static boolean hasNestedMarker(ItemStack stack) {
        return nested(stack, 0, new int[] {2048});
    }

    private static boolean nested(
            net.minecraft.core.component.DataComponentGetter item, int depth, int[] budget) {
        if (depth >= 64 || --budget[0] < 0) return true;
        var container = item.get(DataComponents.CONTAINER);
        if (container != null)
            for (var child : container.nonEmptyItems())
                if (markedChild(child, depth, budget)) return true;
        var bundle = item.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null)
            for (var child : bundle.items()) if (markedChild(child, depth, budget)) return true;
        var projectiles = item.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles != null)
            for (var child : projectiles.items())
                if (markedChild(child, depth, budget)) return true;
        var remainder = item.get(DataComponents.USE_REMAINDER);
        return remainder != null && markedChild(remainder.convertInto(), depth, budget);
    }

    private static boolean markedChild(
            net.minecraft.world.item.ItemStackTemplate child, int depth, int[] budget) {
        var data = child.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARKER) || nested(child, depth + 1, budget);
    }

    /** null rejects a forged/expired display copy. Count is subsequently validated by vanilla. */
    public ItemStack restore(ItemStack incoming) {
        String token =
                incoming.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .getUnsafe()
                        .getString(MARKER)
                        .orElse(null);
        Sent sent;
        synchronized (this) {
            sent = tokens.get(token);
        }
        if (sent == null || !ItemStack.isSameItemSameComponents(incoming, sent.display()))
            return null;
        return sent.original().copyWithCount(incoming.getCount());
    }

    public boolean knownOriginal(ItemStack incoming) {
        return !snapshot(incoming).isEmpty();
    }

    public HashedStack acceptSent(HashedStack received) {
        return new HashedStack() {
            @Override
            public boolean matches(ItemStack actual, HashedPatchMap.HashGenerator hashes) {
                if (received.matches(actual, hashes)) return true;
                if (actual.isEmpty()) return false;
                for (Entry entry : snapshot(actual))
                    if (received.matches(
                            entry.sent().display().copyWithCount(actual.getCount()), hashes))
                        return true;
                return false;
            }
        };
    }
}

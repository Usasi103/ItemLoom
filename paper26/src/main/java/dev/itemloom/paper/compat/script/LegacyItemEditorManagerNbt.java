package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.paper.compat.nbt.LegacyNbt;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Rebuilds incompatible path containers recursively on the editor's detached NBT tree. */
final class LegacyItemEditorManagerNbt {
    private LegacyItemEditorManagerNbt() {}

    static void put(LegacyNbt.Compound root, String key, LegacyNbt value) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(value, "value");
        List<String> path = NiTemplate.split(key, '.', 0);
        LegacyNbt changed = write(root, path, 0, value);
        if (changed != root)
            throw new IllegalArgumentException("An item NBT path cannot replace its compound root");
    }

    private static LegacyNbt write(LegacyNbt node, List<String> path, int depth, LegacyNbt value) {
        String key = path.get(depth);
        Integer index = index(key);
        if (depth == path.size() - 1) return leaf(node, key, index, value);
        if (index != null) {
            if (node instanceof LegacyNbt.ListValue list && insertionIndex(index, list.size())) {
                LegacyNbt child = index == list.size() ? null : list.get(index);
                LegacyNbt replacement = write(child, path, depth + 1, value);
                if (child == null) list.add(replacement);
                else if (child != replacement) list.set(index, replacement);
                return list;
            }
            if (index == 0) {
                LegacyNbt.ListValue list = new LegacyNbt.ListValue();
                list.add(write(null, path, depth + 1, value));
                return list;
            }
        }
        LegacyNbt.Compound compound =
                node instanceof LegacyNbt.Compound existing ? existing : new LegacyNbt.Compound();
        LegacyNbt child = compound.get(key);
        LegacyNbt replacement = write(child, path, depth + 1, value);
        if (child != replacement) compound.put(key, replacement);
        return compound;
    }

    private static LegacyNbt leaf(LegacyNbt node, String key, Integer index, LegacyNbt value) {
        if (index == null) return named(node, key, value);
        if (node instanceof LegacyNbt.ListValue list) {
            if (!insertionIndex(index, list.size())) return named(null, key, value);
            if (index == list.size()) list.add(value);
            else list.set(index, value);
            return list;
        }
        if (node instanceof LegacyNbt.ByteArray array) {
            if (!(value instanceof LegacyNbt.ByteValue number)) return array;
            byte[] contents = array.getAsByteArray();
            if (!insertionIndex(index, contents.length)) return named(null, key, value);
            if (index == contents.length) contents = Arrays.copyOf(contents, contents.length + 1);
            contents[index] = number.getAsByte();
            return new LegacyNbt.ByteArray(contents);
        }
        if (node instanceof LegacyNbt.IntArray array) {
            if (!(value instanceof LegacyNbt.IntValue number)) return array;
            int[] contents = array.getAsIntArray();
            if (!insertionIndex(index, contents.length)) return named(null, key, value);
            if (index == contents.length) contents = Arrays.copyOf(contents, contents.length + 1);
            contents[index] = number.getAsInt();
            return new LegacyNbt.IntArray(contents);
        }
        if (node instanceof LegacyNbt.LongArray array) {
            if (!(value instanceof LegacyNbt.LongValue number)) return array;
            long[] contents = array.getAsLongArray();
            if (!insertionIndex(index, contents.length)) return named(null, key, value);
            if (index == contents.length) contents = Arrays.copyOf(contents, contents.length + 1);
            contents[index] = number.getAsLong();
            return new LegacyNbt.LongArray(contents);
        }
        // A terminal integer leaves existing compounds and scalars intact. A missing container
        // becomes an empty compound so the intermediate path still exists.
        return node == null ? new LegacyNbt.Compound() : node;
    }

    private static LegacyNbt.Compound named(LegacyNbt node, String key, LegacyNbt value) {
        LegacyNbt.Compound compound =
                node instanceof LegacyNbt.Compound existing ? existing : new LegacyNbt.Compound();
        compound.put(key, value);
        return compound;
    }

    private static boolean insertionIndex(int index, int size) {
        return index >= 0 && index <= size;
    }

    private static Integer index(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

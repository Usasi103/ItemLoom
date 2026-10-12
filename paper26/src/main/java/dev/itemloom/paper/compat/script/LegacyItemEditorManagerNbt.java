package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import java.util.*;

final class LegacyItemEditorManagerNbt {
    private LegacyItemEditorManagerNbt() {}

    static void put(LegacyNbt.Compound root, String key, LegacyNbt value) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(value, "value");
        if (insert(root, NiTemplate.split(key, '.', 0), 0, value) != root)
            throw new IllegalArgumentException("An item NBT path cannot replace its compound root");
    }

    private static Integer index(String token) {
        try {
            return Integer.valueOf(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LegacyNbt insert(LegacyNbt node, List<String> path, int at, LegacyNbt value) {
        String token = path.get(at);
        Integer index = index(token);
        if (at == path.size() - 1) return leaf(node, token, index, value);
        if (index != null
                && node instanceof LegacyNbt.ListValue list
                && index >= 0
                && index <= list.size()) {
            LegacyNbt child =
                    insert(index == list.size() ? null : list.get(index), path, at + 1, value);
            if (index == list.size()) list.add(child);
            else list.set(index, child);
            return list;
        }
        if (index != null && index == 0) {
            LegacyNbt.ListValue list = new LegacyNbt.ListValue();
            list.add(insert(null, path, at + 1, value));
            return list;
        }
        LegacyNbt.Compound compound =
                node instanceof LegacyNbt.Compound existing ? existing : new LegacyNbt.Compound();
        compound.put(token, insert(compound.get(token), path, at + 1, value));
        return compound;
    }

    private static LegacyNbt leaf(LegacyNbt node, String key, Integer index, LegacyNbt value) {
        if (index == null) {
            LegacyNbt.Compound compound =
                    node instanceof LegacyNbt.Compound existing
                            ? existing
                            : new LegacyNbt.Compound();
            compound.put(key, value);
            return compound;
        }
        if (node instanceof LegacyNbt.ListValue list) {
            if (index >= 0 && index <= list.size()) {
                if (index == list.size()) list.add(value);
                else list.set(index, value);
                return list;
            }
            return numericMember(key, value);
        }
        if (node instanceof LegacyNbt.ByteArray array) {
            if (!(value instanceof LegacyNbt.ByteValue number)) return node;
            if (index < 0 || index > array.size()) return numericMember(key, value);
            byte[] values = array.getAsByteArray();
            if (index == values.length) values = Arrays.copyOf(values, values.length + 1);
            values[index] = number.getAsByte();
            return new LegacyNbt.ByteArray(values);
        }
        if (node instanceof LegacyNbt.IntArray array) {
            if (!(value instanceof LegacyNbt.IntValue number)) return node;
            if (index < 0 || index > array.size()) return numericMember(key, value);
            int[] values = array.getAsIntArray();
            if (index == values.length) values = Arrays.copyOf(values, values.length + 1);
            values[index] = number.getAsInt();
            return new LegacyNbt.IntArray(values);
        }
        if (node instanceof LegacyNbt.LongArray array) {
            if (!(value instanceof LegacyNbt.LongValue number)) return node;
            if (index < 0 || index > array.size()) return numericMember(key, value);
            long[] values = array.getAsLongArray();
            if (index == values.length) values = Arrays.copyOf(values, values.length + 1);
            values[index] = number.getAsLong();
            return new LegacyNbt.LongArray(values);
        }
        return node == null ? new LegacyNbt.Compound() : node;
    }

    private static LegacyNbt.Compound numericMember(String key, LegacyNbt value) {
        LegacyNbt.Compound compound = new LegacyNbt.Compound();
        compound.put(key, value);
        return compound;
    }
}

package dev.itemloom.paper.compat.script;

import java.util.Arrays;
import java.util.List;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.paper.compat.nbt.LegacyNbt;

/** NI escaped numeric paths, including its compound fallback; all edits target a detached candidate. */
final class LegacyItemEditorManagerNbt {
    private LegacyItemEditorManagerNbt() {}

    private static Integer index(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static void put(LegacyNbt.Compound root, String key, LegacyNbt value) {
        LegacyNbt parent = root, current = root;
        String previous = "";
        List<String> path = NiTemplate.split(key, '.', 0);
        for (int offset = 0; offset < path.size() - 1; offset++) {
            String node = path.get(offset);
            Integer index = index(node);
            boolean fallback = false;
            if (index != null) {
                if (current instanceof LegacyNbt.ListValue list) {
                    LegacyNbt before = parent;
                    parent = current;
                    if (index >= 0 && index < list.size()) current = list.get(index);
                    else if (index == list.size()) current = list.addEmptyCompound();
                    else {
                        fallback = true;
                        parent = before;
                    }
                    if (!fallback) previous = node;
                } else if (index == 0) {
                    LegacyNbt.ListValue list = new LegacyNbt.ListValue();
                    ((LegacyNbt.Compound) parent).put(previous, list);
                    current = list.addEmptyCompound();
                    parent = list;
                    previous = node;
                } else fallback = true;
            }
            if (index == null || fallback) {
                if (current instanceof LegacyNbt.Compound compound) {
                    parent = current;
                    current = compound.computeIfAbsent(node, ignored -> new LegacyNbt.Compound());
                } else {
                    LegacyNbt.Compound replacement = new LegacyNbt.Compound(),
                            child = new LegacyNbt.Compound();
                    ((LegacyNbt.Compound) parent).put(previous, replacement);
                    replacement.put(node, child);
                    parent = replacement;
                    current = child;
                }
                previous = node;
            }
        }
        String node = path.getLast();
        Integer index = index(node);
        boolean fallback = false;
        if (index != null) {
            if (current instanceof LegacyNbt.ByteArray array
                    && value instanceof LegacyNbt.ByteValue number) {
                byte[] bytes = array.getAsByteArray();
                if (index >= 0 && index <= bytes.length) {
                    if (index == bytes.length) bytes = Arrays.copyOf(bytes, bytes.length + 1);
                    bytes[index] = number.getAsByte();
                    replace(parent, previous, new LegacyNbt.ByteArray(bytes));
                } else fallback = true;
            } else if (current instanceof LegacyNbt.IntArray array
                    && value instanceof LegacyNbt.IntValue number) {
                int[] ints = array.getAsIntArray();
                if (index >= 0 && index <= ints.length) {
                    if (index == ints.length) ints = Arrays.copyOf(ints, ints.length + 1);
                    ints[index] = number.getAsInt();
                    replace(parent, previous, new LegacyNbt.IntArray(ints));
                } else fallback = true;
            } else if (current instanceof LegacyNbt.LongArray array
                    && value instanceof LegacyNbt.LongValue number) {
                long[] longs = array.getAsLongArray();
                if (index >= 0 && index <= longs.length) {
                    if (index == longs.length) longs = Arrays.copyOf(longs, longs.length + 1);
                    longs[index] = number.getAsLong();
                    replace(parent, previous, new LegacyNbt.LongArray(longs));
                } else fallback = true;
            } else if (current instanceof LegacyNbt.ListValue list) {
                if (index >= 0 && index < list.size()) list.set(index, value);
                else if (index == list.size()) list.add(value);
                else fallback = true;
            }
        }
        if (index == null || fallback) {
            if (current instanceof LegacyNbt.Compound compound) compound.put(node, value);
            else {
                LegacyNbt.Compound replacement = new LegacyNbt.Compound();
                replacement.put(node, value);
                ((LegacyNbt.Compound) parent).put(previous, replacement);
            }
        }
    }

    private static void replace(LegacyNbt parent, String key, LegacyNbt value) {
        if (parent instanceof LegacyNbt.ListValue list) list.set(Integer.parseInt(key), value);
        else ((LegacyNbt.Compound) parent).put(key, value);
    }
}

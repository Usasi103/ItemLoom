package dev.itemloom.paper.compat.nbt;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/**
 * Typed script values for NI's NBT vocabulary, implemented directly against Paper 26.2.
 * Behavioral reference: NeigeItems / InkerBot NBT wrappers, GPL-3.0 (see NOTICE.md).
 * Raw handles and clones are detached; item-backed mutations pass through one validated commit.
 */
public abstract class LegacyNbt implements Comparable<LegacyNbt>, Cloneable {
    final Tag tag;
    final Store store;

    LegacyNbt(Tag tag, Store store) {
        this.tag = Objects.requireNonNull(tag, "tag");
        this.store = Objects.requireNonNull(store, "store");
    }

    public final byte getId() {
        return tag.getId();
    }

    public final Tag getHandle() {
        return copyTag();
    }

    public Tag copyTag() {
        synchronized (store.owner()) {
            return tag.copy();
        }
    }

    public String getAsString() {
        return tag.toString();
    }

    public final Object toValue() {
        return plain(copyTag());
    }

    public static Object toValue(Object value) {
        if (value instanceof LegacyNbt nbt) return nbt.toValue();
        if (value instanceof Tag nbt) return plain(nbt);
        throw new IllegalArgumentException("Not an NBT value: " + value);
    }

    @Override
    public LegacyNbt clone() {
        return wrap(copyTag());
    }

    @Override
    public final String toString() {
        synchronized (store) {
            return tag.toString();
        }
    }

    @Override
    public final int hashCode() {
        synchronized (store) {
            return tag.hashCode();
        }
    }

    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof LegacyNbt nbt && copyTag().equals(nbt.copyTag());
    }

    @Override
    public int compareTo(LegacyNbt other) {
        int type = Byte.compare(getId(), other.getId());
        return type != 0 ? type : getAsString().compareTo(other.getAsString());
    }

    public static LegacyNbt of(Object value) {
        return wrap(encode(value));
    }

    public static LegacyNbt wrap(Tag value) {
        if (value == null) return null;
        Tag copy = value.copy();
        return view(copy, new Store(copy, null));
    }

    static Compound linked(
            CompoundTag value, ItemStack source, BiConsumer<CompoundTag, CompoundTag> commit) {
        CompoundTag copy = value.copy();
        return new Compound(copy, new Store(copy, source, commit));
    }

    private static LegacyNbt view(Tag value, Store owner) {
        if (value == null) return null;
        return switch (value) {
            case CompoundTag tag -> new Compound(tag, owner);
            case ListTag tag -> new ListValue(tag, owner);
            case ByteTag tag -> new ByteValue(tag, owner);
            case ShortTag tag -> new ShortValue(tag, owner);
            case IntTag tag -> new IntValue(tag, owner);
            case LongTag tag -> new LongValue(tag, owner);
            case FloatTag tag -> new FloatValue(tag, owner);
            case DoubleTag tag -> new DoubleValue(tag, owner);
            case StringTag tag -> new StringValue(tag, owner);
            case ByteArrayTag tag -> new ByteArray(tag, owner);
            case IntArrayTag tag -> new IntArray(tag, owner);
            case LongArrayTag tag -> new LongArray(tag, owner);
            case EndTag ignored -> End.INSTANCE;
            default -> throw new IllegalArgumentException("Unsupported NBT type: " + value.getId());
        };
    }

    private static Tag encode(Object value) {
        if (value instanceof LegacyNbt nbt) return nbt.copyTag();
        if (value instanceof Map<?, ?> map) {
            CompoundTag result = new CompoundTag();
            map.forEach(
                    (key, child) ->
                            result.put(
                                    Objects.requireNonNull(key, "key").toString(), encode(child)));
            return result;
        }
        if (value instanceof Collection<?> list) {
            ListTag result = new ListTag();
            for (Object child : list) result.add(encode(child));
            return result;
        }
        return NmsItems.tag(value);
    }

    private static Object plain(Tag value) {
        if (value instanceof NumericTag number) return number.box();
        if (value instanceof StringTag string) return string.value();
        if (value instanceof CompoundTag compound) {
            Map<String, Object> result = new LinkedHashMap<>();
            compound.entrySet()
                    .forEach(entry -> result.put(entry.getKey(), plain(entry.getValue())));
            return result;
        }
        List<Object> result = new ArrayList<>();
        if (value instanceof ListTag list) {
            for (Tag child : list) result.add(plain(child));
            return result;
        }
        if (value instanceof ByteArrayTag array) {
            for (byte child : array.getAsByteArray()) result.add(child);
            return result;
        }
        if (value instanceof IntArrayTag array) {
            for (int child : array.getAsIntArray()) result.add(child);
            return result;
        }
        if (value instanceof LongArrayTag array) {
            for (long child : array.getAsLongArray()) result.add(child);
            return result;
        }
        return value.toString();
    }

    /** Retain child object identity while validating a detached candidate before any live write. */
    private static final class Store {
        private final Tag root;
        private final ItemStack source;
        private final BiConsumer<CompoundTag, CompoundTag> commit;
        private volatile Store parent;

        private Store(Tag root, BiConsumer<CompoundTag, CompoundTag> commit) {
            this(root, null, commit);
        }

        private Store(Tag root, ItemStack source, BiConsumer<CompoundTag, CompoundTag> commit) {
            this.root = root;
            this.source = source;
            this.commit = commit;
        }

        private Store owner() {
            Store current = this;
            while (current.parent != null) current = current.parent;
            return current;
        }

        synchronized <R> R change(Tag target, Function<Tag, R> operation) {
            Store owner = owner();
            if (owner != this) return owner.change(target, operation);
            if (commit == null) return operation.apply(target);
            List<Object> path = new ArrayList<>();
            // A previously removed child's handle remains a detached value, as in the old API.
            if (!locate(root, target, path)) return operation.apply(target);
            CompoundTag candidate = ((CompoundTag) root).copy();
            Tag at = candidate;
            for (Object step : path)
                at =
                        step instanceof String key
                                ? ((CompoundTag) at).get(key)
                                : ((ListTag) at).get((Integer) step);
            operation.apply(at);
            if (!candidate.equals(root)) commit.accept(((CompoundTag) root).copy(), candidate);
            return operation.apply(target);
        }

        private static boolean locate(Tag node, Tag target, List<Object> path) {
            if (node == target) return true;
            if (node instanceof CompoundTag compound) {
                for (var entry : compound.entrySet()) {
                    path.add(entry.getKey());
                    if (locate(entry.getValue(), target, path)) return true;
                    path.removeLast();
                }
            } else if (node instanceof ListTag list) {
                for (int index = 0; index < list.size(); index++) {
                    path.add(index);
                    if (locate(list.get(index), target, path)) return true;
                    path.removeLast();
                }
            }
            return false;
        }
    }

    /** A newly constructed subtree remains live after insertion; existing shared trees are copied. */
    private static final class Insert {
        private final LegacyNbt source;
        private final Store destination;
        private final Tag snapshot;
        private final boolean adopt;
        private boolean attached;

        private Insert(LegacyNbt source, Store destination) {
            this(source, destination, true);
        }

        private Insert(LegacyNbt source, Store destination, boolean allowAdopt) {
            this.source = Objects.requireNonNull(source, "value");
            this.destination = destination;
            snapshot = source.copyTag();
            adopt =
                    allowAdopt
                            && (source.tag instanceof CompoundTag || source.tag instanceof ListTag)
                            && source.store.parent == null
                            && source.store.commit == null
                            && source.store.root == source.tag
                            && source.store != destination.owner();
        }

        private Tag value(boolean live) {
            if (live && adopt) {
                attached = true;
                return source.tag;
            }
            return snapshot.copy();
        }

        private void finish() {
            if (attached) source.store.parent = destination.owner();
        }
    }

    public static final class Compound extends LegacyNbt implements Map<String, LegacyNbt> {
        public Compound() {
            this(new CompoundTag());
        }

        public Compound(CompoundTag value) {
            this(value.copy(), null);
        }

        private Compound(CompoundTag value, Store owner) {
            super(value, owner == null ? new Store(value, null) : owner);
        }

        private CompoundTag compound() {
            return (CompoundTag) tag;
        }

        public static Compound createUnsafe(Object value) {
            return value instanceof CompoundTag compound ? new Compound(compound) : null;
        }

        @Override
        public CompoundTag copyTag() {
            return (CompoundTag) super.copyTag();
        }

        @Override
        public Compound clone() {
            return new Compound(copyTag(), null);
        }

        @Override
        public int compareTo(LegacyNbt other) {
            if (!(other instanceof Compound value)) return super.compareTo(other);
            int size = Integer.compare(size(), value.size());
            if (size != 0) return size;
            Iterator<Entry<String, LegacyNbt>> left = entrySet().iterator(),
                    right = value.entrySet().iterator();
            while (left.hasNext()) {
                var first = left.next();
                var second = right.next();
                int key = first.getKey().compareTo(second.getKey());
                if (key != 0) return key;
                int child = first.getValue().compareTo(second.getValue());
                if (child != 0) return child;
            }
            return 0;
        }

        public void saveTo(ItemStack item) {
            Objects.requireNonNull(item, "item");
            Store owner = store.owner();
            synchronized (owner) {
                if (owner.root == tag
                        && owner.source != null
                        && (owner.source == item
                                || CraftItemStack.unwrap(owner.source)
                                        == CraftItemStack.unwrap(item))) {
                    // Live edits have already committed. Replaying this stale snapshot as a
                    // replacement would erase changes made through another handle. The normal
                    // commit still validates/migrates an untouched legacy item when saved.
                    owner.commit.accept(compound().copy(), compound().copy());
                    return;
                }
            }
            new LegacyNbtItemStack(item).setTag(this);
        }

        @Override
        public int size() {
            return compound().size();
        }

        @Override
        public boolean isEmpty() {
            return compound().isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String name && compound().contains(name);
        }

        public boolean containsKey(String key) {
            return containsKey((Object) key);
        }

        @Override
        public boolean containsValue(Object value) {
            return values().contains(value);
        }

        @Override
        public LegacyNbt get(Object key) {
            return key instanceof String name ? view(compound().get(name), store) : null;
        }

        public LegacyNbt get(String key) {
            return get((Object) key);
        }

        @Override
        public LegacyNbt put(String key, LegacyNbt value) {
            Objects.requireNonNull(key, "key");
            Insert prepared = new Insert(value, store);
            Tag previous =
                    store.change(
                            tag,
                            node -> ((CompoundTag) node).put(key, prepared.value(node == tag)));
            prepared.finish();
            return wrap(previous);
        }

        public void set(String key, LegacyNbt value) {
            put(key, value);
        }

        @Override
        public LegacyNbt remove(Object key) {
            if (!(key instanceof String name)) return null;
            return wrap(store.change(tag, node -> ((CompoundTag) node).remove(name)));
        }

        public LegacyNbt remove(String key) {
            return remove((Object) key);
        }

        public void delete(String key) {
            remove(key);
        }

        @Override
        public void putAll(Map<? extends String, ? extends LegacyNbt> values) {
            Map<String, Insert> prepared = new LinkedHashMap<>();
            Set<Tag> sources = Collections.newSetFromMap(new IdentityHashMap<>());
            values.forEach(
                    (key, value) ->
                            prepared.put(
                                    Objects.requireNonNull(key),
                                    new Insert(value, store, sources.add(value.tag))));
            store.change(
                    tag,
                    node -> {
                        prepared.forEach(
                                (key, value) ->
                                        ((CompoundTag) node).put(key, value.value(node == tag)));
                        return null;
                    });
            prepared.values().forEach(Insert::finish);
        }

        @Override
        public void clear() {
            store.change(
                    tag,
                    node -> {
                        for (String key : new ArrayList<>(((CompoundTag) node).keySet()))
                            ((CompoundTag) node).remove(key);
                        return null;
                    });
        }

        @Override
        public Set<String> keySet() {
            return mapView().keySet();
        }

        @Override
        public Collection<LegacyNbt> values() {
            return mapView().values();
        }

        private Map<String, LegacyNbt> mapView() {
            return new AbstractMap<>() {
                @Override
                public Set<Entry<String, LegacyNbt>> entrySet() {
                    return Compound.this.entrySet();
                }

                @Override
                public int size() {
                    return Compound.this.size();
                }

                @Override
                public LegacyNbt remove(Object key) {
                    return Compound.this.remove(key);
                }

                @Override
                public void clear() {
                    Compound.this.clear();
                }
            };
        }

        @Override
        public Set<Entry<String, LegacyNbt>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return Compound.this.size();
                }

                @Override
                public void clear() {
                    Compound.this.clear();
                }

                @Override
                public Iterator<Entry<String, LegacyNbt>> iterator() {
                    Iterator<String> keys = new ArrayList<>(compound().keySet()).iterator();
                    return new Iterator<>() {
                        private String current;

                        @Override
                        public boolean hasNext() {
                            return keys.hasNext();
                        }

                        @Override
                        public Entry<String, LegacyNbt> next() {
                            current = keys.next();
                            final String key = current;
                            return new Entry<>() {
                                @Override
                                public String getKey() {
                                    return key;
                                }

                                @Override
                                public LegacyNbt getValue() {
                                    return Compound.this.get(key);
                                }

                                @Override
                                public LegacyNbt setValue(LegacyNbt value) {
                                    return Compound.this.put(key, value);
                                }

                                @Override
                                public int hashCode() {
                                    return key.hashCode() ^ Objects.hashCode(getValue());
                                }

                                @Override
                                public boolean equals(Object value) {
                                    return value instanceof Entry<?, ?> entry
                                            && key.equals(entry.getKey())
                                            && Objects.equals(getValue(), entry.getValue());
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            if (current == null) throw new IllegalStateException();
                            Compound.this.remove(current);
                            current = null;
                        }
                    };
                }
            };
        }

        public byte getTagType(String key) {
            LegacyNbt value = get(key);
            return value == null ? 0 : value.getId();
        }

        public boolean contains(String key, int type) {
            LegacyNbt value = get(key);
            return value != null && (type == 99 ? value instanceof Numeric : value.getId() == type);
        }

        public LegacyNbt getDeep(String path) {
            return getDeep(path, '.', '\\');
        }

        public LegacyNbt getDeep(String path, char separator, char escape) {
            LegacyNbt current = this;
            for (String key : split(path, separator, escape)) {
                if (!(current instanceof Compound compound)) return null;
                current = compound.get(key);
            }
            return current;
        }

        public void putDeep(String path, LegacyNbt value) {
            putDeep(path, value, false);
        }

        public void putDeep(String path, LegacyNbt value, boolean force) {
            putDeep(path, value, force, '.', '\\');
        }

        public void putDeep(
                String path, LegacyNbt value, boolean force, char separator, char escape) {
            List<String> keys = split(path, separator, escape);
            Insert prepared = new Insert(value, store);
            store.change(
                    tag,
                    node -> {
                        CompoundTag current = (CompoundTag) node;
                        for (int index = 0; index < keys.size() - 1; index++) {
                            String key = keys.get(index);
                            Tag child = current.get(key);
                            if (child != null && !(child instanceof CompoundTag) && !force)
                                return null;
                            if (!(child instanceof CompoundTag)) {
                                child = new CompoundTag();
                                current.put(key, child);
                            }
                            current = (CompoundTag) child;
                        }
                        current.put(keys.getLast(), prepared.value(node == tag));
                        return null;
                    });
            prepared.finish();
        }

        public void deleteDeep(String path) {
            List<String> keys = split(path, '.', '\\');
            store.change(
                    tag,
                    node -> {
                        CompoundTag current = (CompoundTag) node;
                        for (int index = 0; index < keys.size() - 1; index++) {
                            if (!(current.get(keys.get(index)) instanceof CompoundTag child))
                                return null;
                            current = child;
                        }
                        current.remove(keys.getLast());
                        return null;
                    });
        }

        public Compound coverWith(Compound overlay) {
            CompoundTag values = overlay.copyTag();
            store.change(
                    tag,
                    node -> {
                        cover((CompoundTag) node, values);
                        return null;
                    });
            return this;
        }

        private static void cover(CompoundTag base, CompoundTag overlay) {
            for (var entry : overlay.entrySet()) {
                if (entry.getValue() instanceof CompoundTag child
                        && base.get(entry.getKey()) instanceof CompoundTag existing)
                    cover(existing, child);
                else base.put(entry.getKey(), entry.getValue().copy());
            }
        }

        public Compound getCompound(String key) {
            return getCompound(key, null);
        }

        public Compound getCompoundOrNull(String key) {
            return getCompound(key);
        }

        public Compound getCompound(String key, Compound fallback) {
            return get(key) instanceof Compound value ? value : fallback;
        }

        public Compound getOrCreateCompound(String key) {
            Compound result = getCompound(key);
            if (result == null) {
                put(key, new Compound());
                result = getCompound(key);
            }
            return result;
        }

        public ListValue getList(String key) {
            return getList(key, (ListValue) null);
        }

        public ListValue getListOrNull(String key) {
            return getList(key);
        }

        public ListValue getList(String key, ListValue fallback) {
            return get(key) instanceof ListValue value ? value : fallback;
        }

        public ListValue getList(String key, int type) {
            ListValue result = getList(key);
            return result == null
                            || type != 0 && !result.isEmpty() && result.getFirst().getId() != type
                    ? new ListValue()
                    : result;
        }

        public ListValue getOrCreateList(String key) {
            ListValue result = getList(key);
            if (result == null) {
                put(key, new ListValue());
                result = getList(key);
            }
            return result;
        }

        public byte getByte(String key) {
            return getByte(key, (byte) 0);
        }

        public byte getByte(String key, byte fallback) {
            return get(key) instanceof Numeric value ? value.getAsByte() : fallback;
        }

        public Byte getByteOrNull(String key) {
            return get(key) instanceof Numeric value ? value.getAsByte() : null;
        }

        public byte getDeepByte(String key) {
            return getDeepByte(key, (byte) 0);
        }

        public byte getDeepByte(String key, byte fallback) {
            return getDeep(key) instanceof Numeric value ? value.getAsByte() : fallback;
        }

        public Byte getDeepByteOrNull(String key) {
            return getDeep(key) instanceof Numeric value ? value.getAsByte() : null;
        }

        public void putByte(String key, byte value) {
            put(key, ByteValue.valueOf(value));
        }

        public void putDeepByte(String key, byte value) {
            putDeepByte(key, value, false);
        }

        public void putDeepByte(String key, byte value, boolean force) {
            putDeep(key, ByteValue.valueOf(value), force);
        }

        public short getShort(String key) {
            return getShort(key, (short) 0);
        }

        public short getShort(String key, short fallback) {
            return get(key) instanceof Numeric value ? value.getAsShort() : fallback;
        }

        public Short getShortOrNull(String key) {
            return get(key) instanceof Numeric value ? value.getAsShort() : null;
        }

        public short getDeepShort(String key) {
            return getDeepShort(key, (short) 0);
        }

        public short getDeepShort(String key, short fallback) {
            return getDeep(key) instanceof Numeric value ? value.getAsShort() : fallback;
        }

        public Short getDeepShortOrNull(String key) {
            return getDeep(key) instanceof Numeric value ? value.getAsShort() : null;
        }

        public void putShort(String key, short value) {
            put(key, ShortValue.valueOf(value));
        }

        public void putDeepShort(String key, short value) {
            putDeepShort(key, value, false);
        }

        public void putDeepShort(String key, short value, boolean force) {
            putDeep(key, ShortValue.valueOf(value), force);
        }

        public int getInt(String key) {
            return getInt(key, 0);
        }

        public int getInt(String key, int fallback) {
            return get(key) instanceof Numeric value ? value.getAsInt() : fallback;
        }

        public Integer getIntOrNull(String key) {
            return get(key) instanceof Numeric value ? value.getAsInt() : null;
        }

        public int getDeepInt(String key) {
            return getDeepInt(key, 0);
        }

        public int getDeepInt(String key, int fallback) {
            return getDeep(key) instanceof Numeric value ? value.getAsInt() : fallback;
        }

        public Integer getDeepIntOrNull(String key) {
            return getDeep(key) instanceof Numeric value ? value.getAsInt() : null;
        }

        public void putInt(String key, int value) {
            put(key, IntValue.valueOf(value));
        }

        public void putDeepInt(String key, int value) {
            putDeepInt(key, value, false);
        }

        public void putDeepInt(String key, int value, boolean force) {
            putDeep(key, IntValue.valueOf(value), force);
        }

        public long getLong(String key) {
            return getLong(key, 0);
        }

        public long getLong(String key, long fallback) {
            return get(key) instanceof Numeric value ? value.getAsLong() : fallback;
        }

        public Long getLongOrNull(String key) {
            return get(key) instanceof Numeric value ? value.getAsLong() : null;
        }

        public long getDeepLong(String key) {
            return getDeepLong(key, 0);
        }

        public long getDeepLong(String key, long fallback) {
            return getDeep(key) instanceof Numeric value ? value.getAsLong() : fallback;
        }

        public Long getDeepLongOrNull(String key) {
            return getDeep(key) instanceof Numeric value ? value.getAsLong() : null;
        }

        public void putLong(String key, long value) {
            put(key, LongValue.valueOf(value));
        }

        public void putDeepLong(String key, long value) {
            putDeepLong(key, value, false);
        }

        public void putDeepLong(String key, long value, boolean force) {
            putDeep(key, LongValue.valueOf(value), force);
        }

        public float getFloat(String key) {
            return getFloat(key, 0);
        }

        public float getFloat(String key, float fallback) {
            return get(key) instanceof Numeric value ? value.getAsFloat() : fallback;
        }

        public Float getFloatOrNull(String key) {
            return get(key) instanceof Numeric value ? value.getAsFloat() : null;
        }

        public float getDeepFloat(String key) {
            return getDeepFloat(key, 0);
        }

        public float getDeepFloat(String key, float fallback) {
            return getDeep(key) instanceof Numeric value ? value.getAsFloat() : fallback;
        }

        public Float getDeepFloatOrNull(String key) {
            return getDeep(key) instanceof Numeric value ? value.getAsFloat() : null;
        }

        public void putFloat(String key, float value) {
            put(key, FloatValue.valueOf(value));
        }

        public void putDeepFloat(String key, float value) {
            putDeepFloat(key, value, false);
        }

        public void putDeepFloat(String key, float value, boolean force) {
            putDeep(key, FloatValue.valueOf(value), force);
        }

        public double getDouble(String key) {
            return getDouble(key, 0);
        }

        public double getDouble(String key, double fallback) {
            return get(key) instanceof Numeric value ? value.getAsDouble() : fallback;
        }

        public Double getDoubleOrNull(String key) {
            return get(key) instanceof Numeric value ? value.getAsDouble() : null;
        }

        public double getDeepDouble(String key) {
            return getDeepDouble(key, 0);
        }

        public double getDeepDouble(String key, double fallback) {
            return getDeep(key) instanceof Numeric value ? value.getAsDouble() : fallback;
        }

        public Double getDeepDoubleOrNull(String key) {
            return getDeep(key) instanceof Numeric value ? value.getAsDouble() : null;
        }

        public void putDouble(String key, double value) {
            put(key, DoubleValue.valueOf(value));
        }

        public void putDeepDouble(String key, double value) {
            putDeepDouble(key, value, false);
        }

        public void putDeepDouble(String key, double value, boolean force) {
            putDeep(key, DoubleValue.valueOf(value), force);
        }

        public byte[] getByteArray(String key) {
            return getByteArray(key, new byte[0]);
        }

        public byte[] getByteArray(String key, byte[] fallback) {
            return get(key) instanceof ByteArray value ? value.getAsByteArray() : fallback;
        }

        public byte[] getByteArrayOrNull(String key) {
            return getByteArray(key, null);
        }

        public byte[] getDeepByteArray(String key) {
            return getDeepByteArray(key, new byte[0]);
        }

        public byte[] getDeepByteArray(String key, byte[] fallback) {
            return getDeep(key) instanceof ByteArray value ? value.getAsByteArray() : fallback;
        }

        public byte[] getDeepByteArrayOrNull(String key) {
            return getDeepByteArray(key, null);
        }

        public void putByteArray(String key, byte[] value) {
            put(key, new ByteArray(value));
        }

        public void putDeepByteArray(String key, byte[] value) {
            putDeepByteArray(key, value, false);
        }

        public void putDeepByteArray(String key, byte[] value, boolean force) {
            putDeep(key, new ByteArray(value), force);
        }

        public void putByteArray(String key, List<? extends Number> value) {
            put(key, new ByteArray(value));
        }

        public void putDeepByteArray(String key, List<? extends Number> value) {
            putDeepByteArray(key, value, false);
        }

        public void putDeepByteArray(String key, List<? extends Number> value, boolean force) {
            putDeep(key, new ByteArray(value), force);
        }

        public int[] getIntArray(String key) {
            return getIntArray(key, new int[0]);
        }

        public int[] getIntArray(String key, int[] fallback) {
            return get(key) instanceof IntArray value ? value.getAsIntArray() : fallback;
        }

        public int[] getIntArrayOrNull(String key) {
            return getIntArray(key, null);
        }

        public int[] getDeepIntArray(String key) {
            return getDeepIntArray(key, new int[0]);
        }

        public int[] getDeepIntArray(String key, int[] fallback) {
            return getDeep(key) instanceof IntArray value ? value.getAsIntArray() : fallback;
        }

        public int[] getDeepIntArrayOrNull(String key) {
            return getDeepIntArray(key, null);
        }

        public void putIntArray(String key, int[] value) {
            put(key, new IntArray(value));
        }

        public void putDeepIntArray(String key, int[] value) {
            putDeepIntArray(key, value, false);
        }

        public void putDeepIntArray(String key, int[] value, boolean force) {
            putDeep(key, new IntArray(value), force);
        }

        public void putIntArray(String key, List<? extends Number> value) {
            put(key, new IntArray(value));
        }

        public void putDeepIntArray(String key, List<? extends Number> value) {
            putDeepIntArray(key, value, false);
        }

        public void putDeepIntArray(String key, List<? extends Number> value, boolean force) {
            putDeep(key, new IntArray(value), force);
        }

        public long[] getLongArray(String key) {
            return getLongArray(key, new long[0]);
        }

        public long[] getLongArray(String key, long[] fallback) {
            return get(key) instanceof LongArray value ? value.getAsLongArray() : fallback;
        }

        public long[] getLongArrayOrNull(String key) {
            return getLongArray(key, null);
        }

        public long[] getDeepLongArray(String key) {
            return getDeepLongArray(key, new long[0]);
        }

        public long[] getDeepLongArray(String key, long[] fallback) {
            return getDeep(key) instanceof LongArray value ? value.getAsLongArray() : fallback;
        }

        public long[] getDeepLongArrayOrNull(String key) {
            return getDeepLongArray(key, null);
        }

        public void putLongArray(String key, long[] value) {
            put(key, new LongArray(value));
        }

        public void putDeepLongArray(String key, long[] value) {
            putDeepLongArray(key, value, false);
        }

        public void putDeepLongArray(String key, long[] value, boolean force) {
            putDeep(key, new LongArray(value), force);
        }

        public void putLongArray(String key, List<? extends Number> value) {
            put(key, new LongArray(value));
        }

        public void putDeepLongArray(String key, List<? extends Number> value) {
            putDeepLongArray(key, value, false);
        }

        public void putDeepLongArray(String key, List<? extends Number> value, boolean force) {
            putDeep(key, new LongArray(value), force);
        }

        public String getString(String key) {
            return getString(key, null);
        }

        public String getStringOrNull(String key) {
            return getString(key);
        }

        public String getString(String key, String fallback) {
            LegacyNbt value = get(key);
            return value == null ? fallback : value.getAsString();
        }

        public boolean getBoolean(String key) {
            return getBoolean(key, false);
        }

        public Boolean getBooleanOrNull(String key) {
            return get(key) instanceof Numeric value ? value.getAsByte() != 0 : null;
        }

        public boolean getBoolean(String key, boolean fallback) {
            return get(key) instanceof Numeric value ? value.getAsByte() != 0 : fallback;
        }

        public String getDeepString(String key) {
            return getDeepString(key, null);
        }

        public String getDeepStringOrNull(String key) {
            return getDeepString(key);
        }

        public String getDeepString(String key, String fallback) {
            LegacyNbt value = getDeep(key);
            return value == null ? fallback : value.getAsString();
        }

        public boolean getDeepBoolean(String key) {
            return getDeepBoolean(key, false);
        }

        public Boolean getDeepBooleanOrNull(String key) {
            return getDeep(key) instanceof Numeric value ? value.getAsByte() != 0 : null;
        }

        public boolean getDeepBoolean(String key, boolean fallback) {
            return getDeep(key) instanceof Numeric value ? value.getAsByte() != 0 : fallback;
        }

        public void putString(String key, String value) {
            put(key, StringValue.valueOf(value));
        }

        public void putBoolean(String key, boolean value) {
            put(key, ByteValue.valueOf(value));
        }

        public void putDeepString(String key, String value) {
            putDeepString(key, value, false);
        }

        public void putDeepString(String key, String value, boolean force) {
            putDeep(key, StringValue.valueOf(value), force);
        }

        public void putDeepBoolean(String key, boolean value) {
            putDeepBoolean(key, value, false);
        }

        public void putDeepBoolean(String key, boolean value, boolean force) {
            putDeep(key, ByteValue.valueOf(value), force);
        }

        public Compound getDeepCompound(String key) {
            return getDeepCompound(key, null);
        }

        public Compound getDeepCompoundOrNull(String key) {
            return getDeepCompound(key);
        }

        public Compound getDeepCompound(String key, Compound fallback) {
            return getDeep(key) instanceof Compound value ? value : fallback;
        }

        public ListValue getDeepList(String key) {
            return getDeepList(key, null);
        }

        public ListValue getDeepListOrNull(String key) {
            return getDeepList(key);
        }

        public ListValue getDeepList(String key, ListValue fallback) {
            return getDeep(key) instanceof ListValue value ? value : fallback;
        }

        public void putUUID(String key, UUID value) {
            putIntArray(key, uuidArray(value));
        }

        public UUID getUUID(String key) {
            return getUUID(key, null);
        }

        public UUID getUUIDOrNull(String key) {
            return getUUID(key);
        }

        public UUID getUUID(String key, UUID fallback) {
            return uuid(get(key), get(key + "Most"), get(key + "Least"), fallback);
        }

        public UUID getDeepUUID(String key) {
            return getDeepUUID(key, null);
        }

        public UUID getDeepUUIDOrNull(String key) {
            return getDeepUUID(key);
        }

        public UUID getDeepUUID(String key, UUID fallback) {
            return uuid(getDeep(key), getDeep(key + "Most"), getDeep(key + "Least"), fallback);
        }

        private static UUID uuid(LegacyNbt value, LegacyNbt most, LegacyNbt least, UUID fallback) {
            if (value instanceof IntArray array) {
                int[] parts = array.getAsIntArray();
                return parts.length == 4
                        ? new UUID(
                                (long) parts[0] << 32 | parts[1] & 0xffffffffL,
                                (long) parts[2] << 32 | parts[3] & 0xffffffffL)
                        : fallback;
            }
            return most instanceof Numeric high && least instanceof Numeric low
                    ? new UUID(high.getAsLong(), low.getAsLong())
                    : fallback;
        }

        private static int[] uuidArray(UUID value) {
            return new int[] {
                (int) (value.getMostSignificantBits() >> 32),
                (int) value.getMostSignificantBits(),
                (int) (value.getLeastSignificantBits() >> 32),
                (int) value.getLeastSignificantBits()
            };
        }

        public static final class Unsafe {
            private Unsafe() {}

            public static Compound of(Object value) {
                return new Compound((CompoundTag) value);
            }
        }
    }

    public abstract static class CollectionValue<E> extends LegacyNbt implements List<E> {
        private final List<E> list =
                new AbstractList<>() {
                    @Override
                    public E get(int index) {
                        return CollectionValue.this.get(index);
                    }

                    @Override
                    public int size() {
                        return CollectionValue.this.size();
                    }

                    @Override
                    public E set(int index, E value) {
                        return CollectionValue.this.set(index, value);
                    }

                    @Override
                    public void add(int index, E value) {
                        CollectionValue.this.add(index, value);
                    }

                    @Override
                    public E remove(int index) {
                        return CollectionValue.this.remove(index);
                    }
                };

        CollectionValue(Tag tag, Store owner) {
            super(tag, owner);
        }

        @Override
        public abstract E get(int index);

        @Override
        public abstract int size();

        @Override
        public abstract E set(int index, E value);

        @Override
        public abstract void add(int index, E value);

        @Override
        public abstract E remove(int index);

        @Override
        public boolean add(E value) {
            add(size(), value);
            return true;
        }

        @Override
        public boolean isEmpty() {
            return size() == 0;
        }

        @Override
        public boolean contains(Object value) {
            return list.contains(value);
        }

        @Override
        public Iterator<E> iterator() {
            return list.iterator();
        }

        @Override
        public ListIterator<E> listIterator() {
            return list.listIterator();
        }

        @Override
        public ListIterator<E> listIterator(int index) {
            return list.listIterator(index);
        }

        @Override
        public List<E> subList(int start, int end) {
            return list.subList(start, end);
        }

        @Override
        public Object[] toArray() {
            return list.toArray();
        }

        @Override
        public <T> T[] toArray(T[] values) {
            return list.toArray(values);
        }

        @Override
        public boolean remove(Object value) {
            return list.remove(value);
        }

        @Override
        public boolean containsAll(Collection<?> values) {
            return list.containsAll(values);
        }

        @Override
        public boolean addAll(Collection<? extends E> values) {
            return addAll(size(), values);
        }

        @Override
        public boolean addAll(int index, Collection<? extends E> values) {
            return list.addAll(index, values);
        }

        @Override
        public boolean removeAll(Collection<?> values) {
            return list.removeAll(values);
        }

        @Override
        public boolean retainAll(Collection<?> values) {
            return list.retainAll(values);
        }

        @Override
        public void clear() {
            list.clear();
        }

        @Override
        public int indexOf(Object value) {
            return list.indexOf(value);
        }

        @Override
        public int lastIndexOf(Object value) {
            return list.lastIndexOf(value);
        }
    }

    public static final class ListValue extends CollectionValue<LegacyNbt> {
        public ListValue() {
            this(new ListTag(), null);
        }

        private ListValue(ListTag tag, Store owner) {
            super(tag, owner == null ? new Store(tag, null) : owner);
        }

        private ListTag list() {
            return (ListTag) tag;
        }

        @Override
        public int size() {
            return list().size();
        }

        @Override
        public LegacyNbt get(int index) {
            return view(list().get(index), store);
        }

        @Override
        public LegacyNbt set(int index, LegacyNbt value) {
            Insert prepared = new Insert(value, store);
            Tag previous =
                    store.change(
                            tag, node -> ((ListTag) node).set(index, prepared.value(node == tag)));
            prepared.finish();
            return wrap(previous);
        }

        @Override
        public void add(int index, LegacyNbt value) {
            Insert prepared = new Insert(value, store);
            store.change(
                    tag,
                    node -> {
                        ((ListTag) node).add(index, prepared.value(node == tag));
                        return null;
                    });
            prepared.finish();
        }

        @Override
        public boolean addAll(int index, Collection<? extends LegacyNbt> values) {
            Set<Tag> sources = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Insert> prepared =
                    values.stream()
                            .map(value -> new Insert(value, store, sources.add(value.tag)))
                            .toList();
            boolean changed =
                    store.change(
                            tag,
                            node ->
                                    ((ListTag) node)
                                            .addAll(
                                                    index,
                                                    prepared.stream()
                                                            .map(value -> value.value(node == tag))
                                                            .toList()));
            prepared.forEach(Insert::finish);
            return changed;
        }

        @Override
        public LegacyNbt remove(int index) {
            return wrap(store.change(tag, node -> ((ListTag) node).remove(index)));
        }

        @Override
        public void clear() {
            store.change(
                    tag,
                    node -> {
                        ((ListTag) node).clear();
                        return null;
                    });
        }

        @Override
        public ListValue clone() {
            return new ListValue((ListTag) copyTag(), null);
        }

        @Override
        public int compareTo(LegacyNbt other) {
            if (!(other instanceof ListValue value)) return super.compareTo(other);
            int size = Integer.compare(size(), value.size());
            if (size != 0) return size;
            for (int index = 0; index < size(); index++) {
                int child = get(index).compareTo(value.get(index));
                if (child != 0) return child;
            }
            return 0;
        }

        public Compound addEmptyCompound() {
            return addEmptyCompound(size());
        }

        public Compound addEmptyCompound(int index) {
            add(index, new Compound());
            return (Compound) get(index);
        }

        public ListValue addEmptyList() {
            return addEmptyList(size());
        }

        public ListValue addEmptyList(int index) {
            add(index, new ListValue());
            return (ListValue) get(index);
        }

        public Compound getCompound(int index) {
            return getCompound(index, null);
        }

        public Compound getCompoundOrNull(int index) {
            return getCompound(index);
        }

        public Compound getCompound(int index, Compound fallback) {
            return get(index) instanceof Compound value ? value : fallback;
        }

        public Compound getOrCreateCompound(int index) {
            if (!(get(index) instanceof Compound)) set(index, new Compound());
            return (Compound) get(index);
        }

        public ListValue getList(int index) {
            return getList(index, null);
        }

        public ListValue getListOrNull(int index) {
            return getList(index);
        }

        public ListValue getList(int index, ListValue fallback) {
            return get(index) instanceof ListValue value ? value : fallback;
        }

        public ListValue getOrCreateList(int index) {
            if (!(get(index) instanceof ListValue)) set(index, new ListValue());
            return (ListValue) get(index);
        }

        public byte getByte(int index) {
            return getByte(index, (byte) 0);
        }

        public byte getByte(int index, byte fallback) {
            return get(index) instanceof Numeric value ? value.getAsByte() : fallback;
        }

        public Byte getByteOrNull(int index) {
            return get(index) instanceof Numeric value ? value.getAsByte() : null;
        }

        public ByteValue addByte(byte value) {
            return addByte(size(), value);
        }

        public ByteValue addByte(int index, byte value) {
            add(index, ByteValue.valueOf(value));
            return (ByteValue) get(index);
        }

        public ByteValue setByte(int index, byte value) {
            set(index, ByteValue.valueOf(value));
            return (ByteValue) get(index);
        }

        public short getShort(int index) {
            return getShort(index, (short) 0);
        }

        public short getShort(int index, short fallback) {
            return get(index) instanceof Numeric value ? value.getAsShort() : fallback;
        }

        public Short getShortOrNull(int index) {
            return get(index) instanceof Numeric value ? value.getAsShort() : null;
        }

        public ShortValue addShort(short value) {
            return addShort(size(), value);
        }

        public ShortValue addShort(int index, short value) {
            add(index, ShortValue.valueOf(value));
            return (ShortValue) get(index);
        }

        public ShortValue setShort(int index, short value) {
            set(index, ShortValue.valueOf(value));
            return (ShortValue) get(index);
        }

        public int getInt(int index) {
            return getInt(index, 0);
        }

        public int getInt(int index, int fallback) {
            return get(index) instanceof Numeric value ? value.getAsInt() : fallback;
        }

        public Integer getIntOrNull(int index) {
            return get(index) instanceof Numeric value ? value.getAsInt() : null;
        }

        public IntValue addInt(int value) {
            return addInt(size(), value);
        }

        public IntValue addInt(int index, int value) {
            add(index, IntValue.valueOf(value));
            return (IntValue) get(index);
        }

        public IntValue setInt(int index, int value) {
            set(index, IntValue.valueOf(value));
            return (IntValue) get(index);
        }

        public long getLong(int index) {
            return getLong(index, 0);
        }

        public long getLong(int index, long fallback) {
            return get(index) instanceof Numeric value ? value.getAsLong() : fallback;
        }

        public Long getLongOrNull(int index) {
            return get(index) instanceof Numeric value ? value.getAsLong() : null;
        }

        public LongValue addLong(long value) {
            return addLong(size(), value);
        }

        public LongValue addLong(int index, long value) {
            add(index, LongValue.valueOf(value));
            return (LongValue) get(index);
        }

        public LongValue setLong(int index, long value) {
            set(index, LongValue.valueOf(value));
            return (LongValue) get(index);
        }

        public float getFloat(int index) {
            return getFloat(index, 0);
        }

        public float getFloat(int index, float fallback) {
            return get(index) instanceof Numeric value ? value.getAsFloat() : fallback;
        }

        public Float getFloatOrNull(int index) {
            return get(index) instanceof Numeric value ? value.getAsFloat() : null;
        }

        public FloatValue addFloat(float value) {
            return addFloat(size(), value);
        }

        public FloatValue addFloat(int index, float value) {
            add(index, FloatValue.valueOf(value));
            return (FloatValue) get(index);
        }

        public FloatValue setFloat(int index, float value) {
            set(index, FloatValue.valueOf(value));
            return (FloatValue) get(index);
        }

        public double getDouble(int index) {
            return getDouble(index, 0);
        }

        public double getDouble(int index, double fallback) {
            return get(index) instanceof Numeric value ? value.getAsDouble() : fallback;
        }

        public Double getDoubleOrNull(int index) {
            return get(index) instanceof Numeric value ? value.getAsDouble() : null;
        }

        public DoubleValue addDouble(double value) {
            return addDouble(size(), value);
        }

        public DoubleValue addDouble(int index, double value) {
            add(index, DoubleValue.valueOf(value));
            return (DoubleValue) get(index);
        }

        public DoubleValue setDouble(int index, double value) {
            set(index, DoubleValue.valueOf(value));
            return (DoubleValue) get(index);
        }

        public ByteArray addByteArray(byte[] value) {
            return addByteArray(size(), value);
        }

        public ByteArray addByteArray(int index, byte[] value) {
            add(index, new ByteArray(value));
            return (ByteArray) get(index);
        }

        public ByteArray setByteArray(int index, byte[] value) {
            set(index, new ByteArray(value));
            return (ByteArray) get(index);
        }

        public ByteArray addByteArray(List<? extends Number> value) {
            return addByteArray(size(), value);
        }

        public ByteArray addByteArray(int index, List<? extends Number> value) {
            add(index, new ByteArray(value));
            return (ByteArray) get(index);
        }

        public ByteArray setByteArray(int index, List<? extends Number> value) {
            set(index, new ByteArray(value));
            return (ByteArray) get(index);
        }

        public byte[] getByteArray(int index) {
            return getByteArray(index, new byte[0]);
        }

        public byte[] getByteArray(int index, byte[] fallback) {
            return get(index) instanceof ByteArray value ? value.getAsByteArray() : fallback;
        }

        public byte[] getByteArrayOrNull(int index) {
            return getByteArray(index, null);
        }

        public IntArray addIntArray(int[] value) {
            return addIntArray(size(), value);
        }

        public IntArray addIntArray(int index, int[] value) {
            add(index, new IntArray(value));
            return (IntArray) get(index);
        }

        public IntArray setIntArray(int index, int[] value) {
            set(index, new IntArray(value));
            return (IntArray) get(index);
        }

        public IntArray addIntArray(List<? extends Number> value) {
            return addIntArray(size(), value);
        }

        public IntArray addIntArray(int index, List<? extends Number> value) {
            add(index, new IntArray(value));
            return (IntArray) get(index);
        }

        public IntArray setIntArray(int index, List<? extends Number> value) {
            set(index, new IntArray(value));
            return (IntArray) get(index);
        }

        public int[] getIntArray(int index) {
            return getIntArray(index, new int[0]);
        }

        public int[] getIntArray(int index, int[] fallback) {
            return get(index) instanceof IntArray value ? value.getAsIntArray() : fallback;
        }

        public int[] getIntArrayOrNull(int index) {
            return getIntArray(index, null);
        }

        public LongArray addLongArray(long[] value) {
            return addLongArray(size(), value);
        }

        public LongArray addLongArray(int index, long[] value) {
            add(index, new LongArray(value));
            return (LongArray) get(index);
        }

        public LongArray setLongArray(int index, long[] value) {
            set(index, new LongArray(value));
            return (LongArray) get(index);
        }

        public LongArray addLongArray(List<? extends Number> value) {
            return addLongArray(size(), value);
        }

        public LongArray addLongArray(int index, List<? extends Number> value) {
            add(index, new LongArray(value));
            return (LongArray) get(index);
        }

        public LongArray setLongArray(int index, List<? extends Number> value) {
            set(index, new LongArray(value));
            return (LongArray) get(index);
        }

        public long[] getLongArray(int index) {
            return getLongArray(index, new long[0]);
        }

        public long[] getLongArray(int index, long[] fallback) {
            return get(index) instanceof LongArray value ? value.getAsLongArray() : fallback;
        }

        public long[] getLongArrayOrNull(int index) {
            return getLongArray(index, null);
        }

        public String getString(int index) {
            return getString(index, null);
        }

        public String getStringOrNull(int index) {
            return getString(index);
        }

        public String getString(int index, String fallback) {
            LegacyNbt value = get(index);
            return value == null ? fallback : value.getAsString();
        }

        public boolean getBoolean(int index) {
            return getBoolean(index, false);
        }

        public Boolean getBooleanOrNull(int index) {
            return get(index) instanceof Numeric value ? value.getAsByte() != 0 : null;
        }

        public boolean getBoolean(int index, boolean fallback) {
            return get(index) instanceof Numeric value ? value.getAsByte() != 0 : fallback;
        }

        public StringValue addString(String value) {
            return addString(size(), value);
        }

        public StringValue addString(int index, String value) {
            add(index, StringValue.valueOf(value));
            return (StringValue) get(index);
        }

        public StringValue setString(int index, String value) {
            set(index, StringValue.valueOf(value));
            return (StringValue) get(index);
        }

        public ByteValue addBoolean(boolean value) {
            return addBoolean(size(), value);
        }

        public ByteValue addBoolean(int index, boolean value) {
            add(index, ByteValue.valueOf(value));
            return (ByteValue) get(index);
        }

        public ByteValue setBoolean(int index, boolean value) {
            set(index, ByteValue.valueOf(value));
            return (ByteValue) get(index);
        }
    }

    public abstract static class Numeric extends LegacyNbt {
        Numeric(NumericTag value, Store owner) {
            super(value, owner);
        }

        public final byte getAsByte() {
            return ((NumericTag) tag).byteValue();
        }

        public final short getAsShort() {
            return ((NumericTag) tag).shortValue();
        }

        public final int getAsInt() {
            return ((NumericTag) tag).intValue();
        }

        public final long getAsLong() {
            return ((NumericTag) tag).longValue();
        }

        public final float getAsFloat() {
            return ((NumericTag) tag).floatValue();
        }

        public final double getAsDouble() {
            return ((NumericTag) tag).doubleValue();
        }

        public final Number getAsNumber() {
            return ((NumericTag) tag).box();
        }

        @Override
        public String getAsString() {
            return getAsNumber().toString();
        }
    }

    public static final class ByteValue extends Numeric {
        private ByteValue(ByteTag value, Store owner) {
            super(value, owner);
        }

        public static ByteValue valueOf(byte value) {
            return (ByteValue) wrap(ByteTag.valueOf(value));
        }

        public static ByteValue valueOf(boolean value) {
            return valueOf((byte) (value ? 1 : 0));
        }

        public boolean getAsBoolean() {
            return getAsByte() != 0;
        }

        @Override
        public ByteValue clone() {
            return valueOf(getAsByte());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            return other instanceof ByteValue value
                    ? Byte.compare(getAsByte(), value.getAsByte())
                    : super.compareTo(other);
        }
    }

    public static final class ShortValue extends Numeric {
        private ShortValue(ShortTag value, Store owner) {
            super(value, owner);
        }

        public static ShortValue valueOf(short value) {
            return (ShortValue) wrap(ShortTag.valueOf(value));
        }

        @Override
        public ShortValue clone() {
            return valueOf(getAsShort());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            return other instanceof ShortValue value
                    ? Short.compare(getAsShort(), value.getAsShort())
                    : super.compareTo(other);
        }
    }

    public static final class IntValue extends Numeric {
        private IntValue(IntTag value, Store owner) {
            super(value, owner);
        }

        public static IntValue valueOf(int value) {
            return (IntValue) wrap(IntTag.valueOf(value));
        }

        @Override
        public IntValue clone() {
            return valueOf(getAsInt());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            return other instanceof IntValue value
                    ? Integer.compare(getAsInt(), value.getAsInt())
                    : super.compareTo(other);
        }
    }

    public static final class LongValue extends Numeric {
        private LongValue(LongTag value, Store owner) {
            super(value, owner);
        }

        public static LongValue valueOf(long value) {
            return (LongValue) wrap(LongTag.valueOf(value));
        }

        @Override
        public LongValue clone() {
            return valueOf(getAsLong());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            return other instanceof LongValue value
                    ? Long.compare(getAsLong(), value.getAsLong())
                    : super.compareTo(other);
        }
    }

    public static final class FloatValue extends Numeric {
        private FloatValue(FloatTag value, Store owner) {
            super(value, owner);
        }

        public static FloatValue valueOf(float value) {
            return (FloatValue) wrap(FloatTag.valueOf(value));
        }

        @Override
        public FloatValue clone() {
            return valueOf(getAsFloat());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            return other instanceof FloatValue value
                    ? Float.compare(getAsFloat(), value.getAsFloat())
                    : super.compareTo(other);
        }
    }

    public static final class DoubleValue extends Numeric {
        private DoubleValue(DoubleTag value, Store owner) {
            super(value, owner);
        }

        public static DoubleValue valueOf(double value) {
            return (DoubleValue) wrap(DoubleTag.valueOf(value));
        }

        @Override
        public DoubleValue clone() {
            return valueOf(getAsDouble());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            return other instanceof DoubleValue value
                    ? Double.compare(getAsDouble(), value.getAsDouble())
                    : super.compareTo(other);
        }
    }

    public static final class ByteArray extends CollectionValue<Byte> {
        public ByteArray(byte[] value) {
            this(new ByteArrayTag(value.clone()), null);
        }

        public ByteArray(List<? extends Number> value) {
            this(array(value));
        }

        private ByteArray(ByteArrayTag value, Store owner) {
            super(value, owner == null ? new Store(value, null) : owner);
        }

        private static byte[] array(List<? extends Number> values) {
            byte[] result = new byte[values.size()];
            for (int index = 0; index < result.length; index++)
                result[index] = values.get(index) == null ? 0 : values.get(index).byteValue();
            return result;
        }

        public byte[] getAsByteArray() {
            return ((ByteArrayTag) tag).getAsByteArray().clone();
        }

        public byte getByte(int index) {
            return ((ByteArrayTag) tag).getAsByteArray()[index];
        }

        @Override
        public Byte get(int index) {
            return getByte(index);
        }

        @Override
        public int size() {
            return ((ByteArrayTag) tag).size();
        }

        @Override
        public Byte set(int index, Byte value) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public void add(int index, Byte value) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public Byte remove(int index) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public String getAsString() {
            return Arrays.toString(getAsByteArray());
        }

        @Override
        public ByteArray clone() {
            return new ByteArray(getAsByteArray());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            if (!(other instanceof ByteArray value)) return super.compareTo(other);
            int length = Integer.compare(size(), value.size());
            return length != 0 ? length : Arrays.compare(getAsByteArray(), value.getAsByteArray());
        }
    }

    public static final class IntArray extends CollectionValue<Integer> {
        public IntArray(int[] value) {
            this(new IntArrayTag(value.clone()), null);
        }

        public IntArray(List<? extends Number> value) {
            this(array(value));
        }

        private IntArray(IntArrayTag value, Store owner) {
            super(value, owner == null ? new Store(value, null) : owner);
        }

        private static int[] array(List<? extends Number> values) {
            int[] result = new int[values.size()];
            for (int index = 0; index < result.length; index++)
                result[index] = values.get(index) == null ? 0 : values.get(index).intValue();
            return result;
        }

        public int[] getAsIntArray() {
            return ((IntArrayTag) tag).getAsIntArray().clone();
        }

        public int getInt(int index) {
            return ((IntArrayTag) tag).getAsIntArray()[index];
        }

        @Override
        public Integer get(int index) {
            return getInt(index);
        }

        @Override
        public int size() {
            return ((IntArrayTag) tag).size();
        }

        @Override
        public Integer set(int index, Integer value) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public void add(int index, Integer value) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public Integer remove(int index) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public String getAsString() {
            return Arrays.toString(getAsIntArray());
        }

        @Override
        public IntArray clone() {
            return new IntArray(getAsIntArray());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            if (!(other instanceof IntArray value)) return super.compareTo(other);
            int length = Integer.compare(size(), value.size());
            return length != 0 ? length : Arrays.compare(getAsIntArray(), value.getAsIntArray());
        }
    }

    public static final class LongArray extends CollectionValue<Long> {
        public LongArray(long[] value) {
            this(new LongArrayTag(value.clone()), null);
        }

        public LongArray(List<? extends Number> value) {
            this(array(value));
        }

        private LongArray(LongArrayTag value, Store owner) {
            super(value, owner == null ? new Store(value, null) : owner);
        }

        private static long[] array(List<? extends Number> values) {
            long[] result = new long[values.size()];
            for (int index = 0; index < result.length; index++)
                result[index] = values.get(index) == null ? 0 : values.get(index).longValue();
            return result;
        }

        public long[] getAsLongArray() {
            return ((LongArrayTag) tag).getAsLongArray().clone();
        }

        public long getLong(int index) {
            return ((LongArrayTag) tag).getAsLongArray()[index];
        }

        @Override
        public Long get(int index) {
            return getLong(index);
        }

        @Override
        public int size() {
            return ((LongArrayTag) tag).size();
        }

        @Override
        public Long set(int index, Long value) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public void add(int index, Long value) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public Long remove(int index) {
            throw new UnsupportedOperationException(
                    "NBT arrays are immutable; replace the array tag");
        }

        @Override
        public String getAsString() {
            return Arrays.toString(getAsLongArray());
        }

        @Override
        public LongArray clone() {
            return new LongArray(getAsLongArray());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            if (!(other instanceof LongArray value)) return super.compareTo(other);
            int length = Integer.compare(size(), value.size());
            return length != 0 ? length : Arrays.compare(getAsLongArray(), value.getAsLongArray());
        }
    }

    public static final class StringValue extends LegacyNbt {
        private StringValue(StringTag value, Store owner) {
            super(value, owner);
        }

        public static StringValue valueOf(String value) {
            return (StringValue) wrap(StringTag.valueOf(value));
        }

        @Override
        public String getAsString() {
            return ((StringTag) tag).value();
        }

        @Override
        public StringValue clone() {
            return valueOf(getAsString());
        }
    }

    public static final class End extends LegacyNbt {
        public static final End INSTANCE = new End();

        private End() {
            super(EndTag.INSTANCE, new Store(EndTag.INSTANCE, null));
        }

        public static End instance() {
            return INSTANCE;
        }

        @Override
        public End clone() {
            return this;
        }
    }

    private static List<String> split(String text, char separator, char escape) {
        List<String> result = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == separator && !escaped) {
                result.add(part.toString());
                part.setLength(0);
            } else {
                if (current != escape && current != separator && escaped) part.append(escape);
                if (current != escape || escaped) part.append(current);
            }
            escaped = current == escape && !escaped;
        }
        if (escaped) part.append(escape);
        result.add(part.toString());
        return result;
    }

    public static final class Type {
        private Type() {}

        public static final byte TAG_END = 0,
                TAG_BYTE = 1,
                TAG_SHORT = 2,
                TAG_INT = 3,
                TAG_LONG = 4,
                TAG_FLOAT = 5,
                TAG_DOUBLE = 6,
                TAG_BYTE_ARRAY = 7,
                TAG_STRING = 8,
                TAG_LIST = 9,
                TAG_COMPOUND = 10,
                TAG_INT_ARRAY = 11,
                TAG_LONG_ARRAY = 12,
                TAG_ANY_NUMBER = 99;
    }

    public static final class Unsafe {
        private Unsafe() {}

        public static Tag getDelegate(LegacyNbt value) {
            return value.copyTag();
        }

        public static LegacyNbt fromNms(Object value) {
            return value == null ? null : wrap((Tag) value);
        }
    }
}

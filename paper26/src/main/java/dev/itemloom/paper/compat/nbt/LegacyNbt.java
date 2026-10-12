package dev.itemloom.paper.compat.nbt;

import dev.itemloom.paper.compat.script.EscapedPath;
import dev.itemloom.paper.nms.NmsItems;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.nbt.*;
import org.bukkit.inventory.ItemStack;

/** Script values own a tree; live roots commit a candidate before publishing local changes. */
public abstract class LegacyNbt implements Comparable<LegacyNbt>, Cloneable {
    private final byte type;
    private final Tag scalar;
    private LegacyNbt parent;

    private LegacyNbt(int type, Tag scalar) {
        this.type = (byte) type;
        this.scalar = scalar;
    }

    public final byte getId() {
        return type;
    }

    public final Tag getHandle() {
        return copyTag();
    }

    public Tag copyTag() {
        return scalar.copy();
    }

    public String getAsString() {
        return copyTag().toString();
    }

    public final Object toValue() {
        if (this instanceof Numeric numeric) return numeric.getAsNumber();
        if (this instanceof StringValue string) return string.getAsString();
        if (this instanceof Compound compound) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : compound.copyTag().keySet())
                result.put(key, compound.get(key).toValue());
            return result;
        }
        if (this instanceof CollectionValue<?> collection) {
            List<Object> result = new ArrayList<>();
            for (Object value : collection)
                result.add(value instanceof LegacyNbt nbt ? nbt.toValue() : value);
            return result;
        }
        return getAsString();
    }

    public static Object toValue(Object value) {
        if (value instanceof LegacyNbt nbt) return nbt.toValue();
        if (value instanceof Tag tag) return wrap(tag).toValue();
        throw new IllegalArgumentException("Expected an NBT value");
    }

    @Override
    public LegacyNbt clone() {
        return wrap(copyTag());
    }

    @Override
    public final String toString() {
        return copyTag().toString();
    }

    @Override
    public final int hashCode() {
        return copyTag().hashCode();
    }

    @Override
    public final boolean equals(Object value) {
        return value instanceof LegacyNbt other && copyTag().equals(other.copyTag());
    }

    @Override
    public int compareTo(LegacyNbt other) {
        int types = Byte.compare(type, other.type);
        return types != 0 ? types : getAsString().compareTo(other.getAsString());
    }

    public static LegacyNbt of(Object value) {
        if (value instanceof LegacyNbt nbt) return nbt.clone();
        if (value instanceof Map<?, ?> map) {
            Compound result = new Compound();
            for (var entry : map.entrySet())
                result.put(Objects.requireNonNull(entry.getKey()).toString(), of(entry.getValue()));
            return result;
        }
        if (value instanceof Collection<?> collection) {
            ListValue result = new ListValue();
            for (Object element : collection) result.add(of(element));
            return result;
        }
        return wrap(NmsItems.tag(value));
    }

    public static LegacyNbt wrap(Tag value) {
        if (value == null) return null;
        return switch (value.getId()) {
            case 0 -> End.INSTANCE;
            case 1 -> ByteValue.valueOf(((NumericTag) value).byteValue());
            case 2 -> ShortValue.valueOf(((NumericTag) value).shortValue());
            case 3 -> IntValue.valueOf(((NumericTag) value).intValue());
            case 4 -> LongValue.valueOf(((NumericTag) value).longValue());
            case 5 -> FloatValue.valueOf(((NumericTag) value).floatValue());
            case 6 -> DoubleValue.valueOf(((NumericTag) value).doubleValue());
            case 7 -> new ByteArray(((ByteArrayTag) value).getAsByteArray());
            case 8 -> StringValue.valueOf(value.asString().orElseThrow());
            case 9 -> new ListValue((ListTag) value);
            case 10 -> new Compound((CompoundTag) value);
            case 11 -> new IntArray(((IntArrayTag) value).getAsIntArray());
            case 12 -> new LongArray(((LongArrayTag) value).getAsLongArray());
            default -> throw new IllegalArgumentException("Unsupported NBT type: " + value.getId());
        };
    }

    private boolean mutable() {
        return this instanceof Compound || this instanceof ListValue;
    }

    final LegacyNbt incoming(LegacyNbt value, Set<LegacyNbt> selected) {
        Objects.requireNonNull(value, "value");
        if (!value.mutable()) return value;
        boolean copy =
                value.parent != null
                        || value instanceof Compound compound && compound.binding != null;
        for (LegacyNbt ancestor = this; ancestor != null; ancestor = ancestor.parent)
            copy |= ancestor == value;
        if (!selected.add(value)) copy = true;
        return copy ? value.clone() : value;
    }

    private static Set<LegacyNbt> identities() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    final void replaceChildren(Collection<LegacyNbt> before, Collection<LegacyNbt> after) {
        Set<LegacyNbt> retained = identities();
        retained.addAll(after);
        for (LegacyNbt child : before) {
            if (child.mutable() && child.parent == this && !retained.contains(child))
                child.parent = null;
        }
        for (LegacyNbt child : after) if (child.mutable()) child.parent = this;
    }

    final void change(Tag desired, Runnable publish) {
        LegacyNbt cursor = this;
        Tag candidate = desired;
        while (cursor.parent != null) {
            LegacyNbt owner = cursor.parent;
            if (owner instanceof Compound compound) {
                CompoundTag replacement = compound.copyTag();
                replacement.put(compound.keyOf(cursor), candidate);
                candidate = replacement;
            } else {
                ListValue list = (ListValue) owner;
                ListTag replacement = list.copyTag();
                replacement.set(list.indexOfIdentity(cursor), candidate);
                candidate = replacement;
            }
            cursor = owner;
        }
        if (cursor instanceof Compound root && root.binding != null && root.localEdits == 0) {
            root.binding.commit(root.copyTag(), (CompoundTag) candidate);
        }
        publish.run();
    }

    final void atomic(Consumer<Compound> preview, Consumer<Compound> publish) {
        Compound current = (Compound) this;
        Compound candidate = current.clone();
        preview.accept(candidate);
        change(
                candidate.copyTag(),
                () -> {
                    LegacyNbt cursor = current;
                    while (cursor.parent != null) cursor = cursor.parent;
                    Compound root = cursor instanceof Compound compound ? compound : null;
                    if (root != null) root.localEdits++;
                    try {
                        publish.accept(current);
                    } finally {
                        if (root != null) root.localEdits--;
                    }
                });
    }

    public static final class Compound extends LegacyNbt implements Map<String, LegacyNbt> {
        private final Map<String, LegacyNbt> entries = new LinkedHashMap<>();
        private LegacyNbtItemStack binding;
        private int localEdits;

        public Compound() {
            super(Type.TAG_COMPOUND, null);
        }

        public Compound(CompoundTag tag) {
            this();
            for (String key : tag.keySet()) entries.put(key, wrap(tag.get(key)));
            replaceChildren(List.of(), entries.values());
        }

        static Compound live(CompoundTag tag, LegacyNbtItemStack binding) {
            Compound result = new Compound(tag);
            result.binding = binding;
            return result;
        }

        public static Compound createUnsafe(Object value) {
            return value instanceof CompoundTag tag ? new Compound(tag) : null;
        }

        @Override
        public CompoundTag copyTag() {
            CompoundTag result = new CompoundTag();
            entries.forEach((key, value) -> result.put(key, value.copyTag()));
            return result;
        }

        @Override
        public Compound clone() {
            return new Compound(copyTag());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            if (types != 0) return types;
            Compound right = (Compound) other;
            int lengths = Integer.compare(size(), right.size());
            if (lengths != 0) return lengths;
            var leftEntries = copyTag().entrySet().iterator();
            var rightEntries = right.copyTag().entrySet().iterator();
            while (leftEntries.hasNext()) {
                var a = leftEntries.next();
                var b = rightEntries.next();
                int keys = a.getKey().compareTo(b.getKey());
                if (keys != 0) return keys;
                int values = wrap(a.getValue()).compareTo(wrap(b.getValue()));
                if (values != 0) return values;
            }
            return 0;
        }

        public void saveTo(ItemStack item) {
            if (binding != null && binding.sameTarget(item)) binding.commit(copyTag(), copyTag());
            else new LegacyNbtItemStack(item).setTag(this);
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String text && containsKey(text);
        }

        public boolean containsKey(String key) {
            return entries.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return entries.containsValue(value);
        }

        @Override
        public LegacyNbt get(Object key) {
            return key instanceof String text ? get(text) : null;
        }

        public LegacyNbt get(String key) {
            return entries.get(key);
        }

        @Override
        public LegacyNbt put(String key, LegacyNbt value) {
            Objects.requireNonNull(key, "key");
            LegacyNbt inserted = incoming(value, identities());
            Map<String, LegacyNbt> next = new LinkedHashMap<>(entries);
            LegacyNbt prior = next.put(key, inserted);
            update(next);
            return prior == null ? null : prior.clone();
        }

        public void set(String key, LegacyNbt value) {
            put(key, value);
        }

        @Override
        public LegacyNbt remove(Object key) {
            return key instanceof String text ? remove(text) : null;
        }

        public LegacyNbt remove(String key) {
            if (!entries.containsKey(key)) return null;
            Map<String, LegacyNbt> next = new LinkedHashMap<>(entries);
            LegacyNbt prior = next.remove(key);
            update(next);
            return prior.clone();
        }

        public void delete(String key) {
            remove(key);
        }

        @Override
        public void putAll(Map<? extends String, ? extends LegacyNbt> values) {
            Set<LegacyNbt> selected = identities();
            Map<String, LegacyNbt> next = new LinkedHashMap<>(entries);
            for (var entry : values.entrySet())
                next.put(
                        Objects.requireNonNull(entry.getKey(), "key"),
                        incoming(entry.getValue(), selected));
            update(next);
        }

        @Override
        public void clear() {
            update(Map.of());
        }

        private void update(Map<String, LegacyNbt> next) {
            CompoundTag candidate = new CompoundTag();
            next.forEach((key, value) -> candidate.put(key, value.copyTag()));
            change(
                    candidate,
                    () -> {
                        replaceChildren(entries.values(), next.values());
                        entries.clear();
                        entries.putAll(next);
                    });
        }

        private String keyOf(LegacyNbt child) {
            for (var entry : entries.entrySet())
                if (entry.getValue() == child) return entry.getKey();
            throw new IllegalStateException("Detached NBT child");
        }

        private Map<String, LegacyNbt> mapView() {
            return new AbstractMap<>() {
                @Override
                public Set<Entry<String, LegacyNbt>> entrySet() {
                    return Compound.this.entrySet();
                }

                @Override
                public LegacyNbt put(String key, LegacyNbt value) {
                    return Compound.this.put(key, value);
                }
            };
        }

        @Override
        public Set<String> keySet() {
            return mapView().keySet();
        }

        @Override
        public Collection<LegacyNbt> values() {
            return mapView().values();
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
                    Iterator<String> keys = new ArrayList<>(copyTag().keySet()).iterator();
                    return new Iterator<>() {
                        private String last;
                        private boolean removable;

                        @Override
                        public boolean hasNext() {
                            return keys.hasNext();
                        }

                        @Override
                        public Entry<String, LegacyNbt> next() {
                            String key = keys.next();
                            last = key;
                            removable = true;
                            return new Entry<>() {
                                @Override
                                public String getKey() {
                                    return key;
                                }

                                @Override
                                public LegacyNbt getValue() {
                                    return get(key);
                                }

                                @Override
                                public LegacyNbt setValue(LegacyNbt value) {
                                    return put(key, value);
                                }

                                @Override
                                public boolean equals(Object other) {
                                    return other instanceof Entry<?, ?> entry
                                            && Objects.equals(key, entry.getKey())
                                            && Objects.equals(getValue(), entry.getValue());
                                }

                                @Override
                                public int hashCode() {
                                    return Objects.hashCode(key) ^ Objects.hashCode(getValue());
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            if (!removable) throw new IllegalStateException();
                            Compound.this.remove(last);
                            removable = false;
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
            return value != null
                    && (type == Type.TAG_ANY_NUMBER
                            ? value instanceof Numeric
                            : value.getId() == type);
        }

        public LegacyNbt getDeep(String path) {
            return getDeep(path, '.', '\\');
        }

        public LegacyNbt getDeep(String path, char delimiter, char escape) {
            LegacyNbt value = this;
            for (String key : EscapedPath.split(path, delimiter, escape)) {
                if (!(value instanceof Compound compound)) return null;
                value = compound.get(key);
            }
            return value;
        }

        public void putDeep(String path, LegacyNbt value) {
            putDeep(path, value, false);
        }

        public void putDeep(String path, LegacyNbt value, boolean force) {
            putDeep(path, value, force, '.', '\\');
        }

        public void putDeep(
                String path, LegacyNbt value, boolean force, char delimiter, char escape) {
            Objects.requireNonNull(value, "value");
            List<String> keys = EscapedPath.split(path, delimiter, escape);
            Compound cursor = this;
            for (int index = 0; index < keys.size() - 1; index++) {
                LegacyNbt child = cursor.get(keys.get(index));
                if (child != null && !(child instanceof Compound) && !force) return;
                if (!(child instanceof Compound)) {
                    final int from = index;
                    LegacyNbt inserted = cursor.incoming(value, identities());
                    cursor.atomic(
                            candidate -> candidate.writePath(keys, from, inserted.clone()),
                            target -> target.writePath(keys, from, inserted));
                    return;
                }
                cursor = (Compound) child;
            }
            cursor.put(keys.getLast(), value);
        }

        private void writePath(List<String> keys, int from, LegacyNbt value) {
            Compound cursor = this;
            for (int index = from; index < keys.size() - 1; index++)
                cursor = cursor.getOrCreateCompound(keys.get(index));
            cursor.put(keys.getLast(), value);
        }

        public void deleteDeep(String path) {
            List<String> keys = EscapedPath.split(path, '.', '\\');
            Compound cursor = this;
            for (int index = 0; index < keys.size() - 1; index++) {
                if (!(cursor.get(keys.get(index)) instanceof Compound next)) return;
                cursor = next;
            }
            cursor.remove(keys.getLast());
        }

        public Compound coverWith(Compound source) {
            Compound detached = Objects.requireNonNull(source).clone();
            atomic(candidate -> candidate.overlay(detached), target -> target.overlay(detached));
            return this;
        }

        private void overlay(Compound source) {
            for (var entry : source.entries.entrySet()) {
                if (get(entry.getKey()) instanceof Compound child
                        && entry.getValue() instanceof Compound added) child.overlay(added);
                else put(entry.getKey(), entry.getValue().clone());
            }
        }

        public byte getByte(String key) {
            return getByte(key, (byte) 0);
        }

        public byte getByte(String key, byte fallback) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsByte() : fallback;
        }

        public Byte getByteOrNull(String key) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsByte() : null;
        }

        public short getShort(String key) {
            return getShort(key, (short) 0);
        }

        public short getShort(String key, short fallback) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsShort() : fallback;
        }

        public Short getShortOrNull(String key) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsShort() : null;
        }

        public int getInt(String key) {
            return getInt(key, (int) 0);
        }

        public int getInt(String key, int fallback) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsInt() : fallback;
        }

        public Integer getIntOrNull(String key) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsInt() : null;
        }

        public long getLong(String key) {
            return getLong(key, (long) 0);
        }

        public long getLong(String key, long fallback) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsLong() : fallback;
        }

        public Long getLongOrNull(String key) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsLong() : null;
        }

        public float getFloat(String key) {
            return getFloat(key, (float) 0);
        }

        public float getFloat(String key, float fallback) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsFloat() : fallback;
        }

        public Float getFloatOrNull(String key) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsFloat() : null;
        }

        public double getDouble(String key) {
            return getDouble(key, (double) 0);
        }

        public double getDouble(String key, double fallback) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsDouble() : fallback;
        }

        public Double getDoubleOrNull(String key) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsDouble() : null;
        }

        public byte[] getByteArray(String key) {
            return getByteArray(key, new byte[0]);
        }

        public byte[] getByteArray(String key, byte[] fallback) {
            LegacyNbt value = get(key);
            return value instanceof ByteArray array ? array.getAsByteArray() : fallback;
        }

        public byte[] getByteArrayOrNull(String key) {
            return getByteArray(key, null);
        }

        public int[] getIntArray(String key) {
            return getIntArray(key, new int[0]);
        }

        public int[] getIntArray(String key, int[] fallback) {
            LegacyNbt value = get(key);
            return value instanceof IntArray array ? array.getAsIntArray() : fallback;
        }

        public int[] getIntArrayOrNull(String key) {
            return getIntArray(key, null);
        }

        public long[] getLongArray(String key) {
            return getLongArray(key, new long[0]);
        }

        public long[] getLongArray(String key, long[] fallback) {
            LegacyNbt value = get(key);
            return value instanceof LongArray array ? array.getAsLongArray() : fallback;
        }

        public long[] getLongArrayOrNull(String key) {
            return getLongArray(key, null);
        }

        public Compound getCompound(String key) {
            return getCompound(key, null);
        }

        public Compound getCompoundOrNull(String key) {
            return getCompound(key, null);
        }

        public Compound getCompound(String key, Compound fallback) {
            LegacyNbt value = get(key);
            return value instanceof Compound typed ? typed : fallback;
        }

        public ListValue getList(String key) {
            return getList(key, null);
        }

        public ListValue getListOrNull(String key) {
            return getList(key, null);
        }

        public ListValue getList(String key, ListValue fallback) {
            LegacyNbt value = get(key);
            return value instanceof ListValue typed ? typed : fallback;
        }

        public String getString(String key) {
            return getString(key, null);
        }

        public String getStringOrNull(String key) {
            return getString(key, null);
        }

        public String getString(String key, String fallback) {
            LegacyNbt value = get(key);
            return value == null ? fallback : value.getAsString();
        }

        public boolean getBoolean(String key) {
            return getBoolean(key, false);
        }

        public Boolean getBooleanOrNull(String key) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsByte() != 0 : null;
        }

        public boolean getBoolean(String key, boolean fallback) {
            LegacyNbt value = get(key);
            return value instanceof Numeric n ? n.getAsByte() != 0 : fallback;
        }

        public byte getDeepByte(String key) {
            return getDeepByte(key, (byte) 0);
        }

        public byte getDeepByte(String key, byte fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsByte() : fallback;
        }

        public Byte getDeepByteOrNull(String key) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsByte() : null;
        }

        public short getDeepShort(String key) {
            return getDeepShort(key, (short) 0);
        }

        public short getDeepShort(String key, short fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsShort() : fallback;
        }

        public Short getDeepShortOrNull(String key) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsShort() : null;
        }

        public int getDeepInt(String key) {
            return getDeepInt(key, (int) 0);
        }

        public int getDeepInt(String key, int fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsInt() : fallback;
        }

        public Integer getDeepIntOrNull(String key) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsInt() : null;
        }

        public long getDeepLong(String key) {
            return getDeepLong(key, (long) 0);
        }

        public long getDeepLong(String key, long fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsLong() : fallback;
        }

        public Long getDeepLongOrNull(String key) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsLong() : null;
        }

        public float getDeepFloat(String key) {
            return getDeepFloat(key, (float) 0);
        }

        public float getDeepFloat(String key, float fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsFloat() : fallback;
        }

        public Float getDeepFloatOrNull(String key) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsFloat() : null;
        }

        public double getDeepDouble(String key) {
            return getDeepDouble(key, (double) 0);
        }

        public double getDeepDouble(String key, double fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsDouble() : fallback;
        }

        public Double getDeepDoubleOrNull(String key) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsDouble() : null;
        }

        public byte[] getDeepByteArray(String key) {
            return getDeepByteArray(key, new byte[0]);
        }

        public byte[] getDeepByteArray(String key, byte[] fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof ByteArray array ? array.getAsByteArray() : fallback;
        }

        public byte[] getDeepByteArrayOrNull(String key) {
            return getDeepByteArray(key, null);
        }

        public int[] getDeepIntArray(String key) {
            return getDeepIntArray(key, new int[0]);
        }

        public int[] getDeepIntArray(String key, int[] fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof IntArray array ? array.getAsIntArray() : fallback;
        }

        public int[] getDeepIntArrayOrNull(String key) {
            return getDeepIntArray(key, null);
        }

        public long[] getDeepLongArray(String key) {
            return getDeepLongArray(key, new long[0]);
        }

        public long[] getDeepLongArray(String key, long[] fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof LongArray array ? array.getAsLongArray() : fallback;
        }

        public long[] getDeepLongArrayOrNull(String key) {
            return getDeepLongArray(key, null);
        }

        public Compound getDeepCompound(String key) {
            return getDeepCompound(key, null);
        }

        public Compound getDeepCompoundOrNull(String key) {
            return getDeepCompound(key, null);
        }

        public Compound getDeepCompound(String key, Compound fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Compound typed ? typed : fallback;
        }

        public ListValue getDeepList(String key) {
            return getDeepList(key, null);
        }

        public ListValue getDeepListOrNull(String key) {
            return getDeepList(key, null);
        }

        public ListValue getDeepList(String key, ListValue fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof ListValue typed ? typed : fallback;
        }

        public String getDeepString(String key) {
            return getDeepString(key, null);
        }

        public String getDeepStringOrNull(String key) {
            return getDeepString(key, null);
        }

        public String getDeepString(String key, String fallback) {
            LegacyNbt value = getDeep(key);
            return value == null ? fallback : value.getAsString();
        }

        public boolean getDeepBoolean(String key) {
            return getDeepBoolean(key, false);
        }

        public Boolean getDeepBooleanOrNull(String key) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsByte() != 0 : null;
        }

        public boolean getDeepBoolean(String key, boolean fallback) {
            LegacyNbt value = getDeep(key);
            return value instanceof Numeric n ? n.getAsByte() != 0 : fallback;
        }

        public Compound getOrCreateCompound(String key) {
            Compound value = getCompound(key);
            if (value != null) return value;
            value = new Compound();
            put(key, value);
            return value;
        }

        public ListValue getOrCreateList(String key) {
            ListValue value = getList(key);
            if (value != null) return value;
            value = new ListValue();
            put(key, value);
            return value;
        }

        public void putByte(String key, byte value) {
            put(key, ByteValue.valueOf(value));
        }

        public void putDeepByte(String key, byte value) {
            putDeep(key, ByteValue.valueOf(value));
        }

        public void putDeepByte(String key, byte value, boolean force) {
            putDeep(key, ByteValue.valueOf(value), force);
        }

        public void putShort(String key, short value) {
            put(key, ShortValue.valueOf(value));
        }

        public void putDeepShort(String key, short value) {
            putDeep(key, ShortValue.valueOf(value));
        }

        public void putDeepShort(String key, short value, boolean force) {
            putDeep(key, ShortValue.valueOf(value), force);
        }

        public void putInt(String key, int value) {
            put(key, IntValue.valueOf(value));
        }

        public void putDeepInt(String key, int value) {
            putDeep(key, IntValue.valueOf(value));
        }

        public void putDeepInt(String key, int value, boolean force) {
            putDeep(key, IntValue.valueOf(value), force);
        }

        public void putLong(String key, long value) {
            put(key, LongValue.valueOf(value));
        }

        public void putDeepLong(String key, long value) {
            putDeep(key, LongValue.valueOf(value));
        }

        public void putDeepLong(String key, long value, boolean force) {
            putDeep(key, LongValue.valueOf(value), force);
        }

        public void putFloat(String key, float value) {
            put(key, FloatValue.valueOf(value));
        }

        public void putDeepFloat(String key, float value) {
            putDeep(key, FloatValue.valueOf(value));
        }

        public void putDeepFloat(String key, float value, boolean force) {
            putDeep(key, FloatValue.valueOf(value), force);
        }

        public void putDouble(String key, double value) {
            put(key, DoubleValue.valueOf(value));
        }

        public void putDeepDouble(String key, double value) {
            putDeep(key, DoubleValue.valueOf(value));
        }

        public void putDeepDouble(String key, double value, boolean force) {
            putDeep(key, DoubleValue.valueOf(value), force);
        }

        public void putString(String key, String value) {
            put(key, StringValue.valueOf(value));
        }

        public void putDeepString(String key, String value) {
            putDeep(key, StringValue.valueOf(value));
        }

        public void putDeepString(String key, String value, boolean force) {
            putDeep(key, StringValue.valueOf(value), force);
        }

        public void putBoolean(String key, boolean value) {
            put(key, ByteValue.valueOf(value));
        }

        public void putDeepBoolean(String key, boolean value) {
            putDeep(key, ByteValue.valueOf(value));
        }

        public void putDeepBoolean(String key, boolean value, boolean force) {
            putDeep(key, ByteValue.valueOf(value), force);
        }

        public void putByteArray(String key, byte[] value) {
            put(key, new ByteArray(value));
        }

        public void putDeepByteArray(String key, byte[] value) {
            putDeep(key, new ByteArray(value));
        }

        public void putDeepByteArray(String key, byte[] value, boolean force) {
            putDeep(key, new ByteArray(value), force);
        }

        public void putIntArray(String key, int[] value) {
            put(key, new IntArray(value));
        }

        public void putDeepIntArray(String key, int[] value) {
            putDeep(key, new IntArray(value));
        }

        public void putDeepIntArray(String key, int[] value, boolean force) {
            putDeep(key, new IntArray(value), force);
        }

        public void putLongArray(String key, long[] value) {
            put(key, new LongArray(value));
        }

        public void putDeepLongArray(String key, long[] value) {
            putDeep(key, new LongArray(value));
        }

        public void putDeepLongArray(String key, long[] value, boolean force) {
            putDeep(key, new LongArray(value), force);
        }

        public void putByteArray(String key, List<? extends Number> value) {
            put(key, new ByteArray(value));
        }

        public void putDeepByteArray(String key, List<? extends Number> value) {
            putDeep(key, new ByteArray(value));
        }

        public void putDeepByteArray(String key, List<? extends Number> value, boolean force) {
            putDeep(key, new ByteArray(value), force);
        }

        public void putIntArray(String key, List<? extends Number> value) {
            put(key, new IntArray(value));
        }

        public void putDeepIntArray(String key, List<? extends Number> value) {
            putDeep(key, new IntArray(value));
        }

        public void putDeepIntArray(String key, List<? extends Number> value, boolean force) {
            putDeep(key, new IntArray(value), force);
        }

        public void putLongArray(String key, List<? extends Number> value) {
            put(key, new LongArray(value));
        }

        public void putDeepLongArray(String key, List<? extends Number> value) {
            putDeep(key, new LongArray(value));
        }

        public void putDeepLongArray(String key, List<? extends Number> value, boolean force) {
            putDeep(key, new LongArray(value), force);
        }

        public ListValue getList(String key, int type) {
            ListValue value = getList(key);
            return value == null || (!value.isEmpty() && type != 0 && value.get(0).getId() != type)
                    ? new ListValue()
                    : value;
        }

        public void putUUID(String key, UUID value) {
            long most = value.getMostSignificantBits(), least = value.getLeastSignificantBits();
            putIntArray(
                    key,
                    new int[] {(int) (most >>> 32), (int) most, (int) (least >>> 32), (int) least});
        }

        public UUID getUUID(String key) {
            return getUUID(key, null);
        }

        public UUID getUUIDOrNull(String key) {
            return getUUID(key, null);
        }

        public UUID getUUID(String key, UUID fallback) {
            return uuid(get(key), get(key + "Most"), get(key + "Least"), fallback);
        }

        public UUID getDeepUUID(String key) {
            return getDeepUUID(key, null);
        }

        public UUID getDeepUUIDOrNull(String key) {
            return getDeepUUID(key, null);
        }

        public UUID getDeepUUID(String key, UUID fallback) {
            return uuid(getDeep(key), getDeep(key + "Most"), getDeep(key + "Least"), fallback);
        }

        private static UUID uuid(LegacyNbt value, LegacyNbt most, LegacyNbt least, UUID fallback) {
            if (value instanceof IntArray array) {
                int[] words = array.getAsIntArray();
                return words.length == 4
                        ? new UUID(
                                ((long) words[0] << 32) | (words[1] & 0xffffffffL),
                                ((long) words[2] << 32) | (words[3] & 0xffffffffL))
                        : fallback;
            }
            return most instanceof Numeric high && least instanceof Numeric low
                    ? new UUID(high.getAsLong(), low.getAsLong())
                    : fallback;
        }

        public static final class Unsafe {
            private Unsafe() {}

            public static Compound of(Object value) {
                return new Compound((CompoundTag) value);
            }
        }
    }

    public abstract static class CollectionValue<E> extends LegacyNbt implements List<E> {
        private CollectionValue(int type, Tag scalar) {
            super(type, scalar);
        }

        public abstract E get(int index);

        public abstract int size();

        public abstract E set(int index, E value);

        public abstract void add(int index, E value);

        public abstract E remove(int index);

        private List<E> view() {
            return new AbstractList<>() {
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
                public boolean addAll(Collection<? extends E> values) {
                    return CollectionValue.this.addAll(values);
                }

                @Override
                public boolean addAll(int index, Collection<? extends E> values) {
                    return CollectionValue.this.addAll(index, values);
                }

                @Override
                public E remove(int index) {
                    return CollectionValue.this.remove(index);
                }
            };
        }

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
            return indexOf(value) >= 0;
        }

        @Override
        public Iterator<E> iterator() {
            return view().iterator();
        }

        @Override
        public ListIterator<E> listIterator() {
            return listIterator(0);
        }

        @Override
        public ListIterator<E> listIterator(int index) {
            return view().listIterator(index);
        }

        @Override
        public List<E> subList(int start, int end) {
            return view().subList(start, end);
        }

        @Override
        public Object[] toArray() {
            return view().toArray();
        }

        @Override
        public <T> T[] toArray(T[] target) {
            return view().toArray(target);
        }

        @Override
        public boolean remove(Object value) {
            int index = indexOf(value);
            if (index < 0) return false;
            remove(index);
            return true;
        }

        @Override
        public boolean containsAll(Collection<?> values) {
            return view().containsAll(values);
        }

        @Override
        public boolean addAll(Collection<? extends E> values) {
            return addAll(size(), values);
        }

        @Override
        public boolean addAll(int index, Collection<? extends E> values) {
            Objects.checkIndex(index, size() + 1);
            boolean changed = false;
            for (E value : values) {
                add(index++, value);
                changed = true;
            }
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> values) {
            return view().removeAll(values);
        }

        @Override
        public boolean retainAll(Collection<?> values) {
            return view().retainAll(values);
        }

        @Override
        public void clear() {
            view().clear();
        }

        @Override
        public int indexOf(Object value) {
            return view().indexOf(value);
        }

        @Override
        public int lastIndexOf(Object value) {
            return view().lastIndexOf(value);
        }
    }

    public static final class ListValue extends CollectionValue<LegacyNbt> {
        private final List<LegacyNbt> elements = new ArrayList<>();

        public ListValue() {
            super(Type.TAG_LIST, null);
        }

        private ListValue(ListTag tag) {
            this();
            for (Tag value : tag) elements.add(wrap(value));
            replaceChildren(List.of(), elements);
        }

        @Override
        public ListTag copyTag() {
            ListTag result = new ListTag();
            for (LegacyNbt value : elements) result.add(value.copyTag());
            return result;
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public LegacyNbt get(int index) {
            return elements.get(index);
        }

        @Override
        public LegacyNbt set(int index, LegacyNbt value) {
            List<LegacyNbt> next = new ArrayList<>(elements);
            LegacyNbt before = next.set(index, incoming(value, identities()));
            update(next);
            return before.clone();
        }

        @Override
        public void add(int index, LegacyNbt value) {
            addAll(index, List.of(value));
        }

        @Override
        public boolean addAll(int index, Collection<? extends LegacyNbt> values) {
            Objects.checkIndex(index, size() + 1);
            Set<LegacyNbt> selected = identities();
            List<LegacyNbt> additions = new ArrayList<>();
            for (LegacyNbt value : values) additions.add(incoming(value, selected));
            if (additions.isEmpty()) return false;
            List<LegacyNbt> next = new ArrayList<>(elements);
            next.addAll(index, additions);
            update(next);
            return true;
        }

        @Override
        public LegacyNbt remove(int index) {
            List<LegacyNbt> next = new ArrayList<>(elements);
            LegacyNbt before = next.remove(index);
            update(next);
            return before.clone();
        }

        @Override
        public void clear() {
            update(List.of());
        }

        private void update(List<LegacyNbt> next) {
            ListTag candidate = new ListTag();
            for (LegacyNbt value : next) candidate.add(value.copyTag());
            change(
                    candidate,
                    () -> {
                        replaceChildren(elements, next);
                        elements.clear();
                        elements.addAll(next);
                    });
        }

        private int indexOfIdentity(LegacyNbt value) {
            for (int index = 0; index < elements.size(); index++)
                if (elements.get(index) == value) return index;
            throw new IllegalStateException("Detached NBT child");
        }

        @Override
        public ListValue clone() {
            return new ListValue(copyTag());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            if (types != 0) return types;
            ListValue right = (ListValue) other;
            int lengths = Integer.compare(size(), right.size());
            if (lengths != 0) return lengths;
            for (int index = 0; index < size(); index++) {
                int values = get(index).compareTo(right.get(index));
                if (values != 0) return values;
            }
            return 0;
        }

        public Compound addEmptyCompound() {
            return addEmptyCompound(size());
        }

        public Compound addEmptyCompound(int index) {
            Compound value = new Compound();
            add(index, value);
            return value;
        }

        public ListValue addEmptyList() {
            return addEmptyList(size());
        }

        public ListValue addEmptyList(int index) {
            ListValue value = new ListValue();
            add(index, value);
            return value;
        }

        public byte getByte(int index) {
            return getByte(index, (byte) 0);
        }

        public byte getByte(int index, byte fallback) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsByte() : fallback;
        }

        public Byte getByteOrNull(int index) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsByte() : null;
        }

        public short getShort(int index) {
            return getShort(index, (short) 0);
        }

        public short getShort(int index, short fallback) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsShort() : fallback;
        }

        public Short getShortOrNull(int index) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsShort() : null;
        }

        public int getInt(int index) {
            return getInt(index, (int) 0);
        }

        public int getInt(int index, int fallback) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsInt() : fallback;
        }

        public Integer getIntOrNull(int index) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsInt() : null;
        }

        public long getLong(int index) {
            return getLong(index, (long) 0);
        }

        public long getLong(int index, long fallback) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsLong() : fallback;
        }

        public Long getLongOrNull(int index) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsLong() : null;
        }

        public float getFloat(int index) {
            return getFloat(index, (float) 0);
        }

        public float getFloat(int index, float fallback) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsFloat() : fallback;
        }

        public Float getFloatOrNull(int index) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsFloat() : null;
        }

        public double getDouble(int index) {
            return getDouble(index, (double) 0);
        }

        public double getDouble(int index, double fallback) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsDouble() : fallback;
        }

        public Double getDoubleOrNull(int index) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsDouble() : null;
        }

        public byte[] getByteArray(int index) {
            return getByteArray(index, new byte[0]);
        }

        public byte[] getByteArray(int index, byte[] fallback) {
            LegacyNbt value = get(index);
            return value instanceof ByteArray array ? array.getAsByteArray() : fallback;
        }

        public byte[] getByteArrayOrNull(int index) {
            return getByteArray(index, null);
        }

        public int[] getIntArray(int index) {
            return getIntArray(index, new int[0]);
        }

        public int[] getIntArray(int index, int[] fallback) {
            LegacyNbt value = get(index);
            return value instanceof IntArray array ? array.getAsIntArray() : fallback;
        }

        public int[] getIntArrayOrNull(int index) {
            return getIntArray(index, null);
        }

        public long[] getLongArray(int index) {
            return getLongArray(index, new long[0]);
        }

        public long[] getLongArray(int index, long[] fallback) {
            LegacyNbt value = get(index);
            return value instanceof LongArray array ? array.getAsLongArray() : fallback;
        }

        public long[] getLongArrayOrNull(int index) {
            return getLongArray(index, null);
        }

        public Compound getCompound(int index) {
            return getCompound(index, null);
        }

        public Compound getCompoundOrNull(int index) {
            return getCompound(index, null);
        }

        public Compound getCompound(int index, Compound fallback) {
            LegacyNbt value = get(index);
            return value instanceof Compound typed ? typed : fallback;
        }

        public ListValue getList(int index) {
            return getList(index, null);
        }

        public ListValue getListOrNull(int index) {
            return getList(index, null);
        }

        public ListValue getList(int index, ListValue fallback) {
            LegacyNbt value = get(index);
            return value instanceof ListValue typed ? typed : fallback;
        }

        public String getString(int index) {
            return getString(index, null);
        }

        public String getStringOrNull(int index) {
            return getString(index, null);
        }

        public String getString(int index, String fallback) {
            LegacyNbt value = get(index);
            return value == null ? fallback : value.getAsString();
        }

        public boolean getBoolean(int index) {
            return getBoolean(index, false);
        }

        public Boolean getBooleanOrNull(int index) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsByte() != 0 : null;
        }

        public boolean getBoolean(int index, boolean fallback) {
            LegacyNbt value = get(index);
            return value instanceof Numeric n ? n.getAsByte() != 0 : fallback;
        }

        public Compound getOrCreateCompound(int index) {
            Compound value = getCompound(index);
            if (value != null) return value;
            value = new Compound();
            set(index, value);
            return value;
        }

        public ListValue getOrCreateList(int index) {
            ListValue value = getList(index);
            if (value != null) return value;
            value = new ListValue();
            set(index, value);
            return value;
        }

        public ByteValue addByte(byte value) {
            return addByte(size(), value);
        }

        public ByteValue addByte(int index, byte value) {
            ByteValue inserted = ByteValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public ByteValue setByte(int index, byte value) {
            ByteValue inserted = ByteValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public ShortValue addShort(short value) {
            return addShort(size(), value);
        }

        public ShortValue addShort(int index, short value) {
            ShortValue inserted = ShortValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public ShortValue setShort(int index, short value) {
            ShortValue inserted = ShortValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public IntValue addInt(int value) {
            return addInt(size(), value);
        }

        public IntValue addInt(int index, int value) {
            IntValue inserted = IntValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public IntValue setInt(int index, int value) {
            IntValue inserted = IntValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public LongValue addLong(long value) {
            return addLong(size(), value);
        }

        public LongValue addLong(int index, long value) {
            LongValue inserted = LongValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public LongValue setLong(int index, long value) {
            LongValue inserted = LongValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public FloatValue addFloat(float value) {
            return addFloat(size(), value);
        }

        public FloatValue addFloat(int index, float value) {
            FloatValue inserted = FloatValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public FloatValue setFloat(int index, float value) {
            FloatValue inserted = FloatValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public DoubleValue addDouble(double value) {
            return addDouble(size(), value);
        }

        public DoubleValue addDouble(int index, double value) {
            DoubleValue inserted = DoubleValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public DoubleValue setDouble(int index, double value) {
            DoubleValue inserted = DoubleValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public StringValue addString(String value) {
            return addString(size(), value);
        }

        public StringValue addString(int index, String value) {
            StringValue inserted = StringValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public StringValue setString(int index, String value) {
            StringValue inserted = StringValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public ByteValue addBoolean(boolean value) {
            return addBoolean(size(), value);
        }

        public ByteValue addBoolean(int index, boolean value) {
            ByteValue inserted = ByteValue.valueOf(value);
            add(index, inserted);
            return inserted;
        }

        public ByteValue setBoolean(int index, boolean value) {
            ByteValue inserted = ByteValue.valueOf(value);
            set(index, inserted);
            return inserted;
        }

        public ByteArray addByteArray(byte[] value) {
            return addByteArray(size(), value);
        }

        public ByteArray addByteArray(int index, byte[] value) {
            ByteArray inserted = new ByteArray(value);
            add(index, inserted);
            return inserted;
        }

        public ByteArray setByteArray(int index, byte[] value) {
            ByteArray inserted = new ByteArray(value);
            set(index, inserted);
            return inserted;
        }

        public IntArray addIntArray(int[] value) {
            return addIntArray(size(), value);
        }

        public IntArray addIntArray(int index, int[] value) {
            IntArray inserted = new IntArray(value);
            add(index, inserted);
            return inserted;
        }

        public IntArray setIntArray(int index, int[] value) {
            IntArray inserted = new IntArray(value);
            set(index, inserted);
            return inserted;
        }

        public LongArray addLongArray(long[] value) {
            return addLongArray(size(), value);
        }

        public LongArray addLongArray(int index, long[] value) {
            LongArray inserted = new LongArray(value);
            add(index, inserted);
            return inserted;
        }

        public LongArray setLongArray(int index, long[] value) {
            LongArray inserted = new LongArray(value);
            set(index, inserted);
            return inserted;
        }

        public ByteArray addByteArray(List<? extends Number> value) {
            return addByteArray(size(), value);
        }

        public ByteArray addByteArray(int index, List<? extends Number> value) {
            ByteArray inserted = new ByteArray(value);
            add(index, inserted);
            return inserted;
        }

        public ByteArray setByteArray(int index, List<? extends Number> value) {
            ByteArray inserted = new ByteArray(value);
            set(index, inserted);
            return inserted;
        }

        public IntArray addIntArray(List<? extends Number> value) {
            return addIntArray(size(), value);
        }

        public IntArray addIntArray(int index, List<? extends Number> value) {
            IntArray inserted = new IntArray(value);
            add(index, inserted);
            return inserted;
        }

        public IntArray setIntArray(int index, List<? extends Number> value) {
            IntArray inserted = new IntArray(value);
            set(index, inserted);
            return inserted;
        }

        public LongArray addLongArray(List<? extends Number> value) {
            return addLongArray(size(), value);
        }

        public LongArray addLongArray(int index, List<? extends Number> value) {
            LongArray inserted = new LongArray(value);
            add(index, inserted);
            return inserted;
        }

        public LongArray setLongArray(int index, List<? extends Number> value) {
            LongArray inserted = new LongArray(value);
            set(index, inserted);
            return inserted;
        }
    }

    public abstract static class Numeric extends LegacyNbt {
        private final NumericTag number;

        private Numeric(NumericTag number) {
            super(number.getId(), number);
            this.number = number;
        }

        public final byte getAsByte() {
            return number.byteValue();
        }

        public final short getAsShort() {
            return number.shortValue();
        }

        public final int getAsInt() {
            return number.intValue();
        }

        public final long getAsLong() {
            return number.longValue();
        }

        public final float getAsFloat() {
            return number.floatValue();
        }

        public final double getAsDouble() {
            return number.doubleValue();
        }

        public final Number getAsNumber() {
            return number.box();
        }

        @Override
        public String getAsString() {
            return number.box().toString();
        }
    }

    public static final class ByteValue extends Numeric {
        private ByteValue(byte value) {
            super(ByteTag.valueOf(value));
        }

        public static ByteValue valueOf(byte value) {
            return new ByteValue(value);
        }

        @Override
        public ByteValue clone() {
            return valueOf(getAsByte());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            return types != 0 ? types : Byte.compare(getAsByte(), ((Numeric) other).getAsByte());
        }

        public static ByteValue valueOf(boolean value) {
            return valueOf((byte) (value ? 1 : 0));
        }

        public boolean getAsBoolean() {
            return getAsByte() != 0;
        }
    }

    public static final class ShortValue extends Numeric {
        private ShortValue(short value) {
            super(ShortTag.valueOf(value));
        }

        public static ShortValue valueOf(short value) {
            return new ShortValue(value);
        }

        @Override
        public ShortValue clone() {
            return valueOf(getAsShort());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            return types != 0 ? types : Short.compare(getAsShort(), ((Numeric) other).getAsShort());
        }
    }

    public static final class IntValue extends Numeric {
        private IntValue(int value) {
            super(IntTag.valueOf(value));
        }

        public static IntValue valueOf(int value) {
            return new IntValue(value);
        }

        @Override
        public IntValue clone() {
            return valueOf(getAsInt());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            return types != 0 ? types : Integer.compare(getAsInt(), ((Numeric) other).getAsInt());
        }
    }

    public static final class LongValue extends Numeric {
        private LongValue(long value) {
            super(LongTag.valueOf(value));
        }

        public static LongValue valueOf(long value) {
            return new LongValue(value);
        }

        @Override
        public LongValue clone() {
            return valueOf(getAsLong());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            return types != 0 ? types : Long.compare(getAsLong(), ((Numeric) other).getAsLong());
        }
    }

    public static final class FloatValue extends Numeric {
        private FloatValue(float value) {
            super(FloatTag.valueOf(value));
        }

        public static FloatValue valueOf(float value) {
            return new FloatValue(value);
        }

        @Override
        public FloatValue clone() {
            return valueOf(getAsFloat());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            return types != 0 ? types : Float.compare(getAsFloat(), ((Numeric) other).getAsFloat());
        }
    }

    public static final class DoubleValue extends Numeric {
        private DoubleValue(double value) {
            super(DoubleTag.valueOf(value));
        }

        public static DoubleValue valueOf(double value) {
            return new DoubleValue(value);
        }

        @Override
        public DoubleValue clone() {
            return valueOf(getAsDouble());
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            return types != 0
                    ? types
                    : Double.compare(getAsDouble(), ((Numeric) other).getAsDouble());
        }
    }

    public static final class StringValue extends LegacyNbt {
        private final String value;

        private StringValue(String value) {
            super(Type.TAG_STRING, StringTag.valueOf(Objects.requireNonNull(value)));
            this.value = value;
        }

        public static StringValue valueOf(String value) {
            return new StringValue(value);
        }

        @Override
        public String getAsString() {
            return value;
        }

        @Override
        public StringValue clone() {
            return valueOf(value);
        }
    }

    public static final class ByteArray extends CollectionValue<Byte> {
        private final byte[] values;

        public ByteArray(byte[] values) {
            super(Type.TAG_BYTE_ARRAY, new ByteArrayTag(values.clone()));
            this.values = values.clone();
        }

        public ByteArray(List<? extends Number> values) {
            this(numbers(values));
        }

        private static byte[] numbers(List<? extends Number> values) {
            byte[] result = new byte[values.size()];
            for (int index = 0; index < result.length; index++) {
                Number value = values.get(index);
                result[index] = value == null ? 0 : value.byteValue();
            }
            return result;
        }

        public byte[] getAsByteArray() {
            return values.clone();
        }

        public byte getByte(int index) {
            return values[index];
        }

        @Override
        public Byte get(int index) {
            return values[index];
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public Byte set(int index, Byte value) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public void add(int index, Byte value) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public Byte remove(int index) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public String getAsString() {
            return Arrays.toString(values);
        }

        @Override
        public ByteArray clone() {
            return new ByteArray(values);
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            if (types != 0) return types;
            ByteArray right = (ByteArray) other;
            int lengths = Integer.compare(size(), right.size());
            return lengths != 0 ? lengths : Arrays.compare(values, right.values);
        }
    }

    public static final class IntArray extends CollectionValue<Integer> {
        private final int[] values;

        public IntArray(int[] values) {
            super(Type.TAG_INT_ARRAY, new IntArrayTag(values.clone()));
            this.values = values.clone();
        }

        public IntArray(List<? extends Number> values) {
            this(numbers(values));
        }

        private static int[] numbers(List<? extends Number> values) {
            int[] result = new int[values.size()];
            for (int index = 0; index < result.length; index++) {
                Number value = values.get(index);
                result[index] = value == null ? 0 : value.intValue();
            }
            return result;
        }

        public int[] getAsIntArray() {
            return values.clone();
        }

        public int getInt(int index) {
            return values[index];
        }

        @Override
        public Integer get(int index) {
            return values[index];
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public Integer set(int index, Integer value) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public void add(int index, Integer value) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public Integer remove(int index) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public String getAsString() {
            return Arrays.toString(values);
        }

        @Override
        public IntArray clone() {
            return new IntArray(values);
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            if (types != 0) return types;
            IntArray right = (IntArray) other;
            int lengths = Integer.compare(size(), right.size());
            return lengths != 0 ? lengths : Arrays.compare(values, right.values);
        }
    }

    public static final class LongArray extends CollectionValue<Long> {
        private final long[] values;

        public LongArray(long[] values) {
            super(Type.TAG_LONG_ARRAY, new LongArrayTag(values.clone()));
            this.values = values.clone();
        }

        public LongArray(List<? extends Number> values) {
            this(numbers(values));
        }

        private static long[] numbers(List<? extends Number> values) {
            long[] result = new long[values.size()];
            for (int index = 0; index < result.length; index++) {
                Number value = values.get(index);
                result[index] = value == null ? 0 : value.longValue();
            }
            return result;
        }

        public long[] getAsLongArray() {
            return values.clone();
        }

        public long getLong(int index) {
            return values[index];
        }

        @Override
        public Long get(int index) {
            return values[index];
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public Long set(int index, Long value) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public void add(int index, Long value) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public Long remove(int index) {
            throw new UnsupportedOperationException("NBT arrays are read-only");
        }

        @Override
        public String getAsString() {
            return Arrays.toString(values);
        }

        @Override
        public LongArray clone() {
            return new LongArray(values);
        }

        @Override
        public int compareTo(LegacyNbt other) {
            int types = Byte.compare(getId(), other.getId());
            if (types != 0) return types;
            LongArray right = (LongArray) other;
            int lengths = Integer.compare(size(), right.size());
            return lengths != 0 ? lengths : Arrays.compare(values, right.values);
        }
    }

    public static final class End extends LegacyNbt {
        public static final End INSTANCE = new End();

        private End() {
            super(Type.TAG_END, EndTag.INSTANCE);
        }

        public static End instance() {
            return INSTANCE;
        }

        @Override
        public End clone() {
            return INSTANCE;
        }
    }

    public static final class Type {
        private Type() {}

        public static final byte TAG_END = 0;
        public static final byte TAG_BYTE = 1;
        public static final byte TAG_SHORT = 2;
        public static final byte TAG_INT = 3;
        public static final byte TAG_LONG = 4;
        public static final byte TAG_FLOAT = 5;
        public static final byte TAG_DOUBLE = 6;
        public static final byte TAG_BYTE_ARRAY = 7;
        public static final byte TAG_STRING = 8;
        public static final byte TAG_LIST = 9;
        public static final byte TAG_COMPOUND = 10;
        public static final byte TAG_INT_ARRAY = 11;
        public static final byte TAG_LONG_ARRAY = 12;
        public static final byte TAG_ANY_NUMBER = 99;
    }

    public static final class Unsafe {
        private Unsafe() {}

        public static Tag getDelegate(LegacyNbt value) {
            return value.copyTag();
        }

        public static LegacyNbt fromNms(Object value) {
            return wrap((Tag) value);
        }
    }
}

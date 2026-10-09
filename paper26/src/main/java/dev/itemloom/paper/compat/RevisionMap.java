package dev.itemloom.paper.compat;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/** Live script map whose views and default mutation methods retain the revision/thread gate. */
final class RevisionMap<V> extends AbstractMap<String, V> {
    private LinkedHashMap<String, V> values = new LinkedHashMap<>();
    private final Runnable check, changed;
    private final BiConsumer<String, V> validate;

    RevisionMap(Runnable check, BiConsumer<String, V> validate, Runnable changed) {
        this.check = check;
        this.validate = validate;
        this.changed = changed;
    }

    /** Only used before the runtime exposes scripts. */
    void seed(Map<String, V> initial) {
        values.putAll(initial);
    }

    Map<String, V> snapshot() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Registry-only replacement: validate before commit; its owner publishes all maps together. */
    Runnable prepareReplacement(Map<String, V> source) {
        check.run();
        LinkedHashMap<String, V> replacement = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    validate.accept(Objects.requireNonNull(key), Objects.requireNonNull(value));
                    replacement.put(key, value);
                });
        return () -> {
            check.run();
            values = replacement;
        };
    }

    @Override
    public int size() {
        check.run();
        return values.size();
    }

    @Override
    public V get(Object key) {
        check.run();
        return values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        check.run();
        return values.containsKey(key);
    }

    @Override
    public V put(String key, V value) {
        check.run();
        validate.accept(Objects.requireNonNull(key), Objects.requireNonNull(value));
        V previous = values.put(key, value);
        changed.run();
        return previous;
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> source) {
        check.run();
        Map<String, V> prepared = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    validate.accept(Objects.requireNonNull(key), Objects.requireNonNull(value));
                    prepared.put(key, value);
                });
        check.run();
        if (prepared.isEmpty()) return;
        values.putAll(prepared);
        changed.run();
    }

    @Override
    public V remove(Object key) {
        check.run();
        V previous = values.remove(key);
        if (previous != null) changed.run();
        return previous;
    }

    @Override
    public void clear() {
        check.run();
        if (values.isEmpty()) return;
        values.clear();
        changed.run();
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
        check.run();
        return new AbstractSet<>() {
            @Override
            public int size() {
                return RevisionMap.this.size();
            }

            @Override
            public void clear() {
                RevisionMap.this.clear();
            }

            @Override
            public Iterator<Entry<String, V>> iterator() {
                check.run();
                Iterator<Entry<String, V>> entries =
                        new LinkedHashMap<>(values).entrySet().iterator();
                return new Iterator<>() {
                    String current;
                    boolean removable;

                    @Override
                    public boolean hasNext() {
                        check.run();
                        return entries.hasNext();
                    }

                    @Override
                    public Entry<String, V> next() {
                        check.run();
                        Entry<String, V> entry = entries.next();
                        current = entry.getKey();
                        removable = true;
                        return new SimpleEntry<>(current, entry.getValue()) {
                            @Override
                            public V setValue(V value) {
                                RevisionMap.this.put(getKey(), value);
                                return super.setValue(value);
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        check.run();
                        if (!removable) throw new IllegalStateException("No current entry");
                        RevisionMap.this.remove(current);
                        removable = false;
                    }
                };
            }
        };
    }
}

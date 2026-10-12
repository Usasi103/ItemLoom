package dev.itemloom.paper.compat;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

final class RevisionMap<V> extends AbstractMap<String, V> {
    private final LinkedHashMap<String, V> values = new LinkedHashMap<>();
    private final Runnable check, changed;
    private final BiConsumer<String, V> validate;

    RevisionMap(Runnable check, BiConsumer<String, V> validate, Runnable changed) {
        this.check = check;
        this.validate = validate;
        this.changed = changed;
    }

    private void validate(String key, V value) {
        validate.accept(Objects.requireNonNull(key), Objects.requireNonNull(value));
    }

    void seed(Map<String, V> source) {
        source.forEach(
                (key, value) -> {
                    validate(key, value);
                    values.put(key, value);
                });
    }

    Map<String, V> snapshot() {
        check.run();
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    Runnable prepareReplacement(Map<String, V> source) {
        check.run();
        LinkedHashMap<String, V> prepared = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    validate(key, value);
                    prepared.put(key, value);
                });
        check.run();
        return () -> {
            check.run();
            values.clear();
            values.putAll(prepared);
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
    public boolean isEmpty() {
        check.run();
        return values.isEmpty();
    }

    @Override
    public boolean containsValue(Object value) {
        check.run();
        return values.containsValue(value);
    }

    @Override
    public boolean equals(Object other) {
        check.run();
        return super.equals(other);
    }

    @Override
    public int hashCode() {
        check.run();
        return super.hashCode();
    }

    @Override
    public String toString() {
        check.run();
        return super.toString();
    }

    @Override
    public V getOrDefault(Object key, V fallback) {
        check.run();
        return super.getOrDefault(key, fallback);
    }

    @Override
    public V putIfAbsent(String key, V value) {
        check.run();
        return super.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        check.run();
        return super.remove(key, value);
    }

    @Override
    public V replace(String key, V value) {
        check.run();
        return super.replace(key, value);
    }

    @Override
    public boolean replace(String key, V oldValue, V newValue) {
        check.run();
        return super.replace(key, oldValue, newValue);
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super V> action) {
        check.run();
        super.forEach(action);
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super V, ? extends V> action) {
        check.run();
        super.replaceAll(action);
    }

    @Override
    public V computeIfAbsent(String key, Function<? super String, ? extends V> action) {
        check.run();
        return super.computeIfAbsent(key, action);
    }

    @Override
    public V computeIfPresent(
            String key, BiFunction<? super String, ? super V, ? extends V> action) {
        check.run();
        return super.computeIfPresent(key, action);
    }

    @Override
    public V compute(String key, BiFunction<? super String, ? super V, ? extends V> action) {
        check.run();
        return super.compute(key, action);
    }

    @Override
    public V merge(String key, V value, BiFunction<? super V, ? super V, ? extends V> action) {
        check.run();
        return super.merge(key, value, action);
    }

    @Override
    public V put(String key, V value) {
        check.run();
        validate(key, value);
        check.run();
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
                    validate(key, value);
                    prepared.put(key, value);
                });
        check.run();
        if (!prepared.isEmpty()) {
            values.putAll(prepared);
            changed.run();
        }
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
        if (!values.isEmpty()) {
            values.clear();
            changed.run();
        }
    }

    @Override
    public Set<String> keySet() {
        check.run();
        return new GatedSet<>(super.keySet());
    }

    @Override
    public Collection<V> values() {
        check.run();
        return new GatedCollection<>(super.values());
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
        check.run();
        return new GatedSet<>(
                new AbstractSet<Entry<String, V>>() {
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
                        Iterator<Entry<String, V>> snapshot =
                                new LinkedHashMap<>(values).entrySet().iterator();
                        return new Iterator<>() {
                            private String removable;

                            @Override
                            public boolean hasNext() {
                                check.run();
                                return snapshot.hasNext();
                            }

                            @Override
                            public Entry<String, V> next() {
                                check.run();
                                Entry<String, V> entry = snapshot.next();
                                removable = entry.getKey();
                                return new SimpleEntry<>(entry) {
                                    @Override
                                    public String getKey() {
                                        check.run();
                                        return super.getKey();
                                    }

                                    @Override
                                    public V getValue() {
                                        check.run();
                                        return super.getValue();
                                    }

                                    @Override
                                    public V setValue(V value) {
                                        check.run();
                                        V previous = RevisionMap.this.put(super.getKey(), value);
                                        super.setValue(value);
                                        return previous;
                                    }

                                    @Override
                                    public boolean equals(Object other) {
                                        check.run();
                                        return super.equals(other);
                                    }

                                    @Override
                                    public int hashCode() {
                                        check.run();
                                        return super.hashCode();
                                    }

                                    @Override
                                    public String toString() {
                                        check.run();
                                        return super.toString();
                                    }
                                };
                            }

                            @Override
                            public void remove() {
                                check.run();
                                if (removable == null)
                                    throw new IllegalStateException("No current entry");
                                RevisionMap.this.remove(removable);
                                removable = null;
                            }
                        };
                    }
                });
    }

    private class GatedCollection<T> implements Collection<T> {
        private final Collection<T> delegate;

        GatedCollection(Collection<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public int size() {
            check.run();
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            check.run();
            return delegate.isEmpty();
        }

        @Override
        public boolean contains(Object value) {
            check.run();
            return delegate.contains(value);
        }

        @Override
        public boolean containsAll(Collection<?> values) {
            check.run();
            return delegate.containsAll(values);
        }

        @Override
        public Object[] toArray() {
            check.run();
            return delegate.toArray();
        }

        @Override
        public <A> A[] toArray(A[] array) {
            check.run();
            return delegate.toArray(array);
        }

        @Override
        public <A> A[] toArray(IntFunction<A[]> factory) {
            check.run();
            return delegate.toArray(factory);
        }

        @Override
        public boolean add(T value) {
            check.run();
            return delegate.add(value);
        }

        @Override
        public boolean addAll(Collection<? extends T> values) {
            check.run();
            return delegate.addAll(values);
        }

        @Override
        public boolean remove(Object value) {
            check.run();
            return delegate.remove(value);
        }

        @Override
        public boolean removeAll(Collection<?> values) {
            check.run();
            return delegate.removeAll(values);
        }

        @Override
        public boolean retainAll(Collection<?> values) {
            check.run();
            return delegate.retainAll(values);
        }

        @Override
        public boolean removeIf(Predicate<? super T> filter) {
            check.run();
            return delegate.removeIf(filter);
        }

        @Override
        public void clear() {
            check.run();
            delegate.clear();
        }

        @Override
        public void forEach(Consumer<? super T> action) {
            check.run();
            delegate.forEach(action);
        }

        @Override
        public boolean equals(Object other) {
            check.run();
            return this == other || delegate.equals(other);
        }

        @Override
        public int hashCode() {
            check.run();
            return delegate.hashCode();
        }

        @Override
        public String toString() {
            check.run();
            return delegate.toString();
        }

        @Override
        public Iterator<T> iterator() {
            check.run();
            Iterator<T> iterator = delegate.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    check.run();
                    return iterator.hasNext();
                }

                @Override
                public T next() {
                    check.run();
                    return iterator.next();
                }

                @Override
                public void remove() {
                    check.run();
                    iterator.remove();
                }

                @Override
                public void forEachRemaining(Consumer<? super T> action) {
                    check.run();
                    iterator.forEachRemaining(action);
                }
            };
        }

        @Override
        public Spliterator<T> spliterator() {
            check.run();
            return guarded(delegate.spliterator());
        }

        @Override
        public Stream<T> stream() {
            check.run();
            return StreamSupport.stream(spliterator(), false);
        }

        @Override
        public Stream<T> parallelStream() {
            check.run();
            return StreamSupport.stream(spliterator(), true);
        }
    }

    private final class GatedSet<T> extends GatedCollection<T> implements Set<T> {
        GatedSet(Set<T> delegate) {
            super(delegate);
        }
    }

    private <T> Spliterator<T> guarded(Spliterator<T> source) {
        return new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                check.run();
                return source.tryAdvance(action);
            }

            @Override
            public void forEachRemaining(Consumer<? super T> action) {
                check.run();
                source.forEachRemaining(
                        value -> {
                            check.run();
                            action.accept(value);
                        });
            }

            @Override
            public Spliterator<T> trySplit() {
                check.run();
                Spliterator<T> split = source.trySplit();
                return split == null ? null : guarded(split);
            }

            @Override
            public long estimateSize() {
                check.run();
                return source.estimateSize();
            }

            @Override
            public int characteristics() {
                check.run();
                return source.characteristics();
            }

            @Override
            public Comparator<? super T> getComparator() {
                check.run();
                return source.getComparator();
            }
        };
    }
}

package dev.itemloom.compat.ni.action;

import java.util.Collection;
import java.util.List;

/** Identity keys preserve script context aliases without exposing NI types to the core. */
public final class NiContextKey<T> {
    private final boolean putInGlobal;
    private final Collection<String> names;

    public NiContextKey(String... names) {
        this(false, names);
    }

    public NiContextKey(boolean putInGlobal, String... names) {
        this(putInGlobal, List.of(names));
    }

    public NiContextKey(boolean putInGlobal, Collection<String> names) {
        this.putInGlobal = putInGlobal;
        this.names = List.copyOf(names);
    }

    public boolean isPutInGlobal() {
        return putInGlobal;
    }

    public Collection<String> getNames() {
        return names;
    }
}

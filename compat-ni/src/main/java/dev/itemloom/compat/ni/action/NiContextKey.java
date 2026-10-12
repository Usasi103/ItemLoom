package dev.itemloom.compat.ni.action;

import java.util.*;

public final class NiContextKey<T> {
    private final boolean putInGlobal;
    private final List<String> names;

    public NiContextKey(String... names) {
        this(false, names);
    }

    public NiContextKey(boolean putInGlobal, String... names) {
        this(putInGlobal, Arrays.asList(names));
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

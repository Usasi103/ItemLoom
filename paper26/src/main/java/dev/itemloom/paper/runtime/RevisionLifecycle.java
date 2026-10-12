package dev.itemloom.paper.runtime;

public interface RevisionLifecycle extends AutoCloseable {
    boolean active();

    void serverEnabled();

    void serverStopping();

    @Override
    void close();
}

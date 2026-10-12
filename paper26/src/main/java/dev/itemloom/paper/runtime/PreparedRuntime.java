package dev.itemloom.paper.runtime;

public interface PreparedRuntime extends AutoCloseable {
    RuntimeSnapshot snapshot();

    void verifySourcesUnchanged() throws Exception;

    void activate();

    @Override
    void close();
}

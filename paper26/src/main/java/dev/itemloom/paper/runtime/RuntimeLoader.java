package dev.itemloom.paper.runtime;

public interface RuntimeLoader {
    PreparedRuntime prepare(long nextRevision) throws Exception;
}

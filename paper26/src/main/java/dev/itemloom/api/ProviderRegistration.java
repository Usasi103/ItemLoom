package dev.itemloom.api;

/** Main-thread, idempotent removal; automatically closed when the owner disables or ItemLoom stops. */
public interface ProviderRegistration extends AutoCloseable {
    @Override
    void close();
}

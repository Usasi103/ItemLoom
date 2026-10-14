package dev.itemloom.api;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Outcome of {@link ItemLoom#rebuild}. The original stack is never modified. On success the
 * caller receives a new stack and decides whether to swap it in; every failure leaves the
 * original as it was.
 */
public final class ItemRebuildResult {
    public enum Status {
        /** A rebuilt stack is available through {@link #item()}. */
        REBUILT,
        /** The input was null or air. */
        EMPTY_ITEM,
        /** The item carries no ItemLoom identity (native or NI-format). */
        NOT_ITEMLOOM,
        /** The identity exists but cannot be read (corrupt or unsupported schema). */
        CORRUPT_IDENTITY,
        /** The identity id is not defined by the current configuration or providers. */
        UNKNOWN_ID,
        /** An override roll name or a keep-list entry was null. */
        INVALID_ARGUMENT,
        /** Generation or protection of the replacement failed; see {@link #cause()}. */
        GENERATION_FAILED,
        /** The service has no active runtime (closed, reloading, or not yet loaded). */
        UNAVAILABLE,
        /** The id is supplied by an external provider, which cannot be rebuilt through ItemLoom. */
        UNSUPPORTED_FORMAT,
        /** The original stack changed while the replacement was being prepared. */
        ITEM_CHANGED
    }

    private final Status status;
    private final ItemStack item;
    private final String message;
    private final Throwable cause;

    private ItemRebuildResult(Status status, ItemStack item, String message, Throwable cause) {
        this.status = Objects.requireNonNull(status, "status");
        this.item = item;
        this.message = Objects.requireNonNull(message, "message");
        this.cause = cause;
    }

    public static ItemRebuildResult rebuilt(ItemStack item) {
        return new ItemRebuildResult(
                Status.REBUILT, Objects.requireNonNull(item, "item").clone(), "Rebuilt", null);
    }

    public static ItemRebuildResult failed(Status status, String message, Throwable cause) {
        if (status == Status.REBUILT) {
            throw new IllegalArgumentException("A successful rebuild carries an item");
        }
        return new ItemRebuildResult(status, null, message, cause);
    }

    public Status status() {
        return status;
    }

    public boolean rebuilt() {
        return status == Status.REBUILT;
    }

    /** A fresh copy of the rebuilt stack; empty when the rebuild failed. */
    public Optional<ItemStack> item() {
        return item == null ? Optional.empty() : Optional.of(item.clone());
    }

    /** A short English reason, suitable for logs. Player-facing text belongs to the caller. */
    public String message() {
        return message;
    }

    public Optional<Throwable> cause() {
        return Optional.ofNullable(cause);
    }
}

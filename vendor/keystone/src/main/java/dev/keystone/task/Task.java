package dev.keystone.task;

import org.bukkit.scheduler.BukkitTask;

/**
 * A scheduled task that can cancel itself from inside its own body: the body of every {@link
 * Tasks} method receives its task, so a timer stops itself with {@code task.cancel()}.
 */
public final class Task {

    private volatile BukkitTask handle;
    private volatile boolean cancelled;

    Task() {}

    void attach(BukkitTask handle) {
        this.handle = handle;
        if (cancelled) {
            handle.cancel();
        }
    }

    public void cancel() {
        cancelled = true;
        BukkitTask current = handle;
        if (current != null) {
            current.cancel();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** The Bukkit task id, or -1 when the body ran inline. */
    public int id() {
        BukkitTask current = handle;
        return current == null ? -1 : current.getTaskId();
    }
}

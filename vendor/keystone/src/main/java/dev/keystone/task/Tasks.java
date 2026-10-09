package dev.keystone.task;

import dev.keystone.Keystone;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit scheduling with self-cancelling bodies.
 *
 * <ul>
 *   <li>{@link #run}: next tick on the main thread; {@link #now}: right away on the calling thread.
 *   <li>{@link #later}: after a delay; {@link #timer}: repeating, first run after the delay.
 *   <li>{@code async*}: Bukkit's async pool instead of the main thread.
 * </ul>
 *
 * <p>After the plugin is disabled Bukkit refuses new tasks. A one-shot task then runs inline on
 * the calling thread, so late cleanup still happens; a repeating one is dropped.
 */
public final class Tasks {

    private Tasks() {}

    public static Task now(Runnable body) {
        return submit(true, false, 0, 0, task -> body.run());
    }

    public static Task run(Runnable body) {
        return submit(false, false, 0, 0, task -> body.run());
    }

    public static Task run(Consumer<Task> body) {
        return submit(false, false, 0, 0, body);
    }

    public static Task later(long delay, Runnable body) {
        return submit(false, false, delay, 0, task -> body.run());
    }

    public static Task later(long delay, Consumer<Task> body) {
        return submit(false, false, delay, 0, body);
    }

    public static Task timer(long delay, long period, Runnable body) {
        return submit(false, false, delay, period, task -> body.run());
    }

    public static Task timer(long delay, long period, Consumer<Task> body) {
        return submit(false, false, delay, period, body);
    }

    public static Task async(Runnable body) {
        return submit(false, true, 0, 0, task -> body.run());
    }

    public static Task asyncLater(long delay, Runnable body) {
        return submit(false, true, delay, 0, task -> body.run());
    }

    public static Task asyncTimer(long delay, long period, Runnable body) {
        return submit(false, true, delay, period, task -> body.run());
    }

    public static Task asyncTimer(long delay, long period, Consumer<Task> body) {
        return submit(false, true, delay, period, body);
    }

    /** Runs {@code body} on the main thread: inline when already there, otherwise next tick. */
    public static void sync(Runnable body) {
        if (Bukkit.isPrimaryThread()) {
            body.run();
        } else {
            run(body);
        }
    }

    /**
     * The general form: {@code delay == 0 && period == 0} is the next tick (or right now with
     * {@code now}, main-thread only); {@code period > 0} repeats.
     */
    public static Task submit(
            boolean now, boolean async, long delay, long period, Consumer<Task> body) {
        Task task = new Task();
        JavaPlugin plugin = Keystone.plugin();
        if (now && !async && delay <= 0 && period <= 0) {
            body.accept(task);
            return task;
        }
        if (!plugin.isEnabled()) {
            if (period <= 0) {
                body.accept(task);
            } else {
                task.cancel();
            }
            return task;
        }
        Runnable runnable =
                () -> {
                    if (task.isCancelled()) {
                        task.cancel();
                        return;
                    }
                    body.accept(task);
                };
        BukkitScheduler scheduler = Bukkit.getScheduler();
        long start = Math.max(0, delay);
        BukkitTask handle;
        if (period > 0 && async) {
            handle = scheduler.runTaskTimerAsynchronously(plugin, runnable, start, period);
        } else if (period > 0) {
            handle = scheduler.runTaskTimer(plugin, runnable, start, period);
        } else if (delay > 0 && async) {
            handle = scheduler.runTaskLaterAsynchronously(plugin, runnable, delay);
        } else if (delay > 0) {
            handle = scheduler.runTaskLater(plugin, runnable, delay);
        } else if (async) {
            handle = scheduler.runTaskAsynchronously(plugin, runnable);
        } else {
            handle = scheduler.runTask(plugin, runnable);
        }
        task.attach(handle);
        return task;
    }
}

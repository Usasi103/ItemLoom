package dev.itemloom.paper.action;

import dev.keystone.task.Task;
import dev.keystone.task.Tasks;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import dev.itemloom.core.ActionFlow.Result;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns one catalog revision's pending continuations, including completion on reload/disable. */
public final class ActionTasks implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Set<Pending<?>> pending = new HashSet<>();
    private final Set<CompletableFuture<?>> tracked = new LinkedHashSet<>();
    private volatile boolean closed;

    public ActionTasks(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean active() {
        return !closed && plugin.isEnabled();
    }

    /** Also own derived futures whose continuations may be queued in a caller-supplied executor. */
    public <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "future");
        boolean cancel;
        synchronized (this) {
            cancel = !active();
            if (!cancel && !future.isDone()) tracked.add(future);
        }
        if (cancel) future.cancel(false);
        else {
            // Do not call an overridden whenComplete/newIncompleteFuture on the supplied
            // future: a script future registers its derived futures with this same owner.
            CompletableFuture.allOf(future)
                    .whenComplete(
                            (value, error) -> {
                                synchronized (ActionTasks.this) {
                                    tracked.remove(future);
                                }
                            });
        }
        return future;
    }

    public CompletionStage<Result> run(Supplier<CompletionStage<Result>> body) {
        Pending<Result> call = register(Result.STOP, false);
        if (call != null) call.invoke(body::get, () -> true, false);
        return call == null ? CompletableFuture.completedFuture(Result.STOP) : call.result;
    }

    public CompletionStage<Result> schedule(
            long ticks, boolean async, boolean immediate, Supplier<CompletionStage<Result>> body) {
        if (immediate
                && ticks <= 0
                && (async ? !Bukkit.isPrimaryThread() : Bukkit.isPrimaryThread())) return run(body);
        Pending<Result> call = register(Result.STOP, false);
        if (call == null) return CompletableFuture.completedFuture(Result.STOP);
        submit(call, ticks, async, 0, () -> call.invoke(body::get, () -> true, false));
        return call.result;
    }

    /** Value-returning legacy callbacks are cancelled on revision close, including queued futures. */
    public <T> CompletableFuture<T> call(
            long ticks,
            boolean async,
            boolean immediate,
            BooleanSupplier enabled,
            Callable<T> body) {
        Pending<T> call = register(null, true);
        if (call == null) return cancelled();
        Runnable invoke =
                () ->
                        call.invoke(
                                () -> CompletableFuture.completedFuture(body.call()),
                                enabled,
                                false);
        if (immediate
                && ticks <= 0
                && (async ? !Bukkit.isPrimaryThread() : Bukkit.isPrimaryThread())) invoke.run();
        else submit(call, ticks, async, 0, invoke);
        return call.result;
    }

    /** The future represents the timer's lifetime; cancelling it also cancels its Keystone task. */
    public CompletableFuture<Void> repeat(
            long ticks, long period, boolean async, BooleanSupplier enabled, Runnable body) {
        // Bukkit treats a zero period as one tick, and negative periods as one-shot tasks.
        if (period < 0)
            return call(
                    ticks,
                    async,
                    false,
                    enabled,
                    () -> {
                        body.run();
                        return null;
                    });
        Pending<Void> call = register(null, true);
        if (call == null) return cancelled();
        submit(
                call,
                ticks,
                async,
                Math.max(1, period),
                () ->
                        call.invoke(
                                () -> {
                                    body.run();
                                    return CompletableFuture.completedFuture(null);
                                },
                                enabled,
                                true));
        return call.result;
    }

    private void submit(Pending<?> call, long ticks, boolean async, long period, Runnable body) {
        try {
            // Keystone may run a one-shot task inline during shutdown. invoke checks active again.
            Task task = Tasks.submit(false, async, ticks, period, ignored -> body.run());
            boolean cancel;
            synchronized (this) {
                call.task = task;
                cancel = closed || call.result.isDone();
            }
            if (cancel) task.cancel();
        } catch (RuntimeException error) {
            call.result.completeExceptionally(error);
        }
    }

    private synchronized <T> Pending<T> register(T stopped, boolean cancelOnClose) {
        if (!active()) return null;
        Pending<T> call = new Pending<>(stopped, cancelOnClose);
        pending.add(call);
        call.result.whenComplete(
                (value, error) -> {
                    Task task;
                    synchronized (ActionTasks.this) {
                        pending.remove(call);
                        task = call.task;
                    }
                    if (task != null) task.cancel();
                });
        return call;
    }

    private static <T> CompletableFuture<T> cancelled() {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.cancel(false);
        return result;
    }

    private final class Pending<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final T stopped;
        final boolean cancelOnClose;
        Task task;

        Pending(T stopped, boolean cancelOnClose) {
            this.stopped = stopped;
            this.cancelOnClose = cancelOnClose;
        }

        void stop() {
            if (cancelOnClose) result.cancel(false);
            else result.complete(stopped);
        }

        void invoke(Callable<CompletionStage<T>> body, BooleanSupplier enabled, boolean repeat) {
            if (result.isDone()) return;
            try {
                if (!active() || !enabled.getAsBoolean()) {
                    stop();
                    return;
                }
                body.call()
                        .whenComplete(
                                (value, error) -> {
                                    if (error != null) result.completeExceptionally(error);
                                    else if (!repeat) result.complete(value);
                                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }
    }

    @Override
    public void close() {
        List<Pending<?>> calls;
        List<CompletableFuture<?>> futures;
        synchronized (this) {
            if (closed) return;
            closed = true;
            calls = List.copyOf(pending);
            pending.clear();
            futures = List.copyOf(tracked);
            tracked.clear();
        }
        // Completion may run user callbacks; never run them under the registry monitor.
        for (Pending<?> call : calls) call.stop();
        // Insertion order gives a parent's registered cancellation observers a chance to
        // finish before its still-pending descendants are cancelled independently.
        for (CompletableFuture<?> future : futures) if (!future.isDone()) future.cancel(false);
    }
}

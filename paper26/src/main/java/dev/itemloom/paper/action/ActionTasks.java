package dev.itemloom.paper.action;

import dev.itemloom.core.ActionFlow.Result;
import dev.keystone.task.Task;
import dev.keystone.task.Tasks;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ActionTasks implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Object monitor = new Object();
    private final LinkedHashSet<Registration> pending = new LinkedHashSet<>();
    private final IdentityHashMap<CompletableFuture<?>, Registration> byFuture =
            new IdentityHashMap<>();
    private volatile boolean closed;

    public ActionTasks(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    public boolean active() {
        return !closed && plugin.isEnabled();
    }

    public <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        own(Objects.requireNonNull(future), false);
        return future;
    }

    public CompletionStage<Result> run(Supplier<CompletionStage<Result>> body) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        Registration registration = own(future, true);
        execute(registration, body, future);
        return future;
    }

    public CompletionStage<Result> schedule(
            long ticks, boolean async, boolean immediate, Supplier<CompletionStage<Result>> body) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        Registration registration = own(future, true);
        dispatch(
                registration,
                ticks,
                0,
                async,
                immediate,
                () -> execute(registration, body, future));
        return future;
    }

    public <T> CompletableFuture<T> call(
            long ticks,
            boolean async,
            boolean immediate,
            BooleanSupplier enabled,
            Callable<T> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Registration registration = own(future, false);
        dispatch(
                registration,
                ticks,
                0,
                async,
                immediate,
                () -> {
                    if (future.isDone()) return;
                    try {
                        if (!active() || !enabled.getAsBoolean()) future.cancel(false);
                        else future.complete(body.call());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                });
        return future;
    }

    public CompletableFuture<Void> repeat(
            long delay, long period, boolean async, BooleanSupplier enabled, Runnable body) {
        if (period < 0)
            return call(
                    delay,
                    async,
                    false,
                    enabled,
                    () -> {
                        body.run();
                        return null;
                    });
        CompletableFuture<Void> future = new CompletableFuture<>();
        Registration registration = own(future, false);
        dispatch(
                registration,
                delay,
                Math.max(1, period),
                async,
                false,
                () -> {
                    if (future.isDone()) return;
                    try {
                        if (!active() || !enabled.getAsBoolean()) future.cancel(false);
                        else body.run();
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                });
        return future;
    }

    private Registration own(CompletableFuture<?> future, boolean action) {
        Registration registration = new Registration(future, action);
        boolean accepted;
        synchronized (monitor) {
            Registration existing = byFuture.get(future);
            if (existing != null) return existing;
            accepted = active();
            if (accepted) {
                pending.add(registration);
                byFuture.put(future, registration);
            }
        }
        // allOf observes the supplied future without invoking subclass continuation factories.
        CompletableFuture.allOf(future).whenComplete((ignored, failure) -> release(registration));
        if (!accepted) registration.stop();
        return registration;
    }

    private void release(Registration registration) {
        synchronized (monitor) {
            pending.remove(registration);
            byFuture.remove(registration.future, registration);
        }
        registration.cancelHandle();
    }

    private void execute(
            Registration registration,
            Supplier<CompletionStage<Result>> body,
            CompletableFuture<Result> future) {
        if (future.isDone()) return;
        if (!active()) {
            registration.stop();
            return;
        }
        try {
            body.get()
                    .whenComplete(
                            (value, failure) -> {
                                if (!active()) future.complete(Result.STOP);
                                else if (failure != null) future.completeExceptionally(failure);
                                else future.complete(value);
                            });
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    private void dispatch(
            Registration registration,
            long delay,
            long period,
            boolean async,
            boolean immediate,
            Runnable body) {
        if (registration.future.isDone()) return;
        if (!active()) {
            registration.stop();
            return;
        }
        try {
            if (immediate && delay <= 0 && async != Bukkit.isPrimaryThread()) body.run();
            else
                registration.attach(
                        Tasks.submit(
                                false,
                                async,
                                delay,
                                period,
                                ignored -> {
                                    if (!active()) registration.stop();
                                    else body.run();
                                }));
        } catch (Throwable failure) {
            registration.future.completeExceptionally(failure);
        }
    }

    @Override
    public void close() {
        ArrayList<Registration> detached;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            detached = new ArrayList<>(pending);
            pending.clear();
            byFuture.clear();
        }
        for (Registration registration : detached) {
            if (registration.action) registration.stop();
        }
        for (Registration registration : detached) {
            if (!registration.action) registration.stop();
        }
    }

    private static final class Registration {
        final CompletableFuture<?> future;
        final boolean action;
        private Task handle;

        Registration(CompletableFuture<?> future, boolean action) {
            this.future = future;
            this.action = action;
        }

        synchronized void attach(Task task) {
            if (future.isDone()) task.cancel();
            else handle = task;
        }

        synchronized void cancelHandle() {
            if (handle == null) return;
            Task attached = handle;
            handle = null;
            attached.cancel();
        }

        @SuppressWarnings("unchecked")
        void stop() {
            if (action) ((CompletableFuture<Result>) future).complete(Result.STOP);
            else future.cancel(false);
            cancelHandle();
        }
    }
}

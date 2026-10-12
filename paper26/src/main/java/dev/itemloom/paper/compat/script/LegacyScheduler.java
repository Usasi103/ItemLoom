package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.paper.action.ActionTasks;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Revision-owned scheduling preserves the exact captured action and evaluation for every callback. */
public final class LegacyScheduler {
    private final JavaPlugin plugin;
    private final ActionTasks tasks;
    private final NiScripts scripts;

    public LegacyScheduler(JavaPlugin plugin, ActionTasks tasks, NiScripts scripts) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.scripts = Objects.requireNonNull(scripts, "scripts");
    }

    public void run(boolean inPrimaryThread, Runnable task) {
        run(plugin, inPrimaryThread, task);
    }

    public void run(Plugin owner, boolean inPrimaryThread, Runnable task) {
        if (inPrimaryThread) sync(owner, task);
        else async(owner, task);
    }

    public void sync(Runnable task) {
        sync(plugin, task);
    }

    public void sync(Plugin owner, Runnable task) {
        submit(owner, 0, false, true, task);
    }

    public void async(Runnable task) {
        async(plugin, task);
    }

    public void async(Plugin owner, Runnable task) {
        submit(owner, 0, true, false, task);
    }

    public void syncLater(long delay, Runnable task) {
        syncLater(plugin, delay, task);
    }

    public void syncLater(Plugin owner, long delay, Runnable task) {
        submit(owner, delay, false, false, task);
    }

    public void asyncLater(long delay, Runnable task) {
        asyncLater(plugin, delay, task);
    }

    public void asyncLater(Plugin owner, long delay, Runnable task) {
        submit(owner, delay, true, false, task);
    }

    public void syncTimer(long delay, long period, Runnable task) {
        syncTimer(plugin, delay, period, task);
    }

    public void syncTimer(Plugin owner, long delay, long period, Runnable task) {
        timer(owner, delay, period, false, task);
    }

    public void asyncTimer(long delay, long period, Runnable task) {
        asyncTimer(plugin, delay, period, task);
    }

    public void asyncTimer(Plugin owner, long delay, long period, Runnable task) {
        timer(owner, delay, period, true, task);
    }

    public void runLater(long delay, Runnable task) {
        runLater(plugin, delay, task);
    }

    public void runLater(Plugin owner, long delay, Runnable task) {
        submit(owner, delay, !Bukkit.isPrimaryThread(), false, task);
    }

    public <T> T syncAndGet(Callable<T> task) {
        return syncAndGet(plugin, task);
    }

    public <T> T syncAndGet(Plugin owner, Callable<T> task) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(task, "task");
        if (!tasks.active() || !owner.isEnabled()) return null;
        if (!Bukkit.isPrimaryThread() && Thread.holdsLock(scripts)) {
            throw new IllegalStateException(
                    "Cannot wait for the main thread while holding the script lock; use sync or a callSyncMethod continuation");
        }
        try {
            return callSyncMethod(owner, task).get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            warning(failure);
        } catch (ExecutionException failure) {
            warning(unwrap(failure));
        } catch (CancellationException ignored) {
        }
        return null;
    }

    public <T> CompletableFuture<T> callSyncMethod(Callable<T> task) {
        return callSyncMethod(plugin, task);
    }

    public <T> CompletableFuture<T> callSyncMethod(Plugin owner, Callable<T> task) {
        return valueCall(owner, task, false);
    }

    public <T> CompletableFuture<T> callSyncStrict(Callable<T> task) {
        return valueCall(plugin, task, true);
    }

    private void submit(Plugin owner, long delay, boolean async, boolean immediate, Runnable task) {
        Objects.requireNonNull(task, "task");
        Captured scope = capture(owner);
        CompletableFuture<Void> future =
                tasks.call(
                        delay,
                        async,
                        immediate,
                        owner::isEnabled,
                        () ->
                                scope.invoke(
                                        false,
                                        () -> {
                                            task.run();
                                            return null;
                                        }));
        if (immediate && delay <= 0 && Bukkit.isPrimaryThread() != async) {
            if (!future.isCancelled()) {
                try {
                    future.join();
                } catch (CompletionException failure) {
                    throw unchecked(unwrap(failure));
                }
            }
        } else {
            future.whenComplete(
                    (value, failure) -> {
                        if (failure != null && !(unwrap(failure) instanceof CancellationException))
                            warning(unwrap(failure));
                    });
        }
    }

    private void timer(Plugin owner, long delay, long period, boolean async, Runnable task) {
        Objects.requireNonNull(task, "task");
        Captured scope = capture(owner);
        tasks.repeat(
                delay,
                period,
                async,
                owner::isEnabled,
                () -> {
                    try {
                        scope.invoke(
                                false,
                                () -> {
                                    task.run();
                                    return null;
                                });
                    } catch (CancellationException cancelled) {
                        throw cancelled;
                    } catch (Throwable failure) {
                        warning(unwrap(failure));
                    }
                });
    }

    private <T> CompletableFuture<T> valueCall(Plugin owner, Callable<T> task, boolean strict) {
        Objects.requireNonNull(task, "task");
        Captured scope = capture(owner);
        GuardedFuture<T> result = new GuardedFuture<>(scope);
        CompletableFuture<T> scheduled =
                tasks.call(
                        0,
                        false,
                        true,
                        owner::isEnabled,
                        () ->
                                scope.invoke(
                                        false,
                                        () -> {
                                            try {
                                                return task.call();
                                            } catch (Exception failure) {
                                                if (strict) throw new CompletionException(failure);
                                                warning(failure);
                                                return null;
                                            }
                                        }));
        result.scheduled = scheduled;
        if (result.isCancelled()) scheduled.cancel(false);
        scheduled.whenComplete(
                (value, failure) -> {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof CancellationException) result.cancel(false);
                    else if (cause != null) result.completeExceptionally(cause);
                    else result.complete(value);
                });
        return result;
    }

    private Captured capture(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        NiActionContext action = NiActionContext.currentOrNull();
        NiEvaluation evaluation;
        try {
            evaluation = NiEvaluation.current();
        } catch (IllegalStateException absent) {
            evaluation = null;
        }
        if (action != null && action.evaluation().scripts() != scripts
                || evaluation != null && evaluation.scripts() != scripts) {
            throw new IllegalStateException(
                    "SchedulerUtils cannot capture a context from another script revision");
        }
        return new Captured(owner, action, evaluation);
    }

    private void warning(Throwable failure) {
        plugin.getLogger().log(Level.WARNING, "Legacy script scheduler callback failed", failure);
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new CompletionException(failure);
    }

    private final class Captured {
        private final Plugin owner;
        private final NiActionContext action;
        private final NiEvaluation evaluation;

        private Captured(Plugin owner, NiActionContext action, NiEvaluation evaluation) {
            this.owner = owner;
            this.action = action;
            this.evaluation = evaluation;
        }

        private <T> T invoke(boolean cancellationCleanup, Supplier<T> operation) {
            synchronized (scripts) {
                if (!scripts.isOpen()
                        || !cancellationCleanup
                                && (!tasks.active()
                                        || !owner.isEnabled()
                                        || action != null && !action.active())) {
                    throw new CancellationException(
                            "Script scheduling revision is no longer active");
                }
                return enter(operation);
            }
        }

        private <T> T enter(Supplier<T> operation) {
            Supplier<T> evaluated =
                    evaluation == null ? operation : () -> evaluation.scoped(operation);
            if (action == null) return evaluated.get();
            boolean previousSync = action.isSync();
            action.setSync(Bukkit.isPrimaryThread());
            try {
                return action.invoke(evaluated);
            } finally {
                action.setSync(previousSync);
            }
        }

        private <T> T settle(Supplier<T> operation) {
            synchronized (scripts) {
                return scripts.isOpen() ? enter(operation) : operation.get();
            }
        }

        private <T> T observe(Throwable failure, Supplier<T> operation) {
            return invoke(unwrap(failure) instanceof CancellationException, operation);
        }
    }

    private final class GuardedFuture<T> extends CompletableFuture<T> {
        private final Captured scope;
        private volatile CompletableFuture<?> scheduled;

        private GuardedFuture(Captured scope) {
            this.scope = scope;
            tasks.track(this);
        }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new GuardedFuture<>(scope);
        }

        @Override
        public boolean complete(T value) {
            return scope.settle(() -> super.complete(value));
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            return scope.settle(() -> super.completeExceptionally(failure));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean changed = scope.settle(() -> super.cancel(mayInterruptIfRunning));
            CompletableFuture<?> linked = scheduled;
            if (linked != null && !linked.isDone()) linked.cancel(mayInterruptIfRunning);
            return changed;
        }

        private void guardWait() {
            if (!isDone() && (Bukkit.isPrimaryThread() || Thread.holdsLock(scripts))) {
                throw new IllegalStateException(
                        "Cannot wait for an unfinished script future on the main thread or while holding the script lock");
            }
        }

        @Override
        public T join() {
            guardWait();
            return super.join();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            guardWait();
            return super.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            Objects.requireNonNull(unit, "unit");
            if (timeout > 0) guardWait();
            return super.get(timeout, unit);
        }

        @Override
        public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return super.thenApply(value -> scope.invoke(false, () -> mapper.apply(value)));
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return super.thenApplyAsync(value -> scope.invoke(false, () -> mapper.apply(value)));
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(
                Function<? super T, ? extends U> mapper, Executor executor) {
            Objects.requireNonNull(mapper, "mapper");
            return super.thenApplyAsync(
                    value -> scope.invoke(false, () -> mapper.apply(value)), executor);
        }

        @Override
        public CompletableFuture<Void> thenAccept(Consumer<? super T> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            return super.thenAccept(
                    value ->
                            scope.invoke(
                                    false,
                                    () -> {
                                        consumer.accept(value);
                                        return null;
                                    }));
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            return super.thenAcceptAsync(
                    value ->
                            scope.invoke(
                                    false,
                                    () -> {
                                        consumer.accept(value);
                                        return null;
                                    }));
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(
                Consumer<? super T> consumer, Executor executor) {
            Objects.requireNonNull(consumer, "consumer");
            return super.thenAcceptAsync(
                    value ->
                            scope.invoke(
                                    false,
                                    () -> {
                                        consumer.accept(value);
                                        return null;
                                    }),
                    executor);
        }

        @Override
        public CompletableFuture<Void> thenRun(Runnable operation) {
            Objects.requireNonNull(operation, "operation");
            return super.thenRun(
                    () ->
                            scope.invoke(
                                    false,
                                    () -> {
                                        operation.run();
                                        return null;
                                    }));
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(Runnable operation) {
            Objects.requireNonNull(operation, "operation");
            return super.thenRunAsync(
                    () ->
                            scope.invoke(
                                    false,
                                    () -> {
                                        operation.run();
                                        return null;
                                    }));
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(Runnable operation, Executor executor) {
            Objects.requireNonNull(operation, "operation");
            return super.thenRunAsync(
                    () ->
                            scope.invoke(
                                    false,
                                    () -> {
                                        operation.run();
                                        return null;
                                    }),
                    executor);
        }

        @Override
        public <U> CompletableFuture<U> thenCompose(
                Function<? super T, ? extends CompletionStage<U>> composer) {
            Objects.requireNonNull(composer, "composer");
            return super.thenCompose(value -> scope.invoke(false, () -> composer.apply(value)));
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(
                Function<? super T, ? extends CompletionStage<U>> composer) {
            Objects.requireNonNull(composer, "composer");
            return super.thenComposeAsync(
                    value -> scope.invoke(false, () -> composer.apply(value)));
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(
                Function<? super T, ? extends CompletionStage<U>> composer, Executor executor) {
            Objects.requireNonNull(composer, "composer");
            return super.thenComposeAsync(
                    value -> scope.invoke(false, () -> composer.apply(value)), executor);
        }

        @Override
        public CompletableFuture<T> whenComplete(
                BiConsumer<? super T, ? super Throwable> observer) {
            Objects.requireNonNull(observer, "observer");
            return super.whenComplete(
                    (value, failure) ->
                            scope.observe(
                                    failure,
                                    () -> {
                                        observer.accept(value, unwrap(failure));
                                        return null;
                                    }));
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(
                BiConsumer<? super T, ? super Throwable> observer) {
            Objects.requireNonNull(observer, "observer");
            return super.whenCompleteAsync(
                    (value, failure) ->
                            scope.observe(
                                    failure,
                                    () -> {
                                        observer.accept(value, unwrap(failure));
                                        return null;
                                    }));
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(
                BiConsumer<? super T, ? super Throwable> observer, Executor executor) {
            Objects.requireNonNull(observer, "observer");
            return super.whenCompleteAsync(
                    (value, failure) ->
                            scope.observe(
                                    failure,
                                    () -> {
                                        observer.accept(value, unwrap(failure));
                                        return null;
                                    }),
                    executor);
        }

        @Override
        public <U> CompletableFuture<U> handle(
                BiFunction<? super T, Throwable, ? extends U> handler) {
            Objects.requireNonNull(handler, "handler");
            return super.handle(
                    (value, failure) ->
                            scope.observe(failure, () -> handler.apply(value, unwrap(failure))));
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(
                BiFunction<? super T, Throwable, ? extends U> handler) {
            Objects.requireNonNull(handler, "handler");
            return super.handleAsync(
                    (value, failure) ->
                            scope.observe(failure, () -> handler.apply(value, unwrap(failure))));
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(
                BiFunction<? super T, Throwable, ? extends U> handler, Executor executor) {
            Objects.requireNonNull(handler, "handler");
            return super.handleAsync(
                    (value, failure) ->
                            scope.observe(failure, () -> handler.apply(value, unwrap(failure))),
                    executor);
        }

        @Override
        public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> recovery) {
            Objects.requireNonNull(recovery, "recovery");
            return super.exceptionally(
                    failure -> scope.observe(failure, () -> recovery.apply(unwrap(failure))));
        }

        @Override
        public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> recovery) {
            Objects.requireNonNull(recovery, "recovery");
            return super.exceptionallyAsync(
                    failure -> scope.observe(failure, () -> recovery.apply(unwrap(failure))));
        }

        @Override
        public CompletableFuture<T> exceptionallyAsync(
                Function<Throwable, ? extends T> recovery, Executor executor) {
            Objects.requireNonNull(recovery, "recovery");
            return super.exceptionallyAsync(
                    failure -> scope.observe(failure, () -> recovery.apply(unwrap(failure))),
                    executor);
        }
    }
}

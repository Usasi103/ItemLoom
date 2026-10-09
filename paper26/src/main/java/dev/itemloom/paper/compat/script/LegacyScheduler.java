package dev.itemloom.paper.compat.script;

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
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.paper.action.ActionTasks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Script-only SchedulerUtils adapter; all work remains owned by its catalog revision. */
public final class LegacyScheduler {
    private final JavaPlugin plugin;
    private final ActionTasks tasks;
    private final NiScripts scripts;

    public LegacyScheduler(JavaPlugin plugin, ActionTasks tasks, NiScripts scripts) {
        this.plugin = Objects.requireNonNull(plugin);
        this.tasks = Objects.requireNonNull(tasks);
        this.scripts = Objects.requireNonNull(scripts);
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
        dispatch(owner, 0, false, true, task);
    }

    public void async(Runnable task) {
        async(plugin, task);
    }

    public void async(Plugin owner, Runnable task) {
        dispatch(owner, 0, true, false, task);
    }

    public void syncLater(long delay, Runnable task) {
        syncLater(plugin, delay, task);
    }

    public void syncLater(Plugin owner, long delay, Runnable task) {
        dispatch(owner, delay, false, false, task);
    }

    public void asyncLater(long delay, Runnable task) {
        asyncLater(plugin, delay, task);
    }

    public void asyncLater(Plugin owner, long delay, Runnable task) {
        dispatch(owner, delay, true, false, task);
    }

    public void syncTimer(long delay, long period, Runnable task) {
        syncTimer(plugin, delay, period, task);
    }

    public void syncTimer(Plugin owner, long delay, long period, Runnable task) {
        repeat(owner, delay, period, false, task);
    }

    public void asyncTimer(long delay, long period, Runnable task) {
        asyncTimer(plugin, delay, period, task);
    }

    public void asyncTimer(Plugin owner, long delay, long period, Runnable task) {
        repeat(owner, delay, period, true, task);
    }

    public void runLater(long delay, Runnable task) {
        runLater(plugin, delay, task);
    }

    public void runLater(Plugin owner, long delay, Runnable task) {
        dispatch(owner, delay, !Bukkit.isPrimaryThread(), false, task);
    }

    public <T> T syncAndGet(Callable<T> task) {
        return syncAndGet(plugin, task);
    }

    public <T> T syncAndGet(Plugin owner, Callable<T> task) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(task);
        if (!tasks.active() || !owner.isEnabled()) return null;
        // The main-thread callback needs this same monitor. Waiting while holding it would
        // deadlock both the script and the server; migrate this call to sync/callSyncMethod.
        if (!Bukkit.isPrimaryThread() && Thread.holdsLock(scripts))
            throw new IllegalStateException(
                    "SchedulerUtils.syncAndGet cannot wait from a running asynchronous script; use sync or callSyncMethod with a continuation");
        try {
            return callSyncMethod(owner, task).get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            warning(error);
        } catch (ExecutionException error) {
            warning(error.getCause());
        } catch (CancellationException ignored) {
            /* Closing a revision must wake waiting callers. */
        }
        return null;
    }

    public <T> CompletableFuture<T> callSyncMethod(Callable<T> task) {
        return callSyncMethod(plugin, task);
    }

    public <T> CompletableFuture<T> callSyncMethod(Plugin owner, Callable<T> task) {
        return callSync(owner, task, true);
    }

    /** Internal side-effect adapter: preserve an uncertain failure instead of reporting a null success. */
    public <T> CompletableFuture<T> callSyncStrict(Callable<T> task) {
        return callSync(plugin, task, false);
    }

    private <T> CompletableFuture<T> callSync(
            Plugin owner, Callable<T> task, boolean tolerateFailure) {
        Objects.requireNonNull(task);
        Callback context = capture(owner);
        GuardedFuture<T> result = new GuardedFuture<>(context);
        CompletableFuture<T> scheduled =
                tasks.call(
                        0,
                        false,
                        true,
                        owner::isEnabled,
                        () ->
                                context.invoke(
                                        () -> {
                                            try {
                                                return task.call();
                                            } catch (Exception error) {
                                                if (!tolerateFailure)
                                                    throw new CompletionException(error);
                                                warning(error);
                                                return null;
                                            }
                                        }));
        scheduled.whenComplete(
                (value, error) -> {
                    if (scheduled.isCancelled()) result.cancel(false);
                    else if (error == null) result.complete(value);
                    else result.completeExceptionally(error);
                });
        CompletableFuture.allOf(result)
                .whenComplete(
                        (value, error) -> {
                            if (result.isCancelled()) scheduled.cancel(false);
                        });
        return result;
    }

    private void dispatch(
            Plugin owner, long delay, boolean async, boolean immediate, Runnable task) {
        Objects.requireNonNull(task);
        Callback context = capture(owner);
        CompletableFuture<Void> scheduled =
                tasks.call(
                        delay,
                        async,
                        immediate,
                        owner::isEnabled,
                        () ->
                                context.invoke(
                                        () -> {
                                            task.run();
                                            return null;
                                        }));
        if (immediate && Bukkit.isPrimaryThread()) {
            // Preserve immediate sync() exceptions; scheduled callbacks report to the logger.
            if (!scheduled.isCancelled()) {
                try {
                    scheduled.join();
                } catch (CompletionException error) {
                    if (error.getCause() instanceof RuntimeException cause) throw cause;
                    if (error.getCause() instanceof Error cause) throw cause;
                    throw error;
                }
            }
        } else
            scheduled.whenComplete(
                    (value, error) -> {
                        if (error != null && !scheduled.isCancelled()) warning(error);
                    });
    }

    private void repeat(Plugin owner, long delay, long period, boolean async, Runnable task) {
        Objects.requireNonNull(task);
        Callback context = capture(owner);
        CompletableFuture<Void> lifetime =
                tasks.repeat(
                        delay,
                        period,
                        async,
                        owner::isEnabled,
                        () -> {
                            try {
                                context.invoke(
                                        () -> {
                                            task.run();
                                            return null;
                                        });
                            } catch (CancellationException stopped) {
                                throw stopped;
                            }
                            // Bukkit keeps a timer registered after a failing invocation; retain
                            // that behavior.
                            catch (Throwable error) {
                                warning(error);
                            }
                        });
        lifetime.whenComplete(
                (value, error) -> {
                    if (error != null && !lifetime.isCancelled()) warning(error);
                });
    }

    private Callback capture(Plugin owner) {
        Objects.requireNonNull(owner);
        NiActionContext action = NiActionContext.currentOrNull();
        NiEvaluation evaluation;
        try {
            evaluation = NiEvaluation.current();
        } catch (IllegalStateException absent) {
            evaluation = null;
        }
        if ((evaluation != null && evaluation.scripts() != scripts)
                || (action != null && action.evaluation().scripts() != scripts))
            throw new IllegalStateException(
                    "SchedulerUtils cannot capture a context from another script revision");
        return new Callback(owner, action, evaluation);
    }

    private final class Callback {
        private final Plugin owner;
        private final NiActionContext action;
        private final NiEvaluation evaluation;

        Callback(Plugin owner, NiActionContext action, NiEvaluation evaluation) {
            this.owner = owner;
            this.action = action;
            this.evaluation = evaluation;
        }

        <T> T invoke(Supplier<T> operation) {
            synchronized (scripts) {
                // Recheck after acquiring the engine monitor: reload may have cancelled a
                // task that was already waiting for the preceding callback to finish.
                if (!tasks.active()
                        || !owner.isEnabled()
                        || !scripts.isOpen()
                        || (action != null && !action.active()))
                    throw new CancellationException("Script scheduler revision is closed");
                return complete(operation);
            }
        }

        <T> T complete(Supplier<T> operation) {
            synchronized (scripts) {
                // A cancelled future still runs its already registered completion callbacks.
                // Restore their context before close clears this revision's engines, without
                // reopening task admission or executing the cancelled callable.
                Supplier<T> scoped =
                        evaluation == null ? operation : () -> evaluation.scoped(operation);
                if (action == null) return scoped.get();
                boolean previousSync = action.isSync();
                action.setSync(Bukkit.isPrimaryThread());
                try {
                    return action.invoke(scoped);
                } finally {
                    action.setSync(previousSync);
                }
            }
        }

        <T> T observe(Throwable error, Supplier<T> operation) {
            synchronized (scripts) {
                // Already-registered cancellation observers may clean up while tasks.close
                // is draining this revision. After scripts.close no user JS is entered.
                if (!scripts.isOpen())
                    throw new CancellationException("Script scheduler revision is closed");
                return cancelled(error) ? complete(operation) : invoke(operation);
            }
        }

        private boolean cancelled(Throwable error) {
            while ((error instanceof CompletionException || error instanceof ExecutionException)
                    && error.getCause() != null) error = error.getCause();
            return error instanceof CancellationException;
        }

        <T> CompletableFuture<T> track(CompletableFuture<T> future) {
            return tasks.track(future);
        }

        boolean holdsScriptLock() {
            return Thread.holdsLock(scripts);
        }
    }

    private void warning(Throwable error) {
        plugin.getLogger().log(Level.WARNING, "Legacy script scheduler callback failed", error);
    }

    /**
     * Common unary continuation methods retain script scope and revision ownership, including
     * registration after completion and explicit executors. Binary/either combinators, obtrude,
     * completeAsync and minimalCompletionStage remain raw Java interop, not script scheduling APIs.
     */
    private static final class GuardedFuture<T> extends CompletableFuture<T> {
        private final Callback context;

        GuardedFuture(Callback context) {
            this.context = context;
            context.track(this);
        }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new GuardedFuture<>(context);
        }

        @Override
        public boolean complete(T value) {
            return context.complete(() -> super.complete(value));
        }

        @Override
        public boolean completeExceptionally(Throwable error) {
            return context.complete(() -> super.completeExceptionally(error));
        }

        @Override
        public boolean cancel(boolean interrupt) {
            return context.complete(() -> super.cancel(interrupt));
        }

        private <A, R> Function<A, R> scoped(Function<? super A, ? extends R> function) {
            Objects.requireNonNull(function);
            return value -> context.invoke(() -> function.apply(value));
        }

        private Consumer<T> scoped(Consumer<? super T> consumer) {
            Objects.requireNonNull(consumer);
            return value ->
                    context.invoke(
                            () -> {
                                consumer.accept(value);
                                return null;
                            });
        }

        private Runnable scoped(Runnable operation) {
            Objects.requireNonNull(operation);
            return () ->
                    context.invoke(
                            () -> {
                                operation.run();
                                return null;
                            });
        }

        private BiConsumer<T, Throwable> observer(
                BiConsumer<? super T, ? super Throwable> observer) {
            Objects.requireNonNull(observer);
            return (value, error) ->
                    context.observe(
                            error,
                            () -> {
                                observer.accept(value, error);
                                return null;
                            });
        }

        private <U> BiFunction<T, Throwable, U> handler(
                BiFunction<? super T, Throwable, ? extends U> handler) {
            Objects.requireNonNull(handler);
            return (value, error) -> context.observe(error, () -> handler.apply(value, error));
        }

        private Function<Throwable, T> recovery(Function<Throwable, ? extends T> function) {
            Objects.requireNonNull(function);
            return error -> context.observe(error, () -> function.apply(error));
        }

        @Override
        public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> function) {
            return super.thenApply(scoped(function));
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> function) {
            return super.thenApplyAsync(scoped(function));
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(
                Function<? super T, ? extends U> function, Executor executor) {
            return super.thenApplyAsync(scoped(function), executor);
        }

        @Override
        public CompletableFuture<Void> thenAccept(Consumer<? super T> consumer) {
            return super.thenAccept(scoped(consumer));
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> consumer) {
            return super.thenAcceptAsync(scoped(consumer));
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(
                Consumer<? super T> consumer, Executor executor) {
            return super.thenAcceptAsync(scoped(consumer), executor);
        }

        @Override
        public CompletableFuture<Void> thenRun(Runnable operation) {
            return super.thenRun(scoped(operation));
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(Runnable operation) {
            return super.thenRunAsync(scoped(operation));
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(Runnable operation, Executor executor) {
            return super.thenRunAsync(scoped(operation), executor);
        }

        @Override
        public <U> CompletableFuture<U> thenCompose(
                Function<? super T, ? extends CompletionStage<U>> function) {
            return super.thenCompose(scoped(function));
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(
                Function<? super T, ? extends CompletionStage<U>> function) {
            return super.thenComposeAsync(scoped(function));
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(
                Function<? super T, ? extends CompletionStage<U>> function, Executor executor) {
            return super.thenComposeAsync(scoped(function), executor);
        }

        @Override
        public CompletableFuture<T> whenComplete(
                BiConsumer<? super T, ? super Throwable> observer) {
            return super.whenComplete(observer(observer));
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(
                BiConsumer<? super T, ? super Throwable> observer) {
            return super.whenCompleteAsync(observer(observer));
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(
                BiConsumer<? super T, ? super Throwable> observer, Executor executor) {
            return super.whenCompleteAsync(observer(observer), executor);
        }

        @Override
        public <U> CompletableFuture<U> handle(
                BiFunction<? super T, Throwable, ? extends U> handler) {
            return super.handle(handler(handler));
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(
                BiFunction<? super T, Throwable, ? extends U> handler) {
            return super.handleAsync(handler(handler));
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(
                BiFunction<? super T, Throwable, ? extends U> handler, Executor executor) {
            return super.handleAsync(handler(handler), executor);
        }

        @Override
        public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> function) {
            return super.exceptionally(recovery(function));
        }

        @Override
        public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> function) {
            return super.exceptionallyAsync(recovery(function));
        }

        @Override
        public CompletableFuture<T> exceptionallyAsync(
                Function<Throwable, ? extends T> function, Executor executor) {
            return super.exceptionallyAsync(recovery(function), executor);
        }

        private void checkWait() {
            if (!isDone() && (Bukkit.isPrimaryThread() || context.holdsScriptLock()))
                throw new IllegalStateException(
                        "Cannot wait for an unfinished SchedulerUtils callback on the server thread or while running a script; use a future continuation");
        }

        @Override
        public T join() {
            checkWait();
            return super.join();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            checkWait();
            return super.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            Objects.requireNonNull(unit);
            if (timeout > 0) checkWait();
            return super.get(timeout, unit);
        }
    }
}

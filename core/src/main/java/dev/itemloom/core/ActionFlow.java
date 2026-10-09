package dev.itemloom.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Source-neutral asynchronous control flow. Immediate completions do not recurse per step. */
public final class ActionFlow {
    public record Result(boolean stopped, String label, int priority) {
        public static final Result CONTINUE = new Result(false, null, 0);
        public static final Result STOP = new Result(true, null, 1);
    }

    @FunctionalInterface
    public interface Step<C> {
        CompletionStage<Result> run(C context);
    }

    private ActionFlow() {}

    public static <C> Step<C> constant(Result result) {
        return context -> CompletableFuture.completedFuture(result);
    }

    public static <C> Step<C> sequence(List<Step<C>> steps) {
        List<Step<C>> owned = List.copyOf(steps);
        return context -> {
            Iterator<Step<C>> iterator = owned.iterator();
            return new Run<>(context, () -> iterator.hasNext() ? iterator.next() : null).start();
        };
    }

    public static <C> Step<C> whileTrue(Predicate<C> condition, Step<C> body) {
        return context -> new Run<>(context, () -> condition.test(context) ? body : null).start();
    }

    public static <C> Step<C> label(String label, Step<C> body) {
        return context ->
                body.run(context)
                        .thenApply(
                                result ->
                                        result.stopped() && Objects.equals(label, result.label())
                                                ? Result.CONTINUE
                                                : result);
    }

    /** Start every selected branch, then retain the highest-priority result (first wins ties). */
    public static <C> Step<C> all(List<Step<C>> steps) {
        List<Step<C>> owned = List.copyOf(steps);
        return context -> {
            List<CompletableFuture<Result>> running = new ArrayList<>();
            for (Step<C> step : owned) {
                try {
                    running.add(step.run(context).toCompletableFuture());
                } catch (Throwable error) {
                    running.add(CompletableFuture.failedFuture(error));
                }
            }
            return CompletableFuture.allOf(running.toArray(CompletableFuture[]::new))
                    .thenApply(
                            ignored -> {
                                Result result = Result.CONTINUE;
                                for (CompletableFuture<Result> branch : running) {
                                    Result current =
                                            Objects.requireNonNull(
                                                    branch.join(), "Action returned no result");
                                    if (current.priority() > result.priority()) result = current;
                                }
                                return result;
                            });
        };
    }

    private static final class Run<C> {
        private final C context;
        private final Supplier<Step<C>> next;
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private final AtomicInteger work = new AtomicInteger();
        private volatile boolean waiting;
        private volatile Result previous = Result.CONTINUE;
        private volatile Throwable failure;

        Run(C context, Supplier<Step<C>> next) {
            this.context = context;
            this.next = next;
        }

        CompletableFuture<Result> start() {
            drain();
            return result;
        }

        private void drain() {
            if (work.getAndIncrement() != 0) return;
            int consumed = 1;
            do {
                while (!result.isDone() && !waiting) {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        break;
                    }
                    if (previous.stopped()) {
                        result.complete(previous);
                        break;
                    }
                    try {
                        Step<C> step = next.get();
                        if (step == null) {
                            result.complete(previous);
                            break;
                        }
                        waiting = true;
                        step.run(context)
                                .whenComplete(
                                        (value, error) -> {
                                            if (error != null) failure = error;
                                            else if (value == null)
                                                failure =
                                                        new IllegalStateException(
                                                                "Action returned no result");
                                            else previous = value;
                                            waiting = false;
                                            drain();
                                        });
                    } catch (Throwable error) {
                        failure = error;
                        waiting = false;
                    }
                }
                consumed = work.addAndGet(-consumed);
            } while (consumed != 0);
        }
    }
}

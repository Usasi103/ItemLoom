package dev.itemloom.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ActionFlow {
    private ActionFlow() {}

    public record Result(boolean stopped, String label, int priority) {
        public static final Result CONTINUE = new Result(false, null, 0);
        public static final Result STOP = new Result(true, null, 1);
    }

    @FunctionalInterface
    public interface Step<C> {
        CompletionStage<Result> run(C context);
    }

    public static <C> Step<C> constant(Result result) {
        Objects.requireNonNull(result, "result");
        return context -> CompletableFuture.completedFuture(result);
    }

    public static <C> Step<C> sequence(List<Step<C>> steps) {
        List<Step<C>> captured = List.copyOf(steps);
        return context -> {
            AtomicInteger next = new AtomicInteger();
            return new Continuation(
                            () -> {
                                int index = next.getAndIncrement();
                                return index < captured.size()
                                        ? Objects.requireNonNull(
                                                captured.get(index).run(context), "action stage")
                                        : null;
                            })
                    .start();
        };
    }

    public static <C> Step<C> whileTrue(Predicate<C> condition, Step<C> body) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(body, "body");
        return context ->
                new Continuation(
                                () ->
                                        condition.test(context)
                                                ? Objects.requireNonNull(
                                                        body.run(context), "action stage")
                                                : null)
                        .start();
    }

    public static <C> Step<C> label(String label, Step<C> body) {
        Objects.requireNonNull(body, "body");
        return context ->
                invoke(body, context)
                        .thenApply(
                                result -> {
                                    Objects.requireNonNull(result, "action result");
                                    return result.stopped() && Objects.equals(label, result.label())
                                            ? Result.CONTINUE
                                            : result;
                                });
    }

    public static <C> Step<C> all(List<Step<C>> steps) {
        List<Step<C>> captured = List.copyOf(steps);
        return context -> {
            List<CompletableFuture<Result>> branches = new ArrayList<>(captured.size());
            for (Step<C> step : captured) {
                branches.add(
                        invoke(step, context)
                                .thenApply(
                                        result -> Objects.requireNonNull(result, "action result"))
                                .toCompletableFuture());
            }
            return CompletableFuture.allOf(branches.toArray(CompletableFuture[]::new))
                    .thenApply(
                            ignored -> {
                                Result selected = Result.CONTINUE;
                                for (CompletableFuture<Result> branch : branches) {
                                    Result result = branch.join();
                                    if (result.priority() > selected.priority()) {
                                        selected = result;
                                    }
                                }
                                return selected;
                            });
        };
    }

    private static <C> CompletionStage<Result> invoke(Step<C> step, C context) {
        try {
            return Objects.requireNonNull(step.run(context), "action stage");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** A local trampoline preserves caller-thread execution without recursive completions. */
    private static final class Continuation {
        private final Supplier<CompletionStage<Result>> next;
        private final CompletableFuture<Result> completion = new CompletableFuture<>();
        private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        private final AtomicInteger draining = new AtomicInteger();

        private Continuation(Supplier<CompletionStage<Result>> next) {
            this.next = next;
        }

        private CompletionStage<Result> start() {
            enqueue(this::advance);
            return completion;
        }

        private void advance() {
            if (completion.isDone()) {
                return;
            }
            try {
                CompletionStage<Result> stage = next.get();
                if (stage == null) {
                    completion.complete(Result.CONTINUE);
                    return;
                }
                stage.whenComplete(
                        (result, failure) ->
                                enqueue(
                                        () -> {
                                            if (failure != null) {
                                                completion.completeExceptionally(failure);
                                            } else if (result == null) {
                                                completion.completeExceptionally(
                                                        new NullPointerException("action result"));
                                            } else if (result.stopped()) {
                                                completion.complete(result);
                                            } else {
                                                advance();
                                            }
                                        }));
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }

        private void enqueue(Runnable action) {
            pending.add(action);
            if (draining.getAndIncrement() != 0) {
                return;
            }
            int observed = 1;
            do {
                Runnable current;
                while ((current = pending.poll()) != null) {
                    current.run();
                }
                observed = draining.addAndGet(-observed);
            } while (observed != 0);
        }
    }
}

package dev.itemloom.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionFlowTest {
    @Test
    void longImmediateLoopsUseConstantJavaCallDepth() {
        AtomicInteger count = new AtomicInteger();
        var step =
                ActionFlow.<AtomicInteger>whileTrue(
                        value -> value.get() < 100_000,
                        value -> {
                            value.incrementAndGet();
                            return CompletableFuture.completedFuture(ActionFlow.Result.CONTINUE);
                        });
        assertEquals(ActionFlow.Result.CONTINUE, step.run(count).toCompletableFuture().join());
        assertEquals(100_000, count.get());
    }

    @Test
    void delayedResultsKeepSequenceOrderAndStopPropagation() {
        List<String> calls = new ArrayList<>();
        CompletableFuture<ActionFlow.Result> delayed = new CompletableFuture<>();
        var sequence =
                ActionFlow.<List<String>>sequence(
                        List.of(
                                value -> {
                                    value.add("first");
                                    return delayed;
                                },
                                value -> {
                                    value.add("never");
                                    return CompletableFuture.completedFuture(
                                            ActionFlow.Result.CONTINUE);
                                }));
        var future = sequence.run(calls).toCompletableFuture();
        assertEquals(List.of("first"), calls);
        assertFalse(future.isDone());
        delayed.complete(ActionFlow.Result.STOP);
        assertEquals(ActionFlow.Result.STOP, future.join());
        assertEquals(List.of("first"), calls);
    }

    @Test
    void labelsCatchOnlyTheirOwnStopsAndAllStartsEveryBranch() {
        List<String> calls = new ArrayList<>();
        var branches =
                ActionFlow.<List<String>>all(
                        List.of(
                                value -> {
                                    value.add("a");
                                    return CompletableFuture.completedFuture(
                                            new ActionFlow.Result(true, "outer", 3));
                                },
                                value -> {
                                    value.add("b");
                                    return CompletableFuture.completedFuture(
                                            ActionFlow.Result.STOP);
                                }));
        var result = ActionFlow.label("inner", branches).run(calls).toCompletableFuture().join();
        assertTrue(result.stopped());
        assertEquals("outer", result.label());
        assertEquals(List.of("a", "b"), calls);
        assertEquals(
                ActionFlow.Result.CONTINUE,
                ActionFlow.label("outer", branches).run(calls).toCompletableFuture().join());
    }

    @Test
    void synchronousAndAsynchronousErrorsCompleteTheReturnedFuture() {
        var sync =
                ActionFlow.<Object>sequence(
                        List.of(
                                context -> {
                                    throw new IllegalArgumentException("bad action");
                                }));
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> sync.run(null).toCompletableFuture().join());
        CompletableFuture<ActionFlow.Result> delayed = new CompletableFuture<>();
        var future =
                ActionFlow.<Object>sequence(List.of(context -> delayed))
                        .run(null)
                        .toCompletableFuture();
        delayed.completeExceptionally(new IllegalStateException("later"));
        assertThrows(java.util.concurrent.CompletionException.class, future::join);
        AtomicInteger started = new AtomicInteger();
        var all =
                ActionFlow.<Object>all(
                        List.of(
                                context -> {
                                    throw new IllegalArgumentException("first failed");
                                },
                                context -> {
                                    started.incrementAndGet();
                                    return CompletableFuture.completedFuture(
                                            ActionFlow.Result.CONTINUE);
                                }));
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> all.run(null).toCompletableFuture().join());
        assertEquals(1, started.get());
    }
}

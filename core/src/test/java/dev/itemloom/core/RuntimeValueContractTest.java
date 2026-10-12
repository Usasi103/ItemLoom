package dev.itemloom.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeValueContractTest {
    @Test
    void explicitSharedCachePreservesNullEntriesAndNoncachedEvaluation() {
        Map<String, String> cache = new HashMap<>();
        cache.put("missing", null);
        GenerationContext shared = GenerationContext.sharingRolls(cache, new Random(3));
        assertNull(
                shared.resolve(
                        "missing",
                        () -> {
                            throw new AssertionError("present null rerolled");
                        }));
        assertEquals("transient", shared.resolve("once", () -> "transient", false));
        assertFalse(cache.containsKey("once"));
        shared.resolve("saved", () -> "kept");
        assertEquals("kept", cache.get("saved"));
        Map<String, String> captured = shared.savedRolls();
        cache.put("saved", "edited");
        assertNull(captured.get("missing"));
        assertEquals("kept", captured.get("saved"));
        assertThrows(UnsupportedOperationException.class, () -> captured.clear());
    }

    @Test
    void identityAndGeneratedValuesRetainIndependentNullCapableRolls() {
        Map<String, String> rolls = new HashMap<>();
        rolls.put("empty", null);
        ItemIdentity identity = new ItemIdentity("item", rolls);
        ItemEngine.Generated<String> generated =
                new ItemEngine.Generated<>("item", 4, "value", rolls);
        rolls.clear();
        assertTrue(identity.rolls().containsKey("empty"));
        assertTrue(generated.savedRolls().containsKey("empty"));
        assertThrows(IllegalArgumentException.class, () -> new ItemIdentity(" ", Map.of()));
        assertThrows(
                NullPointerException.class,
                () -> new ItemEngine.Generated<>("item", 4, null, Map.of()));
    }

    @Test
    void contextKeysAreIdentityBasedAndTypeChecked() {
        GenerationContext context = new GenerationContext(Map.of(), new Random(0));
        GenerationContext.Key<String> first = new GenerationContext.Key<>(String.class);
        GenerationContext.Key<String> second = new GenerationContext.Key<>(String.class);
        context.put(first, "one");
        assertEquals("one", context.get(first));
        assertNull(context.get(second));
        assertThrows(ClassCastException.class, () -> putRaw(context, first, 12));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putRaw(GenerationContext context, GenerationContext.Key key, Object value) {
        context.put(key, value);
    }

    @Test
    void allWaitsForEveryBranchEvenAfterOneFails() {
        CompletableFuture<ActionFlow.Result> later = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();
        var result =
                ActionFlow.<Object>all(
                                List.of(
                                        context ->
                                                CompletableFuture.failedFuture(
                                                        new IllegalArgumentException("first")),
                                        context -> {
                                            started.incrementAndGet();
                                            return later;
                                        }))
                        .run(null)
                        .toCompletableFuture();
        assertEquals(1, started.get());
        assertFalse(result.isDone());
        later.complete(ActionFlow.Result.CONTINUE);
        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void nonpositiveBranchPrioritiesKeepTheDefaultContinuation() {
        var result =
                ActionFlow.<Object>all(
                                List.of(
                                        ActionFlow.constant(
                                                new ActionFlow.Result(true, "negative", -1)),
                                        ActionFlow.constant(
                                                new ActionFlow.Result(true, "zero", 0))))
                        .run(null)
                        .toCompletableFuture()
                        .join();
        assertEquals(ActionFlow.Result.CONTINUE, result);
    }

    @Test
    void equalPrioritiesSelectTheFirstBranchInDeclarationOrder() {
        CompletableFuture<ActionFlow.Result> first = new CompletableFuture<>();
        ActionFlow.Result expected = new ActionFlow.Result(true, "first", 8);
        var future =
                ActionFlow.<Object>all(
                                List.of(
                                        context -> first,
                                        ActionFlow.constant(
                                                new ActionFlow.Result(true, "second", 8))))
                        .run(null)
                        .toCompletableFuture();
        first.complete(expected);
        assertEquals(expected, future.join());
    }

    @Test
    void nullStagesAndResultsAreFailuresRatherThanSilentEndOfSequence() {
        var stage =
                ActionFlow.<Object>sequence(List.of(context -> null))
                        .run(null)
                        .toCompletableFuture();
        assertThrows(CompletionException.class, stage::join);
        AtomicInteger started = new AtomicInteger();
        var all =
                ActionFlow.<Object>all(
                                List.of(
                                        context -> CompletableFuture.completedFuture(null),
                                        context -> {
                                            started.incrementAndGet();
                                            return CompletableFuture.completedFuture(
                                                    ActionFlow.Result.CONTINUE);
                                        }))
                        .run(null)
                        .toCompletableFuture();
        assertEquals(1, started.get());
        assertThrows(CompletionException.class, all::join);
    }

    @Test
    void longSynchronousSequencesUseBoundedCallDepth() {
        AtomicInteger calls = new AtomicInteger();
        ActionFlow.Step<Object> step =
                context -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(ActionFlow.Result.CONTINUE);
                };
        var steps = java.util.Collections.nCopies(100_000, step);
        assertEquals(
                ActionFlow.Result.CONTINUE,
                ActionFlow.sequence(steps).run(null).toCompletableFuture().join());
        assertEquals(100_000, calls.get());
    }
}

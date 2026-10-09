package dev.itemloom.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemEngineTest {
    @Test
    void savedRollsSurviveWithoutRerollAndInputsAreIsolated() {
        Map<String, String> input = new HashMap<>(Map.of("damage", "17.5"));
        GenerationContext context = new GenerationContext(input, new Random(0));
        AtomicInteger calls = new AtomicInteger();
        assertEquals(
                "17.5",
                context.resolve(
                        "damage",
                        () -> {
                            calls.incrementAndGet();
                            return "1";
                        }));
        assertEquals(
                "new",
                context.resolve(
                        "quality",
                        () -> {
                            calls.incrementAndGet();
                            return "new";
                        }));
        assertEquals(
                "new",
                context.resolve(
                        "quality",
                        () -> {
                            calls.incrementAndGet();
                            return "wrong";
                        }));
        assertEquals(1, calls.get());
        assertEquals(Map.of("damage", "17.5"), input);
        Map<String, String> result = context.savedRolls();
        context.rolls().put("quality", "later");
        assertEquals("new", result.get("quality"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("x", "x"));
    }

    @Test
    void recursiveDefinitionHasPathAndDoesNotPoisonContext() {
        GenerationContext context = new GenerationContext(Map.of(), new Random(0));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                context.resolve(
                                        "a",
                                        () ->
                                                context.resolve(
                                                        "b",
                                                        () ->
                                                                context.resolve(
                                                                        "a", () -> "invalid"))));
        assertTrue(error.getMessage().contains("a -> b -> a"));
        assertEquals("ok", context.resolve("a", () -> "ok"));
    }

    @Test
    void reloadDuringGenerationRetainsCapturedRevision() {
        ItemEngine<String> engine = new ItemEngine<>();
        engine.install(
                new ItemEngine.Catalog<>(
                        1,
                        Map.of(
                                "sword",
                                context -> {
                                    engine.install(
                                            new ItemEngine.Catalog<>(
                                                    2, Map.of("sword", later -> "new")));
                                    return "old";
                                })));
        var first = engine.generate("sword", new GenerationContext(Map.of(), new Random(0)));
        assertEquals(1, first.revision());
        assertEquals("old", first.item());
        var second = engine.generate("sword", new GenerationContext(Map.of(), new Random(0)));
        assertEquals(2, second.revision());
        assertEquals("new", second.item());
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.install(new ItemEngine.Catalog<>(1, Map.of())));
        assertEquals(2, engine.catalog().revision());
    }

    @Test
    void candidateCannotBeMutatedAfterValidation() {
        ItemEngine<String> engine = new ItemEngine<>();
        Map<String, ItemRecipe<String>> recipes = new HashMap<>();
        recipes.put("sword", context -> "item");
        var candidate = new ItemEngine.Catalog<>(1, recipes);
        recipes.clear();
        engine.install(candidate);
        assertEquals(
                "item",
                engine.generate("sword", new GenerationContext(Map.of(), new Random(0))).item());
        assertThrows(UnsupportedOperationException.class, () -> engine.ids().clear());
    }
}

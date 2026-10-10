package dev.itemloom.compat.ni.action;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.core.GenerationContext;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class NiActionContextReplacementTest {
    private static NiEvaluation evaluation(NiScripts scripts) {
        return new NiEvaluation(
                new GenerationContext(Map.of(), new Random(3)),
                null,
                null,
                NiEvaluation.Mode.ACTION,
                new NiNodes(),
                scripts,
                null);
    }

    @Test
    void sharedKeysAndGlobalsDoNotMergePerCopyEvaluationCachesOrThreadFlags() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            Object caster = new Object();
            Map<String, Object> global = new HashMap<>(Map.of("choice", "global"));
            Map<String, Object> params = new HashMap<>();
            AtomicBoolean enabled = new AtomicBoolean(true);
            var original =
                    new NiActionContext(evaluation(scripts), caster, global, params, enabled::get);
            original.setSync(false);
            var copy = original.clone();
            assertTrue(copy.isSync());
            assertFalse(original.isSync());
            assertSame(caster, copy.getCaster());
            assertSame(global, copy.getGlobal());
            assertSame(params, copy.getParams());

            Map<String, Object> cache = new HashMap<>(Map.of("choice", "copy"));
            copy.set(NiContextKeys.SECTION_CACHE, cache);
            assertSame(cache, original.getSectionCache());
            assertSame(cache, copy.evaluation().cache());
            assertSame(global, original.evaluation().cache());
            assertEquals("copy", copy.parse("<choice>"));
            assertEquals("global", original.parse("<choice>"));

            original.remove(NiContextKeys.SECTION_CACHE);
            assertSame(global, copy.getSectionCache());
            assertSame(cache, copy.evaluation().cache());
            copy.set(NiContextKeys.SECTION_CACHE, null);
            assertTrue(original.has(NiContextKeys.SECTION_CACHE));
            assertSame(global, copy.evaluation().cache());
            enabled.set(false);
            assertFalse(original.active());
            assertFalse(copy.active());
        }
    }

    @Test
    void aliasCollisionsReapplyIdenticalValuesAndRemoveNullEntriesWithoutRestoration() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var context = new NiActionContext(evaluation(scripts), null, null, scripts::isOpen);
            var first = new NiContextKey<Object>(true, "sharedAlias");
            var second = new NiContextKey<Object>(true, "sharedAlias");
            Object initial = new Object();
            context.set(first, initial);
            context.set(second, "second");
            context.set(first, initial);
            assertSame(initial, context.getBindings().get("sharedAlias"));
            assertSame(initial, context.getGlobal().get("sharedAlias"));
            assertEquals("second", context.get(second));
            context.set(first, null);
            assertTrue(context.has(first));
            assertTrue(context.getBindings().containsKey("sharedAlias"));
            assertTrue(context.getGlobal().containsKey("sharedAlias"));
            assertNull(context.remove(first));
            assertFalse(context.has(first));
            assertTrue(context.has(second));
            assertFalse(context.getBindings().containsKey("sharedAlias"));
            assertFalse(context.getGlobal().containsKey("sharedAlias"));
            assertFalse(context.has(new NiContextKey<>("sharedAlias")));
        }
    }

    @Test
    void nestedFailuresRestoreBothEvaluationAndAbsentOrNullContextBinding() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var parent = new NiActionContext(evaluation(scripts), null, null, scripts::isOpen);
            var child = parent.clone();
            child.set(NiContextKeys.SECTION_CACHE, new HashMap<>());
            Map<String, Object> bindings = parent.getBindings();
            bindings.remove("context");
            IllegalArgumentException failure = new IllegalArgumentException("expected");
            parent.invoke(
                    () -> {
                        assertSame(parent, NiActionContext.currentOrNull());
                        assertSame(parent.evaluation(), NiEvaluation.current());
                        assertSame(
                                failure,
                                assertThrows(
                                        IllegalArgumentException.class,
                                        () ->
                                                child.invoke(
                                                        () -> {
                                                            assertSame(
                                                                    child,
                                                                    NiActionContext
                                                                            .currentOrNull());
                                                            assertSame(
                                                                    child, bindings.get("context"));
                                                            assertSame(
                                                                    child.evaluation(),
                                                                    NiEvaluation.current());
                                                            throw failure;
                                                        })));
                        assertSame(parent, bindings.get("context"));
                        assertSame(parent, NiActionContext.currentOrNull());
                        assertSame(parent.evaluation(), NiEvaluation.current());
                        return null;
                    });
            assertFalse(bindings.containsKey("context"));
            assertNull(NiActionContext.currentOrNull());
            assertThrows(IllegalStateException.class, NiEvaluation::current);

            bindings.put("context", null);
            assertSame(child, child.invoke(() -> bindings.get("context")));
            assertTrue(bindings.containsKey("context"));
            assertNull(bindings.get("context"));
        }
    }

    @Test
    void lazyAliasesOverrideInitializersAndRefreshRetainsRemovedOrNullParameters() {
        AtomicInteger initialized = new AtomicInteger();
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            scripts.setActionInitializer(
                    engine -> {
                        initialized.incrementAndGet();
                        engine.put("removed", "helper");
                        engine.put("alias", "helper");
                    });
            Map<String, Object> params = new HashMap<>(Map.of("param", "snapshot"));
            var context = new NiActionContext(evaluation(scripts), null, params, scripts::isOpen);
            params.put("param", "unrefreshed");
            context.set(new NiContextKey<>("alias"), null);
            context.remove(new NiContextKey<>("removed"));
            assertEquals("plain", context.invoke(() -> context.clone().parse("plain")));
            assertNull(context.getEvent());
            assertNull(context.getItemStack());
            assertNull(context.getNbt());
            assertNull(context.getData());
            assertEquals(0, initialized.get());
            assertEquals(
                    "snapshot/null/undefined",
                    context.evaluate("param + '/' + alias + '/' + typeof removed"));
            assertEquals(1, initialized.get());
            context.refreshParams();
            params.put("param", null);
            context.refreshParams();
            params.remove("param");
            context.refreshParams();
            assertEquals("unrefreshed", context.evaluate("param"));
            assertFalse(context.getGlobal().containsKey("param"));
            assertSame(context.getBindings(), context.clone().getBindings());
            assertEquals(1, initialized.get());
        }
    }

    @Test
    void builderUsesCurrentRevisionAndRetainsInputsWhileReadingLiveSections() {
        NiScripts scripts = new NiScripts(Map.of(), Map.of());
        try (scripts) {
            Map<String, Object> global = new HashMap<>();
            Map<String, Object> params = new HashMap<>();
            Map<String, Object> sections = new HashMap<>(Map.of("entry", "first"));
            Object caster = new Object();
            var key = new NiContextKey<Object>(true, "nullable");
            NiActionContext context =
                    evaluation(scripts)
                            .scoped(
                                    () ->
                                            NiActionContext.builder()
                                                    .caster(caster)
                                                    .global(global)
                                                    .params(params)
                                                    .with(key, null)
                                                    .with(NiContextKeys.SECTIONS, sections)
                                                    .build());
            assertSame(caster, context.getCaster());
            assertSame(caster, context.evaluation().player());
            assertSame(global, context.getGlobal());
            assertSame(params, context.getParams());
            assertTrue(context.has(key));
            assertTrue(global.containsKey("nullable"));
            assertEquals("first", context.parse("<entry>"));
            global.remove("entry");
            sections.put("entry", "changed");
            assertEquals("changed", context.parse("<entry>"));
            assertSame(context, context.getBindings().get("context"));
            scripts.close();
            assertFalse(context.active());
            assertThrows(IllegalStateException.class, () -> context.evaluate("true"));
            assertThrows(
                    IllegalStateException.class,
                    () -> new NiActionContext(evaluation(scripts), null, null, () -> true));
        }
    }
}

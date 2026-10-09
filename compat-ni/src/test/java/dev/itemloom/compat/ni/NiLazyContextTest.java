package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiContextKey;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.core.GenerationContext;
import org.junit.jupiter.api.Test;

class NiLazyContextTest {
    private static NiActionContext context(NiScripts scripts, Map<String, Object> params) {
        return new NiActionContext(
                new NiEvaluation(
                        new GenerationContext(Map.of(), new Random(0)),
                        null,
                        null,
                        NiEvaluation.Mode.ACTION,
                        new NiNodes(),
                        scripts,
                        null),
                null,
                params,
                scripts::isOpen);
    }

    @Test
    void plainParsingAndMetadataDoNotInitializeJs() {
        AtomicInteger initializations = new AtomicInteger();
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            scripts.setActionInitializer(engine -> initializations.incrementAndGet());
            var call = context(scripts, Map.of());
            call.set(NiContextKeys.SECTION_CACHE, new HashMap<>(Map.of("value", "yes")));
            assertEquals("plain/yes", call.parse("plain/<value>"));
            call.set(new NiContextKey<>("custom"), "value");
            call.refreshParams();
            assertEquals(0, initializations.get());
            assertEquals("value", call.evaluate("custom"));
            assertEquals(1, initializations.get());
            assertEquals("value", call.getBindings().get("custom"));
            assertEquals(1, initializations.get());
        }
    }

    @Test
    void pendingEditsKeepPostInitializerOrderingAndParameterSnapshot() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            scripts.setActionInitializer(
                    engine -> {
                        engine.put("shadow", "initializer");
                        engine.put("removed", "initializer");
                    });
            var params = new HashMap<String, Object>();
            params.put("param", "old");
            var call = context(scripts, params);
            params.put("param", "new");
            var shadow = new NiContextKey<String>("shadow");
            call.set(shadow, "edit");
            call.remove(new NiContextKey<>("removed"));
            assertEquals(
                    "old/edit/undefined",
                    call.evaluate("param + '/' + shadow + '/' + typeof removed"));
            call.refreshParams();
            assertEquals("new", call.evaluate("param"));
            params.put("param", null);
            call.refreshParams();
            assertEquals("new", call.evaluate("param"));
        }
    }

    @Test
    void clonesShareOneScopeAndNestedInvocationRestoresContext() {
        AtomicInteger initializations = new AtomicInteger();
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            scripts.setActionInitializer(engine -> initializations.incrementAndGet());
            var original = context(scripts, Map.of());
            var clone = original.clone();
            assertSame(clone, original.invoke(() -> clone.evaluate("context")));
            assertSame(original, original.getBindings().get("context"));
            original.evaluate("var counter = 3");
            assertEquals(4, ((Number) clone.evaluate("++counter")).intValue());
            assertSame(original.getBindings(), clone.getBindings());
            assertEquals(1, initializations.get());
            var separate = context(scripts, Map.of());
            assertEquals("undefined", separate.evaluate("typeof counter"));
            assertEquals(2, initializations.get());
        }
    }

    @Test
    void closedRevisionCannotInitializePendingScopeOrConstructNewContext() {
        NiScripts scripts = new NiScripts(Map.of(), Map.of());
        var pending = context(scripts, Map.of());
        scripts.close();
        assertThrows(IllegalStateException.class, pending::getBindings);
        assertThrows(IllegalStateException.class, () -> pending.evaluate("true"));
        assertThrows(IllegalStateException.class, () -> context(scripts, Map.of()));
    }
}

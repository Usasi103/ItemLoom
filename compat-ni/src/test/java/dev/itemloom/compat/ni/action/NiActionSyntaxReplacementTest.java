package dev.itemloom.compat.ni.action;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.compat.ni.NiConfig;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class NiActionSyntaxReplacementTest {
    @Test
    void explicitTypesTakePriorityAndUseRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var config = new NiConfig(Map.of("type", "INT-TREE", "condition", "true", "repeat", 2));
            var branch =
                    assertInstanceOf(NiActionSyntax.Branch.class, NiActionSyntax.classify(config));
            assertEquals(NiActionSyntax.Kind.INT_TREE, branch.kind());
            assertSame(config, branch.config());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void inferenceOrderIsIndependentOfInsertionOrderAndUnknownType() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("label", "outer");
        config.put("while", "true");
        config.put("repeat", 3);
        config.put("condition", "false");
        config.put("type", "extension-or-typo");
        for (var expected :
                List.of(
                        NiActionSyntax.Kind.CONDITION,
                        NiActionSyntax.Kind.REPEAT,
                        NiActionSyntax.Kind.WHILE,
                        NiActionSyntax.Kind.LABEL)) {
            assertEquals(
                    expected,
                    assertInstanceOf(NiActionSyntax.Branch.class, NiActionSyntax.classify(config))
                            .kind());
            config.remove(expected.name().toLowerCase(Locale.ROOT));
        }
        assertEquals(
                new NiActionSyntax.Text("type: extension-or-typo"),
                NiActionSyntax.classify(config));
    }

    @Test
    void singletonFallbackKeepsNestedDispatchBoundaryAndCaseSensitiveKeys() {
        var nested =
                assertInstanceOf(
                        NiActionSyntax.Nested.class,
                        NiActionSyntax.classify(Map.of("actions", Arrays.asList("tell: a", null))));
        assertEquals(Arrays.asList("tell: a", null), nested.value());
        assertEquals(
                new NiActionSyntax.Text("ACTIONS: tell: b"),
                NiActionSyntax.classify(Map.of("ACTIONS", "tell: b")));
        assertEquals(
                new NiActionSyntax.Text("tell: value"),
                NiActionSyntax.classify(Map.of("tell", "value")));
        assertSame(NiActionSyntax.Empty.INSTANCE, NiActionSyntax.classify(Map.of("tell", 3)));
        assertSame(
                NiActionSyntax.Empty.INSTANCE, NiActionSyntax.classify(Map.of("a", "x", "b", "y")));
        assertSame(NiActionSyntax.Empty.INSTANCE, NiActionSyntax.classify(null));
        assertSame(NiActionSyntax.Empty.INSTANCE, NiActionSyntax.classify(4));
    }

    @Test
    void sequenceRetainsNullsAndBukkitConfigurationUsesTheSameGrammar() {
        List<Object> input =
                new ArrayList<>(Arrays.asList(null, "tell: value", Map.of("repeat", 2)));
        var sequence =
                assertInstanceOf(NiActionSyntax.Sequence.class, NiActionSyntax.classify(input));
        assertEquals(input, sequence.values());
        assertNull(sequence.values().getFirst());
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("while", "false");
        config.set("label", "outer");
        assertEquals(
                NiActionSyntax.Kind.WHILE,
                assertInstanceOf(NiActionSyntax.Branch.class, NiActionSyntax.classify(config))
                        .kind());
        assertThrows(
                IllegalArgumentException.class,
                () -> NiActionSyntax.classify(Map.of("actions", new Object())));
    }
}

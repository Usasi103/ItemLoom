package dev.itemloom.paper.compat.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class LegacyPercentTextTest {
    @Test
    void delimitersParametersAndRejectedCandidates() {
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("%x%", "<>");
        cases.put("%x_A_B%", "<A_B>");
        cases.put("%x_a b%", "<a b>");
        cases.put("%X_Q%", "<Q>");
        cases.put("%missing_q% %x_z%", "%missing_q% <z>");
        cases.put("%x_A%%x_B%", "<A><B>");
        cases.put("%x_A%x_B%", "<A>x_B%");
        for (var example : cases.entrySet()) {
            var result =
                    LegacyPercentText.parse(
                            example.getKey(),
                            (id, params) -> id.equals("x") ? "<" + params + ">" : null);
            assertEquals(example.getValue(), result.text(), example.getKey());
            assertTrue(result.changed());
        }
        for (String unchanged : List.of("plain", "%nil%", "%bad name%-%x_q%", "%x")) {
            var result =
                    LegacyPercentText.parse(
                            unchanged, (id, params) -> id.equals("x") ? "<" + params + ">" : null);
            assertSame(unchanged, result.text());
            assertFalse(result.changed());
        }
    }

    @Test
    void callbacksObserveOriginalOrderAndLiveLookupWithoutRescanningReplacements() {
        List<String> seen = new ArrayList<>();
        Map<String, Function<String, String>> expansions = new HashMap<>();
        expansions.put(
                "first",
                params -> {
                    expansions.put("later", next -> next);
                    return "%later_generated%";
                });
        var result =
                LegacyPercentText.parse(
                        "%first%/%later_A_B%",
                        (id, params) -> {
                            seen.add(id + ":" + params);
                            var expansion = expansions.get(id);
                            return expansion == null ? null : expansion.apply(params);
                        });
        assertEquals("%later_generated%/A_B", result.text());
        assertEquals(List.of("first:", "later:A_B"), seen);
    }

    @Test
    void casingFormattingEmptyIdentifiersAndNonSpaceWhitespace() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            List<String> identifiers = new ArrayList<>();
            LegacyPercentText.parse(
                    "%I% %\u00a7aX% %\u00a7\u00a7BX% %% %_a% %x\ty%",
                    (id, params) -> {
                        identifiers.add(id);
                        return "";
                    });
            assertEquals(List.of("\u0131", "x", "x", "", "", "x\ty"), identifiers);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void successfulNoOpAndEmptyReplacementsStillCountAndErrorsPropagate() {
        assertEquals(
                new LegacyPercentText.Result("%x%", true),
                LegacyPercentText.parse("%x%", (id, params) -> "%x%"));
        assertEquals(
                new LegacyPercentText.Result("", true),
                LegacyPercentText.parse("%x%", (id, params) -> ""));
        RuntimeException expected = new IllegalStateException("callback");
        assertSame(
                expected,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                LegacyPercentText.parse(
                                        "%x%",
                                        (id, params) -> {
                                            throw expected;
                                        })));
        assertThrows(
                NullPointerException.class,
                () -> LegacyPercentText.parse(null, (id, params) -> ""));
    }
}

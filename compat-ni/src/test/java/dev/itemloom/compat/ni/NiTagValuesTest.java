package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NiTagValuesTest {
    @Test
    void typedValuesPreserveExplicitNbtWidthsAndLiteralEscape() {
        assertInstanceOf(Byte.class, NiTagValues.decode("(Byte) 7"));
        assertInstanceOf(Short.class, NiTagValues.decode("(Short) 7"));
        assertInstanceOf(Integer.class, NiTagValues.decode("(Int) 7"));
        assertInstanceOf(Long.class, NiTagValues.decode("(Long) 7"));
        assertInstanceOf(Float.class, NiTagValues.decode("(Float) 7"));
        assertInstanceOf(Double.class, NiTagValues.decode("(Double) 7"));
        assertEquals("(Int) 7", NiTagValues.decode("(String) (Int) 7"));
        assertEquals("(Byte) 999", NiTagValues.decode("(Byte) 999"));
    }

    @Test
    void arraysDoNotSilentlyBecomeListsOrNarrowNumbers() {
        assertArrayEquals(new int[] {1, 2}, (int[]) NiTagValues.decode(List.of(1, 2)));
        assertArrayEquals(new byte[] {1, 2}, (byte[]) NiTagValues.decode("[(Byte) 1,(Byte) 2]"));
        assertEquals("[a,b]", NiTagValues.decode("[a,b]"));
        assertEquals(List.of("a", "b"), NiTagValues.decode(List.of("a", "b")));
        assertThrows(ClassCastException.class, () -> NiTagValues.decode(List.of(1, "wrong")));
    }

    @Test
    void nestedExternalDataIsKeptAlongsideTypedValues() {
        Map<?, ?> result =
                (Map<?, ?>)
                        NiTagValues.decode(
                                Map.of("external", Map.of("key", "value"), "stat", "(Double) 7.2"));
        assertEquals(Map.of("key", "value"), result.get("external"));
        assertEquals(7.2, result.get("stat"));
    }
}

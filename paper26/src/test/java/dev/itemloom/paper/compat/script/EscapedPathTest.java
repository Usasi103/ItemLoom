package dev.itemloom.paper.compat.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EscapedPathTest {
    @Test
    void quotedDelimitersEmptySegmentsAndLiteralEscapes() {
        assertEquals(List.of(""), split(""));
        assertEquals(List.of("a", "b"), split("a.b"));
        assertEquals(List.of("", "a", "", ""), split(".a.."));
        assertEquals(List.of("a.b", "c"), split("a\\.b.c"));
        assertEquals(List.of("a\\q", "b"), split("a\\q.b"));
        assertEquals(List.of("a\\", "b"), split("a\\\\.b"));
        assertEquals(List.of("a\\"), split("a\\"));
        assertEquals(List.of("\"a", "b\""), split("\"a.b\""));
    }

    @Test
    void callerChosenUtf16DelimitersAndCoincidentEscape() {
        assertEquals(List.of("a`b", "c"), EscapedPath.split("a\\`b`c", '`', '\\'));
        assertEquals(List.of("", "|"), EscapedPath.split("|", '|', '|'));
        assertEquals(List.of("", "|"), EscapedPath.split("||", '|', '|'));
        assertEquals(List.of("", "|", "|"), EscapedPath.split("|||", '|', '|'));
        assertEquals(
                List.of("\ud83d", "tail"), EscapedPath.split("\ud83d\ude00tail", '\ude00', '\\'));
        assertThrows(NullPointerException.class, () -> split(null));
    }

    @Test
    void callersReceiveIndependentCollections() {
        List<String> first = split("a.b");
        first.set(0, "changed");
        assertEquals(List.of("a", "b"), split("a.b"));
    }

    private static List<String> split(String text) {
        return EscapedPath.split(text, '.', '\\');
    }
}

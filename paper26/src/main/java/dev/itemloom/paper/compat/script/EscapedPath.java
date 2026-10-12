package dev.itemloom.paper.compat.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Splits legacy paths, preserving empty segments and unknown escape sequences. */
public final class EscapedPath {
    private EscapedPath() {}

    public static List<String> split(String text, char delimiter, char escape) {
        Objects.requireNonNull(text, "text");
        List<String> result = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == delimiter) {
                result.add(part.toString());
                part.setLength(0);
            }
            if (current == escape) {
                if (index + 1 < text.length()) {
                    char next = text.charAt(index + 1);
                    if (next == delimiter || next == escape) {
                        part.append(next);
                        index++;
                        continue;
                    }
                }
                part.append(current);
            } else if (current != delimiter) {
                part.append(current);
            }
        }
        result.add(part.toString());
        return result;
    }
}

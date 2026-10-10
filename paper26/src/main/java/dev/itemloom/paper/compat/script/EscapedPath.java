package dev.itemloom.paper.compat.script;

import java.util.ArrayList;
import java.util.List;

/** Literal UTF-16 path segments shared by legacy NBT entry points. */
public final class EscapedPath {
    private EscapedPath() {}

    public static List<String> split(String text, char separator, char escape) {
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        for (int position = 0; position < text.length(); position++) {
            char value = text.charAt(position);
            if (value == separator) {
                segments.add(segment.toString());
                segment.setLength(0);
                if (value != escape) continue;
                // When both characters coincide, this delimiter also begins an escape.
            }
            if (value == escape && position + 1 < text.length()) {
                char quoted = text.charAt(position + 1);
                if (quoted == separator || quoted == escape) {
                    segment.append(quoted);
                    position++;
                    continue;
                }
            }
            segment.append(value);
        }
        segments.add(segment.toString());
        return segments;
    }
}

package dev.keystone.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Line breaks of a user's file, which may mix CRLF and LF (an editor that saves CRLF over a file
 * Keystone wrote with LF, or lines pasted from elsewhere). Lines are split where {@link
 * String#lines()} splits them ({@code \r\n}, {@code \n}, {@code \r}), and each line keeps its own
 * break, so an edit can leave every other line's bytes alone.
 */
final class LineBreaks {

    static final String LF = "\n";
    static final String CRLF = "\r\n";
    static final String CR = "\r";

    private LineBreaks() {}

    /**
     * The lines of a text and the break after each: {@code lines} is exactly {@code
     * text.lines().toList()}; {@code breaks} holds {@code "\r\n"}, {@code "\n"} or {@code "\r"},
     * and {@code ""} for a last line without a break.
     */
    record Split(List<String> lines, List<String> breaks) {

        /** Whether the text ends with a line break (an empty text does not). */
        boolean endsWithBreak() {
            return !breaks.isEmpty() && !breaks.get(breaks.size() - 1).isEmpty();
        }
    }

    static Split split(String text) {
        List<String> lines = new ArrayList<>();
        List<String> breaks = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') {
                i++;
                continue;
            }
            lines.add(text.substring(start, i));
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                breaks.add(CRLF);
                i += 2;
            } else {
                breaks.add(c == '\r' ? CR : LF);
                i++;
            }
            start = i;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
            breaks.add("");
        }
        return new Split(lines, breaks);
    }

    /**
     * The break most lines of {@code text} end with, by count: LF, CRLF or (old Mac) CR. A tie
     * goes to LF, then CRLF; a text without any break gets LF, the break Keystone writes itself.
     */
    static String dominant(String text) {
        return dominant(split(text).breaks());
    }

    static String dominant(List<String> breaks) {
        int lf = 0;
        int crlf = 0;
        int cr = 0;
        for (String lineBreak : breaks) {
            switch (lineBreak) {
                case LF -> lf++;
                case CRLF -> crlf++;
                case CR -> cr++;
                default -> {}
            }
        }
        if (lf >= crlf && lf >= cr) {
            return LF;
        }
        return crlf >= cr ? CRLF : CR;
    }

    /**
     * The break for lines inserted before line {@code at} of {@code breaks}: the break of the line
     * above; with no line above, the break of the line below. A last line without a break takes
     * the break of the nearest line above it that has one. Without any break at all: LF.
     */
    static String neighbour(List<String> breaks, int at) {
        for (int i = Math.min(at, breaks.size()) - 1; i >= 0; i--) {
            if (!breaks.get(i).isEmpty()) {
                return breaks.get(i);
            }
        }
        for (int i = Math.max(at, 0); i < breaks.size(); i++) {
            if (!breaks.get(i).isEmpty()) {
                return breaks.get(i);
            }
        }
        return LF;
    }
}

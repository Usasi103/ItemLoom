package dev.itemloom.paper.compat.script;

import java.util.Locale;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Percent-token grammar for the script item expansion boundary. */
public final class LegacyPercentText {
    private static final Pattern FORMATTING = Pattern.compile("\u00a7+[a-z0-9]");

    private LegacyPercentText() {}

    public record Result(String text, boolean changed) {}

    /** The callback performs a live lookup; null means retain the original token. */
    public static Result parse(String text, BiFunction<String, String, String> expand) {
        StringBuilder output = new StringBuilder(text.length());
        int search = 0;
        int retainedFrom = 0;
        boolean changed = false;
        while (search < text.length()) {
            int opening = text.indexOf('%', search);
            if (opening < 0) break;
            int closing = text.indexOf('%', opening + 1);
            if (closing < 0) break;
            String candidate = text.substring(opening + 1, closing);
            int underscore = candidate.indexOf('_');
            int identifierEnd = underscore < 0 ? candidate.length() : underscore;
            int space = candidate.indexOf(' ');
            if (space >= 0 && space < identifierEnd) {
                search = closing;
                continue;
            }
            String identifier =
                    candidate.substring(0, identifierEnd).toLowerCase(Locale.getDefault());
            identifier = FORMATTING.matcher(identifier).replaceAll("");
            String parameters = underscore < 0 ? "" : candidate.substring(underscore + 1);
            String replacement = expand.apply(identifier, parameters);
            if (replacement != null) {
                output.append(text, retainedFrom, opening).append(replacement);
                retainedFrom = closing + 1;
                changed = true;
            }
            search = closing + 1;
        }
        if (!changed) return new Result(text, false);
        return new Result(output.append(text, retainedFrom, text.length()).toString(), true);
    }
}

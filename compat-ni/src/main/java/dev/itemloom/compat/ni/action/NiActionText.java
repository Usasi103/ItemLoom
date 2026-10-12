package dev.itemloom.compat.ni.action;

import java.util.function.Function;

/** Percent syntax is translated without consulting installed placeholder expansions. */
public final class NiActionText {
    private NiActionText() {}

    public static String placeholders(String input) {
        return replacePlaceholders(
                input, token -> "<papi::" + token + (token.indexOf('_') < 0 ? "_" : "") + ">");
    }

    public static String replacePlaceholders(String input, Function<String, String> resolver) {
        if (input == null) {
            return null;
        }
        StringBuilder output = new StringBuilder();
        int copied = 0;
        int opening = -1;
        boolean underscore = false;
        for (int position = 0; position < input.length(); position++) {
            char token = input.charAt(position);
            if (token == '%') {
                if (opening < 0) {
                    opening = position;
                    underscore = false;
                } else {
                    String replacement = resolver.apply(input.substring(opening + 1, position));
                    output.append(input, copied, opening);
                    output.append(
                            replacement == null
                                    ? input.substring(opening, position + 1)
                                    : replacement);
                    copied = position + 1;
                    opening = -1;
                }
            } else if (opening >= 0) {
                if (token == '_') {
                    underscore = true;
                } else if (token == ' ' && !underscore) {
                    opening = -1;
                }
            }
        }
        return output.append(input, copied, input.length()).toString();
    }
}

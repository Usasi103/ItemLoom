package dev.itemloom.compat.ni.action;

/** NI converts percent placeholders before parsing action nodes, including unknown expansions. */
public final class NiActionText {
    private NiActionText() {}

    public static String placeholders(String input) {
        return replacePlaceholders(
                input,
                token -> {
                    int split = token.indexOf('_');
                    return "<papi::" + (split < 0 ? token + '_' : token) + '>';
                });
    }

    public static String replacePlaceholders(
            String input, java.util.function.Function<String, String> resolver) {
        StringBuilder result = new StringBuilder(input.length());
        int position = 0;
        while (position < input.length()) {
            int open = input.indexOf('%', position);
            if (open < 0) {
                result.append(input, position, input.length());
                break;
            }
            result.append(input, position, open);
            int cursor = open + 1, split = -1;
            while (cursor < input.length()) {
                char next = input.charAt(cursor);
                if (next == '%' || next == ' ' && split < 0) break;
                if (next == '_' && split < 0) split = cursor;
                cursor++;
            }
            if (cursor < input.length() && input.charAt(cursor) == '%') {
                String value = resolver.apply(input.substring(open + 1, cursor));
                if (value == null) result.append(input, open, cursor + 1);
                else result.append(value);
                position = cursor + 1;
            } else {
                position = Math.min(input.length(), cursor + 1);
                result.append(input, open, position);
            }
        }
        return result.toString();
    }
}

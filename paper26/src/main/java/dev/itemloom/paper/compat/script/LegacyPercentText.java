package dev.itemloom.paper.compat.script;

import java.util.Locale;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

public final class LegacyPercentText {
    public record Result(String text, boolean changed) {}

    private static final Pattern COLORS = Pattern.compile("§+[a-z0-9]");

    private LegacyPercentText() {}

    public static Result parse(String text, BiFunction<String, String, String> expand) {
        StringBuilder result = new StringBuilder();
        int scan = 0, copied = 0;
        boolean changed = false;
        while (scan < text.length()) {
            int open = text.indexOf('%', scan);
            if (open < 0) break;
            int close = text.indexOf('%', open + 1);
            if (close < 0) break;
            String body = text.substring(open + 1, close);
            int underscore = body.indexOf('_');
            String id = underscore < 0 ? body : body.substring(0, underscore);
            if (id.indexOf(' ') >= 0) {
                scan = close;
                continue;
            }
            String parameters = underscore < 0 ? "" : body.substring(underscore + 1);
            id = COLORS.matcher(id.toLowerCase(Locale.getDefault())).replaceAll("");
            String replacement = expand.apply(id, parameters);
            result.append(text, copied, open);
            if (replacement == null) result.append(text, open, close + 1);
            else {
                result.append(replacement);
                changed = true;
            }
            copied = close + 1;
            scan = copied;
        }
        if (!changed) return new Result(text, false);
        return new Result(result.append(text, copied, text.length()).toString(), true);
    }
}

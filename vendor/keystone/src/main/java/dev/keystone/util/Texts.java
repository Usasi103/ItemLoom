package dev.keystone.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Legacy-text helpers shared by lang, menus and items. */
public final class Texts {

    private static final String CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";
    private static final Pattern HEX_BRACE = Pattern.compile("&\\{#([0-9A-Fa-f]{6})}");
    private static final Pattern HEX_PLAIN = Pattern.compile("&#([0-9A-Fa-f]{6})");

    /** A named placeholder argument: fills {@code {name}} in {@link #replaceWithOrder}. */
    public record Named(String name, Object value) {}

    private Texts() {}

    /** A {@code {name}} argument for {@link #replaceWithOrder}. */
    public static Named named(String name, Object value) {
        return new Named(name, value);
    }

    /**
     * {@code &} colour/format codes to {@code §}, plus {@code &#RRGGBB} and {@code &{#RRGGBB}} to
     * the {@code §x§R§R§G§G§B§B} form.
     */
    public static String colored(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        String result = replaceHex(HEX_BRACE, text);
        result = replaceHex(HEX_PLAIN, result);
        char[] chars = result.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && CODES.indexOf(chars[i + 1]) >= 0) {
                chars[i] = '§';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    /** {@link #colored} for every line. */
    public static List<String> colored(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(colored(line));
        }
        return result;
    }

    private static String replaceHex(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        do {
            StringBuilder hex = new StringBuilder("§x");
            for (char c : matcher.group(1).toCharArray()) {
                hex.append('§').append(Character.toLowerCase(c));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(hex.toString()));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    /** Legacy {@code &}/{@code §} text as an Adventure component (item names, titles, lore). */
    public static Component component(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(colored(text));
    }

    /**
     * Fills {@code {0}}, {@code {1}} ... with {@code args} by position, and {@code {name}} with the
     * first {@link Named} argument of that name. Placeholders without a matching argument stay as
     * written.
     *
     * <p>Same algorithm as TabooLib's {@code replaceWithOrder} (MIT), so existing lang text renders
     * identically.
     */
    public static String replaceWithOrder(String text, Object... args) {
        if (args == null || args.length == 0 || text.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        StringBuilder builder = new StringBuilder(text.length());
        int i = 0;
        while (i < chars.length) {
            int mark = i;
            if (chars[i] == '{') {
                int num = 0;
                StringBuilder alias = new StringBuilder();
                while (i + 1 < chars.length && chars[i + 1] != '}') {
                    i++;
                    if (Character.isDigit(chars[i]) && alias.isEmpty()) {
                        num = num * 10 + (chars[i] - '0');
                    } else {
                        alias.append(chars[i]);
                    }
                }
                if (i != mark && i + 1 < chars.length && chars[i + 1] == '}') {
                    i++;
                    if (!alias.isEmpty()) {
                        String name = alias.toString();
                        Object value = null;
                        boolean found = false;
                        for (Object arg : args) {
                            if (arg instanceof Named named && named.name().equals(name)) {
                                value = named.value();
                                found = true;
                                break;
                            }
                        }
                        builder.append(found ? String.valueOf(value) : "{" + name + "}");
                    } else {
                        builder.append(
                                num < args.length && args[num] != null
                                        ? String.valueOf(args[num])
                                        : "{" + num + "}");
                    }
                } else {
                    i = mark;
                }
            }
            if (mark == i) {
                builder.append(chars[i]);
            }
            i++;
        }
        return builder.toString();
    }
}

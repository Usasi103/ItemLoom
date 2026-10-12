package dev.itemloom.compat.sx;

import java.util.Map;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

public final class SxValueRules {
    private SxValueRules() {}

    public static Object scalar(String text) {
        if (text == null || !text.startsWith("[")) {
            return text;
        }
        int end = text.indexOf(']');
        if (end < 0) {
            return text;
        }
        String value = text.substring(end + 1);
        return switch (text.substring(1, end)) {
            case "byte" -> Byte.valueOf(value);
            case "short" -> Short.valueOf(value);
            case "int" -> Integer.valueOf(value);
            case "long" -> Long.valueOf(value);
            case "float" -> Float.valueOf(value);
            case "double" -> Double.valueOf(value);
            default -> text;
        };
    }

    public static String locked(
            String argument,
            Map<String, String> locks,
            Map<String, String> other,
            Function<String, String> randomKey,
            IntUnaryOperator choiceIndex,
            Function<String, String> expand) {
        int split = argument.indexOf('#');
        String key = split < 0 ? argument : argument.substring(0, split);
        String cached = locks.get(key);
        if (cached != null) {
            return cached;
        }
        String selected;
        if (split < 0) {
            selected = randomKey.apply(key);
        } else {
            selected = other.get(key);
            if (selected == null) {
                String[] choices = argument.substring(split + 1).split(":", -1);
                selected = choices[choiceIndex.applyAsInt(choices.length)];
            }
        }
        String result = expand.apply(selected);
        locks.put(key, result);
        return result;
    }
}

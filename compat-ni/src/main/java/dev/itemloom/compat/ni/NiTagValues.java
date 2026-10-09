package dev.itemloom.compat.ni;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** NI's typed numeric strings and array conventions, detached from a particular NBT library. */
public final class NiTagValues {
    private NiTagValues() {}

    public static Object decode(Object value) {
        if (value instanceof String text) {
            Object decoded = scalar(text);
            if (decoded != text) return decoded;
            if (text.startsWith("[") && text.endsWith("]")) {
                List<Object> values = new ArrayList<>();
                for (String part : text.substring(1, text.length() - 1).split(",", -1))
                    values.add(scalar(part));
                Object array = array(values);
                if (array != null) return array;
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach(
                    (key, child) -> {
                        if (child != null) result.put(String.valueOf(key), decode(child));
                    });
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> values = new ArrayList<>();
            for (Object child : list)
                if (child != null) values.add(child instanceof String text ? scalar(text) : child);
            Object array = array(values);
            if (array != null) return array;
            List<Object> result = new ArrayList<>();
            for (Object child : values) result.add(decode(child));
            return result;
        }
        return value;
    }

    private static Object scalar(String value) {
        if (!value.startsWith("(")) return value;
        int end = value.indexOf(") ");
        if (end < 0) return value;
        String type = value.substring(1, end), content = value.substring(end + 2);
        try {
            return switch (type) {
                case "String" -> content;
                case "Byte" -> Byte.valueOf(content);
                case "Short" -> Short.valueOf(content);
                case "Int" -> Integer.valueOf(content);
                case "Long" -> Long.valueOf(content);
                case "Float" -> Float.valueOf(content);
                case "Double" -> Double.valueOf(content);
                default -> value;
            };
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static Object array(List<?> values) {
        if (values.isEmpty()) return null;
        Object first = values.get(0);
        if (first instanceof Byte) {
            byte[] result = new byte[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = ((Byte) values.get(i)).byteValue();
            return result;
        }
        if (first instanceof Integer) {
            int[] result = new int[values.size()];
            for (int i = 0; i < result.length; i++)
                result[i] = ((Integer) values.get(i)).intValue();
            return result;
        }
        if (first instanceof Long) {
            long[] result = new long[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = ((Long) values.get(i)).longValue();
            return result;
        }
        return null;
    }
}

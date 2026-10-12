package dev.itemloom.compat.ni;

import java.util.*;

public final class NiTagValues {
    private NiTagValues() {}

    public static Object decode(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach(
                    (key, child) -> {
                        if (child != null) result.put(String.valueOf(key), decode(child));
                    });
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> decoded = new ArrayList<>();
            for (Object entry : list)
                if (entry != null) decoded.add(entry instanceof String text ? scalar(text) : entry);
            Object array = array(decoded);
            if (array != null) return array;
            return decoded.stream()
                    .map(
                            entry ->
                                    entry instanceof Map<?, ?> || entry instanceof List<?>
                                            ? decode(entry)
                                            : entry)
                    .toList();
        }
        if (value instanceof String text) {
            if (text.startsWith("[") && text.endsWith("]")) {
                List<Object> decoded =
                        Arrays.stream(text.substring(1, text.length() - 1).split(",", -1))
                                .map(NiTagValues::scalar)
                                .toList();
                Object array = array(decoded);
                return array == null ? text : array;
            }
            return scalar(text);
        }
        return value;
    }

    private static Object scalar(String value) {
        int end = value.indexOf(") ");
        if (end < 0 || !value.startsWith("(")) return value;
        String body = value.substring(end + 2);
        try {
            return switch (value.substring(1, end)) {
                case "String" -> body;
                case "Byte" -> Byte.valueOf(body);
                case "Short" -> Short.valueOf(body);
                case "Int" -> Integer.valueOf(body);
                case "Long" -> Long.valueOf(body);
                case "Float" -> Float.valueOf(body);
                case "Double" -> Double.valueOf(body);
                default -> value;
            };
        } catch (NumberFormatException error) {
            return value;
        }
    }

    private static Object array(List<Object> values) {
        if (values.isEmpty()) return null;
        if (values.get(0) instanceof Byte) {
            byte[] result = new byte[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = (Byte) values.get(i);
            return result;
        }
        if (values.get(0) instanceof Integer) {
            int[] result = new int[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = (Integer) values.get(i);
            return result;
        }
        if (values.get(0) instanceof Long) {
            long[] result = new long[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = (Long) values.get(i);
            return result;
        }
        return null;
    }
}

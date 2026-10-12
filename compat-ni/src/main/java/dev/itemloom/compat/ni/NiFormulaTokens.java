package dev.itemloom.compat.ni;

import java.util.*;

final class NiFormulaTokens {
    private NiFormulaTokens() {}

    static List<String> parse(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            boolean operator = "()+-*/%^".indexOf(c) >= 0;
            boolean sign =
                    (c == '+' || c == '-')
                            && (i == 0 || "(+-*/%^".indexOf(input.charAt(i - 1)) >= 0);
            if (operator && !sign) {
                flush(number, result);
                result.add(String.valueOf(c));
            } else if (c != ' ') number.append(c);
        }
        flush(number, result);
        return result;
    }

    private static void flush(StringBuilder value, List<String> result) {
        if (value.isEmpty()) return;
        String text = value.toString();
        Double.parseDouble(text);
        result.add(text);
        value.setLength(0);
    }
}

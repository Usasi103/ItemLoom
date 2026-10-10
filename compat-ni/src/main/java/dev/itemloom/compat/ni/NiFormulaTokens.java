package dev.itemloom.compat.ni;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric and operator tokens for the NI fast calculation input language. */
final class NiFormulaTokens {
    private static final Pattern OPERATORS = Pattern.compile("[()+*/%^+-]");

    private NiFormulaTokens() {}

    static List<String> parse(String input) {
        List<String> result = new ArrayList<>();
        Matcher operators = OPERATORS.matcher(input);
        int numberStart = 0;
        while (operators.find()) {
            if (isNumberSign(input, operators.start())) {
                continue;
            }
            appendNumber(input.substring(numberStart, operators.start()), result);
            result.add(operators.group());
            numberStart = operators.end();
        }
        appendNumber(input.substring(numberStart), result);
        return result;
    }

    private static boolean isNumberSign(String input, int index) {
        char character = input.charAt(index);
        if (character != '+' && character != '-') {
            return false;
        }
        // Signs use the original character adjacency, including skipped whitespace.
        return index == 0 || "(+-*/%^".indexOf(input.charAt(index - 1)) >= 0;
    }

    private static void appendNumber(String segment, List<String> result) {
        String value = segment.replace(" ", "");
        if (value.isEmpty()) {
            return;
        }
        Double.parseDouble(value);
        result.add(value);
    }
}

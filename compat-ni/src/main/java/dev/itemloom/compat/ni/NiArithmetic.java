package dev.itemloom.compat.ni;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The fastcalc arithmetic language; no script engine or server globals are involved. */
final class NiArithmetic {
    private NiArithmetic() {}

    static double evaluate(String source) {
        List<Object> tokens = tokens(source);
        ArrayDeque<Character> operators = new ArrayDeque<>();
        ArrayDeque<Double> values = new ArrayDeque<>();
        for (Object token : tokens) {
            if (token instanceof Double number) {
                values.push(number);
                continue;
            }
            char operator = (Character) token;
            if (operator == '(') operators.push(operator);
            else if (operator == ')') {
                while (!operators.isEmpty() && operators.peek() != '(')
                    apply(operators.pop(), values);
                if (operators.isEmpty())
                    throw new IllegalArgumentException("Unmatched ')' in fastcalc");
                operators.pop();
            } else {
                while (!operators.isEmpty()
                        && operators.peek() != '('
                        && operator != '^'
                        && priority(operators.peek()) >= priority(operator))
                    apply(operators.pop(), values);
                operators.push(operator);
            }
        }
        while (!operators.isEmpty()) apply(operators.pop(), values);
        if (values.isEmpty()) throw new IllegalArgumentException("Empty fastcalc expression");
        return values.pop();
    }

    private static List<Object> tokens(String source) {
        List<Object> result = new ArrayList<>();
        StringBuilder number = new StringBuilder();
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (!operator(value)) {
                if (value != ' ') number.append(value);
                continue;
            }
            if ((value == '+' || value == '-')
                    && (index == 0
                            || operator(source.charAt(index - 1))
                                    && source.charAt(index - 1) != ')')) {
                number.append(value);
                continue;
            }
            if (!number.isEmpty()) {
                result.add(Double.valueOf(number.toString()));
                number.setLength(0);
            }
            result.add(value);
        }
        if (!number.isEmpty()) result.add(Double.valueOf(number.toString()));
        return result;
    }

    private static boolean operator(char value) {
        return "+-*/%^()".indexOf(value) >= 0;
    }

    private static int priority(char value) {
        return value == '^' ? 3 : "*/%".indexOf(value) >= 0 ? 2 : 1;
    }

    private static void apply(char operator, ArrayDeque<Double> values) {
        double right = values.isEmpty() ? 0 : values.pop(),
                left = values.isEmpty() ? 0 : values.pop();
        values.push(
                switch (operator) {
                    case '+' -> left + right;
                    case '-' -> left - right;
                    case '*' -> left * right;
                    case '/' -> left / right;
                    case '%' -> left % right;
                    case '^' -> Math.pow(left, right);
                    case '(' -> 0D;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown fastcalc operator: " + operator);
                });
    }
}

package dev.itemloom.compat.ni;

import java.util.ArrayDeque;
import java.util.List;

/** The fastcalc arithmetic language; no script engine or server globals are involved. */
final class NiArithmetic {
    private NiArithmetic() {}

    static double evaluate(String source) {
        List<String> tokens = NiFormulaTokens.parse(source);
        ArrayDeque<Character> operators = new ArrayDeque<>();
        ArrayDeque<Double> values = new ArrayDeque<>();
        for (String token : tokens) {
            if (token.length() != 1 || "+-*/%^()".indexOf(token.charAt(0)) < 0) {
                values.push(Double.valueOf(token));
                continue;
            }
            char operator = token.charAt(0);
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

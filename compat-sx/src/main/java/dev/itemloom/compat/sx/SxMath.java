package dev.itemloom.compat.sx;

import java.util.ArrayDeque;
import java.util.Deque;

/** Arithmetic-only evaluator; operator stacks keep the input independent of a script runtime. */
final class SxMath {
    private static final int MAX_DEPTH = 64;
    private static final char POSITIVE = 'P', NEGATIVE = 'N';

    private SxMath() {}

    static double evaluate(String input) {
        if (input == null) throw new IllegalArgumentException("Missing SX arithmetic expression");
        Deque<Double> values = new ArrayDeque<>();
        Deque<Character> operators = new ArrayDeque<>();
        boolean operand = true;
        int nesting = 0;
        for (int offset = 0; offset < input.length(); ) {
            char token = input.charAt(offset);
            if (Character.isWhitespace(token)) {
                offset++;
                continue;
            }
            if (operand) {
                if (token == '(' || token == '+' || token == '-') {
                    if (++nesting >= MAX_DEPTH)
                        throw new IllegalArgumentException(
                                "SX arithmetic nesting reaches " + MAX_DEPTH);
                    operators.push(token == '+' ? POSITIVE : token == '-' ? NEGATIVE : token);
                    offset++;
                    continue;
                }
                int start = offset;
                while (offset < input.length()) {
                    char digit = input.charAt(offset);
                    if (digit != '.' && !Character.isDigit(digit)) break;
                    offset++;
                }
                if (offset == start) throw syntax(offset);
                values.push(Double.parseDouble(input.substring(start, offset)));
                operand = false;
            } else if (token == ')') {
                while (!operators.isEmpty() && operators.peek() != '(') reduce(operators, values);
                if (operators.isEmpty()) throw syntax(offset);
                operators.pop();
                nesting--;
                offset++;
            } else {
                int precedence = precedence(token);
                if (precedence == 0) throw syntax(offset);
                while (!operators.isEmpty() && precedence(operators.peek()) >= precedence)
                    reduce(operators, values);
                operators.push(token);
                operand = true;
                offset++;
                continue;
            }
            while (!operators.isEmpty() && unary(operators.peek())) {
                reduce(operators, values);
                nesting--;
            }
        }
        if (operand || nesting != 0) throw syntax(input.length());
        while (!operators.isEmpty()) reduce(operators, values);
        return values.pop();
    }

    private static int precedence(char operator) {
        return switch (operator) {
            case '+', '-' -> 1;
            case '*', '/', '%' -> 2;
            default -> 0;
        };
    }

    private static boolean unary(char operator) {
        return operator == POSITIVE || operator == NEGATIVE;
    }

    private static void reduce(Deque<Character> operators, Deque<Double> values) {
        char operator = operators.pop();
        double right = values.pop();
        if (unary(operator)) {
            values.push(operator == NEGATIVE ? -right : right);
            return;
        }
        double left = values.pop();
        values.push(
                switch (operator) {
                    case '+' -> left + right;
                    case '-' -> left - right;
                    case '*' -> left * right;
                    case '/' -> left / right;
                    case '%' -> left % right;
                    default -> throw new IllegalArgumentException("Invalid SX arithmetic operator");
                });
    }

    private static IllegalArgumentException syntax(int offset) {
        return new IllegalArgumentException("Invalid SX arithmetic at offset " + offset);
    }
}

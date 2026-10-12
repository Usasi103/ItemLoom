package dev.itemloom.compat.sx;

import java.util.ArrayDeque;
import java.util.Deque;

/** Operator stacks separate structural depth from arbitrarily long flat arithmetic. */
final class SxMath {
    private record Operator(char symbol, boolean unary) {}

    static double evaluate(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Null arithmetic expression");
        }
        Deque<Double> values = new ArrayDeque<>();
        Deque<Operator> operators = new ArrayDeque<>();
        boolean operand = true;
        int pending = 0;
        for (int index = 0; index < input.length(); ) {
            char token = input.charAt(index);
            if (Character.isWhitespace(token)) {
                index++;
                continue;
            }
            if (operand) {
                if (token == '+' || token == '-' || token == '(') {
                    if (++pending >= 64) {
                        throw new IllegalArgumentException("Arithmetic nesting exceeds 63");
                    }
                    operators.push(new Operator(token, token != '('));
                    index++;
                    continue;
                }
                int start = index;
                boolean digit = false;
                boolean dot = false;
                while (index < input.length()) {
                    char character = input.charAt(index);
                    if (character >= '0' && character <= '9') {
                        digit = true;
                        index++;
                    } else if (character == '.' && !dot) {
                        dot = true;
                        index++;
                    } else {
                        break;
                    }
                }
                if (!digit) {
                    throw new IllegalArgumentException("Expected arithmetic operand at " + start);
                }
                values.push(Double.parseDouble(input.substring(start, index)));
                operand = false;
                while (!operators.isEmpty() && operators.peek().unary()) {
                    apply(values, operators.pop());
                    pending--;
                }
            } else if (token == ')') {
                while (!operators.isEmpty() && operators.peek().symbol() != '(') {
                    apply(values, operators.pop());
                }
                if (operators.isEmpty()) {
                    throw new IllegalArgumentException("Unmatched arithmetic closing parenthesis");
                }
                operators.pop();
                pending--;
                while (!operators.isEmpty() && operators.peek().unary()) {
                    apply(values, operators.pop());
                    pending--;
                }
                index++;
            } else if ("+-*/%".indexOf(token) >= 0) {
                while (!operators.isEmpty()
                        && operators.peek().symbol() != '('
                        && precedence(operators.peek().symbol()) >= precedence(token)) {
                    apply(values, operators.pop());
                }
                operators.push(new Operator(token, false));
                operand = true;
                index++;
            } else {
                throw new IllegalArgumentException("Unexpected arithmetic token at " + index);
            }
        }
        if (operand || pending != 0) {
            throw new IllegalArgumentException("Incomplete arithmetic expression");
        }
        while (!operators.isEmpty()) {
            apply(values, operators.pop());
        }
        if (values.size() != 1) {
            throw new IllegalArgumentException("Invalid arithmetic expression");
        }
        return values.pop();
    }

    private static int precedence(char token) {
        return token == '+' || token == '-' ? 1 : 2;
    }

    private static void apply(Deque<Double> values, Operator operator) {
        double right = values.pop();
        if (operator.unary()) {
            values.push(operator.symbol() == '-' ? -right : right);
            return;
        }
        double left = values.pop();
        values.push(
                switch (operator.symbol()) {
                    case '+' -> left + right;
                    case '-' -> left - right;
                    case '*' -> left * right;
                    case '/' -> left / right;
                    case '%' -> left % right;
                    default -> throw new IllegalArgumentException("Invalid arithmetic operator");
                });
    }
}

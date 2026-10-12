package dev.itemloom.compat.ni;

import java.util.*;

final class NiArithmetic {
    private NiArithmetic() {}

    static double evaluate(String source) {
        ArrayDeque<Double> values = new ArrayDeque<>();
        ArrayDeque<String> operators = new ArrayDeque<>();
        for (String token : NiFormulaTokens.parse(source)) {
            if (token.equals("(")) operators.push(token);
            else if (token.equals(")")) {
                while (!operators.isEmpty() && !operators.peek().equals("("))
                    apply(values, operators.pop());
                if (operators.isEmpty())
                    throw new IllegalArgumentException("Unmatched right parenthesis");
                operators.pop();
            } else if (token.length() == 1 && "+-*/%^".contains(token)) {
                while (!operators.isEmpty()
                        && !operators.peek().equals("(")
                        && (rank(operators.peek()) > rank(token)
                                || rank(operators.peek()) == rank(token) && !token.equals("^")))
                    apply(values, operators.pop());
                operators.push(token);
            } else values.push(Double.parseDouble(token));
        }
        while (!operators.isEmpty()) apply(values, operators.pop());
        if (values.isEmpty()) throw new IllegalArgumentException("Empty arithmetic expression");
        return values.pop();
    }

    private static int rank(String operator) {
        return operator.equals("^") ? 3 : "*/%".contains(operator) ? 2 : 1;
    }

    private static void apply(ArrayDeque<Double> stack, String operator) {
        double right = stack.isEmpty() ? 0 : stack.pop();
        double left = stack.isEmpty() ? 0 : stack.pop();
        stack.push(
                switch (operator) {
                    case "+" -> left + right;
                    case "-" -> left - right;
                    case "*" -> left * right;
                    case "/" -> left / right;
                    case "%" -> left % right;
                    case "^" -> Math.pow(left, right);
                    default -> 0.0;
                });
    }
}

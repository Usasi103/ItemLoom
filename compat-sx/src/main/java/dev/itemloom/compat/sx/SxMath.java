package dev.itemloom.compat.sx;

/** SX arithmetic: + - * / %, parentheses and unary signs. No script evaluation. */
final class SxMath {
    private final String text;
    private int index, depth;

    private SxMath(String text) {
        this.text = text;
    }

    static double evaluate(String text) {
        SxMath parser = new SxMath(text);
        double result = parser.sum();
        parser.space();
        if (parser.index != text.length())
            throw new IllegalArgumentException("Invalid SX calculator input: " + text);
        return result;
    }

    private void space() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
    }

    private boolean take(char ch) {
        space();
        if (index < text.length() && text.charAt(index) == ch) {
            index++;
            return true;
        }
        return false;
    }

    private double sum() {
        double result = product();
        while (true) {
            if (take('+')) result += product();
            else if (take('-')) result -= product();
            else return result;
        }
    }

    private double product() {
        double result = atom();
        while (true) {
            if (take('*')) result *= atom();
            else if (take('/')) result /= atom();
            else if (take('%')) result %= atom();
            else return result;
        }
    }

    private double atom() {
        if (++depth > 64)
            throw new IllegalArgumentException("SX calculator nesting limit exceeded");
        try {
            if (take('+')) return atom();
            if (take('-')) return -atom();
            if (take('(')) {
                double value = sum();
                if (!take(')'))
                    throw new IllegalArgumentException("Unclosed SX calculator parenthesis");
                return value;
            }
            space();
            int start = index;
            while (index < text.length()
                    && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.'))
                index++;
            if (start == index)
                throw new IllegalArgumentException("SX calculator expected a number at " + index);
            return Double.parseDouble(text.substring(start, index));
        } finally {
            depth--;
        }
    }
}

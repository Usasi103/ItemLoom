package dev.itemloom.compat.ni;

import java.util.*;
import java.util.function.Function;

public final class NiTemplate {
    private record Piece(String literal, int closing) {}

    private final List<Piece> program;
    private final boolean constant;

    private NiTemplate(List<Piece> program, boolean constant) {
        this.program = List.copyOf(program);
        this.constant = constant;
    }

    public static NiTemplate compile(String source) {
        Objects.requireNonNull(source);
        List<Piece> program = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        boolean opening = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                if (i + 1 == source.length()) break;
                char next = source.charAt(i + 1);
                if (next == '<' || next == '>' || next == '\\') {
                    literal.append(next);
                    i++;
                } else literal.append(c);
            } else if (c == '<' || c == '>') {
                if (!literal.isEmpty()) {
                    program.add(new Piece(literal.toString(), 0));
                    literal.setLength(0);
                }
                program.add(new Piece("", c == '<' ? -1 : 1));
                opening |= c == '<';
            } else literal.append(c);
        }
        if (!literal.isEmpty()) program.add(new Piece(literal.toString(), 0));
        return new NiTemplate(program, !opening);
    }

    public boolean isConstant() {
        return constant;
    }

    public String render(Function<String, String> resolve) {
        ArrayDeque<StringBuilder> stack = new ArrayDeque<>();
        stack.push(new StringBuilder());
        for (Piece piece : program) {
            if (piece.closing == -1) stack.push(new StringBuilder());
            else if (piece.closing == 1 && stack.size() > 1) {
                String name = stack.pop().toString();
                String value = resolve.apply(name);
                stack.peek().append(value == null ? "<" + name + ">" : value);
            } else stack.peek().append(piece.closing == 1 ? ">" : piece.literal);
        }
        while (stack.size() > 1) {
            StringBuilder incomplete = stack.pop();
            stack.peek().append('<').append(incomplete);
        }
        return stack.pop().toString();
    }

    public static List<String> arguments(String source, int limit) {
        return split(source, '_', limit);
    }

    public static List<String> split(String source, char separator, int limit) {
        if (limit == 1) return List.of(source);
        List<String> parts = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\'
                    && i + 1 < source.length()
                    && (source.charAt(i + 1) == separator || source.charAt(i + 1) == '\\'))
                token.append(source.charAt(++i));
            else if (c == separator && (limit <= 0 || parts.size() < limit - 1)) {
                parts.add(token.toString());
                token.setLength(0);
            } else token.append(c);
        }
        parts.add(token.toString());
        return parts;
    }
}

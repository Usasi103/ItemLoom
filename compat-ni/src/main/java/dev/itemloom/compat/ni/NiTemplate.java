package dev.itemloom.compat.ni;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Compiled NI delimiters and escapes. Returned values are not implicitly scanned again. */
public final class NiTemplate {
    private enum Operation {
        TEXT,
        ENTER,
        RESOLVE
    }

    private record Instruction(Operation operation, String text) {}

    private final List<Instruction> program;
    private final int stackSize;
    private final String constant;

    private NiTemplate(List<Instruction> program, int stackSize, String constant) {
        this.program = List.copyOf(program);
        this.stackSize = stackSize;
        this.constant = constant;
    }

    public static NiTemplate compile(String source) {
        Objects.requireNonNull(source);
        List<Instruction> instructions = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int depth = 0;
        int maximum = 0;
        for (int offset = 0; offset < source.length(); offset++) {
            char next = source.charAt(offset);
            if (next == '\\') {
                // Historical template parsing consumes a final escape. Parameter splitting differs.
                if (offset + 1 == source.length()) continue;
                char escaped = source.charAt(offset + 1);
                if (escaped == '<' || escaped == '>' || escaped == '\\') {
                    literal.append(escaped);
                    offset++;
                } else {
                    literal.append(next);
                }
            } else if (next == '<') {
                flush(instructions, literal);
                instructions.add(new Instruction(Operation.ENTER, null));
                maximum = Math.max(maximum, ++depth);
            } else if (next == '>' && depth > 0) {
                flush(instructions, literal);
                instructions.add(new Instruction(Operation.RESOLVE, null));
                depth--;
            } else {
                literal.append(next);
            }
        }
        flush(instructions, literal);
        if (maximum == 0) {
            String value = instructions.isEmpty() ? "" : instructions.get(0).text();
            return new NiTemplate(List.of(), 0, value);
        }
        return new NiTemplate(instructions, maximum + 1, null);
    }

    private static void flush(List<Instruction> instructions, StringBuilder literal) {
        if (literal.length() == 0) return;
        instructions.add(new Instruction(Operation.TEXT, literal.toString()));
        literal.setLength(0);
    }

    public boolean isConstant() {
        return constant != null;
    }

    public String render(Function<String, String> resolve) {
        if (constant != null) return constant;
        StringBuilder[] stack = new StringBuilder[stackSize];
        stack[0] = new StringBuilder();
        int depth = 0;
        for (Instruction instruction : program) {
            switch (instruction.operation()) {
                case TEXT -> stack[depth].append(instruction.text());
                case ENTER -> stack[++depth] = new StringBuilder();
                case RESOLVE -> {
                    String expression = stack[depth--].toString();
                    String value = resolve.apply(expression);
                    if (value == null) stack[depth].append('<').append(expression).append('>');
                    else stack[depth].append(value);
                }
            }
        }
        for (int level = 1; level <= depth; level++) stack[0].append('<').append(stack[level]);
        return stack[0].toString();
    }

    /** Underscore arguments keep a final backslash and support parser-specific split limits. */
    public static List<String> arguments(String source, int limit) {
        return split(source, '_', limit);
    }

    public static List<String> split(String source, char separator, int limit) {
        if (limit == 1) return List.of(source);
        List<String> arguments = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        for (int offset = 0; offset < source.length(); offset++) {
            char next = source.charAt(offset);
            if (next == '\\' && offset + 1 < source.length()) {
                char escaped = source.charAt(offset + 1);
                if (escaped == separator || escaped == '\\') {
                    value.append(escaped);
                    offset++;
                } else value.append(next);
            } else if (next == separator && (limit <= 0 || arguments.size() < limit - 1)) {
                arguments.add(value.toString());
                value.setLength(0);
            } else value.append(next);
        }
        arguments.add(value.toString());
        return arguments;
    }
}

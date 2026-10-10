package dev.itemloom.compat.ni;

import java.util.List;

/** Renders the NI gradient node's color groups without changing its input text. */
final class NiGradientText {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private NiGradientText() {}

    static String render(List<String> arguments, boolean legacy) {
        if (arguments.size() < 4
                || arguments.get(0) == null
                || arguments.get(1) == null
                || arguments.get(3) == null) {
            return null;
        }
        int start;
        int end;
        try {
            start = color(arguments.get(0));
            end = color(arguments.get(1));
        } catch (NumberFormatException invalidColor) {
            if (!legacy) {
                return null;
            }
            start = 0;
            end = 0;
        }
        int step;
        try {
            step = Integer.parseInt(arguments.get(2));
        } catch (NumberFormatException invalidStep) {
            step = 1;
        }
        if (legacy && step <= 0) {
            step = 1;
        }
        String text = arguments.get(3);
        StringBuilder result = new StringBuilder();
        appendColor(result, start);
        if (step <= 0 || step >= text.length()) {
            return result.append(text).toString();
        }
        int intervals = text.length() - 1;
        int redStep = groupIncrement(start >>> 16, end >>> 16, step, intervals);
        int greenStep = groupIncrement((start >>> 8) & 255, (end >>> 8) & 255, step, intervals);
        int blueStep = groupIncrement(start & 255, end & 255, step, intervals);
        result.append(text, 0, step);
        for (int group = 1; group <= intervals / step; group++) {
            int red = (start >>> 16) + redStep * group;
            int green = ((start >>> 8) & 255) + greenStep * group;
            int blue = (start & 255) + blueStep * group;
            appendColor(result, (red << 16) | (green << 8) | blue);
            int offset = group * step;
            result.append(text, offset, offset + Math.min(step, text.length() - offset));
        }
        return result.toString();
    }

    private static int groupIncrement(int start, int end, int step, int intervals) {
        return (int) ((long) (end - start) * step / intervals);
    }

    private static int color(String value) {
        return Math.max(0, Math.min(0xffffff, Integer.parseInt(value, 16)));
    }

    private static void appendColor(StringBuilder text, int color) {
        text.append('\u00a7').append('x');
        for (int shift = 20; shift >= 0; shift -= 4) {
            text.append('\u00a7').append(HEX[(color >>> shift) & 15]);
        }
    }
}

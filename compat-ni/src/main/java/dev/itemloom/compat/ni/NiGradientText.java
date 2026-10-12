package dev.itemloom.compat.ni;

import java.util.List;

final class NiGradientText {
    private NiGradientText() {}

    static String render(List<String> arguments, boolean legacy) {
        if (arguments.size() < 4
                || arguments.get(0) == null
                || arguments.get(1) == null
                || arguments.get(3) == null) return null;
        int from, to;
        try {
            from = Math.clamp(Integer.parseInt(arguments.get(0), 16), 0, 0xffffff);
            to = Math.clamp(Integer.parseInt(arguments.get(1), 16), 0, 0xffffff);
        } catch (NumberFormatException error) {
            if (!legacy) return null;
            from = 0;
            to = 0;
        }
        String text = arguments.get(3);
        int step = NiNodes.integer(arguments.get(2), 1);
        if (legacy && step <= 0) step = 1;
        if (step <= 0 || text.length() <= 1 || step >= text.length())
            return NiTextNodes.color(from) + text;
        int[] channels = {from >> 16, from >> 8 & 255, from & 255};
        int[] finish = {to >> 16, to >> 8 & 255, to & 255};
        int[] increment = new int[3];
        for (int i = 0; i < 3; i++)
            increment[i] = (int) ((finish[i] - channels[i]) * (double) step / (text.length() - 1));
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length(); offset += step) {
            result.append(NiTextNodes.color(channels[0] << 16 | channels[1] << 8 | channels[2]));
            result.append(text, offset, Math.min(text.length(), offset + step));
            for (int i = 0; i < 3; i++) channels[i] += increment[i];
        }
        return result.toString();
    }
}

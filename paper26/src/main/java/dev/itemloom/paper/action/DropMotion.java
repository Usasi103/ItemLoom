package dev.itemloom.paper.action;

import java.util.random.RandomGenerator;

record DropMotion(double horizontal, double vertical, String mode) {
    static DropMotion prepare(String x, String y, String mode, RandomGenerator random) {
        if (x == null || y == null || mode == null) return null;
        return new DropMotion(offset(x, random), offset(y, random), mode);
    }

    static double offset(String text, RandomGenerator random) {
        int separator = text.indexOf('-');
        double first;
        double second;
        try {
            if (separator < 0) return Double.parseDouble(text);
            first = Double.parseDouble(text.substring(0, separator));
            second = Double.parseDouble(text.substring(separator + 1));
        } catch (NumberFormatException failure) {
            return 0.1;
        }
        // Invalid numeric ranges are errors, unlike malformed numeric text.
        return random.nextDouble(first, second);
    }

    Velocity at(int index, int count, RandomGenerator random) {
        if ("round".equals(mode)) {
            double angle = Math.PI * 2 * index / count;
            return new Velocity(
                    horizontal * Math.cos(angle), vertical, -horizontal * Math.sin(angle));
        }
        if ("random".equals(mode)) {
            double xAngle = random.nextDouble() * Math.PI * 2;
            double zAngle = random.nextDouble() * Math.PI * 2;
            return new Velocity(
                    horizontal * Math.cos(xAngle), vertical, -horizontal * Math.sin(zAngle));
        }
        return new Velocity(horizontal, vertical, 0);
    }

    record Velocity(double x, double y, double z) {}
}

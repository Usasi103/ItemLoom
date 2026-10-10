package dev.itemloom.paper.action;

import java.util.random.RandomGenerator;

/** One sampled pair of offsets belongs to a whole requested drop list. */
record DropMotion(double horizontal, double vertical, String mode) {
    record Velocity(double x, double y, double z) {}

    static DropMotion prepare(
            String horizontal, String vertical, String mode, RandomGenerator random) {
        if (horizontal == null || vertical == null || mode == null) return null;
        return new DropMotion(offset(horizontal, random), offset(vertical, random), mode);
    }

    static double offset(String text, RandomGenerator random) {
        int separator = text.indexOf('-');
        double first, second;
        try {
            if (separator < 0) return Double.parseDouble(text);
            first = Double.parseDouble(text.substring(0, separator));
            second = Double.parseDouble(text.substring(separator + 1));
        } catch (NumberFormatException malformed) {
            return 0.1;
        }
        // Numeric ranges retain the generator's invalid-bound exception, including 1--2.
        return random.nextDouble(first, second);
    }

    Velocity at(int index, int count, RandomGenerator random) {
        if (mode.equals("round")) {
            double angle = Math.TAU * index / count;
            return new Velocity(
                    horizontal * Math.cos(angle), vertical, -horizontal * Math.sin(angle));
        }
        if (mode.equals("random")) {
            double xAngle = random.nextDouble(Math.TAU);
            double zAngle = random.nextDouble(Math.TAU);
            return new Velocity(
                    horizontal * Math.cos(xAngle), vertical, -horizontal * Math.sin(zAngle));
        }
        return new Velocity(horizontal, vertical, 0);
    }
}

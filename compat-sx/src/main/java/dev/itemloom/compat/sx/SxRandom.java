package dev.itemloom.compat.sx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Ordered probability intervals with detached semantic input for revision fingerprints. */
public final class SxRandom {
    private record Choice(double upper, String text) {}

    private final List<Choice> choices;
    private final double total;
    private final Object semanticInput;

    private SxRandom(Object input) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("value", input);
        semanticInput = new SxConfig(raw).get("value");
        List<Choice> compiled = new ArrayList<>();
        double sum = 0;
        if (input instanceof List<?> list) {
            boolean weighted = !list.isEmpty() && list.getFirst() instanceof Map<?, ?>;
            for (Object element : list) {
                if (weighted) {
                    if (!(element instanceof Map<?, ?> map)) {
                        throw new IllegalArgumentException("Mixed weighted random entries");
                    }
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        sum =
                                append(
                                        compiled,
                                        sum,
                                        Double.parseDouble(String.valueOf(entry.getKey())),
                                        entry.getValue());
                    }
                } else {
                    sum = append(compiled, sum, 1, element);
                }
            }
        } else {
            sum = append(compiled, sum, 1, input);
        }
        if (!Double.isFinite(sum) || sum <= 0) {
            throw new IllegalArgumentException("Random pool requires a finite positive total");
        }
        choices = List.copyOf(compiled);
        total = sum;
    }

    private static double append(List<Choice> choices, double sum, double weight, Object value) {
        if (!Double.isFinite(weight) || weight < 0 || !Double.isFinite(sum + weight)) {
            throw new IllegalArgumentException("Invalid random weight: " + weight);
        }
        double next = sum + weight;
        if (weight > 0) {
            choices.add(new Choice(next, text(value)));
        }
        return next;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String source =
                value instanceof List<?> lines
                        ? String.join("\n", lines.stream().map(String::valueOf).toList())
                        : value.toString();
        return source.replace("/n", "\n")
                .replace("\\n", "\n")
                .replace("%DeleteLore%", "$<DeleteLore>");
    }

    public String pick(RandomGenerator random) {
        double position = random.nextDouble() * total;
        for (Choice choice : choices) {
            if (position < choice.upper()) {
                return choice.text();
            }
        }
        return choices.getLast().text();
    }

    public Object semanticInput() {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("value", semanticInput);
        return new SxConfig(copy).get("value");
    }

    public static Map<String, SxRandom> compile(Map<String, ?> values) {
        Map<String, SxRandom> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    if (!key.startsWith("NoLoad")) {
                        result.put(key, new SxRandom(value));
                    }
                });
        return Collections.unmodifiableMap(result);
    }
}

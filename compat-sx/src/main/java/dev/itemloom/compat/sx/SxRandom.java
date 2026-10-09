package dev.itemloom.compat.sx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Weighted choices are compiled once; null local choices intentionally fall through to globals. */
public final class SxRandom {
    private final List<String> choices;
    private final double[] cumulative;

    private SxRandom(List<String> choices, List<Double> weights) {
        this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
        cumulative = new double[weights.size()];
        double total = 0;
        for (int i = 0; i < weights.size(); i++) {
            double weight = weights.get(i);
            if (!Double.isFinite(weight) || weight < 0)
                throw new IllegalArgumentException("Invalid SX random weight: " + weight);
            cumulative[i] = total += weight;
        }
        if (!Double.isFinite(total) || total <= 0)
            throw new IllegalArgumentException(
                    "SX random weights must have a finite positive total");
    }

    public String pick(RandomGenerator random) {
        double roll = random.nextDouble() * cumulative[cumulative.length - 1];
        int low = 0, high = cumulative.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (roll < cumulative[mid]) high = mid;
            else low = mid + 1;
        }
        return choices.get(low);
    }

    public static Map<String, SxRandom> compile(Map<String, ?> values) {
        Map<String, SxRandom> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    if (key.startsWith("NoLoad")) return;
                    List<String> choices = new ArrayList<>();
                    List<Double> weights = new ArrayList<>();
                    if (value instanceof List<?> list) {
                        if (list.isEmpty())
                            throw new IllegalArgumentException("Empty SX random list: " + key);
                        boolean weighted = list.getFirst() instanceof Map;
                        for (Object entry : list) {
                            if (weighted) {
                                if (!(entry instanceof Map<?, ?> map))
                                    throw new IllegalArgumentException(
                                            "Mixed SX random list: " + key);
                                map.forEach(
                                        (weight, choice) -> {
                                            weights.add(Double.valueOf(weight.toString()));
                                            choices.add(text(choice));
                                        });
                            } else {
                                weights.add(1D);
                                choices.add(text(entry));
                            }
                        }
                    } else {
                        weights.add(1D);
                        choices.add(text(value));
                    }
                    result.put(key, new SxRandom(choices, weights));
                });
        return Collections.unmodifiableMap(result);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text =
                value instanceof List<?> list
                        ? String.join("\n", list.stream().map(String::valueOf).toList())
                        : value.toString();
        return text.replace("/n", "\n")
                .replace("\\n", "\n")
                .replace("%DeleteLore%", "$<DeleteLore>");
    }
}

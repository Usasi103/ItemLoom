package dev.itemloom.compat.ni;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import org.bukkit.entity.Player;

/** Collection-node evaluation, with rendering and cache effects kept apart from selection. */
public final class NiCollectionNodes {
    private NiCollectionNodes() {}

    public static String choices(
            String type, List<String> original, NiEvaluation evaluation, boolean configured) {
        if (original.isEmpty()) return null;
        boolean section = evaluation.mode() == NiEvaluation.Mode.SECTION;
        List<String> candidates = original;
        if (configured && (!section || type.equals("weight"))) {
            candidates = new ArrayList<>(original.size());
            for (String value : original) candidates.add(evaluation.text(value));
        }

        String selected;
        if (type.equals("weight")) {
            List<DecimalChoice> masses = new ArrayList<>();
            for (String candidate : candidates) {
                WeightedText entry = WeightedText.read(candidate);
                BigDecimal weight;
                try {
                    weight = new BigDecimal(entry.weight());
                } catch (NumberFormatException invalid) {
                    weight = BigDecimal.ONE;
                }
                if (weight.signum() > 0) masses.add(new DecimalChoice(entry.text(), weight));
            }
            selected = decimalChoice(masses, evaluation.generation().random());
        } else {
            selected = candidates.get(evaluation.generation().random().nextInt(candidates.size()));
        }
        return configured && section ? evaluation.text(selected) : selected;
    }

    public static String join(NiConfig config, NiEvaluation evaluation) {
        String field =
                evaluation.mode() == NiEvaluation.Mode.ACTION && config.contains("values")
                        ? "values"
                        : "list";
        Object source = config.get(field);
        if (!(source instanceof List<?>) && evaluation.mode() == NiEvaluation.Mode.ACTION)
            return null;
        List<String> values = new ArrayList<>();
        if (source instanceof List<?> list)
            for (Object value : list) if (value != null) values.add(String.valueOf(value));

        String separator = expanded(config, "separator", ", ", evaluation);
        String prefix = expanded(config, "prefix", "", evaluation);
        String postfix = expanded(config, "postfix", "", evaluation);
        int requested = integer(evaluation.text(config.string("limit")), values.size());
        String truncated = evaluation.text(config.string("truncated"));
        boolean shuffled = enabled(config, "shuffled", evaluation);
        if (shuffled) shuffle(values, evaluation.generation().random());

        int count = Math.min(values.size(), Math.max(0, requested));
        String transform = config.string("transform");
        StringBuilder output = new StringBuilder(prefix);
        for (int index = 0; index < count; index++) {
            if (index > 0) output.append(separator);
            output.append(
                    transform(
                            evaluation.text(values.get(index)),
                            index,
                            values,
                            transform,
                            evaluation));
        }
        if (requested < values.size() && truncated != null) {
            if (count > 0) output.append(separator);
            output.append(truncated);
        }
        return output.append(postfix).toString();
    }

    public static String repeat(NiConfig config, NiEvaluation evaluation) {
        String content = expanded(config, "content", "", evaluation);
        int count;
        String separator;
        String prefix;
        String postfix;
        if (evaluation.mode() == NiEvaluation.Mode.ACTION) {
            count = integer(evaluation.text(config.string("repeat")), 1);
            separator = expanded(config, "separator", "", evaluation);
            prefix = expanded(config, "prefix", "", evaluation);
            postfix = expanded(config, "postfix", "", evaluation);
        } else {
            separator = expanded(config, "separator", "", evaluation);
            prefix = expanded(config, "prefix", "", evaluation);
            postfix = expanded(config, "postfix", "", evaluation);
            count = integer(evaluation.text(config.string("repeat")), 1);
        }
        String script = config.string("transform");
        StringBuilder output = new StringBuilder(prefix);
        for (int index = 0; index < count; index++) {
            if (index > 0) output.append(separator);
            output.append(transform(content, index, null, script, evaluation));
        }
        return output.append(postfix).toString();
    }

    public static String inlineRepeat(List<String> arguments, NiEvaluation evaluation) {
        if (evaluation.mode() == NiEvaluation.Mode.SECTION) return null;
        String content = arguments.isEmpty() ? "" : arguments.get(0);
        int count = arguments.size() < 2 ? 1 : integer(arguments.get(1), 1);
        String separator = arguments.size() < 3 ? "" : arguments.get(2);
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) output.append(separator);
            output.append(content);
        }
        return output.toString();
    }

    public static String weightedSequence(String type, NiConfig config, NiEvaluation evaluation) {
        boolean declaration = type.endsWith("declare");
        boolean occurrences = type.startsWith("r");
        String separator = declaration ? "" : expanded(config, "separator", ", ", evaluation);
        String prefix = declaration ? "" : expanded(config, "prefix", "", evaluation);
        String postfix = declaration ? "" : expanded(config, "postfix", "", evaluation);
        boolean shuffled = enabled(config, "shuffled", evaluation);
        boolean ordered = enabled(config, "order", evaluation);
        List<SequenceChoice> available =
                sequenceChoices(config.strings("list"), occurrences, evaluation);
        String key = declaration ? evaluation.text(config.string("key")) : null;
        int requested =
                Math.min(
                        available.size(),
                        Math.max(0, integer(evaluation.text(config.string("amount")), 1)));
        int demand = requested;
        Map<String, String> cache = evaluation.legacyCache();
        if (declaration && !occurrences && key != null && cache != null) {
            for (int slot = 0; slot < requested; slot++) {
                String previous = cache.get(key + "." + slot);
                if (available.removeIf(choice -> choice.text().equals(previous))) demand--;
            }
        }

        List<SequenceChoice> selected =
                selectSequence(available, demand, occurrences, evaluation.generation().random());
        if (shuffled) shuffle(selected, evaluation.generation().random());
        else if (ordered) selected.sort(Comparator.comparingInt(SequenceChoice::position));

        if (!declaration) {
            List<String> selectedText = new ArrayList<>(selected.size());
            for (SequenceChoice choice : selected) selectedText.add(choice.text());
            List<String> values = List.copyOf(selectedText);
            String script = config.string("transform");
            StringBuilder output = new StringBuilder(prefix);
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) output.append(separator);
                output.append(transform(values.get(index), index, values, script, evaluation));
            }
            return output.append(postfix).toString();
        }

        boolean putElse = !occurrences && enabled(config, "putelse", evaluation);
        if (cache != null && key != null) {
            int slot = 0;
            for (SequenceChoice choice : selected) {
                while (cache.containsKey(key + "." + slot)) slot++;
                cache.put(key + "." + slot++, choice.text());
            }
            cache.put(key + ".length", String.valueOf(requested));
            if (putElse) {
                int remainder = 0;
                for (SequenceChoice choice : available)
                    if (!selected.contains(choice))
                        cache.put(key + ".else." + remainder++, choice.text());
                cache.put(key + ".else.length", String.valueOf(remainder));
            }
        }
        return cache == null ? null : cache.get(key + ".0");
    }

    public static String when(NiConfig config, NiEvaluation evaluation) {
        String value = evaluation.text(config.string("value"));
        if (!(config.get("conditions") instanceof List<?> conditions)) return null;

        Map<String, Object> cache = evaluation.cache();
        Object previous = cache.get("value");
        if (value != null) cache.put("value", value);
        boolean action = evaluation.mode() == NiEvaluation.Mode.ACTION;
        boolean accepted = false;
        try {
            for (Object entry : conditions) {
                Object result = entry;
                if (entry instanceof Map<?, ?> branch) {
                    Object condition = branch.get("condition");
                    if (condition != null) {
                        if (!(condition instanceof String expression)) continue;
                        if (!conditionContext(evaluation, value, cache, action)
                                .condition(expression)) continue;
                    }
                    result = branch.get("result");
                }
                accepted = true;
                return evaluation.text(String.valueOf(result));
            }
            return null;
        } finally {
            if (action && value != null) {
                if (previous == null) cache.remove("value");
                else cache.put("value", previous);
            } else if (!action && accepted) cache.remove("value");
        }
    }

    private static NiActionContext conditionContext(
            NiEvaluation evaluation, String value, Map<String, Object> cache, boolean action) {
        Map<String, Object> params = new HashMap<>();
        params.put("value", value);
        params.put("cache", cache);
        var sections = NiYaml.toSection(evaluation.sections());
        params.put("sections", action ? new LegacyConfigReader.BukkitReader(sections) : sections);
        Player caster = evaluation.player() instanceof Player player ? player : null;
        return new NiActionContext(
                evaluation.action(caster),
                caster,
                action ? params : null,
                params,
                evaluation.scripts()::isOpen);
    }

    private static String expanded(
            NiConfig config, String field, String fallback, NiEvaluation evaluation) {
        String value = evaluation.text(config.string(field));
        return value == null ? fallback : value;
    }

    private static boolean enabled(NiConfig config, String field, NiEvaluation evaluation) {
        return "true".equals(evaluation.text(config.string(field)));
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static String transform(
            String text, int index, List<String> list, String script, NiEvaluation evaluation) {
        if (script == null || script.isEmpty()) return text;
        Map<String, Object> bindings = evaluation.bindings();
        bindings.put("it", text);
        bindings.put("index", index);
        if (list != null) bindings.put("list", list);
        Object transformed = evaluation.scripts().transform(script, bindings);
        return transformed == null ? "" : String.valueOf(transformed);
    }

    private static <T> void shuffle(List<T> values, RandomGenerator random) {
        for (int bound = values.size(); bound > 1; bound--) {
            int picked = random.nextInt(bound);
            T last = values.get(bound - 1);
            values.set(bound - 1, values.get(picked));
            values.set(picked, last);
        }
    }

    private record WeightedText(String weight, String text) {
        static WeightedText read(String source) {
            int delimiter = source.indexOf("::");
            return delimiter < 0
                    ? new WeightedText("1", source)
                    : new WeightedText(
                            source.substring(0, delimiter), source.substring(delimiter + 2));
        }
    }

    private record DecimalChoice(String text, BigDecimal weight) {}

    private static String decimalChoice(List<DecimalChoice> weights, RandomGenerator random) {
        if (weights.isEmpty()) return null;
        BigDecimal maximum = BigDecimal.ZERO;
        for (DecimalChoice choice : weights)
            if (choice.weight().compareTo(maximum) > 0) maximum = choice.weight();
        // Uniform proposals followed by exact acceptance give each occurrence mass w / sum(w).
        // Duplicated text therefore accumulates probability without adding remote decimal scales.
        while (true) {
            DecimalChoice choice = weights.get(random.nextInt(weights.size()));
            if (acceptDecimal(choice.weight(), maximum, random)) return choice.text();
        }
    }

    private static boolean acceptDecimal(
            BigDecimal weight, BigDecimal maximum, RandomGenerator random) {
        if (weight.compareTo(maximum) == 0) return true;
        BigInteger radix = BigInteger.valueOf(1_000_000_000);
        BigInteger prefix = BigInteger.ZERO;
        BigInteger denominator = BigInteger.ONE;
        while (true) {
            prefix = prefix.multiply(radix).add(BigInteger.valueOf(random.nextInt(1_000_000_000)));
            denominator = denominator.multiply(radix);
            BigInteger target = weight.unscaledValue().multiply(denominator);
            BigInteger lower = maximum.unscaledValue().multiply(prefix);
            if (compareDecimal(target, weight.scale(), lower, maximum.scale()) <= 0) return false;
            BigInteger upper = lower.add(maximum.unscaledValue());
            if (compareDecimal(target, weight.scale(), upper, maximum.scale()) >= 0) return true;
        }
    }

    private static int compareDecimal(
            BigInteger left, int leftScale, BigInteger right, int rightScale) {
        if (right.signum() == 0) return left.signum();
        int leftDigits = left.toString().length();
        int rightDigits = right.toString().length();
        int magnitude =
                Long.compare((long) leftDigits - leftScale, (long) rightDigits - rightScale);
        if (magnitude != 0) return magnitude;
        // Equal magnitudes bound the required alignment by coefficient length, not exponent size.
        if (leftDigits < rightDigits)
            left = left.multiply(BigInteger.TEN.pow(rightDigits - leftDigits));
        else if (rightDigits < leftDigits)
            right = right.multiply(BigInteger.TEN.pow(leftDigits - rightDigits));
        return left.compareTo(right);
    }

    private record SequenceChoice(String text, double weight, int position) {}

    private static List<SequenceChoice> sequenceChoices(
            List<String> sources, boolean occurrences, NiEvaluation evaluation) {
        List<SequenceChoice> choices = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();
        for (int position = 0; position < sources.size(); position++) {
            WeightedText entry = WeightedText.read(evaluation.text(sources.get(position)));
            double weight;
            try {
                weight = Double.parseDouble(entry.weight());
            } catch (NumberFormatException invalid) {
                weight = 1;
            }
            if (occurrences) choices.add(new SequenceChoice(entry.text(), weight, position));
            else if (weight > 0) {
                Integer previous = positions.get(entry.text());
                if (previous == null) {
                    positions.put(entry.text(), choices.size());
                    choices.add(new SequenceChoice(entry.text(), weight, position));
                } else {
                    SequenceChoice merged = choices.get(previous);
                    choices.set(
                            previous,
                            new SequenceChoice(entry.text(), merged.weight() + weight, position));
                }
            }
        }
        return choices;
    }

    private record Race(SequenceChoice choice, double priority) {}

    private static List<SequenceChoice> selectSequence(
            List<SequenceChoice> available,
            int count,
            boolean occurrences,
            RandomGenerator random) {
        if (count == 0) return new ArrayList<>();
        double total = 0;
        boolean ordinary = true;
        for (SequenceChoice choice : available) {
            total += choice.weight();
            ordinary &= choice.weight() > 0 && Double.isFinite(choice.weight());
        }
        if (!ordinary || !Double.isFinite(total)) {
            if (!occurrences) {
                // Exceptional double boundaries make this map order observable, unlike races.
                Map<String, SequenceChoice> byText = new HashMap<>();
                for (SequenceChoice choice : available) byText.put(choice.text(), choice);
                available = new ArrayList<>(byText.values());
                total = 0;
                for (SequenceChoice choice : available) total += choice.weight();
            }
            return selectExceptionalSequence(available, count, total, random);
        }
        List<Race> races = new ArrayList<>(available.size());
        for (SequenceChoice choice : available) {
            double draw = random.nextDouble();
            // Logarithms avoid division overflow for finite weights near the double limits.
            double priority = Math.log(-Math.log1p(-draw)) - Math.log(choice.weight());
            races.add(new Race(choice, priority));
        }
        races.sort(Comparator.comparingDouble(Race::priority));
        List<SequenceChoice> selected = new ArrayList<>();
        for (int index = 0; index < count; index++) selected.add(races.get(index).choice());
        return selected;
    }

    private static List<SequenceChoice> selectExceptionalSequence(
            List<SequenceChoice> available, int count, double total, RandomGenerator random) {
        List<SequenceChoice> remaining = new ArrayList<>(available);
        List<SequenceChoice> selected = new ArrayList<>();
        for (int draw = 0; draw < count; draw++) {
            double ticket = total * random.nextDouble();
            double boundary = 0;
            int found = -1;
            for (int index = 0; index < remaining.size(); index++) {
                boundary += remaining.get(index).weight();
                if (ticket <= boundary) {
                    found = index;
                    break;
                }
            }
            if (found < 0) continue;
            SequenceChoice choice = remaining.remove(found);
            selected.add(choice);
            // Keep IEEE arithmetic after removals: infinite totals can become NaN here.
            total -= choice.weight();
        }
        return selected;
    }
}

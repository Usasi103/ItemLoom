package dev.itemloom.compat.ni;

import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.entity.Player;

public final class NiCollectionNodes {
    private NiCollectionNodes() {}

    private record Choice(String text, double weight, int index) {}

    private record DecimalChoice(String text, BigDecimal weight) {}

    private record Race(Choice choice, double arrival) {}

    private static String field(
            NiConfig config, String name, String fallback, NiEvaluation evaluation) {
        return evaluation.text(config.string(name, fallback));
    }

    private static List<String> list(Object source) {
        List<String> result = new ArrayList<>();
        if (source instanceof List<?> list)
            for (Object value : list) if (value != null) result.add(value.toString());
        return result;
    }

    public static String choices(
            String type, List<String> original, NiEvaluation evaluation, boolean configured) {
        if (original.isEmpty()) return null;
        boolean section = evaluation.mode() == NiEvaluation.Mode.SECTION;
        List<String> entries = new ArrayList<>(original);
        if (configured && (!section || type.equals("weight"))) entries.replaceAll(evaluation::text);
        String chosen;
        if (!type.equals("weight"))
            chosen = entries.get(evaluation.generation().random().nextInt(entries.size()));
        else {
            List<DecimalChoice> choices = new ArrayList<>();
            long largest = Long.MIN_VALUE;
            for (String entry : entries) {
                int split = entry.indexOf("::");
                BigDecimal weight = BigDecimal.ONE;
                String text = entry;
                if (split >= 0) {
                    text = entry.substring(split + 2);
                    try {
                        weight = new BigDecimal(entry.substring(0, split));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (weight.signum() <= 0) continue;
                choices.add(new DecimalChoice(text, weight));
                largest = Math.max(largest, (long) weight.precision() - weight.scale());
            }
            if (choices.isEmpty()) return null;
            double[] normalized = new double[choices.size()];
            double total = 0;
            for (int i = 0; i < choices.size(); i++) {
                BigDecimal weight = choices.get(i).weight;
                long exponent = (long) weight.precision() - weight.scale() - largest;
                String digits = weight.unscaledValue().abs().toString();
                int length = Math.min(digits.length(), 16);
                double leading =
                        Double.parseDouble(digits.substring(0, length)) / Math.pow(10, length - 1);
                normalized[i] = exponent < -324 ? 0 : leading * Math.pow(10, exponent);
                total += normalized[i];
            }
            double target = evaluation.generation().random().nextDouble() * total;
            chosen = choices.getLast().text;
            for (int i = 0; i < choices.size(); i++)
                if ((target -= normalized[i]) < 0) {
                    chosen = choices.get(i).text;
                    break;
                }
        }
        return configured && section ? evaluation.text(chosen) : chosen;
    }

    private static String transform(
            String source, String value, int index, List<String> list, NiEvaluation evaluation) {
        if (source == null || source.isEmpty()) return value;
        Map<String, Object> bindings = evaluation.bindings();
        bindings.put("it", value);
        bindings.put("index", index);
        if (list != null) bindings.put("list", list);
        Object result = evaluation.scripts().transform(source, bindings);
        return result == null ? "" : result.toString();
    }

    private static <T> void shuffle(List<T> values, RandomGenerator random) {
        for (int bound = values.size(); bound > 1; bound--)
            Collections.swap(values, bound - 1, random.nextInt(bound));
    }

    public static String join(NiConfig config, NiEvaluation evaluation) {
        Object raw =
                evaluation.mode() == NiEvaluation.Mode.ACTION && config.get("values") != null
                        ? config.get("values")
                        : config.get("list");
        if (evaluation.mode() == NiEvaluation.Mode.ACTION && !(raw instanceof List<?>)) return null;
        List<String> values = list(raw);
        String separator = field(config, "separator", ", ", evaluation);
        String prefix = field(config, "prefix", "", evaluation);
        String postfix = field(config, "postfix", "", evaluation);
        int limit = NiNodes.integer(field(config, "limit", null, evaluation), values.size());
        String truncated = field(config, "truncated", null, evaluation);
        boolean shuffled = "true".equals(field(config, "shuffled", null, evaluation));
        if (shuffled) shuffle(values, evaluation.generation().random());
        List<String> included = new ArrayList<>();
        for (int i = 0; i < Math.clamp(limit, 0, values.size()); i++)
            included.add(
                    transform(
                            config.string("transform"),
                            evaluation.text(values.get(i)),
                            i,
                            values,
                            evaluation));
        if (limit < values.size() && truncated != null) included.add(truncated);
        return prefix + String.join(separator, included) + postfix;
    }

    public static String repeat(NiConfig config, NiEvaluation evaluation) {
        String content = field(config, "content", "", evaluation);
        String count = null;
        if (evaluation.mode() == NiEvaluation.Mode.ACTION)
            count = field(config, "repeat", null, evaluation);
        String separator = field(config, "separator", "", evaluation);
        String prefix = field(config, "prefix", "", evaluation);
        String postfix = field(config, "postfix", "", evaluation);
        if (evaluation.mode() == NiEvaluation.Mode.SECTION)
            count = field(config, "repeat", null, evaluation);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < NiNodes.integer(count, 1); i++)
            values.add(transform(config.string("transform"), content, i, null, evaluation));
        return prefix + String.join(separator, values) + postfix;
    }

    public static String inlineRepeat(List<String> arguments, NiEvaluation evaluation) {
        if (evaluation.mode() == NiEvaluation.Mode.SECTION) return null;
        String content = NiNodes.arg(arguments, 0), separator = NiNodes.arg(arguments, 2);
        content = content == null ? "" : content;
        separator = separator == null ? "" : separator;
        return String.join(
                separator,
                Collections.nCopies(
                        Math.max(0, NiNodes.integer(NiNodes.arg(arguments, 1), 1)), content));
    }

    private static Choice parse(String text, int index) {
        int delimiter = text.indexOf("::");
        if (delimiter < 0) return new Choice(text, 1, index);
        return new Choice(
                text.substring(delimiter + 2),
                NiNodes.decimal(text.substring(0, delimiter), 1),
                index);
    }

    public static String weightedSequence(String type, NiConfig config, NiEvaluation evaluation) {
        boolean repeated = type.startsWith("r"), declaration = type.endsWith("declare");
        String separator = "", prefix = "", postfix = "";
        if (!declaration) {
            separator = field(config, "separator", ", ", evaluation);
            prefix = field(config, "prefix", "", evaluation);
            postfix = field(config, "postfix", "", evaluation);
        }
        boolean shuffled = "true".equals(field(config, "shuffled", null, evaluation));
        boolean ordered = "true".equals(field(config, "order", null, evaluation));
        List<Choice> candidates = new ArrayList<>();
        Map<String, Choice> distinct = new HashMap<>();
        List<String> source = list(config.get("list"));
        for (int i = 0; i < source.size(); i++) {
            Choice entry = parse(evaluation.text(source.get(i)), i);
            if (repeated) candidates.add(entry);
            else if (entry.weight > 0) {
                Choice prior = distinct.get(entry.text);
                distinct.put(
                        entry.text,
                        new Choice(
                                entry.text, entry.weight + (prior == null ? 0 : prior.weight), i));
            }
        }
        if (!repeated) candidates.addAll(distinct.values());
        String key = declaration ? field(config, "key", null, evaluation) : null;
        int requested =
                Math.clamp(
                        NiNodes.integer(field(config, "amount", null, evaluation), 1),
                        0,
                        candidates.size());
        int needed = requested;
        Map<String, String> cache = evaluation.legacyCache();
        if (declaration && !repeated && key != null && cache != null) {
            for (int i = 0; i < requested; i++) {
                String previous = cache.get(key + "." + i);
                if (previous != null) {
                    for (int j = 0; j < candidates.size(); j++)
                        if (candidates.get(j).text.equals(previous)) {
                            candidates.remove(j);
                            needed--;
                            break;
                        }
                }
            }
        }
        List<Choice> selected = draw(candidates, needed, evaluation.generation().random());
        if (shuffled) shuffle(selected, evaluation.generation().random());
        else if (ordered) selected.sort(Comparator.comparingInt(Choice::index));
        boolean putElse =
                declaration
                        && !repeated
                        && "true".equals(field(config, "putelse", null, evaluation));
        if (declaration) {
            if (cache == null) return null;
            if (key != null) {
                int slot = 0;
                for (Choice entry : selected) {
                    while (cache.containsKey(key + "." + slot)) slot++;
                    cache.put(key + "." + slot++, entry.text);
                }
                cache.put(key + ".length", String.valueOf(requested));
                if (putElse) {
                    for (int i = 0; i < candidates.size(); i++)
                        cache.put(key + ".else." + i, candidates.get(i).text);
                    cache.put(key + ".else.length", String.valueOf(candidates.size()));
                }
            }
            return cache.get(key + ".0");
        }
        List<String> texts = selected.stream().map(Choice::text).toList();
        List<String> output = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++)
            output.add(transform(config.string("transform"), texts.get(i), i, texts, evaluation));
        return prefix + String.join(separator, output) + postfix;
    }

    private static List<Choice> draw(List<Choice> remaining, int count, RandomGenerator random) {
        List<Choice> result = new ArrayList<>();
        if (count <= 0) return result;
        double total = 0;
        boolean finitePositive = true;
        for (Choice value : remaining) {
            total += value.weight;
            finitePositive &= value.weight > 0 && Double.isFinite(value.weight);
        }
        if (finitePositive && Double.isFinite(total)) {
            List<Choice> sourceOrder = new ArrayList<>(remaining);
            sourceOrder.sort(Comparator.comparingInt(Choice::index));
            List<Race> arrivals = new ArrayList<>();
            for (Choice entry : sourceOrder) {
                double arrival =
                        Math.log(-Math.log1p(-random.nextDouble())) - Math.log(entry.weight);
                arrivals.add(new Race(entry, arrival));
            }
            arrivals.sort(Comparator.comparingDouble(Race::arrival));
            for (int i = 0; i < Math.min(count, arrivals.size()); i++)
                result.add(arrivals.get(i).choice);
            remaining.removeAll(result);
            return result;
        }
        for (int round = 0; round < count; round++) {
            double target = random.nextDouble() * total;
            double boundary = 0;
            for (int i = 0; i < remaining.size(); i++) {
                Choice entry = remaining.get(i);
                boundary += entry.weight;
                if (target <= boundary) {
                    result.add(entry);
                    remaining.remove(i);
                    total -= entry.weight;
                    break;
                }
            }
        }
        return result;
    }

    public static String when(NiConfig config, NiEvaluation evaluation) {
        String value = field(config, "value", null, evaluation);
        if (!(config.get("conditions") instanceof List<?> conditions)) return null;
        Map<String, Object> cache = evaluation.cache();
        Object previous = cache.get("value");
        if (value != null) cache.put("value", value);
        boolean accepted = false;
        try {
            for (Object entry : conditions) {
                Object result = entry;
                if (entry instanceof Map<?, ?> map) {
                    Object condition = map.get("condition");
                    if (condition != null && !(condition instanceof String)) continue;
                    Map<String, Object> params = new HashMap<>();
                    params.put("value", value);
                    params.put("cache", cache);
                    var sections = NiYaml.toSection(evaluation.sections());
                    boolean action = evaluation.mode() == NiEvaluation.Mode.ACTION;
                    params.put(
                            "sections",
                            action ? new LegacyConfigReader.BukkitReader(sections) : sections);
                    Object caster =
                            evaluation.player() instanceof Player ? evaluation.player() : null;
                    NiActionContext context =
                            new NiActionContext(
                                    evaluation,
                                    caster,
                                    action ? params : null,
                                    params,
                                    evaluation.scripts()::isOpen);
                    if (!context.condition((String) condition)) continue;
                    result = map.get("result");
                }
                accepted = true;
                return evaluation.text(String.valueOf(result));
            }
            return null;
        } finally {
            if (evaluation.mode() == NiEvaluation.Mode.ACTION && value != null) {
                if (previous == null) cache.remove("value");
                else cache.put("value", previous);
            } else if (evaluation.mode() == NiEvaluation.Mode.SECTION && accepted)
                cache.remove("value");
        }
    }
}

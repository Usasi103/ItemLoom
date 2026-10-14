package dev.itemloom.core.compare;

import dev.itemloom.core.compare.ComparisonModel.Attribute;
import dev.itemloom.core.compare.ComparisonModel.Bar;
import dev.itemloom.core.compare.ComparisonModel.Settings;
import dev.itemloom.core.compare.ComparisonModel.Slot;
import dev.itemloom.core.compare.ComparisonModel.Snapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** GearCompare-compatible decimal comparisons and roll-bar overlays, without platform access. */
public final class Comparison {
    public static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]*");
    public static final Pattern SLOT = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");
    private static final Pattern UID = Pattern.compile("[a-z0-9]{1,32}");
    private static final Pattern SOURCE_KEY = Pattern.compile("[a-z][a-z0-9_%-]*");

    private Comparison() {}

    public static Map<String, BigDecimal> parseStats(String text) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        if (text != null) {
            for (String entry : text.split(";")) {
                int separator = entry.indexOf('=');
                if (separator < 0) continue;
                String key = entry.substring(0, separator).trim();
                BigDecimal value = number(entry.substring(separator + 1).trim());
                if (SOURCE_KEY.matcher(key).matches() && value != null) result.put(key, value);
            }
        }
        return Map.copyOf(result);
    }

    public static BigDecimal number(String text) {
        if (text == null || text.length() > 128 || !NUMBER.matcher(text).matches()) return null;
        return new BigDecimal(text);
    }

    public static String resolve(String parameters, Snapshot snapshot, Settings settings) {
        if (parameters == null || parameters.length() > 1024 || snapshot == null) return "";
        String[] parts = parameters.split("_", -1);
        boolean equipped = parts[0].equals("equipped");
        boolean bar = parts[0].equals("bar");
        int tail = equipped ? 1 : bar ? 5 : 3;
        int start = equipped || bar ? 1 : 0;
        int boundary = parts.length - tail;
        if (boundary <= start) return "";
        String slot = String.join("_", Arrays.copyOfRange(parts, start, boundary));
        String uid = parts[parts.length - 1];
        if (!settings.slots().containsKey(slot) || !UID.matcher(uid).matches()) return "";
        Slot state = snapshot.slots().getOrDefault(slot, Slot.EMPTY);
        if (equipped) return state.isItem(uid) ? settings.equipped() : "";
        String key = parts[boundary];
        if (!KEY.matcher(key).matches()) return "";
        BigDecimal value = number(parts[boundary + 1]);
        if (value == null) return "";
        if (state.isItem(uid)) return bar ? "" : settings.sameItem();
        BigDecimal against = against(key, state, settings);
        if (against == null) return "";
        Attribute attribute = settings.attributes().get(key);
        BigDecimal effective = effective(value, attribute, snapshot.base());
        BigDecimal reference = effective(against, attribute, snapshot.base());
        if (effective == null || reference == null) return "";
        if (bar) {
            BigDecimal min = number(parts[boundary + 2]);
            BigDecimal max = number(parts[boundary + 3]);
            if (min == null || max == null || max.compareTo(min) <= 0) return "";
            return overlay(value, against, min, max, key, settings);
        }
        Map<String, BigDecimal> candidate = snapshot.candidates().get(uid);
        if (candidate != null && attribute != null && (state.empty() || state.stats() != null)) {
            effective = total(key, candidate, settings, snapshot.base());
            reference =
                    total(key, state.empty() ? Map.of() : state.stats(), settings, snapshot.base());
            if (effective == null || reference == null) return "";
        }
        BigDecimal difference = effective.subtract(reference);
        int decimals = Math.min(2, Math.max(0, Math.max(effective.scale(), reference.scale())));
        BigDecimal shown = difference.abs().setScale(decimals, RoundingMode.HALF_UP);
        if (shown.signum() == 0) return settings.same();
        boolean better = difference.signum() > 0;
        if (settings.lowerIsBetter().contains(key)) better = !better;
        return (better ? settings.better() : settings.worse())
                .replace("{diff}", shown.toPlainString());
    }

    public static BigDecimal effective(
            BigDecimal value, Attribute attribute, Map<String, BigDecimal> base) {
        if (attribute == null || !attribute.percent()) return value;
        BigDecimal fixed = base.get(attribute.baseKey());
        return fixed == null ? null : fixed.multiply(value).movePointLeft(2);
    }

    private static BigDecimal total(
            String key,
            Map<String, BigDecimal> values,
            Settings settings,
            Map<String, BigDecimal> base) {
        Attribute target = settings.attributes().get(key);
        String group = target.percent() ? target.baseKey() : target.providerKey();
        BigDecimal result = BigDecimal.ZERO;
        java.util.Set<String> included = new java.util.HashSet<>();
        for (var entry : settings.attributes().entrySet()) {
            Attribute definition = entry.getValue();
            String ownGroup =
                    definition.percent() ? definition.baseKey() : definition.providerKey();
            if (!group.equals(ownGroup) || included.contains(definition.providerKey())) continue;
            BigDecimal raw = values.get(entry.getKey());
            if (raw == null) raw = values.get(definition.providerKey());
            if (raw == null) continue;
            included.add(definition.providerKey());
            BigDecimal contribution = effective(raw, definition, base);
            if (contribution == null) return null;
            result = result.add(contribution);
        }
        return result;
    }

    private static BigDecimal against(String key, Slot state, Settings settings) {
        BigDecimal zero = settings.hideEmpty() ? null : BigDecimal.ZERO;
        if (state.empty()) return zero;
        if (state.stats() == null || state.stats().isEmpty()) return null;
        return state.stats().getOrDefault(key, zero);
    }

    private static String overlay(
            BigDecimal mine,
            BigDecimal theirs,
            BigDecimal min,
            BigDecimal max,
            String key,
            Settings settings) {
        Bar bar = settings.bar();
        if (!bar.enabled() || settings.lowerIsBetter().contains(key)) return "";
        int own = level(mine, min, max, bar.segments());
        int other = level(theirs, min, max, bar.segments());
        if (own == other) return "";
        int from = Math.min(own, other);
        int to = Math.max(own, other);
        String glyph =
                (own > other ? bar.up() : bar.down())
                        .replace("{from}", String.valueOf((char) ('a' + from)))
                        .replace("{to}", String.valueOf((char) ('a' + to)));
        if (glyph.isEmpty()) return "";
        int rest = bar.width() - bar.segment() * to;
        return "<shift:-" + bar.width() + ">" + glyph + (rest == 0 ? "" : "<shift:" + rest + ">");
    }

    /** Same double-operation order as Division.js, including half-way rounding and clamping. */
    public static int level(BigDecimal value, BigDecimal min, BigDecimal max, int segments) {
        if (!(max.doubleValue() > min.doubleValue())) return segments;
        long position =
                Math.round(
                        (value.doubleValue() - min.doubleValue())
                                / (max.doubleValue() - min.doubleValue())
                                * segments);
        return (int) Math.max(0, Math.min(segments, position));
    }
}

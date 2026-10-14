package dev.itemloom.core.compare;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Immutable values shared by the server-thread snapshot builder and packet-thread placeholders. */
public final class ComparisonModel {
    private ComparisonModel() {}

    public record Attribute(String providerKey, boolean percent, String baseKey) {}

    public record Bar(
            boolean enabled, String up, String down, int width, int segment, int segments) {}

    public record Settings(
            Map<String, String> slots,
            Map<String, Attribute> attributes,
            Set<String> lowerIsBetter,
            boolean hideEmpty,
            String better,
            String worse,
            String same,
            String sameItem,
            String equipped,
            int sweepTicks,
            Bar bar) {
        public Settings {
            slots = Map.copyOf(slots);
            attributes = Map.copyOf(attributes);
            lowerIsBetter = Set.copyOf(lowerIsBetter);
        }
    }

    /** null stats means occupied but not comparable; empty stats still permits instance identity. */
    public record Slot(boolean empty, String uid, Map<String, BigDecimal> stats) {
        public static final Slot EMPTY = new Slot(true, "", null);
        public static final Slot PLAIN = new Slot(false, "", null);

        public Slot {
            uid = uid == null ? "" : uid.trim();
            stats = stats == null ? null : Map.copyOf(stats);
        }

        public boolean isItem(String other) {
            return !uid.isEmpty() && uid.equals(other);
        }
    }

    public record Snapshot(
            Map<String, Slot> slots,
            Map<String, BigDecimal> base,
            Map<String, Map<String, BigDecimal>> candidates) {
        public Snapshot {
            slots = Map.copyOf(slots);
            base = Map.copyOf(base);
            Map<String, Map<String, BigDecimal>> copy = new java.util.LinkedHashMap<>();
            candidates.forEach((uid, values) -> copy.put(uid, Map.copyOf(values)));
            candidates = Map.copyOf(copy);
        }

        public Snapshot(Map<String, Slot> slots, Map<String, BigDecimal> base) {
            this(slots, base, Map.of());
        }
    }
}

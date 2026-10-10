package dev.itemloom.paper.compat;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Value policies shared by adapters for the legacy item data format. */
public final class LegacyStateRules {
    private static final List<String> NUMERIC_STATE_KEYS = List.of("charge", "durability");

    private LegacyStateRules() {}

    /** Quantizes the lost fraction before applying the format's visible-durability rules. */
    public static short damage(int ordinaryMaximum, int remaining, int capacity) {
        double lostFraction = 1 - (double) remaining / capacity;
        short quantized = (short) (int) (ordinaryMaximum * lostFraction);
        return visibleDamage(quantized, ordinaryMaximum, remaining, capacity);
    }

    private static short visibleDamage(short damage, int maximum, int remaining, int capacity) {
        short visible = remaining < capacity && damage <= 0 ? 1 : damage;
        return remaining > 0 && visible >= maximum ? (short) (visible - 1) : visible;
    }

    /** Size has priority; equal-sized inputs retain their own iteration order. */
    public static <T> int compareOrdered(
            int leftSize,
            Iterable<? extends T> left,
            int rightSize,
            Iterable<? extends T> right,
            Comparator<? super T> entries) {
        int sizeOrder = Integer.compare(leftSize, rightSize);
        if (sizeOrder != 0) return sizeOrder;
        Iterator<? extends T> leftEntries = left.iterator();
        Iterator<? extends T> rightEntries = right.iterator();
        while (leftEntries.hasNext()) {
            int order = entries.compare(leftEntries.next(), rightEntries.next());
            if (order != 0) return order;
        }
        return 0;
    }

    /** Later keys are read only after earlier keys compare equal. */
    public static <T, C> Comparator<T> itemOrder(
            ToIntFunction<? super T> type,
            ToIntFunction<? super T> amount,
            Function<? super T, Short> damage,
            Function<? super T, ? extends C> compound,
            Comparator<? super C> compoundOrder) {
        Comparator<T> leading = Comparator.comparingInt(type);
        return leading.thenComparingInt(amount)
                .thenComparing(damage, Short::compare)
                .thenComparing(compound, Comparator.nullsFirst(compoundOrder));
    }

    /** Captures only present format-defined fields, including zero and negative values. */
    public static NumericState captureNumericState(
            Predicate<String> present, ToIntFunction<String> read) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String key : NUMERIC_STATE_KEYS) {
            if (present.test(key)) values.put(key, read.applyAsInt(key));
        }
        return new NumericState(values);
    }

    public static final class NumericState {
        private final Map<String, Integer> values;

        private NumericState(Map<String, Integer> values) {
            this.values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public void applyTo(ObjIntConsumer<String> write) {
            values.forEach((key, value) -> write.accept(key, value));
        }
    }
}

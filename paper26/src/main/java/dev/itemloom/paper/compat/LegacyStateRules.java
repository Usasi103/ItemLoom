package dev.itemloom.paper.compat;

import java.util.*;
import java.util.function.*;

public final class LegacyStateRules {
    private LegacyStateRules() {}

    public static short damage(int maximum, int remaining, int capacity) {
        short value = (short) (int) ((1 - remaining / (double) capacity) * maximum);
        if (remaining < capacity && value <= 0) value = 1;
        if (remaining > 0 && value >= maximum) value--;
        return value;
    }

    public static <T> int compareOrdered(
            int leftSize,
            Iterable<? extends T> left,
            int rightSize,
            Iterable<? extends T> right,
            Comparator<? super T> compare) {
        int size = Integer.compare(leftSize, rightSize);
        if (size != 0) return size;
        Iterator<? extends T> a = left.iterator(), b = right.iterator();
        while (a.hasNext() && b.hasNext()) {
            int value = compare.compare(a.next(), b.next());
            if (value != 0) return value;
        }
        return 0;
    }

    public static <T, C> Comparator<T> itemOrder(
            ToIntFunction<? super T> type,
            ToIntFunction<? super T> amount,
            Function<? super T, Short> damage,
            Function<? super T, ? extends C> compound,
            Comparator<? super C> compare) {
        return (left, right) -> {
            int value = Integer.compare(type.applyAsInt(left), type.applyAsInt(right));
            if (value == 0)
                value = Integer.compare(amount.applyAsInt(left), amount.applyAsInt(right));
            if (value == 0) value = Short.compare(damage.apply(left), damage.apply(right));
            if (value != 0) return value;
            C a = compound.apply(left), b = compound.apply(right);
            return a == null ? b == null ? 0 : -1 : b == null ? 1 : compare.compare(a, b);
        };
    }

    public static NumericState captureNumericState(
            Predicate<String> present, ToIntFunction<String> value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String key : List.of("charge", "durability"))
            if (present.test(key)) result.put(key, value.applyAsInt(key));
        return new NumericState(result);
    }

    public static final class NumericState {
        private final Map<String, Integer> values;

        private NumericState(Map<String, Integer> values) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public void applyTo(ObjIntConsumer<String> target) {
            values.forEach(target::accept);
        }
    }
}

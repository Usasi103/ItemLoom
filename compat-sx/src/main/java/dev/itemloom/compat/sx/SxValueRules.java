package dev.itemloom.compat.sx;

import java.util.Map;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

/** Scalar decoding and lock transactions, independent of expression parsing. */
public final class SxValueRules {
    private static final Map<String, Function<String, Object>> SCALARS =
            Map.of(
                    "byte", Byte::valueOf,
                    "short", Short::valueOf,
                    "int", Integer::valueOf,
                    "long", Long::valueOf,
                    "float", Float::valueOf,
                    "double", Double::valueOf);

    private SxValueRules() {}

    public static Object scalar(String text) {
        if (text == null || !text.startsWith("[")) return text;
        int end = text.indexOf(']');
        if (end < 0) return text;
        Function<String, Object> decoder = SCALARS.get(text.substring(1, end));
        return decoder == null ? text : decoder.apply(text.substring(end + 1));
    }

    /** The chooser receives an exclusive upper bound, as in RandomGenerator.nextInt. */
    public static String locked(
            String argument,
            Map<String, String> locks,
            Map<String, String> other,
            Function<String, String> randomKey,
            IntUnaryOperator choiceIndex,
            Function<String, String> expand) {
        LockRequest request = LockRequest.parse(argument);
        return new LockTransaction(locks, request.key())
                .resolve(() -> request.source().choose(other, randomKey, choiceIndex), expand);
    }

    private record LockRequest(String key, Source source) {
        static LockRequest parse(String argument) {
            int separator = argument.indexOf('#');
            if (separator < 0) return new LockRequest(argument, new RandomKey(argument));
            String key = argument.substring(0, separator);
            return new LockRequest(
                    key, new InlineChoices(key, argument.substring(separator + 1).split(":", -1)));
        }
    }

    private interface Source {
        String choose(
                Map<String, String> other,
                Function<String, String> randomKey,
                IntUnaryOperator choiceIndex);
    }

    private record RandomKey(String key) implements Source {
        @Override
        public String choose(
                Map<String, String> other,
                Function<String, String> randomKey,
                IntUnaryOperator choiceIndex) {
            return randomKey.apply(key);
        }
    }

    private record InlineChoices(String key, String[] choices) implements Source {
        @Override
        public String choose(
                Map<String, String> other,
                Function<String, String> randomKey,
                IntUnaryOperator choiceIndex) {
            String supplied = other.get(key);
            return supplied != null ? supplied : choices[choiceIndex.applyAsInt(choices.length)];
        }
    }

    private record LockTransaction(Map<String, String> locks, String key) {
        String resolve(
                java.util.function.Supplier<String> source, Function<String, String> expand) {
            String cached = locks.get(key);
            if (cached != null) return cached;
            String result = expand.apply(source.get());
            locks.put(key, result);
            return result;
        }
    }
}

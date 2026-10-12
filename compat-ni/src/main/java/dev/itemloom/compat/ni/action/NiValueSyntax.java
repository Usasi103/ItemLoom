package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.NiConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared scalar grammar; branch decisions and domain conversion remain injected policies. */
final class NiValueSyntax {
    static <T> Function<NiActionContext, T> compile(
            Object input,
            Class<T> type,
            Consumer<String> validate,
            Function<NiConfig, Function<NiActionContext, T>> branchCompiler,
            Function<Object, T> convert,
            Function<Object, T> scriptValue) {
        if (input == null) {
            return context -> null;
        }
        if (input instanceof List<?> list) {
            List<Function<NiActionContext, T>> alternatives = new ArrayList<>();
            for (Object child : list) {
                alternatives.add(
                        compile(child, type, validate, branchCompiler, convert, scriptValue));
            }
            return context -> {
                for (var alternative : alternatives) {
                    T value = alternative.apply(context);
                    if (value != null) {
                        return value;
                    }
                }
                return null;
            };
        }
        if (input instanceof String text) {
            return text(text, type, validate, convert, scriptValue);
        }
        NiConfig config = NiValues.config(input);
        if (config != null) {
            Function<NiActionContext, T> branch = branchCompiler.apply(config);
            if (branch != null) {
                return branch;
            }
            if (config.values().size() != 1) {
                return context -> null;
            }
            var entry = config.values().entrySet().iterator().next();
            if (entry.getValue() == null) {
                return context -> null;
            }
            if (entry.getKey().equals("raw") || entry.getKey().equals("js")) {
                return text(
                        entry.getKey() + ": " + config.string(entry.getKey()),
                        type,
                        validate,
                        convert,
                        scriptValue);
            }
        }
        T constant = convert.apply(input);
        return context -> constant;
    }

    private static <T> Function<NiActionContext, T> text(
            String text,
            Class<T> type,
            Consumer<String> validate,
            Function<Object, T> convert,
            Function<Object, T> scriptValue) {
        int delimiter = text.indexOf(": ");
        String prefix =
                (delimiter < 0 ? text : text.substring(0, delimiter)).toLowerCase(Locale.ROOT);
        if (prefix.equals("raw") || prefix.equals("js")) {
            if (delimiter < 0) {
                return context -> null;
            }
            String body = text.substring(delimiter + 2);
            if (prefix.equals("raw")) {
                T value = convert.apply(body);
                return context -> value;
            }
            validate.accept(body);
            return context -> {
                try {
                    return scriptValue.apply(context.evaluate(body));
                } catch (RuntimeException error) {
                    context.evaluation()
                            .warning("Value script failed: " + body + ": " + error.getMessage());
                    return null;
                }
            };
        }
        T initial = convert.apply(text);
        if (initial != null
                && !(type == String.class && text.indexOf('<') >= 0 && text.indexOf('>') >= 0)) {
            return context -> initial;
        }
        return context -> convert.apply(context.parse(text));
    }
}

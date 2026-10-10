package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.NiConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Scalar value grammar. Branch selection and type conversion are supplied by the caller. */
final class NiValueSyntax {
    private sealed interface Input permits Scalar, Text, Alternatives, Configuration {}

    private record Scalar(Object value) implements Input {}

    private enum TextMode {
        SCRIPT,
        RAW,
        TEMPLATE
    }

    private record Text(TextMode mode, String value) implements Input {}

    private record Alternatives(List<?> values) implements Input {}

    private record Configuration(NiConfig config, Object original) implements Input {}

    private NiValueSyntax() {}

    static <T> Function<NiActionContext, T> compile(
            Object input,
            Class<T> type,
            Consumer<String> validate,
            Function<NiConfig, Function<NiActionContext, T>> branchCompiler,
            Function<Object, T> convert,
            Function<Object, T> scriptValue) {
        return new Compiler<>(type, validate, branchCompiler, convert, scriptValue).compile(input);
    }

    private static Input decode(Object input) {
        if (input instanceof String text) {
            int delimiter = text.indexOf(": ");
            String prefix =
                    (delimiter < 0 ? text : text.substring(0, delimiter)).toLowerCase(Locale.ROOT);
            TextMode mode =
                    switch (prefix) {
                        case "js" -> TextMode.SCRIPT;
                        case "raw" -> TextMode.RAW;
                        default -> TextMode.TEMPLATE;
                    };
            String value =
                    mode == TextMode.TEMPLATE
                            ? text
                            : delimiter < 0 ? null : text.substring(delimiter + 2);
            return new Text(mode, value);
        }
        if (input instanceof List<?> list) return new Alternatives(list);
        NiConfig config = NiValues.config(input);
        return config == null ? new Scalar(input) : new Configuration(config, input);
    }

    private record Compiler<T>(
            Class<T> type,
            Consumer<String> validate,
            Function<NiConfig, Function<NiActionContext, T>> branchCompiler,
            Function<Object, T> convert,
            Function<Object, T> scriptValue) {

        Function<NiActionContext, T> compile(Object input) {
            if (input == null) return constant(null);
            return switch (decode(input)) {
                case Scalar scalar -> constant(convert.apply(scalar.value()));
                case Text text -> text(text);
                case Alternatives alternatives -> alternatives(alternatives.values());
                case Configuration configuration -> configuration(configuration);
            };
        }

        private Function<NiActionContext, T> text(Text text) {
            return switch (text.mode()) {
                case RAW -> constant(convert.apply(text.value()));
                case SCRIPT -> {
                    if (text.value() == null) yield constant(null);
                    validate.accept(text.value());
                    yield context -> {
                        try {
                            return scriptValue.apply(context.evaluate(text.value()));
                        } catch (RuntimeException error) {
                            context.evaluation()
                                    .warning("Value script failed: " + error.getMessage());
                            return null;
                        }
                    };
                }
                case TEMPLATE -> {
                    T value = convert.apply(text.value());
                    boolean interpolation =
                            type == String.class
                                    && text.value().contains("<")
                                    && text.value().contains(">");
                    yield value != null && !interpolation
                            ? constant(value)
                            : context -> convert.apply(context.parse(text.value()));
                }
            };
        }

        private Function<NiActionContext, T> alternatives(List<?> inputs) {
            List<Function<NiActionContext, T>> choices = new ArrayList<>(inputs.size());
            // Compile the entire list before evaluation, including alternatives after a constant.
            for (Object input : inputs) choices.add(compile(input));
            return context -> {
                for (Function<NiActionContext, T> choice : choices) {
                    T value = choice.apply(context);
                    if (value != null) return value;
                }
                return null;
            };
        }

        private Function<NiActionContext, T> configuration(Configuration input) {
            NiConfig config = input.config();
            Function<NiActionContext, T> branch = branchCompiler.apply(config);
            if (branch != null) return branch;
            if (config.keys().size() != 1) return constant(null);
            String key = config.keys().iterator().next();
            if (config.get(key) == null) return constant(null);
            return switch (key) {
                case "js", "raw" -> compile(key + ": " + config.string(key));
                default -> constant(convert.apply(input.original()));
            };
        }

        private Function<NiActionContext, T> constant(T value) {
            return context -> value;
        }
    }
}

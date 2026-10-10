package dev.itemloom.compat.ni.action;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.core.ActionFlow.Step;

/** NI action syntax compiled to source-neutral control flow; platform effects live in Host. */
public final class NiActions {
    public interface Host {
        CompletionStage<Result> execute(String id, String content, NiActionContext context);

        void fork(Step<NiActionContext> action, NiActionContext context, boolean asynchronous);

        default CompletionStage<Result> dispatch(
                Step<NiActionContext> action, NiActionContext context) {
            return action.run(context);
        }

        default CompletionStage<Result> custom(
                java.util.function.Supplier<CompletionStage<Result>> operation, boolean asyncSafe) {
            return operation.get();
        }
    }

    private final Host host;
    private final Consumer<String> validate;

    private record Handler(
            boolean asyncSafe,
            java.util.function.BiFunction<NiActionContext, String, ?> function) {}

    private final Map<String, Handler> handlers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Step<NiActionContext> EMPTY = ActionFlow.constant(Result.CONTINUE);
    // BaseActionManager.loadBasicActions registers these in NI's plugin-qualified table.
    // JS syntax and item editors never entered that table and must not gain this alias.
    private static final Set<String> BASIC_ACTIONS =
            Set.of(
                    "tell",
                    "tell-no-color",
                    "tellnocolor",
                    "tell-or-print",
                    "tell-or-print-no-color",
                    "chat",
                    "chat-with-color",
                    "chatwithcolor",
                    "command",
                    "player",
                    "command-no-color",
                    "commandnocolor",
                    "console",
                    "console-no-color",
                    "consolenocolor",
                    "broadcast",
                    "broadcast-no-color",
                    "broadcastnocolor",
                    "title",
                    "title-no-color",
                    "titlenocolor",
                    "broadcast-title",
                    "broadcast-title-no-color",
                    "actionbar",
                    "actionbar-no-color",
                    "actionbarnocolor",
                    "sound",
                    "give-money",
                    "givemoney",
                    "take-money",
                    "takemoney",
                    "give-exp",
                    "giveexp",
                    "take-exp",
                    "takeexp",
                    "set-exp",
                    "setexp",
                    "give-level",
                    "givelevel",
                    "take-level",
                    "takelevel",
                    "set-level",
                    "setlevel",
                    "give-food",
                    "givefood",
                    "take-food",
                    "takefood",
                    "set-food",
                    "setfood",
                    "give-saturation",
                    "givesaturation",
                    "take-saturation",
                    "takesaturation",
                    "set-saturation",
                    "setsaturation",
                    "give-health",
                    "givehealth",
                    "take-health",
                    "takehealth",
                    "set-health",
                    "sethealth",
                    "cast-skill",
                    "castskill",
                    "combo",
                    "combo-clear",
                    "comboclear",
                    "set-potion",
                    "setpotion",
                    "set-potion-effect",
                    "setpotioneffect",
                    "remove-potion",
                    "removepotion",
                    "remove-potion-effect",
                    "removepotioneffect",
                    "delay",
                    "return",
                    "return-weight",
                    "returnweight",
                    "set-global",
                    "setglobal",
                    "set-global-int",
                    "set-global-long",
                    "set-global-double",
                    "del-global",
                    "delglobal",
                    "take-ni-item",
                    "takeniitem",
                    "server",
                    "sync",
                    "async",
                    "catch-chat",
                    "catchchat",
                    "catch-sign",
                    "catchsign",
                    "clear-catch-chat",
                    "clear-catch-sign",
                    "func",
                    "set-cooldown",
                    "teleport",
                    "tp");

    public NiActions(Host host) {
        this(host, source -> {});
    }

    public NiActions(Host host, Consumer<String> validate) {
        this.host = Objects.requireNonNull(host);
        this.validate = Objects.requireNonNull(validate);
    }

    public void register(
            String id,
            boolean asyncSafe,
            java.util.function.BiFunction<NiActionContext, String, ?> handler) {
        handlers.put(
                id.toLowerCase(Locale.ROOT),
                new Handler(asyncSafe, Objects.requireNonNull(handler)));
    }

    public Step<NiActionContext> compile(Object input) {
        Step<NiActionContext> compiled = compileBody(input);
        return context ->
                host.dispatch(
                        current -> {
                            if (!current.active())
                                return CompletableFuture.completedFuture(Result.STOP);
                            try {
                                return compiled.run(current);
                            } catch (RuntimeException error) {
                                current.evaluation()
                                        .warning("Action evaluation failed: " + error.getMessage());
                                return CompletableFuture.completedFuture(Result.STOP);
                            }
                        },
                        context);
    }

    private Step<NiActionContext> compileBody(Object input) {
        return switch (NiActionSyntax.classify(input)) {
            case NiActionSyntax.Empty ignored -> EMPTY;
            case NiActionSyntax.Text form -> text(form.source());
            case NiActionSyntax.Sequence form ->
                    ActionFlow.sequence(form.values().stream().map(this::compile).toList());
            case NiActionSyntax.Nested form -> compile(form.value());
            case NiActionSyntax.Branch form -> compileBranch(form);
        };
    }

    private Step<NiActionContext> compileBranch(NiActionSyntax.Branch form) {
        NiConfig config = form.config();
        NiActionSyntax.Kind type = form.kind();
        return switch (type) {
            case CONDITION -> {
                String condition = config.string("condition");
                if (condition != null) validate.accept(condition);
                var yes = compile(config.get("actions"));
                var no = compile(config.get("deny"));
                var sync = compile(config.get("sync"));
                var async = compile(config.get("async"));
                yield context -> {
                    if (!context.condition(condition)) return no.run(context);
                    if (config.contains("sync")) host.fork(sync, context, false);
                    if (config.contains("async")) host.fork(async, context, true);
                    return yes.run(context);
                };
            }
            case LABEL ->
                    ActionFlow.label(
                            config.string("label", "label"), compile(config.get("actions")));
            case REPEAT -> {
                var amount = NiValues.compile(config.get("repeat"), Integer.class, validate);
                var body = compile(config.get("actions"));
                String key = config.string("global-id", "i");
                yield context -> {
                    Integer count = amount.apply(context);
                    int limit = count == null ? 0 : count;
                    int[] index = {0};
                    return ActionFlow.<NiActionContext>whileTrue(
                                    current -> current.active() && index[0] < limit,
                                    current -> {
                                        current.getGlobal().put(key, index[0]++);
                                        return body.run(current);
                                    })
                            .run(context);
                };
            }
            case WHILE -> {
                String condition = config.string("while");
                if (condition != null) validate.accept(condition);
                var body = compile(config.get("actions"));
                var finish = compile(config.get("finally"));
                yield context ->
                        ActionFlow.<NiActionContext>whileTrue(
                                        NiActionContext::active,
                                        current ->
                                                host.dispatch(
                                                        scoped ->
                                                                scoped.condition(condition)
                                                                        ? body.run(scoped)
                                                                        : CompletableFuture
                                                                                .completedFuture(
                                                                                        Result
                                                                                                .STOP),
                                                        current))
                                .run(context)
                                .thenCompose(ignored -> finish.run(context));
            }
            case CONTAINS, KEY, INT_TREE, DOUBLE_TREE -> {
                var fallback = compile(config.get("default-action"));
                var match = compile(config.get("match-action"));
                boolean contains = type == NiActionSyntax.Kind.CONTAINS;
                var select =
                        NiValues.select(
                                config,
                                "actions",
                                fallback,
                                branch ->
                                        contains
                                                ? compile(branch)
                                                : ActionFlow.sequence(
                                                        List.of(match, compile(branch))),
                                validate);
                yield context -> select.apply(context).run(context);
            }
            case WEIGHT, CONDITION_WEIGHT -> {
                var entries =
                        NiValues.weighted(
                                config.get("actions"),
                                type == NiActionSyntax.Kind.CONDITION_WEIGHT,
                                "actions",
                                this::compile,
                                validate);
                var amount = NiValues.compile(config.get("amount"), Integer.class, validate);
                boolean ordered = config.bool("order", false);
                yield context -> {
                    Integer count = amount.apply(context);
                    var selected =
                            NiValues.sample(entries, context, count == null ? 1 : count, ordered);
                    if (!selected.combineResults() && !selected.values().isEmpty())
                        return selected.values().getFirst().run(context);
                    return ActionFlow.all(selected.values()).run(context);
                };
            }
        };
    }

    private Step<NiActionContext> text(String source) {
        int separator = source.indexOf(": ");
        String id =
                (separator < 0 ? source : source.substring(0, separator)).toLowerCase(Locale.ROOT);
        String content = separator < 0 ? "" : source.substring(separator + 2);
        if (id.equals("js")) {
            validate.accept(content);
            return context -> {
                try {
                    return result(context.evaluate(content));
                } catch (RuntimeException error) {
                    context.evaluation()
                            .warning("Action script failed: " + source + ": " + error.getMessage());
                    return CompletableFuture.completedFuture(Result.STOP);
                }
            };
        }
        String converted = NiActionText.placeholders(content);
        return context -> {
            String actionId = registeredId(id);
            String value = context.parse(converted);
            Handler handler = handlers.get(actionId);
            if (handler != null)
                return host.custom(
                        () ->
                                context.invoke(
                                        () -> result(handler.function().apply(context, value))),
                        handler.asyncSafe());
            return switch (actionId) {
                case "return" ->
                        CompletableFuture.completedFuture(
                                new Result(true, value.isEmpty() ? null : value, 1));
                case "return-weight", "returnweight" -> {
                    String[] parts = value.split(" ", 2);
                    Integer priority = NiValues.strictInteger(parts[0]);
                    yield CompletableFuture.completedFuture(
                            new Result(
                                    true,
                                    parts.length > 1 ? parts[1] : null,
                                    priority == null ? 1 : priority));
                }
                case "set-global",
                        "setglobal",
                        "set-global-int",
                        "set-global-long",
                        "set-global-double",
                        "set-global-boolean" -> {
                    String[] parts = value.split(" ", 2);
                    if (parts.length == 2) {
                        Object parsed =
                                switch (actionId) {
                                    case "set-global-int" -> NiValues.strictInteger(parts[1]);
                                    case "set-global-long" -> NiValues.strictLong(parts[1]);
                                    case "set-global-double" ->
                                            NiValues.convert(parts[1], Double.class);
                                    case "set-global-boolean" ->
                                            NiValues.convert(parts[1], Boolean.class);
                                    default -> parts[1];
                                };
                        context.getGlobal().put(parts[0], parsed);
                    }
                    yield CompletableFuture.completedFuture(Result.CONTINUE);
                }
                case "del-global", "delglobal" -> {
                    context.getGlobal().remove(value);
                    yield CompletableFuture.completedFuture(Result.CONTINUE);
                }
                default -> host.execute(actionId, value, context);
            };
        };
    }

    private String registeredId(String id) {
        if (!id.startsWith("neigeitems.")) return id;
        String local = id.substring("neigeitems.".length());
        return BASIC_ACTIONS.contains(local) || handlers.containsKey(local) ? local : id;
    }

    private static CompletionStage<Result> result(Object value) {
        if (value instanceof dev.itemloom.compat.ni.script.LegacyActionResult legacy)
            return CompletableFuture.completedFuture(legacy.value());
        if (value instanceof Result result) return CompletableFuture.completedFuture(result);
        if (value instanceof Boolean bool)
            return CompletableFuture.completedFuture(bool ? Result.CONTINUE : Result.STOP);
        if (value instanceof CompletionStage<?> future)
            return future.thenCompose(NiActions::result);
        return CompletableFuture.completedFuture(Result.CONTINUE);
    }
}

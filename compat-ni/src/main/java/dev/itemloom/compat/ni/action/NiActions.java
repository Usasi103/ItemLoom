package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.script.LegacyActionResult;
import dev.itemloom.core.ActionFlow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Action plans retain a host dispatch boundary at every source-language nesting level. */
public final class NiActions {
    public interface Host {
        CompletionStage<ActionFlow.Result> execute(
                String id, String content, NiActionContext context);

        void fork(
                ActionFlow.Step<NiActionContext> action,
                NiActionContext context,
                boolean asynchronous);

        default CompletionStage<ActionFlow.Result> dispatch(
                ActionFlow.Step<NiActionContext> action, NiActionContext context) {
            return action.run(context);
        }

        default CompletionStage<ActionFlow.Result> custom(
                Supplier<CompletionStage<ActionFlow.Result>> operation, boolean asyncSafe) {
            return operation.get();
        }
    }

    private record Registration(
            boolean asyncSafe, BiFunction<NiActionContext, String, ?> handler) {}

    private static final Set<String> QUALIFIED_BASICS =
            Set.copyOf(
                    List.of(
                            """
            tell tell-no-color tellnocolor tell-or-print tell-or-print-no-color
            chat chat-with-color chatwithcolor command player command-no-color commandnocolor
            console console-no-color consolenocolor broadcast broadcast-no-color broadcastnocolor
            title title-no-color titlenocolor broadcast-title broadcast-title-no-color
            actionbar actionbar-no-color actionbarnocolor sound
            give-money givemoney take-money takemoney give-exp giveexp take-exp takeexp set-exp setexp
            give-level givelevel take-level takelevel set-level setlevel
            give-food givefood take-food takefood set-food setfood
            give-saturation givesaturation take-saturation takesaturation set-saturation setsaturation
            give-health givehealth take-health takehealth set-health sethealth
            cast-skill castskill combo combo-clear comboclear
            set-potion setpotion set-potion-effect setpotioneffect
            remove-potion removepotion remove-potion-effect removepotioneffect
            delay return return-weight returnweight set-global setglobal set-global-int set-global-long set-global-double
            del-global delglobal take-ni-item takeniitem server sync async catch-chat catchchat catch-sign catchsign
            clear-catch-chat clear-catch-sign func set-cooldown teleport tp
            """
                                    .strip()
                                    .split("\\s+")));
    private final Host host;
    private final Consumer<String> validate;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    public NiActions(Host host) {
        this(host, source -> {});
    }

    public NiActions(Host host, Consumer<String> validate) {
        this.host = Objects.requireNonNull(host);
        this.validate = Objects.requireNonNull(validate);
    }

    public void register(
            String id, boolean asyncSafe, BiFunction<NiActionContext, String, ?> handler) {
        registrations.put(
                Objects.requireNonNull(id).toLowerCase(Locale.ROOT),
                new Registration(asyncSafe, Objects.requireNonNull(handler)));
    }

    public ActionFlow.Step<NiActionContext> compile(Object input) {
        NiActionSyntax.Form form = NiActionSyntax.classify(input);
        ActionFlow.Step<NiActionContext> body =
                switch (form) {
                    case NiActionSyntax.Empty ignored ->
                            ActionFlow.constant(ActionFlow.Result.CONTINUE);
                    case NiActionSyntax.Text text -> text(text.source());
                    case NiActionSyntax.Sequence sequence -> {
                        List<ActionFlow.Step<NiActionContext>> children = new ArrayList<>();
                        sequence.values().forEach(value -> children.add(compile(value)));
                        yield ActionFlow.sequence(children);
                    }
                    case NiActionSyntax.Nested nested -> compile(nested.value());
                    case NiActionSyntax.Branch branch -> branch(branch.kind(), branch.config());
                };
        return dispatched(body);
    }

    private ActionFlow.Step<NiActionContext> dispatched(
            ActionFlow.Step<NiActionContext> operation) {
        ActionFlow.Step<NiActionContext> guarded =
                context -> {
                    if (!context.active()) {
                        return completed(ActionFlow.Result.STOP);
                    }
                    try {
                        return operation.run(context);
                    } catch (RuntimeException error) {
                        context.evaluation()
                                .warning("Action evaluation failed: " + error.getMessage());
                        return completed(ActionFlow.Result.STOP);
                    }
                };
        return context -> host.dispatch(guarded, context);
    }

    private ActionFlow.Step<NiActionContext> text(String source) {
        int delimiter = source.indexOf(": ");
        String id =
                (delimiter < 0 ? source : source.substring(0, delimiter)).toLowerCase(Locale.ROOT);
        String content = delimiter < 0 ? "" : source.substring(delimiter + 2);
        if (id.equals("js")) {
            validate.accept(content);
            return context -> {
                try {
                    return result(context.evaluate(content));
                } catch (RuntimeException error) {
                    context.evaluation()
                            .warning(
                                    "Action script failed: " + content + ": " + error.getMessage());
                    return completed(ActionFlow.Result.STOP);
                }
            };
        }
        String template = NiActionText.placeholders(content);
        return context -> {
            String rendered = context.parse(template);
            String resolved = id;
            if (id.startsWith("neigeitems.")) {
                String unqualified = id.substring("neigeitems.".length());
                if (registrations.containsKey(unqualified)
                        || QUALIFIED_BASICS.contains(unqualified)) {
                    resolved = unqualified;
                }
            }
            Registration registered = registrations.get(resolved);
            if (registered != null) {
                return host.custom(
                        () ->
                                result(
                                        context.invoke(
                                                () ->
                                                        registered
                                                                .handler()
                                                                .apply(context, rendered))),
                        registered.asyncSafe());
            }
            return basic(resolved, rendered, context);
        };
    }

    private CompletionStage<ActionFlow.Result> basic(
            String id, String content, NiActionContext context) {
        if (id.equals("return")) {
            return completed(new ActionFlow.Result(true, content.isEmpty() ? null : content, 1));
        }
        if (id.equals("return-weight") || id.equals("returnweight")) {
            int split = content.indexOf(' ');
            Integer priority =
                    NiValues.strictInteger(split < 0 ? content : content.substring(0, split));
            String label = split < 0 ? null : content.substring(split + 1);
            return completed(
                    new ActionFlow.Result(
                            true,
                            label == null || label.isEmpty() ? null : label,
                            priority == null ? 1 : priority));
        }
        if (id.equals("del-global") || id.equals("delglobal")) {
            context.getGlobal().remove(content);
            return completed(ActionFlow.Result.CONTINUE);
        }
        Class<?> target =
                switch (id) {
                    case "set-global", "setglobal" -> String.class;
                    case "set-global-int", "setglobal-int" -> Integer.class;
                    case "set-global-long", "setglobal-long" -> Long.class;
                    case "set-global-double", "setglobal-double" -> Double.class;
                    case "set-global-boolean", "setglobal-boolean" -> Boolean.class;
                    default -> null;
                };
        if (target != null) {
            int split = content.indexOf(' ');
            if (split >= 0) {
                String value = content.substring(split + 1);
                Object converted =
                        target == Integer.class
                                ? NiValues.strictInteger(value)
                                : target == Long.class
                                        ? NiValues.strictLong(value)
                                        : NiValues.convert(value, target);
                context.getGlobal().put(content.substring(0, split), converted);
            }
            return completed(ActionFlow.Result.CONTINUE);
        }
        return host.execute(id, content, context);
    }

    private ActionFlow.Step<NiActionContext> branch(NiActionSyntax.Kind kind, NiConfig config) {
        return switch (kind) {
            case CONDITION -> condition(config);
            case LABEL ->
                    ActionFlow.label(
                            config.string("label", "label"), compile(config.get("actions")));
            case REPEAT -> repeat(config);
            case WHILE -> loop(config);
            case CONTAINS, KEY, INT_TREE, DOUBLE_TREE ->
                    select(config, kind == NiActionSyntax.Kind.CONTAINS);
            case WEIGHT, CONDITION_WEIGHT ->
                    weight(config, kind == NiActionSyntax.Kind.CONDITION_WEIGHT);
        };
    }

    private ActionFlow.Step<NiActionContext> condition(NiConfig config) {
        String condition = config.string("condition");
        if (condition != null) {
            validate.accept(condition);
        }
        ActionFlow.Step<NiActionContext> yes = compile(config.get("actions"));
        ActionFlow.Step<NiActionContext> no = compile(config.get("deny"));
        ActionFlow.Step<NiActionContext> sync = compile(config.get("sync"));
        ActionFlow.Step<NiActionContext> async = compile(config.get("async"));
        return context -> {
            if (!context.condition(condition)) {
                return no.run(context);
            }
            if (config.get("sync") != null) {
                host.fork(sync, context, false);
            }
            if (config.get("async") != null) {
                host.fork(async, context, true);
            }
            return yes.run(context);
        };
    }

    private ActionFlow.Step<NiActionContext> repeat(NiConfig config) {
        var amount = NiValues.compile(config.get("repeat"), Integer.class, validate);
        String global = config.string("global-id", "i");
        ActionFlow.Step<NiActionContext> action = compile(config.get("actions"));
        return context -> {
            Integer evaluated = amount.apply(context);
            int limit = evaluated == null ? 0 : evaluated;
            AtomicInteger index = new AtomicInteger();
            ActionFlow.Step<NiActionContext> iteration =
                    call -> {
                        call.getGlobal().put(global, index.getAndIncrement());
                        return action.run(call);
                    };
            return ActionFlow.whileTrue(
                            (NiActionContext call) -> call.active() && index.get() < limit,
                            iteration)
                    .run(context);
        };
    }

    private ActionFlow.Step<NiActionContext> loop(NiConfig config) {
        String condition = config.string("while");
        if (condition != null) {
            validate.accept(condition);
        }
        ActionFlow.Step<NiActionContext> check =
                dispatched(
                        context ->
                                completed(
                                        context.condition(condition)
                                                ? ActionFlow.Result.CONTINUE
                                                : ActionFlow.Result.STOP));
        ActionFlow.Step<NiActionContext> action = compile(config.get("actions"));
        ActionFlow.Step<NiActionContext> completion = compile(config.get("finally"));
        ActionFlow.Step<NiActionContext> iteration = ActionFlow.sequence(List.of(check, action));
        return context ->
                ActionFlow.whileTrue(NiActionContext::active, iteration)
                        .run(context)
                        .thenCompose(ignored -> completion.run(context));
    }

    private ActionFlow.Step<NiActionContext> select(NiConfig config, boolean contains) {
        ActionFlow.Step<NiActionContext> fallback = compile(config.get("default-action"));
        ActionFlow.Step<NiActionContext> match = compile(config.get("match-action"));
        var selected =
                NiValues.select(
                        config,
                        "actions",
                        fallback,
                        value ->
                                contains
                                        ? compile(value)
                                        : ActionFlow.sequence(List.of(match, compile(value))),
                        validate);
        return context -> selected.apply(context).run(context);
    }

    private ActionFlow.Step<NiActionContext> weight(NiConfig config, boolean conditional) {
        var choices =
                NiValues.weighted(
                        config.get("actions"), conditional, "actions", this::compile, validate);
        var amount =
                NiValues.compile(
                        config.get("amount") == null ? 1 : config.get("amount"),
                        Integer.class,
                        validate);
        boolean ordered = config.bool("order", false);
        return context -> {
            Integer requested = amount.apply(context);
            var selection =
                    NiValues.sample(choices, context, requested == null ? 1 : requested, ordered);
            if (!selection.combineResults() && !selection.values().isEmpty()) {
                return selection.values().getFirst().run(context);
            }
            return ActionFlow.all(selection.values()).run(context);
        };
    }

    private static CompletionStage<ActionFlow.Result> result(Object value) {
        if (value instanceof CompletionStage<?> stage) {
            return stage.thenCompose(NiActions::result);
        }
        if (value instanceof LegacyActionResult legacy) {
            return completed(legacy.value());
        }
        if (value instanceof ActionFlow.Result result) {
            return completed(result);
        }
        if (value instanceof Boolean bool) {
            return completed(bool ? ActionFlow.Result.CONTINUE : ActionFlow.Result.STOP);
        }
        return completed(ActionFlow.Result.CONTINUE);
    }

    private static CompletionStage<ActionFlow.Result> completed(ActionFlow.Result result) {
        return CompletableFuture.completedFuture(result);
    }
}

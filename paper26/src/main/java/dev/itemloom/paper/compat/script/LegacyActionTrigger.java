package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiValues;
import dev.itemloom.compat.ni.script.LegacyActionManager;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.paper.action.PaperActions;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.configuration.ConfigurationSection;
import org.openjdk.nashorn.api.scripting.AbstractJSObject;

/** Immutable compiled trigger fields retain their original script-facing config handle. */
public final class LegacyActionTrigger {
    private final PaperActions runtime;
    private final String id;
    private final String type;
    private final ConfigurationSection config;
    private final Evaluator cooldown;
    private final Evaluator tick;
    private final String group;
    private final ConsumeInfo consume;
    private final LegacyActionManager.Action actions;
    private final LegacyActionManager.Action async;
    private final LegacyActionManager.Action sync;
    private final boolean hasActions;
    private final boolean hasAsync;
    private final boolean hasSync;

    private LegacyActionTrigger(
            PaperActions runtime,
            LegacyActionManager manager,
            Consumer<String> validate,
            String id,
            String type,
            ConfigurationSection config) {
        this.runtime = runtime;
        this.id = id;
        this.type = type;
        this.config = Objects.requireNonNull(config, "config");
        cooldown = new Evaluator(config.get("cooldown", "1000"), manager, validate);
        tick = new Evaluator(config.get("tick", "10"), manager, validate);
        group = config.getString("group", type + "_" + id);
        ConfigurationSection consumeConfig = config.getConfigurationSection("consume");
        consume =
                consumeConfig == null
                        ? null
                        : new ConsumeInfo(runtime, manager, validate, consumeConfig);
        hasActions = config.get("actions") != null;
        hasAsync = config.get("async") != null;
        hasSync = config.get("sync") != null;
        actions = manager.wrap(runtime.compiler().compile(config.get("actions")));
        async = manager.wrap(runtime.compiler().compile(config.get("async")));
        sync = manager.wrap(runtime.compiler().compile(config.get("sync")));
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public ConfigurationSection getConfig() {
        return config;
    }

    public Evaluator getCooldown() {
        return cooldown;
    }

    public Evaluator getTick() {
        return tick;
    }

    public String getGroup() {
        return group;
    }

    public ConsumeInfo getConsume() {
        return consume;
    }

    public LegacyActionManager.Action getActions() {
        return actions;
    }

    public LegacyActionManager.Action getAsync() {
        return async;
    }

    public LegacyActionManager.Action getSync() {
        return sync;
    }

    public void run(NiActionContext context) {
        async(context);
        sync(context);
    }

    public void async(NiActionContext context) {
        Objects.requireNonNull(context, "context");
        if ((!hasActions && !hasAsync) || !runtime.active() || !context.active()) return;
        runtime.tasks()
                .schedule(
                        0,
                        true,
                        false,
                        () -> {
                            if (runtime.active() && context.active()) {
                                if (hasActions) actions.eval(context.clone());
                                if (hasAsync) async.eval(context.clone());
                            }
                            return CompletableFuture.completedFuture(ActionFlow.Result.CONTINUE);
                        });
    }

    public void sync(NiActionContext context) {
        Objects.requireNonNull(context, "context");
        if (!hasSync || !runtime.active() || !context.active()) return;
        runtime.tasks()
                .schedule(
                        0,
                        false,
                        true,
                        () -> {
                            if (runtime.active() && context.active()) sync.eval(context.clone());
                            return CompletableFuture.completedFuture(ActionFlow.Result.CONTINUE);
                        });
    }

    public static final class Evaluator {
        private final LegacyActionManager manager;
        private final Function<NiActionContext, Long> compiled;
        private final Long constant;

        private Evaluator(Object source, LegacyActionManager manager, Consumer<String> validate) {
            this.manager = manager;
            compiled = NiValues.compile(source, Long.class, validate);
            Long converted = null;
            if (source instanceof String || source instanceof Number) {
                try {
                    converted = NiValues.convert(source, Long.class);
                } catch (RuntimeException ignored) {
                }
            }
            constant = converted;
        }

        public Long get(NiActionContext context) {
            Objects.requireNonNull(context, "context");
            return constant != null ? constant : compiled.apply(context);
        }

        public Long getOrDefault(NiActionContext context, Long fallback) {
            Long value = get(context);
            return value == null ? fallback : value;
        }

        public LegacyActionManager getManager() {
            return manager;
        }

        public Class<Long> getType() {
            return Long.class;
        }

        public boolean isConstant() {
            return constant != null;
        }

        public long value(Supplier<NiActionContext> context, long fallback) {
            if (constant != null) return constant;
            NiActionContext captured = context.get();
            return captured == null ? fallback : getOrDefault(captured, fallback);
        }
    }

    public static final class ConsumeInfo {
        private final LegacyActionManager.Action pre;
        private final LegacyActionManager.Action deny;
        private final String condition;
        private final String amount;
        private final boolean hasPre;
        private final boolean hasDeny;

        private ConsumeInfo(
                PaperActions runtime,
                LegacyActionManager manager,
                Consumer<String> validate,
                ConfigurationSection config) {
            hasPre = config.get("pre") != null;
            hasDeny = config.get("deny") != null;
            pre = manager.wrap(runtime.compiler().compile(config.get("pre")));
            deny = manager.wrap(runtime.compiler().compile(config.get("deny")));
            condition = config.getString("condition");
            amount = config.getString("amount");
            if (condition != null) validate.accept(condition);
        }

        public LegacyActionManager.Action getPre() {
            return pre;
        }

        public String getCondition() {
            return condition;
        }

        public String getAmount() {
            return amount;
        }

        public LegacyActionManager.Action getDeny() {
            return deny;
        }

        public boolean hasPre() {
            return hasPre;
        }

        public boolean hasDeny() {
            return hasDeny;
        }
    }

    public static final class Factory extends ScriptConstructor {
        private final PaperActions runtime;
        private final LegacyActionManager manager;
        private final Consumer<String> validate;

        public Factory(
                PaperActions runtime, LegacyActionManager manager, Consumer<String> validate) {
            super(LegacyActionTrigger.class);
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.manager = Objects.requireNonNull(manager, "manager");
            this.validate = Objects.requireNonNull(validate, "validate");
            ensureActive();
        }

        public LegacyActionTrigger create(String id, String type, ConfigurationSection config) {
            ensureActive();
            return new LegacyActionTrigger(runtime, manager, validate, id, type, config);
        }

        public ScriptConstructor consumeFactory() {
            return new ScriptConstructor(ConsumeInfo.class) {
                @Override
                public Object newObject(Object... arguments) {
                    if (arguments.length != 1
                            || !(arguments[0] instanceof ConfigurationSection config)) {
                        throw new IllegalArgumentException(
                                "ConsumeInfo requires one ConfigurationSection");
                    }
                    ensureActive();
                    return new ConsumeInfo(runtime, manager, validate, config);
                }
            };
        }

        @Override
        public Object newObject(Object... arguments) {
            if (arguments.length != 3 || !(arguments[2] instanceof ConfigurationSection config)) {
                throw new IllegalArgumentException(
                        "ActionTrigger requires id, type and ConfigurationSection");
            }
            return create(
                    arguments[0] == null ? null : arguments[0].toString(),
                    arguments[1] == null ? null : arguments[1].toString(),
                    config);
        }

        private void ensureActive() {
            if (!runtime.active())
                throw new IllegalStateException("Item action revision is closed");
        }
    }

    public abstract static class ScriptConstructor extends AbstractJSObject {
        private final Class<?> type;

        private ScriptConstructor(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public boolean isInstance(Object instance) {
            return type.isInstance(instance);
        }

        @Override
        public boolean hasMember(String name) {
            return "class".equals(name);
        }

        @Override
        public Object getMember(String name) {
            return hasMember(name) ? type : null;
        }

        @Override
        public Set<String> keySet() {
            return Set.of("class");
        }

        @Override
        public String getClassName() {
            return type.getSimpleName();
        }
    }
}

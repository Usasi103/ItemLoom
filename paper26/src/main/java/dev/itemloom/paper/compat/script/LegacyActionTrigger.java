package dev.itemloom.paper.compat.script;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiValues;
import dev.itemloom.compat.ni.script.LegacyActionManager;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.paper.action.PaperActions;
import org.bukkit.configuration.ConfigurationSection;
import org.openjdk.nashorn.api.scripting.AbstractJSObject;

/**
 * Compiled trigger and script view share the same actions and evaluators, owned by one revision.
 * Contract derived from Neige's item/action/ActionTrigger and ConsumeInfo at ca93bc4f;
 * GPL-3.0, see NOTICE.md. Old Java class names are translated only at the script boundary.
 */
public final class LegacyActionTrigger {
    private final Factory owner;
    private final String id, type, group;
    private final ConfigurationSection config;
    private final Evaluator cooldown, tick;
    private final ConsumeInfo consume;
    private final LegacyActionManager.Action actions, async, sync;
    private final boolean hasActions, hasAsync, hasSync;

    private LegacyActionTrigger(
            Factory owner, String id, String type, ConfigurationSection config) {
        this.owner = owner;
        this.id = id;
        this.type = type;
        this.config = Objects.requireNonNull(config, "config");
        cooldown = new Evaluator(owner, config.get("cooldown", "1000"));
        tick = new Evaluator(owner, config.get("tick", "10"));
        group = config.getString("group", type + "_" + id);
        ConfigurationSection consumeConfig = config.getConfigurationSection("consume");
        consume = consumeConfig == null ? null : new ConsumeInfo(owner, consumeConfig);
        hasActions = config.get("actions") != null;
        hasAsync = config.get("async") != null;
        hasSync = config.get("sync") != null;
        actions = owner.compile(config.get("actions"));
        async = owner.compile(config.get("async"));
        sync = owner.compile(config.get("sync"));
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

    /** The two worker branches and the main-thread branch do not await each other's futures. */
    public void run(NiActionContext context) {
        async(context);
        sync(context);
    }

    public void async(NiActionContext context) {
        Objects.requireNonNull(context, "context");
        if ((!hasActions && !hasAsync) || !owner.runtime.active() || !context.active()) return;
        owner.runtime
                .tasks()
                .schedule(
                        0,
                        true,
                        false,
                        () -> {
                            // eval clones on the worker, preserving shared bindings but independent
                            // sync flags.
                            if (hasActions) actions.eval(context);
                            if (hasAsync) async.eval(context);
                            return CompletableFuture.completedFuture(Result.CONTINUE);
                        });
    }

    public void sync(NiActionContext context) {
        Objects.requireNonNull(context, "context");
        if (!hasSync || !owner.runtime.active() || !context.active()) return;
        owner.runtime
                .tasks()
                .schedule(
                        0,
                        false,
                        true,
                        () -> {
                            sync.eval(context);
                            return CompletableFuture.completedFuture(Result.CONTINUE);
                        });
    }

    /** Long-valued NI evaluator contract used by trigger cooldown and tick. */
    public static final class Evaluator {
        private final LegacyActionManager manager;
        private final Long constant;
        private final Function<NiActionContext, Long> evaluator;

        private Evaluator(Factory owner, Object source) {
            manager = owner.manager;
            constant =
                    source instanceof String || source instanceof Number
                            ? NiValues.convert(source, Long.class)
                            : null;
            evaluator = NiValues.compile(source, Long.class, owner.validate);
        }

        public Long get(NiActionContext context) {
            return evaluator.apply(Objects.requireNonNull(context, "context"));
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

        /** Avoids creating a script context for a literal runtime cooldown/tick value. */
        public long value(Supplier<NiActionContext> context, long fallback) {
            if (constant != null) return constant;
            NiActionContext evaluation = context.get();
            Long value = evaluation == null ? null : get(evaluation);
            return value == null ? fallback : value;
        }
    }

    public static final class ConsumeInfo {
        private final LegacyActionManager.Action pre, deny;
        private final String condition, amount;
        private final boolean hasPre, hasDeny;

        private ConsumeInfo(Factory owner, ConfigurationSection config) {
            Objects.requireNonNull(config, "config");
            hasPre = config.get("pre") != null;
            hasDeny = config.get("deny") != null;
            pre = owner.compile(config.get("pre"));
            deny = owner.compile(config.get("deny"));
            condition = config.getString("condition");
            amount = config.getString("amount");
            if (condition != null) owner.validate.accept(condition);
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

    /** Script constructors bind a revision directly, without a process-wide runtime registry. */
    public static final class Factory extends ScriptConstructor {
        private final PaperActions runtime;
        private final LegacyActionManager manager;
        private final Consumer<String> validate;
        private final ScriptConstructor consumeFactory;

        public Factory(
                PaperActions runtime, LegacyActionManager manager, Consumer<String> validate) {
            super(LegacyActionTrigger.class);
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.manager = Objects.requireNonNull(manager, "manager");
            this.validate = Objects.requireNonNull(validate, "validate");
            consumeFactory =
                    new ScriptConstructor(ConsumeInfo.class) {
                        @Override
                        public Object newObject(Object... arguments) {
                            if (arguments.length != 1
                                    || !(arguments[0] instanceof ConfigurationSection config))
                                throw new IllegalArgumentException(
                                        "ConsumeInfo requires one ConfigurationSection");
                            requireActive();
                            return new ConsumeInfo(Factory.this, config);
                        }
                    };
        }

        public LegacyActionTrigger create(String id, String type, ConfigurationSection config) {
            requireActive();
            return new LegacyActionTrigger(this, id, type, config);
        }

        private void requireActive() {
            if (!runtime.active())
                throw new IllegalStateException("Item action revision is closed");
        }

        private LegacyActionManager.Action compile(Object source) {
            return manager.wrap(runtime.compiler().compile(source));
        }

        public ScriptConstructor consumeFactory() {
            return consumeFactory;
        }

        @Override
        public Object newObject(Object... arguments) {
            if (arguments.length != 3 || !(arguments[2] instanceof ConfigurationSection config))
                throw new IllegalArgumentException(
                        "ActionTrigger requires id, type and ConfigurationSection");
            return create(text(arguments[0]), text(arguments[1]), config);
        }

        private static String text(Object value) {
            return value == null ? null : value.toString();
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
            return "class".equals(name) ? type : null;
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

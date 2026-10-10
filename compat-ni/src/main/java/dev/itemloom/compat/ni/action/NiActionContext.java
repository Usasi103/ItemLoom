package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.script.LegacyActionResult;
import dev.itemloom.core.ActionFlow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** An action call's view of shared variables, with its own evaluation and thread status. */
public final class NiActionContext implements Cloneable {
    private static final ThreadLocal<NiActionContext> CURRENT = new ThreadLocal<>();

    private final SharedState shared;
    private NiEvaluation evaluation;
    private boolean sync;

    /** Presence matters: removing a binding and assigning null have different script semantics. */
    private record Binding(boolean present, Object value) {
        static Binding at(Map<String, Object> values, String name) {
            return new Binding(values.containsKey(name), values.get(name));
        }

        void apply(Map<String, Object> values, String name) {
            if (present) values.put(name, value);
            else values.remove(name);
        }
    }

    /** All accesses to mutable scope state use the revision's script monitor. */
    private static final class SharedState {
        final NiScripts scripts;
        final Object caster;
        final Map<String, Object> global;
        final Map<String, Object> params;
        final BooleanSupplier active;
        final Map<NiContextKey<?>, Object> keys = new IdentityHashMap<>();
        Map<String, Object> initialNames;
        Map<String, Binding> pending = new java.util.LinkedHashMap<>();
        NiScripts.Scope scope;

        SharedState(
                NiScripts scripts,
                Object caster,
                Map<String, Object> global,
                Map<String, Object> params,
                BooleanSupplier active,
                NiActionContext context) {
            this.scripts = scripts;
            this.caster = caster;
            this.global = global == null ? Collections.synchronizedMap(new HashMap<>()) : global;
            this.params = params;
            this.active = active;
            initialNames = params == null ? new HashMap<>() : new HashMap<>(params);
            initialNames.put("target", caster);
            initialNames.put("player", caster instanceof Player player ? player : null);
            initialNames.put("global", this.global);
            initialNames.put("glo", this.global);
            initialNames.put("context", context);
        }

        NiScripts.Scope resolve() {
            if (scope == null) {
                NiScripts.Scope created = scripts.actionScope(initialNames);
                pending.forEach((name, binding) -> binding.apply(created.bindings(), name));
                scope = created;
                pending = null;
                initialNames = null;
            }
            return scope;
        }

        Binding binding(String name) {
            if (scope != null) return Binding.at(scope.bindings(), name);
            Binding change = pending.get(name);
            return change == null ? Binding.at(initialNames, name) : change;
        }

        void bind(String name, Binding binding) {
            if (scope == null) pending.put(name, binding);
            else binding.apply(scope.bindings(), name);
        }
    }

    public NiActionContext() {
        this((Object) null);
    }

    public NiActionContext(Object caster) {
        this(caster, null, null);
    }

    public NiActionContext(Player caster, Map<String, Object> params) {
        this(caster, null, params);
    }

    public NiActionContext(
            Object caster, Map<String, Object> suppliedGlobal, Map<String, Object> params) {
        this(
                NiEvaluation.current().action(caster),
                caster,
                suppliedGlobal,
                params,
                NiEvaluation.current().scripts()::isOpen);
    }

    public NiActionContext(
            NiEvaluation evaluation,
            Object caster,
            Map<String, Object> params,
            BooleanSupplier active) {
        this(evaluation, caster, null, params, active);
    }

    public NiActionContext(
            NiEvaluation evaluation,
            Object caster,
            Map<String, Object> suppliedGlobal,
            Map<String, Object> params,
            BooleanSupplier active) {
        NiScripts scripts = evaluation.scripts();
        synchronized (scripts) {
            if (!scripts.isOpen())
                throw new IllegalStateException("Script revision has been closed");
            shared = new SharedState(scripts, caster, suppliedGlobal, params, active, this);
            this.evaluation = evaluation.withActionCache(shared.global);
        }
        sync = primaryThread();
    }

    private NiActionContext(SharedState shared, NiEvaluation evaluation) {
        this.shared = shared;
        this.evaluation = evaluation;
        sync = primaryThread();
    }

    private static boolean primaryThread() {
        return Bukkit.getServer() == null || Bukkit.isPrimaryThread();
    }

    public NiEvaluation evaluation() {
        return evaluation;
    }

    public boolean active() {
        return shared.active.getAsBoolean();
    }

    public Object getCaster() {
        return shared.caster;
    }

    public Player getPlayer() {
        return shared.caster instanceof Player player ? player : null;
    }

    public Map<String, Object> getGlobal() {
        return shared.global;
    }

    public Map<String, Object> getParams() {
        return shared.params;
    }

    public Map<String, Object> getBindings() {
        synchronized (shared.scripts) {
            return shared.resolve().bindings();
        }
    }

    public ItemStack getItemStack() {
        return get(NiContextKeys.ITEM_STACK);
    }

    public Object getNbt() {
        return get(NiContextKeys.NBT);
    }

    public Map<String, String> getData() {
        return get(NiContextKeys.DATA);
    }

    public Event getEvent() {
        return get(NiContextKeys.EVENT);
    }

    public Map<String, Object> getSectionCache() {
        Map<String, Object> cache = get(NiContextKeys.SECTION_CACHE);
        return cache == null ? shared.global : cache;
    }

    public static NiActionContext currentOrNull() {
        return CURRENT.get();
    }

    public void refreshParams() {
        synchronized (shared.scripts) {
            if (shared.params == null) return;
            shared.params.forEach(
                    (name, value) -> {
                        if (value != null) shared.bind(name, new Binding(true, value));
                    });
        }
    }

    public boolean isSync() {
        return sync;
    }

    public void setSync(boolean sync) {
        this.sync = sync;
    }

    public boolean has(NiContextKey<?> key) {
        synchronized (shared.scripts) {
            return shared.keys.containsKey(key);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(NiContextKey<T> key) {
        synchronized (shared.scripts) {
            return (T) shared.keys.get(key);
        }
    }

    public <T> void set(NiContextKey<T> key, T value) {
        synchronized (shared.scripts) {
            shared.keys.put(key, value);
            updateAliases(key, new Binding(true, value));
            if (key == NiContextKeys.SECTION_CACHE)
                evaluation = evaluation.withActionCache(getSectionCache());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T remove(NiContextKey<T> key) {
        synchronized (shared.scripts) {
            T previous = (T) shared.keys.remove(key);
            updateAliases(key, new Binding(false, null));
            if (key == NiContextKeys.SECTION_CACHE)
                evaluation = evaluation.withActionCache(shared.global);
            return previous;
        }
    }

    private void updateAliases(NiContextKey<?> key, Binding binding) {
        for (String name : key.getNames()) {
            if (key.isPutInGlobal()) binding.apply(shared.global, name);
            shared.bind(name, binding);
        }
    }

    @Override
    public NiActionContext clone() {
        synchronized (shared.scripts) {
            return new NiActionContext(shared, evaluation);
        }
    }

    public static NiActionContext empty() {
        return new NiActionContext();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String parse(String text) {
        return invoke(
                () -> {
                    NiEvaluation parser =
                            has(NiContextKeys.SECTIONS)
                                    ? evaluation.withSections(
                                            NiValues.config(get(NiContextKeys.SECTIONS)))
                                    : evaluation;
                    return parser.text(text);
                });
    }

    public Object evaluate(String expression) {
        if (!active()) throw new IllegalStateException("Action context is no longer active");
        return invoke(() -> shared.scripts.evaluate(expression, shared.resolve()));
    }

    public <T> T invoke(Supplier<T> operation) {
        synchronized (shared.scripts) {
            NiActionContext previousCall = CURRENT.get();
            Binding previousBinding = shared.binding("context");
            CURRENT.set(this);
            shared.bind("context", new Binding(true, this));
            try {
                return evaluation.scoped(operation);
            } finally {
                shared.bind("context", previousBinding);
                if (previousCall == null) CURRENT.remove();
                else CURRENT.set(previousCall);
            }
        }
    }

    public boolean condition(String expression) {
        if (expression == null) return true;
        try {
            Object result = evaluate(expression);
            if (result instanceof LegacyActionResult legacy) return !legacy.isStop();
            if (result instanceof ActionFlow.Result flow) return !flow.stopped();
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException error) {
            evaluation.warning(
                    "Action condition failed: " + expression + ": " + error.getMessage());
            return false;
        }
    }

    public static final class Builder {
        private Object caster;
        private Map<String, Object> global;
        private Map<String, Object> params;
        private final Map<NiContextKey<?>, Object> keys = new HashMap<>();

        public Builder() {}

        public Builder caster(Object caster) {
            this.caster = caster;
            return this;
        }

        public Builder global(Map<String, Object> global) {
            this.global = global;
            return this;
        }

        public Builder params(Map<String, Object> params) {
            this.params = params;
            return this;
        }

        public <T> Builder with(NiContextKey<T> key, T value) {
            keys.put(key, value);
            return this;
        }

        public NiActionContext build() {
            NiActionContext context = new NiActionContext(caster, global, params);
            keys.forEach((key, value) -> apply(context, key, value));
            return context;
        }

        @SuppressWarnings("unchecked")
        private static <T> void apply(NiActionContext context, NiContextKey<T> key, Object value) {
            context.set(key, (T) value);
        }
    }
}

package dev.itemloom.compat.ni.action;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.core.ActionFlow;
import org.bukkit.entity.Player;

/** Mutable state belongs to one action invocation, including its own script-global scope. */
public final class NiActionContext implements Cloneable {
    private static final ThreadLocal<NiActionContext> CURRENT = new ThreadLocal<>();
    private NiEvaluation evaluation;
    private final Object caster;
    private final Map<String, Object> global;
    private final Map<String, Object> params;
    private final ScopeState scope;
    private final BooleanSupplier active;
    private final Map<NiContextKey<?>, Object> values = new HashMap<>();
    private boolean sync;

    /** Shared by shallow clones, just as the eagerly created scope was. Guarded by scripts. */
    private static final class ScopeState {
        private static final Object REMOVED = new Object();
        private final NiScripts scripts;
        private Map<String, Object> initial;
        private Map<String, Object> changes = new java.util.LinkedHashMap<>();
        private NiScripts.Scope resolved;

        ScopeState(NiScripts scripts, Map<String, Object> initial) {
            if (!scripts.isOpen())
                throw new IllegalStateException("Script revision has been closed");
            this.scripts = scripts;
            this.initial = initial;
        }

        NiScripts.Scope resolve() {
            if (resolved == null) {
                NiScripts.Scope prepared = scripts.actionScope(initial);
                // Metadata is set after namespace/library initialization in the eager path.
                // Replay it after initialization so an alias or helper cannot overwrite it.
                changes.forEach(
                        (name, value) -> {
                            if (value == REMOVED) prepared.bindings().remove(name);
                            else prepared.bindings().put(name, value);
                        });
                resolved = prepared;
                initial = null;
                changes = null;
            }
            return resolved;
        }

        void put(String name, Object value) {
            if (resolved == null) changes.put(name, value);
            else resolved.bindings().put(name, value);
        }

        void remove(String name) {
            if (resolved == null) changes.put(name, REMOVED);
            else resolved.bindings().remove(name);
        }

        boolean contains(String name) {
            if (resolved != null) return resolved.bindings().containsKey(name);
            return changes.containsKey(name)
                    ? changes.get(name) != REMOVED
                    : initial.containsKey(name);
        }

        Object value(String name) {
            if (resolved != null) return resolved.bindings().get(name);
            Object value = changes.containsKey(name) ? changes.get(name) : initial.get(name);
            return value == REMOVED ? null : value;
        }
    }

    public NiActionContext() {
        this(null);
    }

    public NiActionContext(Object caster) {
        this(caster, null, null);
    }

    public NiActionContext(Player caster, Map<String, Object> params) {
        this(caster, null, params);
    }

    public NiActionContext(Object caster, Map<String, Object> global, Map<String, Object> params) {
        this(
                NiEvaluation.current().action(caster),
                caster,
                global,
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
        this.caster = caster;
        this.params = params;
        this.active = active;
        global =
                suppliedGlobal == null
                        ? Collections.synchronizedMap(new HashMap<>())
                        : suppliedGlobal;
        this.evaluation = evaluation.withActionCache(global);
        Map<String, Object> bindings = params == null ? new HashMap<>() : new HashMap<>(params);
        bindings.put("target", caster);
        bindings.put("player", caster instanceof Player ? caster : null);
        bindings.put("global", global);
        bindings.put("glo", global);
        bindings.put("context", this);
        scope = new ScopeState(evaluation.scripts(), bindings);
        sync = org.bukkit.Bukkit.getServer() == null || org.bukkit.Bukkit.isPrimaryThread();
    }

    public NiEvaluation evaluation() {
        return evaluation;
    }

    public boolean active() {
        return active.getAsBoolean();
    }

    public Object getCaster() {
        return caster;
    }

    public Player getPlayer() {
        return caster instanceof Player player ? player : null;
    }

    public Map<String, Object> getGlobal() {
        return global;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public Map<String, Object> getBindings() {
        synchronized (scope.scripts) {
            return scope.resolve().bindings();
        }
    }

    public org.bukkit.inventory.ItemStack getItemStack() {
        return get(NiContextKeys.ITEM_STACK);
    }

    public Object getNbt() {
        return get(NiContextKeys.NBT);
    }

    public Map<String, String> getData() {
        return get(NiContextKeys.DATA);
    }

    public org.bukkit.event.Event getEvent() {
        return get(NiContextKeys.EVENT);
    }

    public Map<String, Object> getSectionCache() {
        Map<String, Object> cache = get(NiContextKeys.SECTION_CACHE);
        return cache == null ? global : cache;
    }

    public static NiActionContext currentOrNull() {
        return CURRENT.get();
    }

    public void refreshParams() {
        synchronized (scope.scripts) {
            if (params != null)
                params.forEach(
                        (key, value) -> {
                            if (value != null) scope.put(key, value);
                        });
        }
    }

    public boolean isSync() {
        return sync;
    }

    public void setSync(boolean value) {
        sync = value;
    }

    public boolean has(NiContextKey<?> key) {
        return values.containsKey(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(NiContextKey<T> key) {
        return (T) values.get(key);
    }

    public <T> void set(NiContextKey<T> key, T value) {
        synchronized (scope.scripts) {
            values.put(key, value);
            for (String name : key.getNames()) {
                if (key.isPutInGlobal()) global.put(name, value);
                scope.put(name, value);
            }
            if (key == NiContextKeys.SECTION_CACHE)
                evaluation = evaluation.withActionCache(getSectionCache());
        }
    }

    public <T> T remove(NiContextKey<T> key) {
        synchronized (scope.scripts) {
            T previous = get(key);
            values.remove(key);
            for (String name : key.getNames()) {
                if (key.isPutInGlobal()) global.remove(name);
                scope.remove(name);
            }
            if (key == NiContextKeys.SECTION_CACHE) evaluation = evaluation.withActionCache(global);
            return previous;
        }
    }

    @Override
    public NiActionContext clone() {
        try {
            NiActionContext result = (NiActionContext) super.clone();
            result.sync =
                    org.bukkit.Bukkit.getServer() == null || org.bukkit.Bukkit.isPrimaryThread();
            return result;
        } catch (CloneNotSupportedException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static NiActionContext empty() {
        return new NiActionContext();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Object caster;
        private Map<String, Object> global, params;
        private final Map<NiContextKey<?>, Object> values = new HashMap<>();

        public Builder caster(Object value) {
            caster = value;
            return this;
        }

        public Builder global(Map<String, Object> value) {
            global = value;
            return this;
        }

        public Builder params(Map<String, Object> value) {
            params = value;
            return this;
        }

        public <T> Builder with(NiContextKey<T> key, T value) {
            values.put(key, value);
            return this;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        public NiActionContext build() {
            NiActionContext result = new NiActionContext(caster, global, params);
            values.forEach((key, value) -> result.set((NiContextKey) key, value));
            return result;
        }
    }

    public String parse(String text) {
        // Keep the same lock order as evaluate: Nashorn callbacks may parse nodes recursively.
        return invoke(
                () ->
                        (has(NiContextKeys.SECTIONS)
                                        ? evaluation.withSections(
                                                NiValues.config(get(NiContextKeys.SECTIONS)))
                                        : evaluation)
                                .text(text));
    }

    public Object evaluate(String expression) {
        if (!active()) throw new IllegalStateException("Action revision is closed");
        return invoke(() -> evaluation.scripts().evaluate(expression, scope.resolve()));
    }

    public <T> T invoke(java.util.function.Supplier<T> operation) {
        synchronized (evaluation.scripts()) {
            return scoped(() -> evaluation.scoped(operation));
        }
    }

    private <T> T scoped(java.util.function.Supplier<T> operation) {
        NiActionContext previous = CURRENT.get();
        boolean hadContext = scope.contains("context");
        Object previousBinding = scope.value("context");
        scope.put("context", this);
        CURRENT.set(this);
        try {
            return operation.get();
        } finally {
            if (hadContext) scope.put("context", previousBinding);
            else scope.remove("context");
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public boolean condition(String source) {
        if (source == null) return true;
        try {
            Object result = evaluate(source);
            if (result instanceof dev.itemloom.compat.ni.script.LegacyActionResult legacy)
                return !legacy.isStop();
            return result instanceof ActionFlow.Result flow
                    ? !flow.stopped()
                    : Boolean.TRUE.equals(result);
        } catch (RuntimeException error) {
            evaluation.warning("Action condition failed: " + source + ": " + error.getMessage());
            return false;
        }
    }
}

package dev.itemloom.compat.ni.action;

import dev.itemloom.compat.ni.*;
import dev.itemloom.compat.ni.script.LegacyActionResult;
import dev.itemloom.core.ActionFlow;
import java.util.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;

public final class NiActionContext implements Cloneable {
    private static final ThreadLocal<NiActionContext> CURRENT = new ThreadLocal<>();

    private record BindingEdit(boolean present, Object value) {}

    private static final class Shared {
        final Object caster;
        final Map<String, Object> global;
        final Map<String, Object> params;
        final BooleanSupplier active;
        final NiScripts scripts;
        final IdentityHashMap<NiContextKey<?>, Object> keys = new IdentityHashMap<>();
        final Map<String, Object> initial = new HashMap<>();
        final Map<String, BindingEdit> edits = new LinkedHashMap<>();
        NiScripts.Scope scope;

        Shared(
                Object caster,
                Map<String, Object> global,
                Map<String, Object> params,
                BooleanSupplier active,
                NiScripts scripts) {
            this.caster = caster;
            this.global = global;
            this.params = params;
            this.active = active;
            this.scripts = scripts;
            if (params != null) initial.putAll(params);
        }

        Map<String, Object> bindings() {
            if (scope == null) {
                scope = scripts.actionScope(initial);
                edits.forEach(
                        (key, edit) -> {
                            if (edit.present) scope.bindings().put(key, edit.value);
                            else scope.bindings().remove(key);
                        });
                edits.clear();
            }
            return scope.bindings();
        }

        BindingEdit read(String name) {
            if (scope != null)
                return new BindingEdit(
                        scope.bindings().containsKey(name), scope.bindings().get(name));
            return edits.getOrDefault(
                    name, new BindingEdit(initial.containsKey(name), initial.get(name)));
        }

        void write(String name, BindingEdit edit) {
            if (scope == null) edits.put(name, edit);
            else if (edit.present) scope.bindings().put(name, edit.value);
            else scope.bindings().remove(name);
        }
    }

    private final Shared shared;
    private NiEvaluation evaluation;
    private boolean sync = mainThread();

    private static boolean mainThread() {
        return Bukkit.getServer() == null || Bukkit.isPrimaryThread();
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
        if (!evaluation.scripts().isOpen())
            throw new IllegalStateException("Script revision has been closed");
        Map<String, Object> global =
                suppliedGlobal == null
                        ? Collections.synchronizedMap(new HashMap<>())
                        : suppliedGlobal;
        shared = new Shared(caster, global, params, active, evaluation.scripts());
        shared.initial.put("target", caster);
        shared.initial.put("player", caster instanceof Player ? caster : null);
        shared.initial.put("global", global);
        shared.initial.put("glo", global);
        shared.initial.put("context", this);
        this.evaluation = evaluation.withActionCache(global);
    }

    private NiActionContext(NiActionContext original) {
        shared = original.shared;
        evaluation = original.evaluation;
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
            return shared.bindings();
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
            if (shared.params != null)
                shared.params.forEach(
                        (name, value) -> {
                            if (value != null) shared.write(name, new BindingEdit(true, value));
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
            for (String name : key.getNames()) {
                shared.write(name, new BindingEdit(true, value));
                if (key.isPutInGlobal()) shared.global.put(name, value);
            }
            if (key == NiContextKeys.SECTION_CACHE)
                evaluation = evaluation.withActionCache(getSectionCache());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T remove(NiContextKey<T> key) {
        synchronized (shared.scripts) {
            T previous = (T) shared.keys.remove(key);
            for (String name : key.getNames()) {
                shared.write(name, new BindingEdit(false, null));
                if (key.isPutInGlobal()) shared.global.remove(name);
            }
            if (key == NiContextKeys.SECTION_CACHE)
                evaluation = evaluation.withActionCache(shared.global);
            return previous;
        }
    }

    @Override
    public NiActionContext clone() {
        return new NiActionContext(this);
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
                    NiEvaluation context =
                            has(NiContextKeys.SECTIONS)
                                    ? evaluation.withSections(
                                            NiValues.config(get(NiContextKeys.SECTIONS)))
                                    : evaluation;
                    return context.text(text);
                });
    }

    public Object evaluate(String expression) {
        if (!active()) throw new IllegalStateException("Action context is no longer active");
        return invoke(
                () -> {
                    shared.bindings();
                    return shared.scripts.evaluate(expression, shared.scope);
                });
    }

    public <T> T invoke(Supplier<T> operation) {
        synchronized (shared.scripts) {
            NiActionContext previous = CURRENT.get();
            BindingEdit binding = shared.read("context");
            CURRENT.set(this);
            shared.write("context", new BindingEdit(true, this));
            try {
                return evaluation.scoped(operation);
            } finally {
                shared.write("context", binding);
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
        }
    }

    public boolean condition(String expression) {
        if (expression == null) return true;
        try {
            Object result = evaluate(expression);
            return Boolean.TRUE.equals(result)
                    || result instanceof LegacyActionResult legacy && !legacy.isStop()
                    || result instanceof ActionFlow.Result flow && !flow.stopped();
        } catch (RuntimeException error) {
            evaluation.warning(
                    "Action condition failed: " + expression + ": " + error.getMessage());
            return false;
        }
    }

    public static final class Builder {
        private Object caster;
        private Map<String, Object> global, params;
        private final Map<NiContextKey<?>, Object> keys = new IdentityHashMap<>();

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

        @SuppressWarnings({"rawtypes", "unchecked"})
        public NiActionContext build() {
            NiActionContext context = new NiActionContext(caster, global, params);
            keys.forEach((key, value) -> context.set((NiContextKey) key, value));
            return context;
        }
    }
}

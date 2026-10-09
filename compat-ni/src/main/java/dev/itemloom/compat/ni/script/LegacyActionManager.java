package dev.itemloom.compat.ni.script;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiActions;
import dev.itemloom.core.ActionFlow;

/** Script-facing manager contract. Scheduling and side effects remain owned by the new runtime. */
public final class LegacyActionManager {
    private final NiActions compiler;
    private final BiFunction<
                    ActionFlow.Step<NiActionContext>,
                    NiActionContext,
                    CompletionStage<ActionFlow.Result>>
            execute;

    public LegacyActionManager(
            NiActions compiler,
            BiFunction<
                            ActionFlow.Step<NiActionContext>,
                            NiActionContext,
                            CompletionStage<ActionFlow.Result>>
                    execute) {
        this.compiler = compiler;
        this.execute = execute;
    }

    public final class Action {
        private final ActionFlow.Step<NiActionContext> step;

        private Action(ActionFlow.Step<NiActionContext> step) {
            this.step = step;
        }

        public CompletableFuture<LegacyActionResult> eval(NiActionContext context) {
            return evalAsyncSafe(context.clone());
        }

        public CompletableFuture<LegacyActionResult> evalAsyncSafe(NiActionContext context) {
            return execute.apply(step, context)
                    .thenApply(LegacyActionResult::new)
                    .toCompletableFuture();
        }

        public LegacyActionManager getManager() {
            return LegacyActionManager.this;
        }
    }

    public Action wrap(ActionFlow.Step<NiActionContext> step) {
        return new Action(java.util.Objects.requireNonNull(step));
    }

    public Action compile(Object source) {
        return source instanceof Action action ? action : new Action(compiler.compile(source));
    }

    public Action compile(Object source, Object fallback) {
        return compile(source == null ? fallback : source);
    }

    public CompletableFuture<LegacyActionResult> runActionWithResult(
            Action action, NiActionContext context) {
        return action.eval(context);
    }

    public CompletableFuture<LegacyActionResult> runActionWithResult(Action action) {
        return action.eval(NiActionContext.empty());
    }

    public LegacyActionResult runAction(Action action, NiActionContext context) {
        action.eval(context);
        return LegacyActionResult.Results.SUCCESS;
    }

    public LegacyActionResult runAction(Action action) {
        return runAction(action, NiActionContext.empty());
    }

    public String parseNode(String text, NiActionContext context) {
        return context.parse(text);
    }

    public String parseNullableNode(String text, NiActionContext context) {
        return context.parse(text);
    }

    public LegacyActionResult parseCondition(String text, NiActionContext context) {
        return LegacyActionResult.Results.fromBoolean(context.condition(text));
    }

    public void addConsumer(String id, BiConsumer<NiActionContext, String> handler) {
        addConsumer(id, true, handler);
    }

    public void addConsumer(
            String id, boolean asyncSafe, BiConsumer<NiActionContext, String> handler) {
        if (handler != null)
            compiler.register(
                    id,
                    asyncSafe,
                    (context, text) -> {
                        handler.accept(context, text);
                        return null;
                    });
    }

    public void addConsumer(Collection<String> ids, BiConsumer<NiActionContext, String> handler) {
        addConsumer(ids, true, handler);
    }

    public void addConsumer(
            Collection<String> ids,
            boolean asyncSafe,
            BiConsumer<NiActionContext, String> handler) {
        ids.forEach(id -> addConsumer(id, asyncSafe, handler));
    }

    public void addFunction(String id, BiFunction<NiActionContext, String, ?> handler) {
        addFunction(id, true, handler);
    }

    public void addFunction(
            String id, boolean asyncSafe, BiFunction<NiActionContext, String, ?> handler) {
        if (handler != null) compiler.register(id, asyncSafe, handler);
    }

    public void addFunction(
            Collection<String> ids, BiFunction<NiActionContext, String, ?> handler) {
        addFunction(ids, true, handler);
    }

    public void addFunction(
            Collection<String> ids,
            boolean asyncSafe,
            BiFunction<NiActionContext, String, ?> handler) {
        ids.forEach(id -> addFunction(id, asyncSafe, handler));
    }
}

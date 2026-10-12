package dev.itemloom.compat.ni.script;

import dev.itemloom.compat.ni.action.*;
import dev.itemloom.core.ActionFlow;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

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
            this.step = Objects.requireNonNull(step);
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
        return new Action(step);
    }

    public Action compile(Object source) {
        return source instanceof Action action ? action : wrap(compiler.compile(source));
    }

    public Action compile(Object source, Object fallback) {
        return compile(source == null ? fallback : source);
    }

    public CompletableFuture<LegacyActionResult> runActionWithResult(
            Action action, NiActionContext context) {
        return action.eval(context);
    }

    public CompletableFuture<LegacyActionResult> runActionWithResult(Action action) {
        return runActionWithResult(action, NiActionContext.empty());
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
                    (context, value) -> {
                        handler.accept(context, value);
                        return ActionFlow.Result.CONTINUE;
                    });
    }

    public void addConsumer(Collection<String> ids, BiConsumer<NiActionContext, String> handler) {
        addConsumer(ids, true, handler);
    }

    public void addConsumer(
            Collection<String> ids,
            boolean asyncSafe,
            BiConsumer<NiActionContext, String> handler) {
        for (String id : ids) addConsumer(id, asyncSafe, handler);
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
        for (String id : ids) addFunction(id, asyncSafe, handler);
    }
}

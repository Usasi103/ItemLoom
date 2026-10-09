package dev.itemloom.probe;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.paper.action.ActionTasks;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Real ticks and script callbacks, with a private in-memory catalog and no reference NI plugin. */
final class SchedulerProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin probe) {
        Run run = new Run(probe);
        run.start();
        return run.report;
    }

    private static final String SCRIPT =
            """
            var legacyScheduler = Java.type('pers.neige.neigeitems.utils.SchedulerUtils');
            var LegacyContext = Java.type('pers.neige.neigeitems.action.ActionContext');
            var Evaluation = Java.type('dev.itemloom.compat.ni.NiEvaluation');
            var Failure = Java.type('java.lang.IllegalStateException');
            function withEvaluation(expected, completed) {
                legacyScheduler.syncLater(1, function() {
                    try { completed.complete(LegacyContext.currentOrNull() == null && Evaluation.current().equals(expected)); }
                    catch (error) { completed.completeExceptionally(new Failure(String(error))); }
                });
            }
            function withoutEvaluation(completed) {
                legacyScheduler.syncLater(1, function() {
                    try {
                        var absent = false;
                        try { Evaluation.current(); } catch (expected) { absent = true; }
                        completed.complete(absent && LegacyContext.currentOrNull() == null);
                    } catch (error) { completed.completeExceptionally(new Failure(String(error))); }
                });
            }
            """;

    private static final class Run {
        private final JavaPlugin probe;
        private final PlayerActionState players = new PlayerActionState();
        private final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        private final Queue<BukkitTask> controlTasks = new ConcurrentLinkedQueue<>();
        private final List<NiCatalog> catalogs = new ArrayList<>();
        private final List<ActionTasks> auxiliaryTasks = new ArrayList<>();
        private final List<String> checks = new ArrayList<>();
        private final Queue<String> calls = new ConcurrentLinkedQueue<>();
        private final AtomicInteger syncTicks = new AtomicInteger();
        private final AtomicInteger asyncTicks = new AtomicInteger();
        private final AtomicInteger negativePeriod = new AtomicInteger();
        private NiCatalog catalog;
        private final AtomicBoolean finished = new AtomicBoolean();

        Run(JavaPlugin probe) {
            this.probe = probe;
        }

        void start() {
            try {
                if (!Bukkit.isPrimaryThread())
                    throw new IllegalStateException(
                            "Scheduler probe must start on the server thread");
                controlTasks.add(
                        Bukkit.getScheduler()
                                .runTaskLater(
                                        probe,
                                        () ->
                                                finish(
                                                        new IllegalStateException(
                                                                "Scheduler probe timed out after 240 ticks; passed="
                                                                        + checks)),
                                        240));
                catalog = createCatalog(1);
                immediate();
                asyncContext();
            } catch (Throwable error) {
                finish(error);
            }
        }

        private NiCatalog createCatalog(long revision) {
            NiRepository.Input input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of("scheduler.js", SCRIPT),
                            Map.of(),
                            Map.of());
            NiCatalog result =
                    new NiCatalog(revision, input, probe, (viewer, text) -> null, players);
            catalogs.add(result);
            return result;
        }

        private void immediate() {
            NiActionContext context = catalog.actionContext(null, Map.of("calls", calls));
            context.evaluate(
                    """
                    sync(function() { calls.add('sync'); });
                    SchedulerUtils.sync(plugin, function() { calls.add('owned-sync'); });
                    SchedulerUtils.run(true, function() { calls.add('run-sync'); });
                    SchedulerUtils.run(plugin, true, function() { calls.add('owned-run-sync'); });
                    """);
            check(
                    List.copyOf(calls)
                            .equals(List.of("sync", "owned-sync", "run-sync", "owned-run-sync")),
                    "sync aliases and enabled Plugin overloads run immediately");
            check(
                    number(context.evaluate("SchedulerUtils.syncAndGet(function() { return 17; })"))
                            == 17,
                    "syncAndGet returns the main-thread value");
            check(
                    number(
                                    context.evaluate(
                                            "SchedulerUtils.syncAndGet(plugin, function() { return 19; })"))
                            == 19,
                    "syncAndGet Plugin overload");
            check(
                    number(
                                    context.evaluate(
                                            "SchedulerUtils.callSyncMethod(function() { return 23; }).join()"))
                            == 23,
                    "completed callSyncMethod permits main-thread join");
            check(
                    number(
                                    context.evaluate(
                                            "SchedulerUtils.callSyncMethod(plugin, function() { return 29; }).get()"))
                            == 29,
                    "callSyncMethod Plugin overload");
            Plugin disabled =
                    (Plugin)
                            Proxy.newProxyInstance(
                                    Plugin.class.getClassLoader(),
                                    new Class<?>[] {Plugin.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "isEnabled" -> false;
                                                case "getName", "toString" ->
                                                        "DisabledSchedulerProbeOwner";
                                                case "hashCode" -> System.identityHashCode(proxy);
                                                case "equals" -> proxy == args[0];
                                                default ->
                                                        throw new UnsupportedOperationException(
                                                                method.toString());
                                            });
            Object result =
                    catalog.actionContext(null, Map.of("disabled", disabled, "calls", calls))
                            .evaluate(
                                    """
                    var forbidden = function() { calls.add('disabled-owner-ran'); };
                    SchedulerUtils.sync(disabled, forbidden);
                    SchedulerUtils.async(disabled, forbidden);
                    SchedulerUtils.run(disabled, true, forbidden);
                    SchedulerUtils.run(disabled, false, forbidden);
                    SchedulerUtils.syncLater(disabled, 1, forbidden);
                    SchedulerUtils.asyncLater(disabled, 1, forbidden);
                    SchedulerUtils.syncTimer(disabled, 1, 1, forbidden);
                    SchedulerUtils.asyncTimer(disabled, 1, 1, forbidden);
                    SchedulerUtils.runLater(disabled, 1, forbidden);
                    SchedulerUtils.callSyncMethod(disabled, forbidden).isCancelled()
                        && SchedulerUtils.syncAndGet(disabled, forbidden) == null;
                    """);
            check(
                    Boolean.TRUE.equals(result),
                    "disabled Plugin owner cancels value calls without invocation");
        }

        private void asyncContext() {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            NiActionContext context = catalog.actionContext(null, Map.of("done", done));
            context.evaluate(
                    """
                    global.put('marker', 'retained');
                    async(function() {
                        try {
                            var correct = !Bukkit.isPrimaryThread() && !context.isSync()
                                && ActionContext.currentOrNull().equals(context)
                                && context.parse('<marker>') == 'retained';
                            global.put('fromAsync', 'updated');
                            sync(function() {
                                done.complete(correct && Bukkit.isPrimaryThread() && context.isSync()
                                    && ActionContext.currentOrNull().equals(context)
                                    && context.parse('<fromAsync>') == 'updated');
                            });
                        } catch (error) { done.completeExceptionally(new (Java.type('java.lang.IllegalStateException'))(String(error))); }
                    });
                    """);
            after(
                    done,
                    passed -> {
                        check(
                                Boolean.TRUE.equals(passed),
                                "builtin async and sync aliases preserve action globals, parsing, and current context");
                        check(
                                context.isSync(),
                                "callback restores the originating context sync flag");
                        ordinaryScriptContexts();
                    });
        }

        private void ordinaryScriptContexts() {
            var evaluation = catalog.actionContext(null, null).evaluation();
            CompletableFuture<Boolean> evaluated = new CompletableFuture<>(),
                    bare = new CompletableFuture<>();
            evaluation.scoped(
                    () ->
                            evaluation
                                    .scripts()
                                    .invoke(
                                            "scheduler.js",
                                            "withEvaluation",
                                            Map.of(),
                                            evaluation,
                                            evaluated));
            evaluation.scripts().invoke("scheduler.js", "withoutEvaluation", Map.of(), bare);
            after(
                    CompletableFuture.allOf(evaluated, bare),
                    ignored -> {
                        check(
                                Boolean.TRUE.equals(evaluated.join()),
                                "ordinary script scheduler restores NiEvaluation without fabricating an action");
                        check(
                                Boolean.TRUE.equals(bare.join()),
                                "callback without action or evaluation retains its script lifecycle");
                        blockingGuards();
                    });
        }

        private void blockingGuards() {
            Queue<String> guards = new ConcurrentLinkedQueue<>();
            AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>(),
                    chained = new AtomicReference<>();
            CompletableFuture<Boolean> armed = new CompletableFuture<>();
            AtomicInteger invoked = new AtomicInteger();
            catalog.actionContext(
                            null,
                            Map.of(
                                    "guards", guards, "pending", pending, "chained", chained,
                                    "armed", armed, "invoked", invoked))
                    .evaluate(
                            """
                    async(function() {
                        try {
                            function rejected(name, operation) {
                                try { operation(); guards.add(name + ': DID NOT REJECT'); }
                                catch (error) { guards.add(name + ': ' + String(error)); }
                            }
                            rejected('syncAndGet', function() { SchedulerUtils.syncAndGet(function() { return 1; }); });
                            var future = SchedulerUtils.callSyncMethod(function() { invoked.incrementAndGet(); return 31; });
                            var derived = future.thenApply(function(value) { return value + 1; });
                            pending.set(future);
                            chained.set(derived);
                            rejected('get', function() { future.get(); });
                            rejected('join', function() { future.join(); });
                            rejected('timed-get', function() { future.get(1, Java.type('java.util.concurrent.TimeUnit').SECONDS); });
                            rejected('derived-get', function() { derived.get(); });
                            rejected('derived-join', function() { derived.join(); });
                            armed.complete(true);
                        } catch (error) { armed.completeExceptionally(new (Java.type('java.lang.IllegalStateException'))(String(error))); }
                    });
                    """);
            after(
                    armed,
                    ignored -> {
                        check(
                                guards.size() == 6
                                        && guards.stream()
                                                .allMatch(
                                                        value ->
                                                                value.contains("cannot wait")
                                                                        || value.contains(
                                                                                "Cannot wait")),
                                "syncAndGet plus direct, timed, and chained Future waits fail explicitly under the script lock: "
                                        + guards);
                        after(
                                CompletableFuture.allOf(pending.get(), chained.get()),
                                unused -> {
                                    check(
                                            number(pending.get().join()) == 31
                                                    && number(chained.get().join()) == 32
                                                    && invoked.get() == 1,
                                            "guarded futures still complete normally after the asynchronous script releases its lock");
                                    continuationContexts();
                                });
                    });
        }

        private void continuationContexts() {
            Queue<String> observed = new ConcurrentLinkedQueue<>();
            AtomicReference<CompletableFuture<Object>> source = new AtomicReference<>();
            AtomicReference<Function<Object, Object>> mapper = new AtomicReference<>();
            AtomicReference<BiConsumer<Object, Throwable>> observer = new AtomicReference<>();
            AtomicReference<BiFunction<Object, Throwable, Object>> handler =
                    new AtomicReference<>();
            AtomicReference<Consumer<Object>> consumer = new AtomicReference<>();
            AtomicReference<Runnable> runnable = new AtomicReference<>();
            AtomicReference<Function<Throwable, Object>> recovery = new AtomicReference<>();
            AtomicReference<Function<Object, CompletionStage<Object>>> composer =
                    new AtomicReference<>();
            catalog.actionContext(
                            null,
                            Map.of(
                                    "source",
                                    source,
                                    "mapper",
                                    mapper,
                                    "observer",
                                    observer,
                                    "handler",
                                    handler,
                                    "consumer",
                                    consumer,
                                    "runnable",
                                    runnable,
                                    "recovery",
                                    recovery,
                                    "composer",
                                    composer,
                                    "observed",
                                    observed))
                    .evaluate(
                            """
                    function recordScope(name) {
                        var active = ActionContext.currentOrNull();
                        var valid = active != null && active.equals(context)
                            && Java.type('java.lang.Thread').holdsLock(context.evaluation().scripts())
                            && context.isSync() == Bukkit.isPrimaryThread();
                        observed.add(name + ':' + valid);
                    }
                    mapper.set(new (Java.type('java.util.function.Function'))(function(value) { recordScope('apply'); return value + 1; }));
                    observer.set(new (Java.type('java.util.function.BiConsumer'))(function(value, error) { recordScope('observe'); }));
                    handler.set(new (Java.type('java.util.function.BiFunction'))(function(value, error) { recordScope('handle'); return error == null ? value : 37; }));
                    consumer.set(new (Java.type('java.util.function.Consumer'))(function(value) { recordScope('accept'); }));
                    runnable.set(new (Java.type('java.lang.Runnable'))(function() { recordScope('run'); }));
                    recovery.set(new (Java.type('java.util.function.Function'))(function(error) { recordScope('recover'); return 43; }));
                    composer.set(new (Java.type('java.util.function.Function'))(function(value) {
                        recordScope('compose');
                        return Java.type('java.util.concurrent.CompletableFuture').completedFuture(value);
                    }));
                    source.set(SchedulerUtils.callSyncMethod(function() { return 7; }));
                    """);
            CompletableFuture<Boolean> registered = new CompletableFuture<>();
            // This Java caller has neither the script monitor nor an action ThreadLocal.
            // Merely scheduling registration through SchedulerUtils.async would mask the bug.
            controlTasks.add(
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(
                                    probe,
                                    () -> {
                                        try {
                                            CompletableFuture<Object> ready = source.get();
                                            Executor direct = Runnable::run;
                                            CompletableFuture<?>[] stages = {
                                                ready.thenApply(mapper.get()),
                                                        ready.whenComplete(observer.get()),
                                                        ready.handle(handler.get()),
                                                ready.thenApplyAsync(mapper.get()),
                                                        ready.whenCompleteAsync(observer.get()),
                                                        ready.handleAsync(handler.get()),
                                                ready.thenApplyAsync(mapper.get(), direct),
                                                        ready.whenCompleteAsync(
                                                                observer.get(), direct),
                                                        ready.handleAsync(handler.get(), direct),
                                                ready.thenAccept(consumer.get()),
                                                        ready.thenRun(runnable.get()),
                                                        ready.thenCompose(composer.get())
                                            };
                                            CompletableFuture.allOf(stages)
                                                    .whenComplete(
                                                            (ignored, error) -> {
                                                                if (error == null)
                                                                    registered.complete(true);
                                                                else
                                                                    registered
                                                                            .completeExceptionally(
                                                                                    error);
                                                            });
                                        } catch (Throwable error) {
                                            registered.completeExceptionally(error);
                                        }
                                    }));
            after(
                    registered,
                    ignored -> {
                        check(
                                observed.size() == 12
                                        && observed.stream()
                                                .allMatch(value -> value.endsWith(":true")),
                                "completed futures restore script lock and context for foreign-thread registration, default Async, and explicit executors: "
                                        + observed);
                        CompletableFuture<Object> cancelled = source.get().newIncompleteFuture();
                        CompletableFuture<Object> cancellationObserver =
                                cancelled.whenComplete(observer.get());
                        CompletableFuture<Object> recovered = cancelled.handle(handler.get());
                        CompletableFuture<Object> manual = source.get().newIncompleteFuture();
                        CompletableFuture<Object> applied = manual.thenApply(mapper.get());
                        CompletableFuture<Object> failed = source.get().newIncompleteFuture();
                        CompletableFuture<Object> recoveredError =
                                failed.exceptionally(recovery.get());
                        CompletableFuture<Boolean> completed = new CompletableFuture<>();
                        controlTasks.add(
                                Bukkit.getScheduler()
                                        .runTaskAsynchronously(
                                                probe,
                                                () -> {
                                                    try {
                                                        cancelled.cancel(false);
                                                        manual.complete(50);
                                                        failed.completeExceptionally(
                                                                new IllegalStateException(
                                                                        "probe-owned failure"));
                                                        completed.complete(true);
                                                    } catch (Throwable error) {
                                                        completed.completeExceptionally(error);
                                                    }
                                                }));
                        after(
                                completed,
                                complete -> {
                                    check(
                                            cancellationObserver.isCompletedExceptionally()
                                                    && number(recovered.join()) == 37
                                                    && number(applied.join()) == 51
                                                    && number(recoveredError.join()) == 43,
                                            "caller cancel, complete, and completeExceptionally preserve ordinary future results");
                                    check(
                                            observed.size() == 16
                                                    && observed.stream()
                                                            .allMatch(
                                                                    value ->
                                                                            value.endsWith(
                                                                                    ":true")),
                                            "caller-driven completions restore scope for cancellation observers, handle, and exceptionally");
                                    timersAndDelays();
                                });
                    });
        }

        private void timersAndDelays() {
            CompletableFuture<Boolean> syncDone = new CompletableFuture<>(),
                    asyncDone = new CompletableFuture<>();
            CompletableFuture<Boolean> mainLater = new CompletableFuture<>(),
                    asyncLater = new CompletableFuture<>();
            CompletableFuture<Boolean> mainRunLater = new CompletableFuture<>(),
                    asyncRunLater = new CompletableFuture<>();
            catalog.actionContext(
                            null,
                            Map.of(
                                    "syncTicks",
                                    syncTicks,
                                    "asyncTicks",
                                    asyncTicks,
                                    "negativePeriod",
                                    negativePeriod,
                                    "syncDone",
                                    syncDone,
                                    "asyncDone",
                                    asyncDone,
                                    "mainLater",
                                    mainLater,
                                    "asyncLater",
                                    asyncLater,
                                    "mainRunLater",
                                    mainRunLater,
                                    "asyncRunLater",
                                    asyncRunLater))
                    .evaluate(
                            """
                    global.put('marker', 'timer-context');
                    SchedulerUtils.syncLater(plugin, 2, function() {
                        mainLater.complete(Bukkit.isPrimaryThread() && context.isSync() && context.parse('<marker>') == 'timer-context');
                    });
                    SchedulerUtils.asyncLater(plugin, 2, function() {
                        asyncLater.complete(!Bukkit.isPrimaryThread() && !context.isSync() && context.parse('<marker>') == 'timer-context');
                    });
                    SchedulerUtils.runLater(2, function() { mainRunLater.complete(Bukkit.isPrimaryThread()); });
                    SchedulerUtils.run(plugin, false, function() {
                        SchedulerUtils.runLater(plugin, 2, function() { asyncRunLater.complete(!Bukkit.isPrimaryThread()); });
                    });
                    SchedulerUtils.syncTimer(plugin, 1, 0, function() {
                        if (syncTicks.incrementAndGet() >= 2) syncDone.complete(Bukkit.isPrimaryThread() && context.isSync());
                    });
                    SchedulerUtils.asyncTimer(1, 1, function() {
                        if (asyncTicks.incrementAndGet() >= 2) asyncDone.complete(!Bukkit.isPrimaryThread() && !context.isSync());
                    });
                    SchedulerUtils.syncTimer(1, -1, function() { negativePeriod.incrementAndGet(); });
                    """);
            check(
                    !mainLater.isDone() && !mainRunLater.isDone(),
                    "positive-delay callbacks do not execute inline");
            after(
                    CompletableFuture.allOf(
                            syncDone,
                            asyncDone,
                            mainLater,
                            asyncLater,
                            mainRunLater,
                            asyncRunLater),
                    ignored -> {
                        check(
                                Boolean.TRUE.equals(mainLater.join())
                                        && Boolean.TRUE.equals(asyncLater.join()),
                                "syncLater and asyncLater retain thread and action context");
                        check(
                                Boolean.TRUE.equals(mainRunLater.join())
                                        && Boolean.TRUE.equals(asyncRunLater.join()),
                                "runLater preserves the submitting thread category");
                        check(
                                Boolean.TRUE.equals(syncDone.join())
                                        && Boolean.TRUE.equals(asyncDone.join()),
                                "syncTimer including period zero and asyncTimer repeat on their requested threads");
                        replaceRevision();
                    });
        }

        private void replaceRevision() {
            NiCatalog previous = catalog;
            catalog = createCatalog(2);
            AtomicReference<CompletableFuture<?>> cancelled = new AtomicReference<>();
            AtomicInteger late = new AtomicInteger();
            CompletableFuture<Boolean> closed = new CompletableFuture<>();
            ActionTasks queuedTasks = new ActionTasks(probe);
            auxiliaryTasks.add(queuedTasks);
            LegacyScheduler queuedScheduler =
                    new LegacyScheduler(
                            probe,
                            queuedTasks,
                            previous.actionContext(null, null).evaluation().scripts());
            // Only this isolated scheduling registry closes on the worker. NiCatalog.close
            // may return reserved inventory items, so the actual catalogs close on the main thread.
            Runnable closeQueued = queuedTasks::close;
            previous.actionContext(
                            null,
                            Map.of(
                                    "cancelled",
                                    cancelled,
                                    "late",
                                    late,
                                    "closeQueued",
                                    closeQueued,
                                    "queuedScheduler",
                                    queuedScheduler,
                                    "closed",
                                    closed))
                    .evaluate(
                            """
                    async(function() {
                        try {
                            SchedulerUtils.syncLater(10, function() { late.incrementAndGet(); });
                            SchedulerUtils.asyncLater(10, function() { late.incrementAndGet(); });
                            cancelled.set(queuedScheduler.callSyncMethod(function() { late.incrementAndGet(); return 41; }));
                            closeQueued.run();
                            queuedScheduler.sync(function() { late.incrementAndGet(); });
                            queuedScheduler.async(function() { late.incrementAndGet(); });
                            closed.complete(true);
                        } catch (error) { closed.completeExceptionally(new (Java.type('java.lang.IllegalStateException'))(String(error))); }
                    });
                    """);
            after(
                    closed,
                    ignored -> {
                        check(
                                cancelled.get() != null && cancelled.get().isCancelled(),
                                "scheduler registry close cancels an already-queued callSyncMethod future");
                        previous.close();
                        int stoppedSync = syncTicks.get(), stoppedAsync = asyncTicks.get();
                        AtomicInteger nextTicks = new AtomicInteger();
                        CompletableFuture<Boolean> nextStarted = new CompletableFuture<>();
                        catalog.actionContext(
                                        null,
                                        Map.of("nextTicks", nextTicks, "nextStarted", nextStarted))
                                .evaluate(
                                        """
                        SchedulerUtils.syncTimer(1, 1, function() {
                            if (nextTicks.incrementAndGet() == 2) nextStarted.complete(true);
                        });
                        """);
                        after(
                                nextStarted,
                                started -> {
                                    check(
                                            Boolean.TRUE.equals(started) && nextTicks.get() >= 2,
                                            "replacement revision schedules normally after the old revision closes");
                                    PendingContinuations continuations = queueContinuations();
                                    catalog.close();
                                    check(
                                            continuations.stages.stream()
                                                    .allMatch(CompletableFuture::isCancelled),
                                            "close completes queued Async continuation futures without waiting for their external executor");
                                    check(
                                            continuations.observers.get() == 3
                                                    && continuations.scopeValid.get(),
                                            "close runs already-registered cancellation observers inside the captured scope before scripts close");
                                    for (Runnable queued : continuations.queue) queued.run();
                                    CompletableFuture<?> lateObserver =
                                            continuations.source.whenComplete(
                                                    continuations.observer);
                                    CompletableFuture<?> lateBody =
                                            continuations.source.thenApply(continuations.mapper);
                                    check(
                                            continuations.effects.get() == 0,
                                            "queued and newly-registered continuations do not execute JS after scripts close");
                                    check(
                                            cancellationOutcome(lateObserver)
                                                    && cancellationOutcome(lateBody),
                                            "late continuations finish with cancellation: observer="
                                                    + lateObserver.state()
                                                    + ", body="
                                                    + lateBody.state());
                                    int stoppedNext = nextTicks.get();
                                    later(
                                            14,
                                            () -> {
                                                check(
                                                        syncTicks.get() == stoppedSync
                                                                && asyncTicks.get() == stoppedAsync,
                                                        "replacement stops both old synchronous and asynchronous timers");
                                                check(
                                                        late.get() == 0,
                                                        "cancelled callbacks and submissions after close produce no effects");
                                                check(
                                                        nextTicks.get() == stoppedNext,
                                                        "final catalog close stops its timer");
                                                check(
                                                        negativePeriod.get() == 1,
                                                        "negative timer period executes exactly once");
                                                check(
                                                        !calls.contains("disabled-owner-ran"),
                                                        "all disabled Plugin owner scheduling overloads remain inactive");
                                                finish(null);
                                            });
                                });
                    });
        }

        private PendingContinuations queueContinuations() {
            Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
            AtomicInteger effects = new AtomicInteger(), observers = new AtomicInteger();
            AtomicBoolean scopeValid = new AtomicBoolean(true);
            AtomicReference<CompletableFuture<Object>> source = new AtomicReference<>();
            AtomicReference<Function<Object, Object>> mapper = new AtomicReference<>();
            AtomicReference<BiConsumer<Object, Throwable>> observer = new AtomicReference<>();
            AtomicReference<BiFunction<Object, Throwable, Object>> handler =
                    new AtomicReference<>();
            AtomicReference<BiConsumer<Object, Throwable>> cancellationObserver =
                    new AtomicReference<>();
            catalog.actionContext(
                            null,
                            Map.of(
                                    "effects",
                                    effects,
                                    "observers",
                                    observers,
                                    "scopeValid",
                                    scopeValid,
                                    "source",
                                    source,
                                    "mapper",
                                    mapper,
                                    "observer",
                                    observer,
                                    "handler",
                                    handler,
                                    "cancellationObserver",
                                    cancellationObserver))
                    .evaluate(
                            """
                    mapper.set(new (Java.type('java.util.function.Function'))(function(value) { effects.incrementAndGet(); return value; }));
                    observer.set(new (Java.type('java.util.function.BiConsumer'))(function(value, error) { effects.incrementAndGet(); }));
                    handler.set(new (Java.type('java.util.function.BiFunction'))(function(value, error) { effects.incrementAndGet(); return value; }));
                    cancellationObserver.set(new (Java.type('java.util.function.BiConsumer'))(function(value, error) {
                        var active = ActionContext.currentOrNull();
                        scopeValid.set(scopeValid.get() && error != null && active != null && active.equals(context)
                            && Java.type('java.lang.Thread').holdsLock(context.evaluation().scripts())
                            && context.evaluation().scripts().isOpen() && !context.active());
                        observers.incrementAndGet();
                    }));
                    source.set(SchedulerUtils.callSyncMethod(function() { return 5; }));
                    """);
            Executor held = queue::add;
            List<CompletableFuture<Object>> stages =
                    List.of(
                            source.get().thenApplyAsync(mapper.get(), held),
                            source.get().whenCompleteAsync(observer.get(), held),
                            source.get().handleAsync(handler.get(), held));
            for (CompletableFuture<Object> stage : stages)
                stage.whenComplete(cancellationObserver.get());
            check(
                    queue.size() == 3 && stages.stream().noneMatch(CompletableFuture::isDone),
                    "three Async continuation families are queued without running user callbacks");
            return new PendingContinuations(
                    queue,
                    stages,
                    effects,
                    observers,
                    scopeValid,
                    source.get(),
                    mapper.get(),
                    observer.get());
        }

        private record PendingContinuations(
                Queue<Runnable> queue,
                List<CompletableFuture<Object>> stages,
                AtomicInteger effects,
                AtomicInteger observers,
                AtomicBoolean scopeValid,
                CompletableFuture<Object> source,
                Function<Object, Object> mapper,
                BiConsumer<Object, Throwable> observer) {}

        private <T> void after(CompletionStage<T> stage, Consumer<T> next) {
            stage.whenComplete(
                    (value, error) ->
                            later(
                                    0,
                                    () -> {
                                        if (error != null)
                                            throw new IllegalStateException(
                                                    "Scheduler probe callback failed", error);
                                        next.accept(value);
                                    }));
        }

        private void later(long ticks, Runnable next) {
            if (finished.get()) return;
            try {
                controlTasks.add(
                        Bukkit.getScheduler()
                                .runTaskLater(
                                        probe,
                                        () -> {
                                            if (finished.get()) return;
                                            try {
                                                next.run();
                                            } catch (Throwable error) {
                                                finish(error);
                                            }
                                        },
                                        ticks));
            } catch (Throwable error) {
                finish(error);
            }
        }

        private void check(boolean value, String name) {
            if (!value) throw new AssertionError(name);
            checks.add(name);
        }

        private void finish(Throwable error) {
            if (!Bukkit.isPrimaryThread()) {
                Throwable cause = error;
                try {
                    controlTasks.add(Bukkit.getScheduler().runTask(probe, () -> finish(cause)));
                } catch (Throwable dispatchFailure) {
                    if (cause != null) dispatchFailure.addSuppressed(cause);
                    finished.set(true);
                    report.completeExceptionally(
                            new IllegalStateException(
                                    "Cannot schedule private catalog cleanup on the server thread",
                                    dispatchFailure));
                }
                return;
            }
            if (!finished.compareAndSet(false, true)) return;
            for (BukkitTask task : controlTasks) {
                try {
                    task.cancel();
                } catch (Throwable cleanup) {
                    error = append(error, cleanup);
                }
            }
            controlTasks.clear();
            for (ActionTasks value : auxiliaryTasks) {
                try {
                    value.close();
                } catch (Throwable cleanup) {
                    error = append(error, cleanup);
                }
            }
            for (NiCatalog value : catalogs) {
                try {
                    value.close();
                } catch (Throwable cleanup) {
                    error = append(error, cleanup);
                }
            }
            players.close();
            if (error != null) report.completeExceptionally(error);
            else
                report.complete(
                        Map.of(
                                "passed",
                                true,
                                "checks",
                                checks.size(),
                                "verified",
                                List.copyOf(checks),
                                "referenceRequired",
                                false,
                                "revisionReplacement",
                                "private catalogs; ItemsService reload is covered separately",
                                "pendingCancellation",
                                "isolated ActionTasks registry using the same script engine; catalogs close on the server thread"));
        }

        private static Throwable append(Throwable previous, Throwable error) {
            if (previous == null) return error;
            previous.addSuppressed(error);
            return previous;
        }

        private static int number(Object value) {
            return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
        }

        private static boolean cancellationOutcome(CompletableFuture<?> future) {
            if (!future.isDone()) return false;
            try {
                future.getNow(null);
                return false;
            } catch (java.util.concurrent.CancellationException cancelled) {
                return true;
            } catch (java.util.concurrent.CompletionException failed) {
                Throwable cause = failed;
                while ((cause instanceof java.util.concurrent.CompletionException
                                || cause instanceof java.util.concurrent.ExecutionException)
                        && cause.getCause() != null) cause = cause.getCause();
                return cause instanceof java.util.concurrent.CancellationException;
            }
        }
    }
}

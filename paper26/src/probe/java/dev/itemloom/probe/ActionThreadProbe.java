package dev.itemloom.probe;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Real scheduler and Nashorn actions against private catalogs, without players or NI. */
final class ActionThreadProbe {
    private static final String EXPANSION =
            """
            var manager = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
            var CompletableFuture = Java.type('java.util.concurrent.CompletableFuture');
            var Thread = Java.type('java.lang.Thread');
            var Bukkit = Java.type('org.bukkit.Bukkit');
            var ActionContext = Java.type('pers.neige.neigeitems.action.ActionContext');
            function enable() {
                manager.addFunction('probe-ready-async', true, function(context, text) {
                    context.setSync(false);
                    var ready = CompletableFuture.completedFuture(true);
                    context.getParams().get('trace').add('ready-async|' + Bukkit.isPrimaryThread() + '|'
                        + context.isSync() + '|' + ActionContext.currentOrNull().equals(context) + '|'
                        + Thread.currentThread().getName());
                    context.getParams().get('readyFlags').add(ready.isDone());
                    return ready;
                });
                manager.addFunction('probe-ready-sync', true, function(context, text) {
                    context.setSync(true);
                    var ready = CompletableFuture.completedFuture(true);
                    context.getParams().get('trace').add('ready-sync|' + Bukkit.isPrimaryThread() + '|'
                        + context.isSync() + '|' + ActionContext.currentOrNull().equals(context) + '|'
                        + Thread.currentThread().getName());
                    context.getParams().get('readyFlags').add(ready.isDone());
                    return ready;
                });
            }
            """;

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Action thread probe must start on the server thread");
        Runner runner = new Runner(plugin);
        runner.start();
        return runner.report;
    }

    private static String record(String label) {
        return "trace.add('"
                + label
                + "|' + Bukkit.isPrimaryThread() + '|' + context.isSync() + '|'"
                + " + ActionContext.currentOrNull().equals(context) + '|' + Java.type('java.lang.Thread').currentThread().getName());";
    }

    private static String js(String label) {
        return "js: " + record(label) + " true;";
    }

    private static Map<String, Object> functions() {
        Map<String, Object> functions = new LinkedHashMap<>();
        functions.put(
                "threads",
                List.of(
                        js("start-main"),
                        "async",
                        js("string-async"),
                        "tell: effect-main",
                        js("after-effect-worker"),
                        "delay: 2",
                        js("after-delay-worker"),
                        Map.of(
                                "while",
                                "(function(){"
                                        + record("while-condition")
                                        + "return remaining.getAndDecrement() > 0;})()",
                                "actions",
                                List.of(js("while-body"), "tell: while-effect"),
                                "finally",
                                js("while-finally")),
                        "sync",
                        js("string-sync"),
                        "delay: 2",
                        js("after-delay-main")));
        functions.put(
                "ready",
                List.of(
                        "probe-ready-async",
                        js("ready-follow-worker"),
                        "probe-ready-sync",
                        js("ready-follow-main")));
        functions.put(
                "wait-main",
                List.of("js: " + record("wait-main-before") + " pending;", js("wait-main-after")));
        functions.put(
                "wait-worker",
                List.of(
                        "async",
                        "js: " + record("wait-worker-before") + " armed.complete(true); pending;",
                        js("wait-worker-after")));
        functions.put(
                "cancel-main",
                List.of(js("cancel-main-before"), "delay: 12", js("forbidden-old-main")));
        functions.put(
                "cancel-worker",
                List.of(
                        "async",
                        "js: " + record("cancel-worker-before") + " armed.complete(true); true;",
                        "delay: 12",
                        js("forbidden-old-worker")));
        functions.put("replacement", js("replacement-main"));
        functions.put(
                "close-main",
                List.of(js("close-main-before"), "delay: 12", js("forbidden-after-close")));
        return functions;
    }

    private static final class Runner {
        private final JavaPlugin plugin;
        private final PlayerActionState players = new PlayerActionState();
        private final Queue<String> trace = new ConcurrentLinkedQueue<>();
        private final Queue<Boolean> readyFlags = new ConcurrentLinkedQueue<>();
        private final Queue<BukkitTask> controls = new ConcurrentLinkedQueue<>();
        private final List<NiCatalog> catalogs = new ArrayList<>();
        private final List<String> verified = new ArrayList<>();
        private final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CommandSender sender;
        private NiCatalog catalog;
        private int attempted;

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
            sender =
                    (CommandSender)
                            Proxy.newProxyInstance(
                                    CommandSender.class.getClassLoader(),
                                    new Class<?>[] {CommandSender.class},
                                    (proxy, method, arguments) ->
                                            switch (method.getName()) {
                                                case "sendMessage" -> {
                                                    if (arguments != null)
                                                        for (Object value : arguments) {
                                                            if (value instanceof String text)
                                                                effect(text);
                                                            else if (value
                                                                    instanceof String[] messages)
                                                                for (String text : messages)
                                                                    effect(text);
                                                        }
                                                    yield null;
                                                }
                                                case "getName", "toString" -> "ActionThreadProbe";
                                                case "getServer" -> plugin.getServer();
                                                case "hasPermission", "isPermissionSet", "isOp" ->
                                                        true;
                                                case "hashCode" -> System.identityHashCode(proxy);
                                                case "equals" -> proxy == arguments[0];
                                                default ->
                                                        throw new UnsupportedOperationException(
                                                                "Unexpected synthetic sender call: "
                                                                        + method);
                                            });
        }

        private void effect(String label) {
            trace.add(
                    label
                            + "|"
                            + Bukkit.isPrimaryThread()
                            + "|effect|effect|"
                            + Thread.currentThread().getName());
        }

        void start() {
            try {
                later(
                        120,
                        () ->
                                finish(
                                        new AssertionError(
                                                "Action thread probe timed out after 120 ticks")));
                catalog = createCatalog(1);
                after(
                        catalog.runFunction(
                                "threads",
                                sender,
                                Map.of("trace", trace, "remaining", new AtomicInteger(2))),
                        outcome -> {
                            check(
                                    !outcome.stopped(),
                                    "mixed string/JS/effect/delay action sequence completes");
                            expect("start-main", true, true, 1);
                            expect("string-async", false, false, 1);
                            expectEffect("effect-main", 1);
                            expect("after-effect-worker", false, false, 1);
                            expect("after-delay-worker", false, false, 1);
                            expect("while-condition", false, false, 3);
                            expect("while-body", false, false, 2);
                            expectEffect("while-effect", 2);
                            expect("while-finally", false, false, 1);
                            expect("string-sync", true, true, 1);
                            expect("after-delay-main", true, true, 1);
                            readyCompletions();
                        });
            } catch (Throwable error) {
                finish(error);
            }
        }

        private NiCatalog createCatalog(long revision) {
            var input =
                    new NiRepository.Input(
                            new NiConfig(Map.of("Language", "en_us")),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            functions(),
                            Map.of(),
                            Map.of("action-thread-probe.js", EXPANSION),
                            Map.of());
            NiCatalog value =
                    new NiCatalog(revision, input, plugin, (viewer, text) -> null, players);
            catalogs.add(value);
            return value;
        }

        private void readyCompletions() {
            after(
                    catalog.runFunction(
                            "ready", sender, Map.of("trace", trace, "readyFlags", readyFlags)),
                    outcome -> {
                        check(
                                !outcome.stopped()
                                        && readyFlags.size() == 2
                                        && readyFlags.stream().allMatch(Boolean.TRUE::equals),
                                "custom switch actions return futures already completed before continuation registration");
                        expect("ready-async", true, false, 1);
                        expect("ready-follow-worker", false, false, 1);
                        expect("ready-sync", false, true, 1);
                        expect("ready-follow-main", true, true, 1);
                        oppositeThreadCompletions();
                    });
        }

        private void oppositeThreadCompletions() {
            CompletableFuture<Boolean> mainPending = new CompletableFuture<>();
            var main =
                    catalog.runFunction(
                            "wait-main", sender, Map.of("trace", trace, "pending", mainPending));
            check(
                    !main.toCompletableFuture().isDone(),
                    "a pending script future does not block the main thread");
            controls.add(
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(
                                    plugin,
                                    () -> {
                                        effect("complete-main-on-worker");
                                        mainPending.complete(true);
                                    }));
            after(
                    main,
                    outcome -> {
                        check(
                                !outcome.stopped(),
                                "main-owned action resumes after worker future completion");
                        expect("wait-main-before", true, true, 1);
                        expect("wait-main-after", true, true, 1);
                        expectThread("complete-main-on-worker", false, 1);
                        CompletableFuture<Boolean> pending = new CompletableFuture<>(),
                                armed = new CompletableFuture<>();
                        var worker =
                                catalog.runFunction(
                                        "wait-worker",
                                        sender,
                                        Map.of("trace", trace, "pending", pending, "armed", armed));
                        after(
                                armed,
                                ignored -> {
                                    effect("complete-worker-on-main");
                                    pending.complete(true);
                                    after(
                                            worker,
                                            result -> {
                                                check(
                                                        !result.stopped(),
                                                        "worker-owned action resumes after main future completion");
                                                expect("wait-worker-before", false, false, 1);
                                                expect("wait-worker-after", false, false, 1);
                                                expectThread("complete-worker-on-main", true, 1);
                                                cancelRevisions();
                                            });
                                });
                    });
        }

        private void cancelRevisions() {
            NiCatalog previous = catalog;
            var main =
                    previous.runFunction("cancel-main", sender, Map.of("trace", trace))
                            .toCompletableFuture();
            CompletableFuture<Boolean> armed = new CompletableFuture<>();
            var worker =
                    previous.runFunction(
                                    "cancel-worker", sender, Map.of("trace", trace, "armed", armed))
                            .toCompletableFuture();
            after(
                    armed,
                    ignored -> {
                        catalog = createCatalog(2);
                        previous.close();
                        check(
                                main.isDone()
                                        && main.join().stopped()
                                        && worker.isDone()
                                        && worker.join().stopped(),
                                "replacing a private catalog stops both old main and worker action futures");
                        after(
                                catalog.runFunction("replacement", sender, Map.of("trace", trace)),
                                outcome -> {
                                    check(
                                            !outcome.stopped(),
                                            "replacement catalog executes independently of cancelled old work");
                                    expect("replacement-main", true, true, 1);
                                    var closing =
                                            catalog.runFunction(
                                                            "close-main",
                                                            sender,
                                                            Map.of("trace", trace))
                                                    .toCompletableFuture();
                                    catalog.close();
                                    check(
                                            closing.isDone() && closing.join().stopped(),
                                            "closing the replacement catalog completes its pending action with STOP");
                                    later(
                                            16,
                                            () -> {
                                                expect("cancel-main-before", true, true, 1);
                                                expect("cancel-worker-before", false, false, 1);
                                                expect("close-main-before", true, true, 1);
                                                check(
                                                        trace.stream()
                                                                .noneMatch(
                                                                        value ->
                                                                                value.startsWith(
                                                                                        "forbidden-")),
                                                        "old revision and closed revision produce no continuation after their delay deadlines");
                                                finish(null);
                                            });
                                });
                    });
        }

        private List<String> entries(String label) {
            return trace.stream().filter(value -> value.startsWith(label + "|")).toList();
        }

        private void expect(String label, boolean main, boolean sync, int count) {
            List<String> entries = entries(label);
            String prefix = label + "|" + main + "|" + sync + "|true|";
            check(
                    entries.size() == count
                            && entries.stream().allMatch(value -> value.startsWith(prefix)),
                    label
                            + " executes "
                            + count
                            + " time(s) on "
                            + (main ? "main" : "worker")
                            + " with matching current action context");
        }

        private void expectEffect(String label, int count) {
            expectThread(label, true, count);
        }

        private void expectThread(String label, boolean main, int count) {
            List<String> entries = entries(label);
            check(
                    entries.size() == count
                            && entries.stream()
                                    .allMatch(value -> value.startsWith(label + "|" + main + "|")),
                    label + " executes " + count + " time(s) on " + (main ? "main" : "worker"));
        }

        private void check(boolean passed, String name) {
            attempted++;
            if (!passed) throw new AssertionError(name);
            verified.add(name);
        }

        private <T> void after(CompletionStage<T> stage, Consumer<T> next) {
            stage.whenComplete(
                    (value, error) ->
                            later(
                                    0,
                                    () -> {
                                        if (error != null)
                                            throw new IllegalStateException(
                                                    "Action thread probe continuation failed",
                                                    error);
                                        next.accept(value);
                                    }));
        }

        private void later(long ticks, Runnable body) {
            if (finished.get()) return;
            try {
                controls.add(
                        Bukkit.getScheduler()
                                .runTaskLater(
                                        plugin,
                                        () -> {
                                            if (finished.get()) return;
                                            try {
                                                body.run();
                                            } catch (Throwable error) {
                                                finish(error);
                                            }
                                        },
                                        ticks));
            } catch (Throwable error) {
                finish(error);
            }
        }

        private void finish(Throwable failure) {
            if (!Bukkit.isPrimaryThread()) {
                Throwable cause = failure;
                try {
                    controls.add(Bukkit.getScheduler().runTask(plugin, () -> finish(cause)));
                } catch (Throwable error) {
                    report.completeExceptionally(error);
                }
                return;
            }
            if (!finished.compareAndSet(false, true)) return;
            for (BukkitTask task : controls) task.cancel();
            for (NiCatalog value : catalogs) {
                try {
                    value.close();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            players.close();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String value : trace)
                counts.merge(value.substring(0, value.indexOf('|')), 1, Integer::sum);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", failure == null);
            result.put("checks", attempted);
            result.put("passedChecks", verified.size());
            result.put("verified", List.copyOf(verified));
            result.put("threadLog", List.copyOf(trace));
            result.put(
                    "threadLogFormat",
                    "label|primaryThread|context.isSync|currentContextMatches|threadName; effect entries omit context");
            result.put("counts", counts);
            result.put("referenceRequired", false);
            result.put("server", plugin.getServer().getMinecraftVersion());
            result.put(
                    "fixtures",
                    "private catalogs/functions, real Paper scheduler, synthetic CommandSender; no players or commands");
            result.put(
                    "revisionReplacement",
                    "private catalog replacement and close; ItemsService filesystem reload is covered separately");
            if (failure != null) result.put("failure", failure.toString());
            report.complete(result);
        }
    }
}

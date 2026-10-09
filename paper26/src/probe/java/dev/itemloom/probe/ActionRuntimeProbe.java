package dev.itemloom.probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.paper.ItemsService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercises real scheduler/reload behavior with a private configuration tree in the sandbox. */
final class ActionRuntimeProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin probe) throws Exception {
        Path root = probe.getDataFolder().toPath().resolve("runtime-fixtures");
        Files.createDirectories(root.resolve("Items"));
        Files.createDirectories(root.resolve("Functions"));
        Files.createDirectories(root.resolve("Expansions"));
        Files.writeString(
                root.resolve("Items/probe.yml"),
                "probe: {material: STONE}\n",
                StandardCharsets.UTF_8);
        String functions =
                """
                legacy:
                  - 'legacy-record: imported'
                  - "js: new (Java.type('pers.neige.neigeitems.action.result.StopResult'))('legacy', 7)"
                delayed:
                  - 'js: calls.add("before");'
                  - 'delay: 4'
                  - 'js: calls.add("after");'
                long-delay:
                  - 'js: calls.add("before");'
                  - 'delay: 20'
                  - 'js: calls.add("after");'
                thread-check:
                  condition: 'true'
                  async:
                    - 'js: wasAsync.set(!Packages.org.bukkit.Bukkit.isPrimaryThread());'
                    - 'console: il list'
                    - 'js: finished.complete(!Packages.org.bukkit.Bukkit.isPrimaryThread());'
                """;
        Path file = root.resolve("Functions/probe.yml");
        Files.writeString(file, functions, StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("Expansions/probe.js"),
                """
                var manager = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
                function enable() {
                    manager.addConsumer('legacy-record', function(context, text) {
                        context.getParams().get('calls').add(text);
                    });
                }
                """,
                StandardCharsets.UTF_8);
        ItemsService service =
                new ItemsService(
                        probe, root, (viewer, text) -> null, root.resolve("return-ledger.json"));
        check(service.reload(Bukkit.getConsoleSender()), "initial load");
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        var imported =
                service.runFunction("legacy", null, Map.of("calls", calls)).toCompletableFuture();
        check(
                imported.isDone()
                        && imported.join().equals(new ActionFlow.Result(true, "legacy", 7)),
                "legacy return type adaptation");
        check(calls.equals(List.of("imported")), "legacy expansion action registration");
        calls.clear();
        CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        CompletionStage<ActionFlow.Result> waiting =
                service.runFunction("delayed", null, Map.of("calls", calls));
        check(
                calls.equals(List.of("before")) && !waiting.toCompletableFuture().isDone(),
                "delay does not block the server thread");
        Files.writeString(
                file,
                "bad: {condition: 'broken(', actions: 'tell: never'}\n",
                StandardCharsets.UTF_8);
        check(!service.reload(Bukkit.getConsoleSender()), "bad script rejects reload");
        check(service.ids().contains("probe"), "bad reload retains catalog");
        waiting.whenComplete(
                (result, error) -> {
                    try {
                        if (error != null) throw new IllegalStateException(error);
                        check(
                                !result.stopped() && calls.equals(List.of("before", "after")),
                                "failed reload retains old pending actions");
                        Files.writeString(file, functions, StandardCharsets.UTF_8);
                        calls.clear();
                        var cancelled =
                                service.runFunction("long-delay", null, Map.of("calls", calls))
                                        .toCompletableFuture();
                        check(service.reload(Bukkit.getConsoleSender()), "valid reload");
                        check(
                                cancelled.isDone() && cancelled.join().stopped(),
                                "valid reload completes old pending futures");
                        java.util.concurrent.atomic.AtomicBoolean wasAsync =
                                new java.util.concurrent.atomic.AtomicBoolean();
                        CompletableFuture<Boolean> finished = new CompletableFuture<>();
                        service.runFunction(
                                "thread-check",
                                null,
                                Map.of("wasAsync", wasAsync, "finished", finished));
                        finished.whenComplete(
                                (restoredAsync, threadError) ->
                                        Bukkit.getScheduler()
                                                .runTask(
                                                        probe,
                                                        () -> {
                                                            try {
                                                                if (threadError != null)
                                                                    throw new IllegalStateException(
                                                                            threadError);
                                                                check(
                                                                        wasAsync.get()
                                                                                && Boolean.TRUE
                                                                                        .equals(
                                                                                                restoredAsync),
                                                                        "async branch resumes its script thread after a Bukkit effect");
                                                                var stopped =
                                                                        service.runFunction(
                                                                                        "long-delay",
                                                                                        null,
                                                                                        Map.of(
                                                                                                "calls",
                                                                                                calls))
                                                                                .toCompletableFuture();
                                                                service.close();
                                                                check(
                                                                        stopped.isDone()
                                                                                && stopped.join()
                                                                                        .stopped(),
                                                                        "close completes pending futures");
                                                                Bukkit.getScheduler()
                                                                        .runTaskLater(
                                                                                probe,
                                                                                () -> {
                                                                                    try {
                                                                                        check(
                                                                                                calls
                                                                                                        .equals(
                                                                                                                List
                                                                                                                        .of(
                                                                                                                                "before",
                                                                                                                                "before")),
                                                                                                "cancelled work produces no late effects");
                                                                                        report
                                                                                                .complete(
                                                                                                        Map
                                                                                                                .of(
                                                                                                                        "checks",
                                                                                                                        12,
                                                                                                                        "passed",
                                                                                                                        true,
                                                                                                                        "calls",
                                                                                                                        List
                                                                                                                                .copyOf(
                                                                                                                                        calls),
                                                                                                                        "referenceRequired",
                                                                                                                        false));
                                                                                    } catch (
                                                                                            Throwable
                                                                                                    failure) {
                                                                                        report
                                                                                                .completeExceptionally(
                                                                                                        failure);
                                                                                    }
                                                                                },
                                                                                24);
                                                            } catch (Throwable failure) {
                                                                service.close();
                                                                report.completeExceptionally(
                                                                        failure);
                                                            }
                                                        }));
                    } catch (Throwable failure) {
                        service.close();
                        report.completeExceptionally(failure);
                    }
                });
        Bukkit.getScheduler()
                .runTaskLater(
                        probe,
                        () -> {
                            if (!report.isDone()) {
                                service.close();
                                report.completeExceptionally(
                                        new IllegalStateException("Action probe timed out"));
                            }
                        },
                        160);
        return report;
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}

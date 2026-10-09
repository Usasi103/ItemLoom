package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ActionFlow.Result;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Registered input listeners and real schedulers, without sending player chat to the server. */
@SuppressWarnings("deprecation")
final class InputCaptureProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Input probe starts on the server thread");
        Runner runner = new Runner(plugin);
        runner.start();
        return runner.report;
    }

    private static String mark(String name) {
        return "js: marks.add('"
                + name
                + "|' + Java.type('org.bukkit.Bukkit').isPrimaryThread() + '|' + context.isSync()); true;";
    }

    private static final class Runner {
        final JavaPlugin plugin;
        final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        final Queue<String> marks = new ConcurrentLinkedQueue<>();
        final Queue<BukkitTask> controls = new ConcurrentLinkedQueue<>();
        final List<BuiltinActionProbe.Fixture> fixtures = new ArrayList<>();
        final List<String> verified = new ArrayList<>();
        final AtomicBoolean finished = new AtomicBoolean();
        final int baselineListeners;
        BuiltinActionProbe.Fixture fixture;
        int attempted;

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
            baselineListeners = listeners();
            fixture = create(null);
        }

        BuiltinActionProbe.Fixture create(BuiltinActionProbe.SyntheticPlayer player) {
            var created =
                    player == null
                            ? new BuiltinActionProbe.Fixture(plugin)
                            : new BuiltinActionProbe.Fixture(plugin, player);
            fixtures.add(created);
            return created;
        }

        void start() {
            later(
                    240,
                    () ->
                            finish(
                                    new AssertionError(
                                            "Input capture probe timed out after 240 ticks")));
            try {
                check(
                        listeners() == baselineListeners + 1,
                        "each revision owns one registered chat listener");
                noUser();
                fifo();
            } catch (Throwable error) {
                finish(error);
            }
        }

        NiActionContext context() {
            return fixture.context(fixture.player.value, Map.of("marks", marks));
        }

        void noUser() {
            check(
                    !fixture.ready(fixture.context(null, Map.of()), "catch-chat: absent").stopped(),
                    "catch-chat without a player completes immediately");
            var orphan = new BuiltinActionProbe.SyntheticPlayer(plugin);
            check(
                    !fixture.ready(fixture.context(orphan.value, Map.of()), "catchChat: absent")
                            .stopped(),
                    "catchChat without registered user state completes immediately");
            fixture.player.online = false;
            check(
                    !fixture.ready(context(), "catch-chat: offline").stopped(),
                    "offline player with retained state cannot leave a waiting future");
            fixture.player.online = true;
        }

        void fifo() {
            NiActionContext firstContext = context(), secondContext = context();
            var first =
                    fixture.run(
                                    firstContext,
                                    List.of(
                                            "NeigeItems.catch-chat: first false",
                                            mark("fifo-first")))
                            .toCompletableFuture();
            var second =
                    fixture.run(
                                    secondContext,
                                    List.of("catchChat: second\\ key FALSE", mark("fifo-second")))
                            .toCompletableFuture();
            check(
                    !first.isDone() && !second.isDone(),
                    "both FIFO actions suspend without blocking the main thread");
            var firstChat = chat(fixture.player.value, "one", true).toCompletableFuture();
            after(
                    CompletableFuture.allOf(first, firstChat),
                    ignored -> {
                        check(
                                !first.join().stopped() && !firstChat.join() && !second.isDone(),
                                "first message consumes exactly one entry and cancel=false clears previous cancellation");
                        check(
                                "one".equals(firstContext.getGlobal().get("first")),
                                "captured text goes to the configured global key");
                        expect("fifo-first", true);
                        var secondChat =
                                chat(fixture.player.value, "two", false).toCompletableFuture();
                        after(
                                CompletableFuture.allOf(second, secondChat),
                                done -> {
                                    check(
                                            !second.join().stopped()
                                                    && secondChat.join()
                                                    && "two"
                                                            .equals(
                                                                    secondContext
                                                                            .getGlobal()
                                                                            .get("second key")),
                                            "escaped key is preserved and uppercase FALSE retains default cancellation");
                                    expect("fifo-second", true);
                                    emptyKey();
                                });
                    });
        }

        void emptyKey() {
            NiActionContext context = context();
            var result = fixture.run(context, "catch-chat").toCompletableFuture();
            var message = chat(fixture.player.value, "empty-key", false).toCompletableFuture();
            after(
                    CompletableFuture.allOf(result, message),
                    ignored -> {
                        check(
                                !result.join().stopped()
                                        && message.join()
                                        && "empty-key".equals(context.getGlobal().get(""))
                                        && !context.getGlobal().containsKey("catchChat"),
                                "bare catch-chat preserves NI's actual empty-string key and default cancel=true");
                        workerResume();
                    });
        }

        void workerResume() {
            NiActionContext context = context();
            after(
                    worker(
                            fixture,
                            context,
                            List.of("catch-chat: worker true", mark("worker-resume"))),
                    pending -> {
                        check(
                                !pending.toCompletableFuture().isDone(),
                                "worker-owned capture waits without occupying its scheduler worker");
                        var message =
                                chat(fixture.player.value, "worker-message", false)
                                        .toCompletableFuture();
                        after(
                                CompletableFuture.allOf(pending.toCompletableFuture(), message),
                                ignored -> {
                                    check(
                                            !pending.toCompletableFuture().join().stopped()
                                                    && message.join()
                                                    && "worker-message"
                                                            .equals(
                                                                    context.getGlobal()
                                                                            .get("worker")),
                                            "worker-owned capture receives its text and cancellation");
                                    expect("worker-resume", false);
                                    clear();
                                });
                    });
        }

        void clear() {
            NiActionContext mainContext = context(), workerContext = context();
            mainContext.getGlobal().put("cleared", "keep-existing");
            var main =
                    fixture.run(
                                    mainContext,
                                    List.of(
                                            "catch-chat: cleared",
                                            mark("clear-main"),
                                            "catch-chat: reentrant",
                                            mark("reentrant-main")))
                            .toCompletableFuture();
            after(
                    worker(
                            fixture,
                            workerContext,
                            List.of("catch-chat: worker-cleared", mark("clear-worker"))),
                    worker -> {
                        check(
                                !fixture.ready(context(), "NeigeItems.clear-catch-chat: ignored")
                                        .stopped(),
                                "clear-catch-chat itself succeeds");
                        after(
                                worker,
                                outcome -> {
                                    check(
                                            !outcome.stopped()
                                                    && !workerContext
                                                            .getGlobal()
                                                            .containsKey("worker-cleared")
                                                    && "keep-existing"
                                                            .equals(
                                                                    mainContext
                                                                            .getGlobal()
                                                                            .get("cleared")),
                                            "clear resumes all queued actions successfully without writing null to global");
                                    expect("clear-main", true);
                                    expect("clear-worker", false);
                                    check(
                                            !main.isDone(),
                                            "a continuation can enqueue another capture during clear without deadlock or being cleared again");
                                    var message =
                                            chat(fixture.player.value, "reentered", false)
                                                    .toCompletableFuture();
                                    after(
                                            CompletableFuture.allOf(main, message),
                                            ignored -> {
                                                check(
                                                        !main.join().stopped()
                                                                && message.join()
                                                                && "reentered"
                                                                        .equals(
                                                                                mainContext
                                                                                        .getGlobal()
                                                                                        .get(
                                                                                                "reentrant")),
                                                        "clear leaves no ghost FIFO entries before a reentrant capture");
                                                expect("reentrant-main", true);
                                                quit();
                                            });
                                });
                    });
        }

        void quit() {
            fixture.players.configure(false);
            NiActionContext mainContext = context(), workerContext = context();
            var main =
                    fixture.run(
                                    mainContext,
                                    List.of("catch-chat: quit-main", mark("forbidden-quit-main")))
                            .toCompletableFuture();
            after(
                    worker(
                            fixture,
                            workerContext,
                            List.of("catch-chat: quit-worker", mark("forbidden-quit-worker"))),
                    worker -> {
                        dispatch(new PlayerQuitEvent(fixture.player.value, (Component) null));
                        check(
                                fixture.ready(context(), "catch-chat: during-quit").stopped(),
                                "quit fences a new capture while CraftPlayer may still report online inside the event");
                        fixture.player.online = false;
                        fixture.players.quit(fixture.player.id);
                        after(
                                CompletableFuture.allOf(main, worker.toCompletableFuture()),
                                ignored -> {
                                    check(
                                            main.join().stopped()
                                                    && worker.toCompletableFuture()
                                                            .join()
                                                            .stopped(),
                                            "quit terminates both main and worker captures with STOP");
                                    check(
                                            fixture.players.contains(fixture.player.id)
                                                    && !fixture.ready(
                                                                    context(),
                                                                    "catch-chat: offline-again")
                                                            .stopped(),
                                            "retained user state after quit does not create a new offline wait");
                                    check(
                                            !mainContext.getGlobal().containsKey("quit-main")
                                                    && !workerContext
                                                            .getGlobal()
                                                            .containsKey("quit-worker"),
                                            "quit does not write capture results");
                                    after(
                                            chat(fixture.player.value, "late-after-quit", false),
                                            cancelled -> {
                                                check(
                                                        !cancelled,
                                                        "quit removes old entries so late chat is not swallowed");
                                                fixture.player.online = true;
                                                fixture.players.join(fixture.player.id);
                                                dispatch(
                                                        new PlayerJoinEvent(
                                                                fixture.player.value,
                                                                (Component) null));
                                                closeRevision();
                                            });
                                });
                    });
        }

        void closeRevision() {
            BuiltinActionProbe.Fixture old = fixture;
            NiActionContext mainContext = context(), workerContext = context();
            var main =
                    old.run(
                                    mainContext,
                                    List.of("catch-chat: close-main", mark("forbidden-close-main")))
                            .toCompletableFuture();
            after(
                    worker(
                            old,
                            workerContext,
                            List.of("catch-chat: close-worker", mark("forbidden-close-worker"))),
                    worker -> {
                        old.close();
                        check(
                                main.isDone()
                                        && main.join().stopped()
                                        && worker.toCompletableFuture().isDone()
                                        && worker.toCompletableFuture().join().stopped(),
                                "closing a revision completes pending capture action futures with STOP immediately");
                        check(
                                listeners() == baselineListeners,
                                "closed revision unregisters its chat listener");
                        fixture = create(old.player);
                        NiActionContext replacement = context();
                        var next =
                                fixture.run(
                                                replacement,
                                                List.of(
                                                        "catch-chat: replacement",
                                                        mark("replacement-main")))
                                        .toCompletableFuture();
                        var message =
                                chat(fixture.player.value, "fresh-revision", false)
                                        .toCompletableFuture();
                        after(
                                CompletableFuture.allOf(next, message),
                                ignored -> {
                                    check(
                                            !next.join().stopped()
                                                    && message.join()
                                                    && "fresh-revision"
                                                            .equals(
                                                                    replacement
                                                                            .getGlobal()
                                                                            .get("replacement")),
                                            "a replacement revision captures chat for the same player without old waiters");
                                    check(
                                            !mainContext.getGlobal().containsKey("close-main")
                                                    && !workerContext
                                                            .getGlobal()
                                                            .containsKey("close-worker"),
                                            "closed revision receives no late input mutation");
                                    expect("replacement-main", true);
                                    later(
                                            3,
                                            () -> {
                                                check(
                                                        marks.stream()
                                                                .noneMatch(
                                                                        value ->
                                                                                value.startsWith(
                                                                                        "forbidden-")),
                                                        "quit and close never run the following action");
                                                finish(null);
                                            });
                                });
                    });
        }

        CompletionStage<CompletionStage<Result>> worker(
                BuiltinActionProbe.Fixture owner, NiActionContext context, Object source) {
            CompletableFuture<CompletionStage<Result>> armed = new CompletableFuture<>();
            async(
                    () -> {
                        try {
                            context.setSync(false);
                            // run returns only after the immediate worker-thread catch action has
                            // enqueued.
                            armed.complete(owner.run(context, source));
                        } catch (Throwable error) {
                            armed.completeExceptionally(error);
                        }
                    });
            return armed;
        }

        CompletionStage<Boolean> chat(Player player, String message, boolean initiallyCancelled) {
            CompletableFuture<Boolean> sent = new CompletableFuture<>();
            async(
                    () -> {
                        try {
                            AsyncPlayerChatEvent event =
                                    new AsyncPlayerChatEvent(
                                            true, player, message, new HashSet<>());
                            event.setCancelled(initiallyCancelled);
                            dispatch(event);
                            sent.complete(event.isCancelled());
                        } catch (Throwable error) {
                            sent.completeExceptionally(error);
                        }
                    });
            return sent;
        }

        boolean owned(RegisteredListener listener) {
            return listener.getPlugin() == plugin
                    && listener.getListener()
                            .getClass()
                            .getName()
                            .equals("dev.itemloom.paper.action.ActionInputCapture");
        }

        int listeners() {
            int count = 0;
            for (var listener : AsyncPlayerChatEvent.getHandlerList().getRegisteredListeners())
                if (owned(listener)) count++;
            return count;
        }

        void dispatch(Event event) {
            for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
                if (!owned(listener)) continue;
                try {
                    listener.callEvent(event);
                } catch (org.bukkit.event.EventException error) {
                    throw new IllegalStateException("Input listener failed", error);
                }
            }
        }

        void expect(String label, boolean sync) {
            check(
                    marks.stream()
                                    .filter(value -> value.equals(label + "|" + sync + "|" + sync))
                                    .count()
                            == 1,
                    label
                            + " resumes exactly once on its original "
                            + (sync ? "main" : "worker")
                            + " thread kind");
        }

        void check(boolean success, String name) {
            attempted++;
            if (!success) throw new AssertionError(name);
            verified.add(name);
        }

        <T> void after(CompletionStage<T> future, Consumer<T> continuation) {
            future.whenComplete(
                    (value, error) ->
                            later(
                                    0,
                                    () -> {
                                        if (error != null)
                                            throw new IllegalStateException(
                                                    "Input probe continuation failed", error);
                                        continuation.accept(value);
                                    }));
        }

        void async(Runnable body) {
            controls.add(Bukkit.getScheduler().runTaskAsynchronously(plugin, body));
        }

        void later(long ticks, Runnable body) {
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
                report.completeExceptionally(error);
            }
        }

        void finish(Throwable failure) {
            if (!finished.compareAndSet(false, true)) return;
            for (BukkitTask task : controls) task.cancel();
            for (var value : fixtures) {
                try {
                    value.close();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure == null && listeners() != baselineListeners)
                failure = new AssertionError("Input probe leaked listeners");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("passed", failure == null);
            value.put("checks", attempted);
            value.put("passedChecks", verified.size());
            value.put("verified", List.copyOf(verified));
            value.put("continuations", List.copyOf(marks));
            value.put("server", plugin.getServer().getMinecraftVersion());
            value.put(
                    "fixtures",
                    "synthetic players and async chat/quit/join events passed to registered input listeners only; real Paper scheduler; no real client chat");
            value.put(
                    "referenceContract",
                    "NI BaseActionManager/ChatCatcher/AsyncPlayerChatListener at ca93bc4; quit/close stop revision-owned waits");
            if (failure != null) value.put("failure", failure.toString());
            report.complete(value);
        }
    }
}

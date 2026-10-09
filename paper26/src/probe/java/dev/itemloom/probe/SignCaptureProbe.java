package dev.itemloom.probe;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.paper.action.PaperActions;
import dev.itemloom.paper.action.PlayerActionState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Real IL queues and embedded Keystone sign events, with synthetic packet-facing players. */
@SuppressWarnings("deprecation")
final class SignCaptureProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Sign probe starts on the server thread");
        Runner runner = new Runner(plugin);
        runner.start();
        return runner.report;
    }

    private static String mark(String label) {
        return "js: marks.add('"
                + label
                + "|' + Java.type('org.bukkit.Bukkit').isPrimaryThread() + '|' + context.isSync()); true;";
    }

    private static final class Runner {
        final JavaPlugin plugin;
        final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        final List<Fixture> fixtures = new ArrayList<>();
        final List<String> checks = new ArrayList<>();
        final Queue<String> marks = new ConcurrentLinkedQueue<>();
        final Queue<BukkitTask> controls = new ConcurrentLinkedQueue<>();
        final AtomicBoolean finished = new AtomicBoolean();
        final int baselineListeners;
        Fixture fixture;

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
            baselineListeners = listeners();
            fixture = create(null);
        }

        Fixture create(SignPlayer player) {
            Fixture value = new Fixture(plugin, player == null ? new SignPlayer(plugin) : player);
            fixtures.add(value);
            return value;
        }

        NiActionContext context() {
            return fixture.context(fixture.player.value, Map.of("marks", marks));
        }

        void start() {
            later(240, () -> finish(new AssertionError("Sign capture probe timed out")));
            try {
                noUser();
                fifo();
                clearAndReopen();
                worker();
            } catch (Throwable error) {
                finish(error);
            }
        }

        void noUser() {
            check(
                    !fixture.ready(fixture.context(null, Map.of()), "catch-sign: none").stopped(),
                    "catch-sign without a player succeeds immediately");
            SignPlayer orphan = new SignPlayer(plugin);
            check(
                    !fixture.ready(fixture.context(orphan.value, Map.of()), "catchSign: none")
                            .stopped(),
                    "catchSign without registered player state succeeds immediately");
            fixture.player.online = false;
            check(
                    !fixture.ready(context(), "catch-sign: none").stopped()
                            && fixture.player.opens.isEmpty(),
                    "offline retained player state cannot open or wait for a sign");
            fixture.player.online = true;
        }

        void fifo() {
            NiActionContext firstContext = context(), secondContext = context();
            var first =
                    fixture.run(
                                    firstContext,
                                    List.of(
                                            "NeigeItems.catch-sign: entire key false",
                                            mark("fifo-main")))
                            .toCompletableFuture();
            var second = fixture.run(secondContext, "catchSign: second").toCompletableFuture();
            check(
                    !first.isDone() && !second.isDone() && fixture.player.opens.size() == 2,
                    "every catch opens a UI immediately while both FIFO waits remain pending");
            BlockPosition position = fixture.player.lastPosition();
            check(
                    !submit(
                                    fixture.player,
                                    Position.block(
                                            position.blockX() + 1,
                                            position.blockY(),
                                            position.blockZ()),
                                    Side.FRONT,
                                    "wrong")
                            && !submit(fixture.player, position, Side.BACK, "wrong")
                            && !first.isDone()
                            && !second.isDone(),
                    "wrong sign position and side do not consume either queued capture (safety correction)");
            check(
                    submit(fixture.player, position, Side.FRONT, "one", "two", "three", "four")
                            && first.isDone()
                            && !first.join().stopped()
                            && !second.isDone(),
                    "a matching sign submission completes exactly the oldest capture inside that event");
            String key = "entire key false";
            check(
                    firstContext.getGlobal().get(key) instanceof String[] lines
                            && Arrays.equals(lines, new String[] {"one", "two", "three", "four"})
                            && "one".equals(firstContext.getGlobal().get(key + ".0"))
                            && "four".equals(firstContext.getGlobal().get(key + ".3")),
                    "the complete key receives String[4] and indexed keys without splitting its spaces");
            expect("fifo-main", true);
            check(
                    !waiting(fixture.player.value) && fixture.player.opens.size() == 2,
                    "submitting a FIFO entry does not automatically open the next entry's UI");
            check(
                    !submit(fixture.player, position, Side.FRONT, "duplicate") && !second.isDone(),
                    "duplicate packets without a new prompt cannot consume another FIFO entry (safety correction)");
            NiActionContext thirdContext = context();
            var third = fixture.run(thirdContext, "catch-sign").toCompletableFuture();
            submit(fixture.player, fixture.player.lastPosition(), Side.FRONT, "oldest");
            check(
                    second.isDone()
                            && !second.join().stopped()
                            && !third.isDone()
                            && "oldest".equals(secondContext.getGlobal().get("second.0")),
                    "a later catch opens a prompt whose answer still goes to the oldest surviving FIFO entry");
            var opener = fixture.run(context(), "catch-sign: opener").toCompletableFuture();
            submit(fixture.player, fixture.player.lastPosition(), Side.FRONT, "bare");
            check(
                    third.isDone()
                            && !third.join().stopped()
                            && thirdContext.getGlobal().get("") instanceof String[]
                            && "bare".equals(thirdContext.getGlobal().get(".0"))
                            && "".equals(thirdContext.getGlobal().get(".3")),
                    "bare catch-sign uses the empty key and shared input pads the submitted lines to four");
            fixture.ready(context(), "clear-catch-sign");
            check(
                    opener.isDone() && !opener.join().stopped(),
                    "clear releases the final unprompted FIFO entry successfully");
        }

        void clearAndReopen() {
            NiActionContext preserved = context();
            preserved.getGlobal().put("keep", "prior-value");
            var clearing = fixture.run(preserved, "catch-sign: keep").toCompletableFuture();
            int updates = fixture.player.blockUpdates;
            fixture.ready(context(), "clear-catch-sign");
            check(
                    clearing.isDone()
                            && !clearing.join().stopped()
                            && "prior-value".equals(preserved.getGlobal().get("keep"))
                            && !preserved.getGlobal().containsKey("keep.0")
                            && waiting(fixture.player.value)
                            && fixture.player.blockUpdates == updates,
                    "clear resumes successfully without null writes, closing UI, or restoring its fake block");
            check(
                    submit(fixture.player, fixture.player.lastPosition(), Side.FRONT, "unused")
                            && "prior-value".equals(preserved.getGlobal().get("keep")),
                    "an answer to a cleared UI is consumed by the shared prompt without mutating cleared context");

            NiActionContext reentrant = context(), queuedContext = context();
            var first =
                    fixture.run(
                                    reentrant,
                                    List.of(
                                            "catch-sign: first",
                                            "clear-catch-sign",
                                            "catch-sign: replacement",
                                            mark("reopened")))
                            .toCompletableFuture();
            var queued =
                    fixture.run(queuedContext, "catch-sign: cleared-before-next-tick")
                            .toCompletableFuture();
            int restores = fixture.player.restores;
            int opens = fixture.player.opens.size();
            submit(fixture.player, fixture.player.lastPosition(), Side.FRONT, "first-answer");
            check(
                    "first-answer".equals(reentrant.getGlobal().get("first.0"))
                            && queued.isDone()
                            && !queued.join().stopped()
                            && !queuedContext.getGlobal().containsKey("cleared-before-next-tick")
                            && !first.isDone()
                            && fixture.player.opens.size() == opens + 1
                            && waiting(fixture.player.value),
                    "immediate submission can clear queued waits and create a fresh catch before any next-tick callback");
            check(
                    fixture.player.restores == restores,
                    "the old prompt's finally cannot restore over a reentrant same-position prompt");
            submit(fixture.player, fixture.player.lastPosition(), Side.FRONT, "replacement-answer");
            check(
                    first.isDone()
                            && !first.join().stopped()
                            && "replacement-answer"
                                    .equals(reentrant.getGlobal().get("replacement.0")),
                    "fresh prompt receives only the new submitted text after reentrant clear and catch");
            expect("reopened", true);
        }

        void worker() {
            NiActionContext context = context();
            int opens = fixture.player.opens.size();
            CompletableFuture<CompletionStage<Result>> armed = new CompletableFuture<>();
            async(
                    () -> {
                        try {
                            context.setSync(false);
                            armed.complete(
                                    fixture.run(
                                            context,
                                            List.of("catch-sign: worker", mark("worker"))));
                        } catch (Throwable error) {
                            armed.completeExceptionally(error);
                        }
                    });
            after(
                    armed,
                    pending ->
                            until(
                                    () -> fixture.player.opens.size() == opens + 1,
                                    40,
                                    () -> {
                                        check(
                                                !pending.toCompletableFuture().isDone()
                                                        && fixture.player.uiThreads.stream()
                                                                .allMatch(Boolean::booleanValue),
                                                "worker capture opens its UI on the main thread without blocking its worker");
                                        submit(
                                                fixture.player,
                                                fixture.player.lastPosition(),
                                                Side.FRONT,
                                                "worker-answer");
                                        after(
                                                pending,
                                                result -> {
                                                    check(
                                                            !result.stopped()
                                                                    && "worker-answer"
                                                                            .equals(
                                                                                    context.getGlobal()
                                                                                            .get(
                                                                                                    "worker.0")),
                                                            "worker-owned sign capture stores the submitted four-line result");
                                                    expect("worker", false);
                                                    clearWorker();
                                                });
                                    }));
        }

        void clearWorker() {
            NiActionContext context = context();
            context.getGlobal().put("worker-clear", "existing");
            int opens = fixture.player.opens.size();
            CompletableFuture<CompletionStage<Result>> armed = new CompletableFuture<>();
            async(
                    () -> {
                        try {
                            context.setSync(false);
                            armed.complete(
                                    fixture.run(
                                            context,
                                            List.of(
                                                    "catch-sign: worker-clear",
                                                    mark("worker-clear"))));
                        } catch (Throwable error) {
                            armed.completeExceptionally(error);
                        }
                    });
            after(
                    armed,
                    pending ->
                            until(
                                    () -> fixture.player.opens.size() == opens + 1,
                                    40,
                                    () -> {
                                        fixture.ready(context(), "clear-catch-sign");
                                        after(
                                                pending,
                                                result -> {
                                                    check(
                                                            !result.stopped()
                                                                    && "existing"
                                                                            .equals(
                                                                                    context.getGlobal()
                                                                                            .get(
                                                                                                    "worker-clear"))
                                                                    && !context.getGlobal()
                                                                            .containsKey(
                                                                                    "worker-clear.0")
                                                                    && waiting(
                                                                            fixture.player.value),
                                                            "clear resumes a worker-owned capture successfully without changing its data or closing UI");
                                                    expect("worker-clear", false);
                                                    submit(
                                                            fixture.player,
                                                            fixture.player.lastPosition(),
                                                            Side.FRONT,
                                                            "discarded");
                                                    quit();
                                                });
                                    }));
        }

        void quit() {
            fixture.players.configure(false);
            var pending = fixture.run(context(), "catch-sign: quit").toCompletableFuture();
            dispatchInput(new PlayerQuitEvent(fixture.player.value, (Component) null));
            check(
                    pending.isDone() && pending.join().stopped() && !waiting(fixture.player.value),
                    "quit stops its sign waits and cancels the revision's shared prompt");
            check(
                    fixture.ready(context(), "catch-sign: reentrant-quit").stopped(),
                    "quit fences new sign captures even while isOnline and retained state remain true");
            fixture.player.online = false;
            fixture.players.quit(fixture.player.id);
            check(
                    fixture.players.contains(fixture.player.id)
                            && !fixture.ready(context(), "catch-sign: offline").stopped(),
                    "offline catch completes without waiting when metadata survives quit");
            fixture.close();
            fixture = create(null);
            revisionIdentity();
        }

        void revisionIdentity() {
            Fixture old = fixture;
            var previous = old.run(context(), "catch-sign: previous").toCompletableFuture();
            fixture = create(old.player);
            NiActionContext freshContext = context();
            var fresh = fixture.run(freshContext, "catch-sign: fresh").toCompletableFuture();
            int restores = fixture.player.restores;
            old.close();
            check(
                    previous.isDone()
                            && previous.join().stopped()
                            && waiting(fixture.player.value)
                            && fixture.player.restores == restores,
                    "closing an older revision cannot cancel or restore a replacement revision's prompt");
            submit(fixture.player, fixture.player.lastPosition(), Side.FRONT, "fresh-only");
            check(
                    fresh.isDone()
                            && !fresh.join().stopped()
                            && "fresh-only".equals(freshContext.getGlobal().get("fresh.0")),
                    "a replacement revision consumes its own prompt after old revision teardown");
            reentrantClose();
        }

        void reentrantClose() {
            Fixture old = fixture;
            NiActionContext ctx =
                    old.context(
                            old.player.value, Map.of("closeAction", (Runnable) old.actions::close));
            var result =
                    old.run(ctx, List.of("catch-sign: closing", "js: closeAction.run(); true;"))
                            .toCompletableFuture();
            submit(old.player, old.player.lastPosition(), Side.FRONT, "close-now");
            check(
                    result.isDone() && !waiting(old.player.value) && !old.actions.active(),
                    "synchronous sign continuation can close its revision without leaking a prompt or deadlocking");
            fixture = create(null);
            asynchronousClose();
        }

        void asynchronousClose() {
            Fixture old = fixture;
            var pending = old.run(context(), "catch-sign: async-close").toCompletableFuture();
            CompletableFuture<Void> closed = new CompletableFuture<>();
            async(
                    () -> {
                        try {
                            old.actions.close();
                            closed.complete(null);
                        } catch (Throwable error) {
                            closed.completeExceptionally(error);
                        }
                    });
            after(
                    closed,
                    ignored ->
                            until(
                                    () -> !waiting(old.player.value),
                                    40,
                                    () -> {
                                        check(
                                                pending.isDone()
                                                        && pending.join().stopped()
                                                        && !waiting(old.player.value),
                                                "asynchronous close releases its prompt through main-thread cleanup after ActionTasks is closed");
                                        check(
                                                old.player.uiThreads.stream()
                                                        .allMatch(Boolean::booleanValue),
                                                "all opening and restoring packet-facing UI calls remain on the main thread");
                                        finish(null);
                                    }));
        }

        boolean submit(SignPlayer player, BlockPosition position, Side side, String... text) {
            UncheckedSignChangeEvent event =
                    new UncheckedSignChangeEvent(
                            player.value,
                            position,
                            side,
                            Arrays.stream(text)
                                    .map(Component::text)
                                    .map(value -> (Component) value)
                                    .toList());
            for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
                if (!listener.getListener()
                        .getClass()
                        .getName()
                        .equals("dev.itemloom.internal.keystone.ui.SignInput$SignListener"))
                    continue;
                try {
                    listener.callEvent(event);
                } catch (org.bukkit.event.EventException error) {
                    throw new IllegalStateException("Embedded sign listener failed", error);
                }
            }
            return event.isCancelled();
        }

        void dispatchInput(Event event) {
            for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
                if (!owned(listener)) continue;
                try {
                    listener.callEvent(event);
                } catch (org.bukkit.event.EventException error) {
                    throw new IllegalStateException("Sign queue listener failed", error);
                }
            }
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
            for (RegisteredListener listener :
                    AsyncPlayerChatEvent.getHandlerList().getRegisteredListeners())
                if (owned(listener)) count++;
            return count;
        }

        void expect(String label, boolean sync) {
            check(
                    marks.stream()
                                    .filter(value -> value.equals(label + "|" + sync + "|" + sync))
                                    .count()
                            == 1,
                    label
                            + " resumes exactly once on the original "
                            + (sync ? "main" : "worker")
                            + " thread kind");
        }

        void check(boolean success, String name) {
            if (!success) throw new AssertionError(name);
            checks.add(name);
        }

        void until(BooleanSupplier predicate, int remaining, Runnable next) {
            if (predicate.getAsBoolean()) {
                next.run();
                return;
            }
            if (remaining <= 0)
                throw new AssertionError("Sign probe asynchronous transition timed out");
            later(1, () -> until(predicate, remaining - 1, next));
        }

        <T> void after(CompletionStage<T> stage, Consumer<T> next) {
            stage.whenComplete(
                    (value, error) ->
                            later(
                                    0,
                                    () -> {
                                        if (error != null)
                                            throw new IllegalStateException(
                                                    "Sign probe continuation failed", error);
                                        next.accept(value);
                                    }));
        }

        void async(Runnable body) {
            controls.add(Bukkit.getScheduler().runTaskAsynchronously(plugin, body));
        }

        void later(long ticks, Runnable body) {
            if (finished.get()) return;
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
        }

        void finish(Throwable failure) {
            if (!finished.compareAndSet(false, true)) return;
            for (BukkitTask task : controls) task.cancel();
            for (Fixture value : fixtures) {
                try {
                    value.close();
                    if (waiting(value.player.value))
                        throw new AssertionError("Sign probe leaked a shared prompt");
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure == null && listeners() != baselineListeners)
                failure = new AssertionError("Sign probe leaked an input listener");
            if (failure != null) report.completeExceptionally(failure);
            else
                report.complete(
                        Map.of(
                                "passed",
                                true,
                                "checks",
                                checks.size(),
                                "assertions",
                                List.copyOf(checks),
                                "continuations",
                                List.copyOf(marks),
                                "syntheticPlayers",
                                true,
                                "realClient",
                                false,
                                "scope",
                                "actual embedded Keystone virtual-sign events and IL FIFO actions; no real client packet capture",
                                "safetyCorrections",
                                "position/side filter and one-shot unanswered prompt; packets carry no prompt generation token"));
        }
    }

    private static boolean waiting(Player player) {
        try {
            Class<?> input =
                    Class.forName(
                            "dev.itemloom.internal.keystone.ui.SignInput",
                            true,
                            PaperActions.class.getClassLoader());
            return (boolean) input.getMethod("isWaiting", Player.class).invoke(null, player);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot inspect embedded Keystone prompt", error);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final NiScripts scripts = new NiScripts(Map.of(), Map.of());
        final PlayerActionState players = new PlayerActionState();
        final PaperActions actions;
        final SignPlayer player;

        Fixture(JavaPlugin plugin, SignPlayer player) {
            this.player = player;
            players.join(player.id);
            actions = new PaperActions(plugin, scripts, players, 500);
        }

        NiActionContext context(Object caster, Map<String, Object> params) {
            return new NiActionContext(
                    new NiEvaluation(
                            new GenerationContext(Map.of(), new Random(1)),
                            null,
                            caster,
                            NiEvaluation.Mode.ACTION,
                            new NiNodes(),
                            scripts,
                            null),
                    caster,
                    params,
                    actions::active);
        }

        CompletionStage<Result> run(NiActionContext context, Object source) {
            return actions.run(actions.compiler().compile(source), context);
        }

        Result ready(NiActionContext context, Object source) {
            var result = run(context, source).toCompletableFuture();
            if (!result.isDone())
                throw new AssertionError("Expected immediate sign action completion: " + source);
            return result.join();
        }

        @Override
        public void close() {
            actions.close();
            scripts.close();
            players.close();
        }
    }

    private static final class SignPlayer {
        final UUID id = UUID.randomUUID();
        final Player value;
        final List<BlockPosition> opens = new ArrayList<>();
        final Queue<Boolean> uiThreads = new ConcurrentLinkedQueue<>();
        final Location location;
        boolean online = true;
        int restores, blockUpdates;

        SignPlayer(JavaPlugin plugin) {
            location = new Location(plugin.getServer().getWorlds().getFirst(), 7, 78, 11);
            value =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class<?>[] {Player.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getUniqueId" -> id;
                                                case "getName", "getDisplayName", "toString" ->
                                                        "SignCaptureProbe";
                                                case "getPlayer" -> proxy;
                                                case "getServer" -> plugin.getServer();
                                                case "isOnline", "isValid" -> online;
                                                case "getWorld" -> location.getWorld();
                                                case "getLocation" -> location.clone();
                                                case "sendBlockChange" -> {
                                                    uiThreads.add(Bukkit.isPrimaryThread());
                                                    blockUpdates++;
                                                    if (((BlockData) args[1]).getMaterial()
                                                            != Material.OAK_WALL_SIGN) restores++;
                                                    yield null;
                                                }
                                                case "sendBlockUpdate" -> {
                                                    uiThreads.add(Bukkit.isPrimaryThread());
                                                    yield null;
                                                }
                                                case "openVirtualSign" -> {
                                                    uiThreads.add(Bukkit.isPrimaryThread());
                                                    if (args[1] != Side.FRONT)
                                                        throw new AssertionError(
                                                                "Unexpected virtual sign side");
                                                    opens.add((BlockPosition) args[0]);
                                                    yield null;
                                                }
                                                case "hashCode" -> id.hashCode();
                                                case "equals" -> proxy == args[0];
                                                default ->
                                                        throw new UnsupportedOperationException(
                                                                "Unexpected sign player operation: "
                                                                        + method);
                                            });
        }

        BlockPosition lastPosition() {
            return opens.getLast();
        }
    }
}

package dev.itemloom.paper.action;

import dev.keystone.ui.SignInput;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ActionFlow.Result;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Revision-owned NI chat/sign catcher queues. Keystone owns the virtual sign protocol.
 * Only the queue is locked; completing futures can execute scripts and always happens outside it.
 */
@SuppressWarnings("deprecation")
public final class ActionInputCapture implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ActionTasks tasks;
    private final PlayerActionState players;
    private final Map<UUID, Captures> pending = new HashMap<>();
    // Uses the same monitor as chat so quit/close fence both inputs atomically.
    private final Map<UUID, SignCaptures> signs = new HashMap<>();
    private final Set<UUID> quitting = new HashSet<>();
    private volatile boolean closed;

    ActionInputCapture(JavaPlugin plugin, ActionTasks tasks, PlayerActionState players) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.players = players;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    CompletionStage<Result> capture(String content, NiActionContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isOnline() || !players.contains(player.getUniqueId()))
            return completed(Result.CONTINUE);
        List<String> args = NiTemplate.split(content, ' ', 0);
        String key = args.isEmpty() ? "catchChat" : args.getFirst();
        boolean cancel = args.size() < 2 || !args.get(1).equals("false");
        Capture capture = new Capture(player.getUniqueId(), context, key, cancel);
        synchronized (pending) {
            if (closed || !tasks.active() || !context.active() || quitting.contains(capture.player))
                return completed(Result.STOP);
            if (!player.isOnline() || !players.contains(capture.player))
                return completed(Result.CONTINUE);
            Captures captures = pending.computeIfAbsent(capture.player, ignored -> new Captures());
            captures.queue.addLast(capture);
            captures.all.add(capture);
        }
        capture.result.whenComplete(
                (value, error) -> {
                    synchronized (pending) {
                        Captures captures = pending.get(capture.player);
                        if (captures == null) return;
                        captures.queue.remove(capture);
                        captures.all.remove(capture);
                        if (captures.all.isEmpty()) pending.remove(capture.player);
                    }
                });
        return capture.result;
    }

    CompletionStage<Result> captureSign(String content, NiActionContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isOnline() || !players.contains(player.getUniqueId()))
            return completed(Result.CONTINUE);
        // NI uses the complete, already parsed content verbatim, including spaces or "".
        Capture capture = new Capture(player.getUniqueId(), context, content, false);
        SignCaptures captures;
        synchronized (pending) {
            if (closed || !tasks.active() || !context.active() || quitting.contains(capture.player))
                return completed(Result.STOP);
            if (!player.isOnline() || !players.contains(capture.player))
                return completed(Result.CONTINUE);
            captures = signs.computeIfAbsent(capture.player, ignored -> new SignCaptures());
            captures.queue.addLast(capture);
            captures.all.add(capture);
        }
        capture.result.whenComplete(
                (value, error) -> {
                    synchronized (pending) {
                        captures.queue.remove(capture);
                        captures.all.remove(capture);
                        discardFinishedSigns(capture.player, captures);
                    }
                });
        // Every catch opens immediately on the main thread, even when another FIFO entry
        // is already waiting. A submission still completes just the oldest entry.
        tasks.schedule(
                        0,
                        false,
                        true,
                        () -> {
                            synchronized (pending) {
                                if (closed
                                        || !context.active()
                                        || quitting.contains(capture.player))
                                    return completed(Result.STOP);
                                if (signs.get(capture.player) != captures
                                        || !captures.queue.contains(capture))
                                    return completed(Result.CONTINUE);
                            }
                            SignInput.Prompt prompt =
                                    SignInput.openPrompt(
                                            player,
                                            new String[0],
                                            lines -> submitted(capture.player, captures, lines));
                            boolean cancel;
                            synchronized (pending) {
                                cancel = closed || signs.get(capture.player) != captures;
                                if (!cancel) {
                                    captures.prompt = prompt;
                                    discardFinishedSigns(capture.player, captures);
                                }
                            }
                            if (cancel) prompt.cancel();
                            return completed(Result.CONTINUE);
                        })
                .whenComplete(
                        (value, error) -> {
                            if (error != null) capture.result.completeExceptionally(error);
                            else if (value.stopped()) capture.result.complete(Result.STOP);
                        });
        return capture.result;
    }

    void clearSign(Player player) {
        if (player == null) return;
        List<Capture> cleared;
        synchronized (pending) {
            SignCaptures captures = signs.get(player.getUniqueId());
            if (captures == null) return;
            cleared = List.copyOf(captures.queue);
            captures.queue.clear();
        }
        // Legacy clear resumes successfully and deliberately leaves the current UI open.
        for (Capture capture : cleared) resume(capture, null);
    }

    private void submitted(UUID player, SignCaptures captures, String[] lines) {
        Capture capture;
        synchronized (pending) {
            if (closed || signs.get(player) != captures) return;
            capture = captures.queue.pollFirst();
            discardFinishedSigns(player, captures);
        }
        if (capture != null) resume(capture, lines.clone());
    }

    /** Call with pending held; a cleared queue can still own an unanswered UI. */
    private void discardFinishedSigns(UUID player, SignCaptures captures) {
        if (captures.all.isEmpty() && (captures.prompt == null || !captures.prompt.isWaiting()))
            signs.remove(player, captures);
    }

    void clear(Player player) {
        if (player == null) return;
        List<Capture> cleared;
        synchronized (pending) {
            Captures captures = pending.get(player.getUniqueId());
            if (captures == null) return;
            cleared = List.copyOf(captures.queue);
            captures.queue.clear();
        }
        for (Capture capture : cleared) resume(capture, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent event) {
        Capture capture;
        synchronized (pending) {
            Captures captures = pending.get(event.getPlayer().getUniqueId());
            capture = captures == null ? null : captures.queue.pollFirst();
        }
        if (capture == null) return;
        event.setCancelled(capture.cancel);
        resume(capture, event.getMessage());
    }

    private void resume(Capture capture, Object message) {
        tasks.schedule(
                        0,
                        !capture.context.isSync(),
                        true,
                        () -> {
                            if (closed || capture.result.isDone() || !capture.context.active())
                                return completed(Result.STOP);
                            if (message != null) {
                                capture.context.getGlobal().put(capture.key, message);
                                if (message instanceof String[] lines)
                                    for (int line = 0; line < 4; line++)
                                        capture.context
                                                .getGlobal()
                                                .put(capture.key + "." + line, lines[line]);
                            }
                            return completed(Result.CONTINUE);
                        })
                .whenComplete(
                        (value, error) -> {
                            if (error != null) capture.result.completeExceptionally(error);
                            else capture.result.complete(value);
                        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        List<Capture> stopped;
        SignInput.Prompt prompt;
        synchronized (pending) {
            quitting.add(event.getPlayer().getUniqueId());
            Captures captures = pending.remove(event.getPlayer().getUniqueId());
            stopped = captures == null ? new ArrayList<>() : new ArrayList<>(captures.all);
            SignCaptures sign = signs.remove(event.getPlayer().getUniqueId());
            if (sign != null) stopped.addAll(sign.all);
            prompt = sign == null ? null : sign.prompt;
        }
        if (prompt != null) cancelPrompts(List.of(prompt));
        for (Capture capture : stopped) capture.result.complete(Result.STOP);
        // CraftPlayer can still report online inside PlayerQuitEvent. Keep a short fence
        // until the event returns; registration also rechecks online state under the lock.
        tasks.schedule(
                1,
                false,
                false,
                () -> {
                    synchronized (pending) {
                        quitting.remove(event.getPlayer().getUniqueId());
                    }
                    return completed(Result.CONTINUE);
                });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        synchronized (pending) {
            quitting.remove(event.getPlayer().getUniqueId());
        }
    }

    @Override
    public void close() {
        List<Capture> stopped = new ArrayList<>();
        List<SignInput.Prompt> prompts = new ArrayList<>();
        synchronized (pending) {
            if (closed) return;
            closed = true;
            for (Captures captures : pending.values()) stopped.addAll(captures.all);
            for (SignCaptures captures : signs.values()) {
                stopped.addAll(captures.all);
                if (captures.prompt != null) prompts.add(captures.prompt);
            }
            pending.clear();
            signs.clear();
            quitting.clear();
        }
        HandlerList.unregisterAll(this);
        for (Capture capture : stopped) capture.result.complete(Result.STOP);
        cancelPrompts(prompts);
    }

    private void cancelPrompts(List<SignInput.Prompt> prompts) {
        if (prompts.isEmpty()) return;
        Runnable cancel =
                () -> {
                    for (SignInput.Prompt prompt : prompts) {
                        try {
                            prompt.cancel();
                        } catch (RuntimeException error) {
                            plugin.getLogger()
                                    .log(
                                            java.util.logging.Level.WARNING,
                                            "Cannot release closed sign input prompt",
                                            error);
                        }
                    }
                };
        if (Bukkit.isPrimaryThread()) cancel.run();
        // PaperActions closes ActionTasks first. This cleanup must survive that revision's
        // task cancellation, and the prompt token can never cancel a replacement's UI.
        else if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, cancel);
            } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) {
                if (plugin.isEnabled()) throw disabled;
            }
        }
        // During plugin disable Keystone's own listener releases all remaining prompts.
    }

    private static CompletionStage<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }

    private static class Captures {
        final ArrayDeque<Capture> queue = new ArrayDeque<>();
        // Includes a chat already polled whose continuation has not reached its owner thread yet.
        final Set<Capture> all = new HashSet<>();
    }

    private static final class SignCaptures extends Captures {
        SignInput.Prompt prompt;
    }

    private static final class Capture {
        final UUID player;
        final NiActionContext context;
        final String key;
        final boolean cancel;
        final CompletableFuture<Result> result = new CompletableFuture<>();

        Capture(UUID player, NiActionContext context, String key, boolean cancel) {
            this.player = player;
            this.context = context;
            this.key = key;
            this.cancel = cancel;
        }
    }
}

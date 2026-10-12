package dev.itemloom.paper.action;

import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ActionFlow.Result;
import dev.keystone.ui.SignInput;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class ActionInputCapture implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ActionTasks tasks;
    private final PlayerActionState players;
    private final Object monitor = new Object();
    private final Map<UUID, Mailbox> chat = new HashMap<>();
    private final Map<UUID, Mailbox> signs = new HashMap<>();
    private final Set<Waiter> unsettled = new LinkedHashSet<>();
    private final Set<UUID> quitting = new HashSet<>();
    private boolean closed;

    ActionInputCapture(JavaPlugin plugin, ActionTasks tasks, PlayerActionState players) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.players = players;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    CompletionStage<Result> capture(String content, NiActionContext context) {
        List<String> words = NiTemplate.split(content, ' ', 0);
        String key = words.isEmpty() ? "catchChat" : words.getFirst();
        boolean cancel = words.size() < 2 || !words.get(1).equals("false");
        return enroll(context, key, cancel, false);
    }

    CompletionStage<Result> captureSign(String key, NiActionContext context) {
        return enroll(context, key, true, true);
    }

    private CompletionStage<Result> enroll(
            NiActionContext context, String key, boolean cancel, boolean sign) {
        Player player = context.getPlayer();
        if (!eligible(player)) return CompletableFuture.completedFuture(Result.CONTINUE);
        Waiter request;
        Mailbox mailbox;
        synchronized (monitor) {
            if (closed
                    || !tasks.active()
                    || !context.active()
                    || quitting.contains(player.getUniqueId())) {
                return CompletableFuture.completedFuture(Result.STOP);
            }
            if (!eligible(player)) return CompletableFuture.completedFuture(Result.CONTINUE);
            request = new Waiter(player.getUniqueId(), context, key, cancel);
            mailbox =
                    (sign ? signs : chat).computeIfAbsent(request.player, ignored -> new Mailbox());
            mailbox.queue.addLast(request);
            unsettled.add(request);
        }
        if (sign) {
            tasks.schedule(
                            0,
                            false,
                            true,
                            () -> {
                                open(player, mailbox, request);
                                return CompletableFuture.completedFuture(Result.CONTINUE);
                            })
                    .whenComplete(
                            (ignored, failure) -> {
                                if (failure != null || ignored != null && ignored.stopped())
                                    settle(request, Result.STOP);
                            });
        }
        return request.future;
    }

    private boolean eligible(Player player) {
        return player != null && player.isOnline() && players.contains(player.getUniqueId());
    }

    private void open(Player player, Mailbox mailbox, Waiter request) {
        boolean allowed;
        synchronized (monitor) {
            allowed =
                    !closed
                            && !request.future.isDone()
                            && signs.get(request.player) == mailbox
                            && eligible(player);
        }
        if (!allowed) {
            settle(request, Result.STOP);
            return;
        }
        SignInput.Prompt prompt =
                SignInput.openPrompt(
                        player,
                        new String[] {"", "", "", ""},
                        lines -> {
                            Waiter target;
                            synchronized (monitor) {
                                target = closed ? null : mailbox.queue.pollFirst();
                            }
                            if (target != null) resume(target, lines.clone());
                        });
        boolean obsolete;
        synchronized (monitor) {
            obsolete = closed || signs.get(request.player) != mailbox;
            if (!obsolete) mailbox.prompt = prompt;
        }
        if (obsolete) cancel(prompt);
    }

    void clear(Player player) {
        drain(player, chat);
    }

    void clearSign(Player player) {
        drain(player, signs);
    }

    private void drain(Player player, Map<UUID, Mailbox> source) {
        if (player == null) return;
        List<Waiter> batch;
        synchronized (monitor) {
            Mailbox mailbox = source.get(player.getUniqueId());
            if (mailbox == null) return;
            batch = new ArrayList<>(mailbox.queue);
            mailbox.queue.clear();
        }
        for (Waiter request : batch) resume(request, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent event) {
        Waiter request;
        synchronized (monitor) {
            Mailbox mailbox = closed ? null : chat.get(event.getPlayer().getUniqueId());
            request = mailbox == null ? null : mailbox.queue.pollFirst();
        }
        if (request == null) return;
        event.setCancelled(request.cancel);
        resume(request, event.getMessage());
    }

    private void resume(Waiter request, Object answer) {
        tasks.schedule(
                        0,
                        !request.context.isSync(),
                        true,
                        () -> {
                            boolean allowed;
                            synchronized (monitor) {
                                allowed =
                                        !closed
                                                && unsettled.contains(request)
                                                && !request.future.isDone()
                                                && !quitting.contains(request.player)
                                                && request.context.active();
                            }
                            if (!allowed) settle(request, Result.STOP);
                            else {
                                if (answer != null) {
                                    request.context.getGlobal().put(request.key, answer);
                                    if (answer instanceof String[] lines) {
                                        for (int index = 0; index < lines.length; index++) {
                                            request.context
                                                    .getGlobal()
                                                    .put(request.key + "." + index, lines[index]);
                                        }
                                    }
                                }
                                settle(request, Result.CONTINUE);
                            }
                            return CompletableFuture.completedFuture(Result.CONTINUE);
                        })
                .whenComplete(
                        (result, failure) -> {
                            if (failure != null || result != null && result.stopped())
                                settle(request, Result.STOP);
                        });
    }

    private void settle(Waiter request, Result result) {
        synchronized (monitor) {
            unsettled.remove(request);
            Mailbox first = chat.get(request.player);
            Mailbox second = signs.get(request.player);
            if (first != null) first.queue.remove(request);
            if (second != null) second.queue.remove(request);
        }
        request.future.complete(result);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        List<Waiter> batch;
        SignInput.Prompt prompt;
        synchronized (monitor) {
            quitting.add(id);
            chat.remove(id);
            Mailbox sign = signs.remove(id);
            prompt = sign == null ? null : sign.prompt;
            batch = unsettled.stream().filter(waiter -> waiter.player.equals(id)).toList();
            unsettled.removeAll(batch);
        }
        if (prompt != null) cancel(prompt);
        for (Waiter waiter : batch) waiter.future.complete(Result.STOP);
        tasks.schedule(
                1,
                false,
                false,
                () -> {
                    synchronized (monitor) {
                        quitting.remove(id);
                    }
                    return CompletableFuture.completedFuture(Result.CONTINUE);
                });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        synchronized (monitor) {
            quitting.remove(event.getPlayer().getUniqueId());
        }
    }

    @Override
    public void close() {
        List<Waiter> batch;
        List<SignInput.Prompt> prompts;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            batch = new ArrayList<>(unsettled);
            prompts =
                    signs.values().stream()
                            .map(box -> box.prompt)
                            .filter(java.util.Objects::nonNull)
                            .toList();
            unsettled.clear();
            chat.clear();
            signs.clear();
            quitting.clear();
        }
        HandlerList.unregisterAll(this);
        for (Waiter waiter : batch) waiter.future.complete(Result.STOP);
        for (SignInput.Prompt prompt : prompts) cancel(prompt);
    }

    private void cancel(SignInput.Prompt prompt) {
        Runnable release =
                () -> {
                    try {
                        prompt.cancel();
                    } catch (IllegalPluginAccessException failure) {
                        if (plugin.isEnabled())
                            plugin.getLogger()
                                    .log(Level.WARNING, "Could not cancel sign prompt", failure);
                    } catch (RuntimeException failure) {
                        plugin.getLogger()
                                .log(Level.WARNING, "Could not cancel sign prompt", failure);
                    }
                };
        if (Bukkit.isPrimaryThread()) release.run();
        else if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, release);
            } catch (IllegalPluginAccessException failure) {
                if (plugin.isEnabled()) throw failure;
            }
        }
    }

    private static final class Mailbox {
        final ArrayDeque<Waiter> queue = new ArrayDeque<>();
        SignInput.Prompt prompt;
    }

    private static final class Waiter {
        final UUID player;
        final NiActionContext context;
        final String key;
        final boolean cancel;
        final CompletableFuture<Result> future = new CompletableFuture<>();

        Waiter(UUID player, NiActionContext context, String key, boolean cancel) {
            this.player = player;
            this.context = context;
            this.key = key;
            this.cancel = cancel;
        }
    }
}

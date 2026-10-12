package dev.itemloom.paper.integration;

import dev.itemloom.paper.ItemsService;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread resolver with bounded, nonblocking snapshots for asynchronous callers. */
public final class NiPapiExpansion extends PlaceholderExpansion implements Listener, AutoCloseable {
    private static final long REFRESH_NANOS = 50_000_000L;
    private static final long WARNING_NANOS = 30_000_000_000L;

    private record Request(UUID viewer, String parameters) {}

    private static final class Cached {
        final WeakReference<OfflinePlayer> viewer;
        final Object revision;
        String value = "";
        long computedAt;
        boolean pending = true;

        Cached(OfflinePlayer viewer, Object revision) {
            this.viewer = new WeakReference<>(viewer);
            this.revision = revision;
        }
    }

    private final JavaPlugin plugin;
    private final Supplier<Object> revision;
    private final BiFunction<OfflinePlayer, String, String> resolver;
    private final LinkedHashMap<Request, Cached> results = new LinkedHashMap<>(16, 0.75f, true);
    private Object currentRevision;
    private BukkitTask task;
    private boolean scheduled;
    private boolean closed;
    private long lastWarning;

    public NiPapiExpansion(
            JavaPlugin plugin,
            Supplier<Object> revision,
            BiFunction<OfflinePlayer, String, String> resolver) {
        this.plugin = plugin;
        this.revision = revision;
        this.resolver = resolver;
    }

    public static Runnable install(JavaPlugin plugin, ItemsService items) {
        ItemsService.requireThread();
        var expansion =
                new NiPapiExpansion(plugin, items::placeholderRevision, items::requestPlaceholder);
        if (!expansion.registerIfAvailable()) {
            plugin.getLogger().warning("无法注册 ni 占位符，保留现有提供者。");
            expansion.close();
        }
        return expansion::close;
    }

    public synchronized boolean registerIfAvailable() {
        ItemsService.requireThread();
        if (closed || Bukkit.getPluginManager().getPlugin("NeigeItems") != null || owner() != null)
            return false;
        boolean registered = register();
        if (!registered || owner() != this) return false;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        return true;
    }

    private static PlaceholderExpansion owner() {
        return PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion("ni");
    }

    @Override
    public String getIdentifier() {
        return "ni";
    }

    @Override
    public String getAuthor() {
        return "ItemLoom";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer viewer, String parameters) {
        synchronized (this) {
            if (closed) return "";
        }
        if (Bukkit.isPrimaryThread()) return resolve(viewer, parameters);
        Object token = revision.get();
        synchronized (this) {
            if (closed) return "";
            synchronizeRevision(token);
            if (token == null) return "";
            Request key = new Request(viewer == null ? null : viewer.getUniqueId(), parameters);
            Cached value = results.get(key);
            if (value == null || value.viewer.get() != viewer) {
                value = new Cached(viewer, token);
                results.put(key, value);
                while (results.size() > 2048) results.pollFirstEntry();
            } else if (System.nanoTime() - value.computedAt >= REFRESH_NANOS) {
                value.pending = true;
            }
            String previous = value.value;
            if (value.pending) schedule();
            return previous;
        }
    }

    private void synchronizeRevision(Object token) {
        if (currentRevision == token) return;
        results.clear();
        currentRevision = token;
    }

    private void schedule() {
        if (scheduled || closed) return;
        scheduled = true;
        try {
            task = Bukkit.getScheduler().runTaskLater(plugin, this::drain, 1L);
        } catch (RuntimeException failure) {
            scheduled = false;
            task = null;
            if (!closed && plugin.isEnabled()) throw failure;
        }
    }

    private void drain() {
        var work = new ArrayList<Map.Entry<Request, Cached>>();
        synchronized (this) {
            task = null;
            if (closed) {
                scheduled = false;
                return;
            }
            synchronizeRevision(revision.get());
            for (var entry : results.entrySet()) {
                if (entry.getValue().pending) work.add(Map.entry(entry.getKey(), entry.getValue()));
                if (work.size() == 64) break;
            }
        }
        for (var entry : work) {
            Request key = entry.getKey();
            Cached value = entry.getValue();
            OfflinePlayer viewer = value.viewer.get();
            synchronized (this) {
                if (!current(key, value)) continue;
                if ((key.viewer != null && viewer == null)
                        || (viewer instanceof Player player && !player.isOnline())) {
                    results.remove(key);
                    continue;
                }
            }
            String resolved = resolve(viewer, key.parameters);
            synchronized (this) {
                if (!current(key, value)) continue;
                value.value = resolved;
                value.computedAt = System.nanoTime();
                value.pending = false;
            }
        }
        synchronized (this) {
            scheduled = false;
            if (!closed && results.values().stream().anyMatch(value -> value.pending)) schedule();
        }
    }

    private boolean current(Request key, Cached value) {
        if (closed) return false;
        Object token = revision.get();
        synchronizeRevision(token);
        // Iteration does not alter the access order used to choose the next batch.
        Cached present = null;
        for (var entry : results.entrySet())
            if (entry.getKey().equals(key)) {
                present = entry.getValue();
                break;
            }
        if (present != value) return false;
        if (value.revision != token || token == null) {
            results.remove(key);
            return false;
        }
        return true;
    }

    private String resolve(OfflinePlayer viewer, String parameters) {
        synchronized (this) {
            if (closed) return "";
        }
        try {
            String value = resolver.apply(viewer, parameters);
            synchronized (this) {
                return closed || value == null ? "" : value;
            }
        } catch (RuntimeException failure) {
            synchronized (this) {
                long now = System.nanoTime();
                if (lastWarning == 0 || now - lastWarning >= WARNING_NANOS) {
                    lastWarning = now;
                    plugin.getLogger().warning("ni 占位符解析失败: " + failure.getMessage());
                }
            }
            return "";
        }
    }

    @EventHandler
    public synchronized void quit(PlayerQuitEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        results.keySet().removeIf(key -> viewer.equals(key.viewer));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel();
        task = null;
        scheduled = false;
        results.clear();
        HandlerList.unregisterAll(this);
        if (owner() == this) unregister();
    }
}

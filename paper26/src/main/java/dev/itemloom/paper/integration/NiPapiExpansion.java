package dev.itemloom.paper.integration;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import dev.itemloom.paper.ItemsService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Optional PAPI boundary. Async callers read prior results and never wait for Bukkit or Nashorn. */
public final class NiPapiExpansion extends PlaceholderExpansion implements Listener, AutoCloseable {
    private static final int MAX_RESULTS = 2048, MAX_PER_TICK = 64;
    private static final long REFRESH_NANOS = 50_000_000L;

    private record Request(UUID viewer, String parameters) {}

    private static final class Result {
        final WeakReference<OfflinePlayer> viewer;
        final Object revision;
        String value = "";
        long computedAt;
        boolean pending = true;

        Result(OfflinePlayer viewer, Object revision) {
            this.viewer = new WeakReference<>(viewer);
            this.revision = revision;
        }
    }

    private final JavaPlugin plugin;
    private final Supplier<Object> revision;
    private final BiFunction<OfflinePlayer, String, String> resolver;
    private final Map<Request, Result> results = new LinkedHashMap<>(16, .75f, true);
    private boolean scheduled;
    private Object cacheRevision;
    private volatile boolean closed;
    private BukkitTask task;
    private long lastWarning;

    public NiPapiExpansion(
            JavaPlugin plugin,
            Supplier<Object> revision,
            BiFunction<OfflinePlayer, String, String> resolver) {
        this.plugin = plugin;
        this.revision = revision;
        this.resolver = resolver;
    }

    /** Register before item YAML conversion so %ni_*% is recognized on the first load. */
    public static Runnable install(JavaPlugin plugin, ItemsService items) {
        ItemsService.requireThread();
        var expansion =
                new NiPapiExpansion(plugin, items::placeholderRevision, items::requestPlaceholder);
        if (!expansion.registerIfAvailable()) {
            plugin.getLogger()
                    .info(
                            "Keeping the existing NI/PAPI namespace owner; ItemLoom ni expansion was not registered");
            expansion.close();
        }
        return expansion::close;
    }

    public boolean registerIfAvailable() {
        ItemsService.requireThread();
        if (closed
                || Bukkit.getPluginManager().getPlugin("NeigeItems") != null
                || registered() != null) return false;
        // PAPI register() replaces an existing identifier; the precheck above is intentional.
        if (!register() || registered() != this) return false;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        return true;
    }

    private static PlaceholderExpansion registered() {
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
        if (closed) return "";
        if (Bukkit.isPrimaryThread()) return resolver.apply(viewer, parameters);
        Object current = revision.get();
        if (current == null) return "";
        Request key = new Request(viewer == null ? null : viewer.getUniqueId(), parameters);
        String value;
        boolean enqueue = false;
        synchronized (results) {
            if (closed) return "";
            if (cacheRevision != current) {
                results.clear();
                cacheRevision = current;
            }
            Result result = results.get(key);
            if (result == null || result.revision != current || result.viewer.get() != viewer) {
                result = new Result(viewer, current);
                if (!results.containsKey(key) && results.size() >= MAX_RESULTS)
                    results.remove(results.keySet().iterator().next());
                results.put(key, result);
            }
            value = result.value;
            if (System.nanoTime() - result.computedAt >= REFRESH_NANOS) result.pending = true;
            if (result.pending && !scheduled) {
                scheduled = true;
                enqueue = true;
            }
        }
        if (enqueue) schedule();
        return value;
    }

    private void schedule() {
        synchronized (results) {
            if (closed) return;
            try {
                // A zero-delay task scheduled while Paper is draining tasks can run in the
                // same heartbeat. An explicit tick is required for the per-tick batch bound.
                task = Bukkit.getScheduler().runTaskLater(plugin, this::drain, 1);
            } catch (RuntimeException unavailable) {
                scheduled = false;
                if (!closed && plugin.isEnabled()) throw unavailable;
            }
        }
    }

    private void drain() {
        ItemsService.requireThread();
        var batch = new ArrayList<Map.Entry<Request, Result>>();
        synchronized (results) {
            if (closed) return;
            for (var entry : results.entrySet()) {
                if (entry.getValue().pending)
                    batch.add(Map.entry(entry.getKey(), entry.getValue()));
                if (batch.size() == MAX_PER_TICK) break;
            }
        }
        for (var entry : batch) {
            Request key = entry.getKey();
            Result result = entry.getValue();
            synchronized (results) {
                if (closed) break;
                if (results.get(key) != result) continue;
            }
            OfflinePlayer viewer = result.viewer.get();
            String value = "";
            if ((key.viewer() == null || viewer != null)
                    && (!(viewer instanceof org.bukkit.entity.Player player) || player.isOnline())
                    && result.revision == revision.get()) {
                try {
                    value = resolver.apply(viewer, key.parameters());
                } catch (RuntimeException failure) {
                    long now = System.nanoTime();
                    if (now - lastWarning >= 30_000_000_000L) {
                        lastWarning = now;
                        plugin.getLogger()
                                .log(
                                        java.util.logging.Level.WARNING,
                                        "Async NI placeholder failed: " + key.parameters(),
                                        failure);
                    }
                }
            }
            synchronized (results) {
                if (results.get(key) == result) {
                    if (closed
                            || result.revision != revision.get()
                            || key.viewer() != null && viewer == null) results.remove(key);
                    else {
                        result.value = value;
                        result.computedAt = System.nanoTime();
                        result.pending = false;
                    }
                }
            }
        }
        boolean again;
        synchronized (results) {
            again = !closed && results.values().stream().anyMatch(result -> result.pending);
            scheduled = again;
            task = null;
        }
        if (again) schedule();
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        synchronized (results) {
            results.keySet().removeIf(key -> id.equals(key.viewer()));
        }
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        synchronized (results) {
            if (closed) return;
            closed = true;
            results.clear();
            cacheRevision = null;
            scheduled = false;
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
        HandlerList.unregisterAll(this);
        // unregister() deletes by identifier in PAPI 2.12.3; never remove a replacement owner.
        if (registered() == this) unregister();
    }
}

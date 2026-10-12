package dev.itemloom.paper.action;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerActionState implements AutoCloseable {
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Data> values = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long sequence;
    private boolean resetOnQuit = true;
    private volatile boolean closed;
    private ReturnLedger ledger;

    public PlayerActionState() {
        this(Clock.systemUTC());
    }

    public PlayerActionState(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void initializeReturns(JavaPlugin plugin, Path path) {
        Objects.requireNonNull(path);
        if (closed || ledger != null)
            throw new IllegalStateException("Return ledger already initialized or closed");
        ledger = new ReturnLedger(plugin, this, path);
    }

    synchronized ReturnLedger returns(JavaPlugin plugin) {
        if (closed) throw new IllegalStateException("Player state is closed");
        if (ledger == null) ledger = new ReturnLedger(plugin, this, null);
        return ledger;
    }

    public synchronized ReturnLedger returnLedger() {
        if (ledger == null) throw new IllegalStateException("Return ledger is not initialized");
        return ledger;
    }

    public synchronized void configure(boolean resetOnQuit) {
        this.resetOnQuit = resetOnQuit;
    }

    public synchronized void join(UUID id) {
        if (closed) return;
        if (resetOnQuit) values.put(id, new Data());
        else values.computeIfAbsent(id, ignored -> new Data());
        if (++sequence == 0) sequence++;
        sessions.put(id, new Session(sequence));
    }

    public synchronized void join(Player player) {
        join(player.getUniqueId());
        if (closed) return;
        sessions.get(player.getUniqueId()).target = new WeakReference<>(player);
        if (ledger != null) ledger.joined(player);
    }

    public synchronized void quit(UUID id) {
        sessions.remove(id);
        if (!closed && resetOnQuit) values.remove(id);
    }

    synchronized long session(UUID id) {
        Session current = closed ? null : sessions.get(id);
        return current == null ? 0 : current.token;
    }

    synchronized void observe(Player player) {
        Session current = closed ? null : sessions.get(player.getUniqueId());
        if (current != null && current.target == null) current.target = new WeakReference<>(player);
    }

    synchronized Player resolve(UUID id, long expected) {
        Session current = closed ? null : sessions.get(id);
        if (current == null || expected == 0 || current.token != expected || current.target == null)
            return null;
        Player player = current.target.get();
        return player != null && player.isOnline() ? player : null;
    }

    public boolean active() {
        return !closed;
    }

    public boolean contains(UUID id) {
        return !closed && values.containsKey(id);
    }

    private Data data(UUID id) {
        return closed ? null : values.get(id);
    }

    public boolean hasMetadata(UUID id, String key) {
        Data data = data(id);
        return data != null && data.metadata.containsKey(key);
    }

    public Object getMetadata(UUID id, String key, Object fallback) {
        Data data = data(id);
        return data == null ? fallback : data.metadata.getOrDefault(key, fallback);
    }

    public void setMetadata(UUID id, String key, Object value) {
        Data data = data(id);
        if (data != null) data.metadata.put(key, value);
    }

    public Object metadataIfAbsent(UUID id, String key, Supplier<?> factory) {
        Data data = data(id);
        return data == null ? null : data.metadata.computeIfAbsent(key, ignored -> factory.get());
    }

    public long checkCooldown(UUID id, String group, long duration) {
        Data data = data(id);
        if (data == null) return Long.MAX_VALUE;
        if (duration <= 0) return 0;
        long now = clock.millis();
        AtomicLong remaining = new AtomicLong();
        data.deadlines.compute(
                group,
                (ignored, deadline) -> {
                    if (deadline != null && deadline > now) {
                        remaining.set(deadline - now);
                        return deadline;
                    }
                    return now + duration;
                });
        return remaining.get();
    }

    public long getCooldown(UUID id, String group) {
        Data data = data(id);
        if (data == null) return Long.MAX_VALUE;
        Long deadline = data.deadlines.get(group);
        return deadline == null ? 0 : Math.max(0, deadline - clock.millis());
    }

    public void setCooldown(UUID id, String group, long duration) {
        Data data = data(id);
        if (data != null) data.deadlines.put(group, clock.millis() + duration);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (ledger != null) ledger.close();
        sessions.clear();
        values.clear();
    }

    private static final class Data {
        final ConcurrentHashMap<String, Object> metadata = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Long> deadlines = new ConcurrentHashMap<>();
    }

    private static final class Session {
        final long token;
        WeakReference<Player> target;

        Session(long token) {
            this.token = token;
        }
    }
}

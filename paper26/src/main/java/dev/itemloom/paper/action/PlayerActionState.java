package dev.itemloom.paper.action;

import java.time.Clock;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Player action state owned by the item service, independently of a compiled catalog revision.
 * The owner calls join/quit and changes configuration only after a successful reload.
 */
public final class PlayerActionState implements AutoCloseable {
    private final Clock clock;
    private final Map<UUID, State> players = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private long nextSession;
    private ReturnLedger returns;
    private boolean resetOnQuit = true;
    private volatile boolean closed;

    public PlayerActionState() {
        this(Clock.systemUTC());
    }

    public PlayerActionState(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The service calls this before loading catalogs; standalone runtime probes use an in-memory ledger. */
    public synchronized void initializeReturns(JavaPlugin plugin, Path file) {
        if (closed || returns != null)
            throw new IllegalStateException(
                    "Return ledger is already initialized or state is closed");
        returns = new ReturnLedger(plugin, this, Objects.requireNonNull(file));
    }

    synchronized ReturnLedger returns(JavaPlugin plugin) {
        if (returns == null) {
            if (closed) throw new IllegalStateException("Player action state is closed");
            returns = new ReturnLedger(plugin, this, null);
        }
        return returns;
    }

    public synchronized ReturnLedger returnLedger() {
        if (returns == null)
            throw new IllegalStateException("Return ledger has not been initialized");
        return returns;
    }

    /** Changing the policy preserves all existing state until the next join or quit. */
    public synchronized void configure(boolean resetCooldownWhenPlayerQuit) {
        if (!closed) resetOnQuit = resetCooldownWhenPlayerQuit;
    }

    /** Also called for players already online when the service starts, but never for a reload. */
    public synchronized void join(UUID player) {
        Objects.requireNonNull(player, "player");
        if (closed) return;
        sessions.put(player, new Session(++nextSession));
        if (resetOnQuit) players.put(player, new State());
        else players.computeIfAbsent(player, ignored -> new State());
    }

    /** Bind the actual current player object; a reconnect cannot reuse an old session's target. */
    public synchronized void join(Player player) {
        join(player.getUniqueId());
        if (closed) return;
        observe(player);
        if (returns != null) returns.joined(player);
    }

    /** With reset disabled, metadata and cooldowns survive a reconnect until service shutdown. */
    public synchronized void quit(UUID player) {
        Objects.requireNonNull(player, "player");
        sessions.remove(player);
        if (!closed && resetOnQuit) players.remove(player);
    }

    synchronized long session(UUID player) {
        Session session = sessions.get(player);
        return session == null ? 0 : session.id;
    }

    synchronized void observe(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.player.get() == null)
            session.player = new WeakReference<>(player);
    }

    synchronized Player resolve(UUID player, long expectedSession) {
        if (closed) return null;
        Session session = sessions.get(player);
        if (session == null || session.id != expectedSession) return null;
        Player target = session.player.get();
        return target != null && target.isOnline() ? target : null;
    }

    public boolean active() {
        return !closed;
    }

    public boolean contains(UUID player) {
        return state(player) != null;
    }

    public boolean hasMetadata(UUID player, String key) {
        State state = state(player);
        return state != null && state.metadata.containsKey(key);
    }

    /** Metadata values retain their identity, including mutable lists used by old scripts. */
    public Object getMetadata(UUID player, String key, Object fallback) {
        State state = state(player);
        return state == null ? fallback : state.metadata.getOrDefault(key, fallback);
    }

    public void setMetadata(UUID player, String key, Object value) {
        State state = state(player);
        if (state != null) state.metadata.put(key, value);
    }

    public Object metadataIfAbsent(
            UUID player, String key, java.util.function.Supplier<?> factory) {
        State state = state(player);
        return state == null ? null : state.metadata.computeIfAbsent(key, ignored -> factory.get());
    }

    /**
     * Returns the remaining milliseconds, or reserves the next cooldown and returns zero.
     * Non-positive durations bypass the check without changing an existing reservation.
     */
    public long checkCooldown(UUID player, String key, long duration) {
        State state = state(player);
        if (state == null) return Long.MAX_VALUE;
        Objects.requireNonNull(key, "key");
        if (duration <= 0) return 0;
        synchronized (state.cooldowns) {
            long now = clock.millis();
            long until = state.cooldowns.getOrDefault(key, 0L);
            if (until > now) return until - now;
            state.cooldowns.put(key, now + duration);
            return 0;
        }
    }

    public long getCooldown(UUID player, String key) {
        State state = state(player);
        if (state == null) return Long.MAX_VALUE;
        Objects.requireNonNull(key, "key");
        synchronized (state.cooldowns) {
            long now = clock.millis();
            long until = state.cooldowns.getOrDefault(key, 0L);
            return until > now ? until - now : 0;
        }
    }

    /** Keys are used verbatim. The item trigger adapter supplies its own group namespace. */
    public void setCooldown(UUID player, String key, long duration) {
        State state = state(player);
        if (state == null) return;
        Objects.requireNonNull(key, "key");
        synchronized (state.cooldowns) {
            state.cooldowns.put(key, clock.millis() + duration);
        }
    }

    private State state(UUID player) {
        Objects.requireNonNull(player, "player");
        return closed ? null : players.get(player);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (returns != null) returns.close();
        players.clear();
        sessions.clear();
    }

    private static final class Session {
        final long id;
        WeakReference<Player> player = new WeakReference<>(null);

        Session(long id) {
            this.id = id;
        }
    }

    private static final class State {
        final Map<String, Object> metadata = new ConcurrentHashMap<>();
        final Map<String, Long> cooldowns = new HashMap<>();
    }
}

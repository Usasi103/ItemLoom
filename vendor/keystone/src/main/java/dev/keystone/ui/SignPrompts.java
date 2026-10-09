package dev.keystone.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Per-plugin sign ownership, including submissions waiting for their next-tick callback. */
final class SignPrompts {

    record Spot(UUID world, int x, int y, int z) {}

    private enum State {
        WAITING,
        SUBMITTED,
        RUNNING,
        DONE,
        CANCELLED
    }

    // Queries may come from another thread; mutations and callbacks require the main thread.
    private final Map<UUID, Session> current = new ConcurrentHashMap<>();
    private final Set<Session> outstanding = new HashSet<>();
    private final BooleanSupplier mainThread;
    private final Consumer<Runnable> nextTick;
    private boolean closed;

    SignPrompts(BooleanSupplier mainThread, Consumer<Runnable> nextTick) {
        this.mainThread = mainThread;
        this.nextTick = nextTick;
    }

    void requireMainThread() {
        if (!mainThread.getAsBoolean()) {
            throw new IllegalStateException("sign prompts must be changed on the main thread");
        }
    }

    void requireOpen() {
        requireMainThread();
        if (closed) {
            throw new IllegalStateException("sign prompts belong to a disabled plugin");
        }
    }

    Session open(
            UUID player,
            Spot spot,
            Consumer<String[]> callback,
            boolean deferred,
            Runnable restore) {
        requireOpen();
        Session session = new Session(player, spot, callback, deferred, restore);
        Session previous = current.put(player, session);
        outstanding.add(session);
        if (previous != null) {
            // An already accepted legacy submission still owns its callback. Only an
            // unanswered prompt is replaced; neither may restore over this new display.
            if (previous.state == State.WAITING) {
                previous.state = State.CANCELLED;
                outstanding.remove(previous);
            }
            try {
                restore(previous);
            } catch (RuntimeException | Error failure) {
                session.failed(failure);
                throw failure;
            }
        }
        return session;
    }

    boolean isWaiting(UUID player) {
        Session session = current.get(player);
        return session != null && session.isWaiting();
    }

    Session waiting(UUID player, Spot spot) {
        requireMainThread();
        Session session = current.get(player);
        return session != null && session.isWaiting() && session.spot.equals(spot) ? session : null;
    }

    /** Drops accepted callbacks too; a departing client needs no block restoration. */
    void quit(UUID player) {
        requireMainThread();
        current.remove(player);
        for (Session session : new ArrayList<>(outstanding)) {
            if (session.player.equals(player)) {
                session.state = State.CANCELLED;
                session.restored = true;
                outstanding.remove(session);
            }
        }
    }

    /** Closes the entire owner before restoring displays, so cleanup cannot reopen prompts. */
    void close() {
        requireMainThread();
        if (closed) {
            return;
        }
        closed = true;
        var sessions = new ArrayList<>(outstanding);
        outstanding.clear();
        current.clear();
        for (Session session : sessions) {
            session.state = State.CANCELLED;
        }
        Throwable failure = null;
        for (Session session : sessions) {
            try {
                restore(session);
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void restore(Session session) {
        if (session.restored) {
            return;
        }
        session.restored = true;
        Session displayed = current.get(session.player);
        if (displayed != null && displayed != session && displayed.spot.equals(session.spot)) {
            // The new prompt now owns this fake block, including its eventual restoration.
            return;
        }
        session.restore.run();
    }

    private void deliver(Session session, String[] lines) {
        requireMainThread();
        if (session.state != State.SUBMITTED) {
            return;
        }
        session.state = State.RUNNING;
        Throwable failure = null;
        try {
            session.callback.accept(lines);
        } catch (RuntimeException | Error callbackFailure) {
            failure = callbackFailure;
            throw callbackFailure;
        } finally {
            if (session.state == State.RUNNING) {
                session.state = State.DONE;
            }
            outstanding.remove(session);
            current.remove(session.player, session);
            try {
                restore(session);
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    throw cleanup;
                }
                if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    final class Session implements SignInput.Prompt {

        private final UUID player;
        private final Spot spot;
        private final Consumer<String[]> callback;
        private final boolean deferred;
        private final Runnable restore;
        private volatile State state = State.WAITING;
        private boolean restored;

        private Session(
                UUID player,
                Spot spot,
                Consumer<String[]> callback,
                boolean deferred,
                Runnable restore) {
            this.player = player;
            this.spot = spot;
            this.callback = callback;
            this.deferred = deferred;
            this.restore = restore;
        }

        @Override
        public boolean isWaiting() {
            return state == State.WAITING && current.get(player) == this;
        }

        @Override
        public boolean cancel() {
            requireMainThread();
            if (state != State.WAITING && state != State.SUBMITTED) {
                return false;
            }
            state = State.CANCELLED;
            outstanding.remove(this);
            current.remove(player, this);
            SignPrompts.this.restore(this);
            return true;
        }

        void submit(String[] lines) {
            requireMainThread();
            if (!isWaiting()) {
                return;
            }
            state = State.SUBMITTED;
            String[] submitted = lines.clone();
            try {
                if (deferred) {
                    nextTick.accept(() -> deliver(this, submitted));
                } else {
                    deliver(this, submitted);
                }
            } catch (RuntimeException | Error failure) {
                failed(failure);
                throw failure;
            }
        }

        /** Retains the original failure when best-effort display cleanup also fails. */
        void failed(Throwable failure) {
            try {
                cancel();
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }
}

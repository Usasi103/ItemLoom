package dev.itemloom.paper.display;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.item.ItemStack;

/** Serializes outbound delivery while the main thread prepares display packets. */
public final class OrderedDisplayHandler extends ChannelDuplexHandler {
    public record Rendered(Object packet, List<DisplayLedger.Sent> sent) {
        public Rendered {
            sent = List.copyOf(sent);
        }

        public static Rendered unchanged(Object packet) {
            return new Rendered(packet, List.of());
        }
    }

    private static final int MAX_WRITES = 256;
    private static final int MAX_ITEMS = 2048;

    private static final class Pending {
        final Object packet;
        final ChannelPromise promise;
        final int itemCount;
        final boolean render;
        Rendered result;

        Pending(Object packet, ChannelPromise promise, int itemCount, boolean render) {
            this.packet = packet;
            this.promise = promise;
            this.itemCount = itemCount;
            this.render = render;
        }

        boolean flush() {
            return promise == null;
        }
    }

    private static final class Flight {
        final List<Pending> entries;
        final AtomicBoolean cancelled = new AtomicBoolean();
        ScheduledFuture<?> timeout;

        Flight(List<Pending> entries) {
            this.entries = entries;
        }
    }

    private Predicate<Object> wanted;
    private BiFunction<Object, BooleanSupplier, Rendered> renderer;
    private Consumer<Runnable> scheduler;
    private Consumer<Throwable> errors;
    private Runnable resync;
    private final Predicate<ItemStack> managed;
    private final DisplayLedger ledger;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final AtomicBoolean resyncPending = new AtomicBoolean();
    private Flight flight;
    private int items;
    private boolean startQueued;
    private boolean delivering;
    private boolean passthrough;
    private volatile boolean terminated;

    public OrderedDisplayHandler(
            Predicate<Object> wanted,
            BiFunction<Object, BooleanSupplier, Rendered> renderer,
            Consumer<Runnable> scheduler,
            Consumer<Throwable> errors,
            Runnable resync,
            Predicate<ItemStack> managed,
            DisplayLedger ledger) {
        this.wanted = wanted;
        this.renderer = renderer;
        this.scheduler = scheduler;
        this.errors = errors;
        this.resync = resync;
        this.managed = managed;
        this.ledger = ledger;
    }

    @Override
    public synchronized void write(
            ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (terminated) {
            discard(message, promise);
            return;
        }
        boolean render = false;
        int count = 0;
        try {
            render = !passthrough && wanted.test(message);
            if (render) {
                int[] visited = {0};
                ItemPackets.any(message, (item, ordinary) -> ++visited[0] > MAX_ITEMS);
                count = visited[0];
            }
        } catch (RuntimeException failure) {
            fallback(ctx, failure);
        }
        if (queue.size() >= MAX_WRITES || items + count > MAX_ITEMS) {
            if (passthrough && delivering) {
                discard(message, promise);
                terminate();
                ctx.close();
                return;
            }
            fallback(ctx, new IllegalStateException("Display queue capacity exceeded"));
            render = false;
            count = 0;
        }
        Object owned = message;
        if (render && !passthrough) {
            try {
                owned = ItemPackets.map(message, (item, ordinary) -> ProofItemCopies.copy(item));
                if (owned != message) ReferenceCountUtil.release(message);
            } catch (RuntimeException failure) {
                fallback(ctx, failure);
                render = false;
                count = 0;
            }
        }
        queue.addLast(new Pending(owned, promise, count, render && !passthrough));
        items += count;
        advance(ctx);
    }

    @Override
    public synchronized void flush(ChannelHandlerContext ctx) {
        if (terminated) return;
        if (!queue.isEmpty() && queue.peekLast().flush()) return;
        queue.addLast(new Pending(null, null, 0, false));
        if (queue.size() > MAX_WRITES)
            fallback(ctx, new IllegalStateException("Display queue capacity exceeded"));
        advance(ctx);
    }

    private void advance(ChannelHandlerContext ctx) {
        if (terminated || delivering || flight != null) return;
        delivering = true;
        try {
            while (!queue.isEmpty() && !terminated) {
                Pending head = queue.peekFirst();
                if (!passthrough && head.render && head.result == null) break;
                queue.removeFirst();
                items -= head.itemCount;
                deliver(ctx, head);
            }
        } finally {
            delivering = false;
        }
        if (!queue.isEmpty() && !terminated && !passthrough && !startQueued) {
            startQueued = true;
            try {
                ctx.executor().execute(() -> start(ctx));
            } catch (RuntimeException failure) {
                startQueued = false;
                fallback(ctx, failure);
            }
        }
    }

    private synchronized void start(ChannelHandlerContext ctx) {
        startQueued = false;
        if (terminated || passthrough || flight != null || queue.isEmpty()) return;
        Flight current = new Flight(List.copyOf(queue));
        flight = current;
        var render = renderer;
        var main = scheduler;
        try {
            current.timeout =
                    ctx.executor()
                            .schedule(() -> timeout(ctx, current), 250, TimeUnit.MILLISECONDS);
            main.accept(() -> render(ctx, current, render));
        } catch (RuntimeException failure) {
            fallback(ctx, failure);
        }
    }

    private void render(
            ChannelHandlerContext ctx,
            Flight current,
            BiFunction<Object, BooleanSupplier, Rendered> callback) {
        var completed = new ArrayList<Rendered>(current.entries.size());
        for (Pending pending : current.entries) {
            if (current.cancelled.get() || terminated) return;
            Rendered result = Rendered.unchanged(pending.packet);
            if (pending.render) {
                try {
                    result =
                            callback.apply(
                                    pending.packet, () -> !current.cancelled.get() && !terminated);
                    if (result == null)
                        throw new IllegalArgumentException("Display renderer returned null");
                } catch (RuntimeException failure) {
                    report(failure);
                }
            }
            completed.add(result);
        }
        try {
            ctx.executor().execute(() -> finish(ctx, current, completed));
        } catch (RuntimeException rejected) {
            synchronized (this) {
                terminate();
            }
        }
    }

    private synchronized void finish(
            ChannelHandlerContext ctx, Flight current, List<Rendered> results) {
        if (terminated || flight != current || current.cancelled.get()) return;
        flight = null;
        current.timeout.cancel(false);
        for (int index = 0; index < current.entries.size(); index++)
            current.entries.get(index).result = results.get(index);
        advance(ctx);
    }

    private synchronized void timeout(ChannelHandlerContext ctx, Flight current) {
        if (flight == current && !terminated)
            fallback(ctx, new IllegalStateException("Display rendering timed out"));
    }

    private void fallback(ChannelHandlerContext ctx, Throwable failure) {
        if (passthrough || terminated) return;
        report(failure);
        stopRendering(ctx);
    }

    public synchronized void stopRendering(ChannelHandlerContext ctx) {
        passthrough = true;
        cancelFlight();
        for (Pending entry : queue) entry.result = null;
        wanted = null;
        renderer = null;
        scheduler = null;
        errors = null;
        resync = null;
        resyncPending.set(false);
        advance(ctx);
    }

    private void deliver(ChannelHandlerContext ctx, Pending pending) {
        if (pending.flush()) {
            ctx.flush();
            return;
        }
        Rendered rendered = passthrough ? null : pending.result;
        Object outgoing = rendered == null ? pending.packet : rendered.packet();
        var proofs = new ArrayList<DisplayLedger.Sent>();
        if (rendered != null) proofs.addAll(rendered.sent());
        try {
            ItemPackets.any(
                    outgoing,
                    (item, ordinary) -> {
                        if (!item.isEmpty()
                                && !DisplayLedger.marked(item)
                                && !DisplayLedger.hasNestedMarker(item)
                                && managed.test(item)) {
                            proofs.add(DisplayLedger.canonical(item));
                        }
                        return false;
                    });
        } catch (RuntimeException failure) {
            report(failure);
        }
        ChannelPromise promise = proofs.isEmpty() ? pending.promise : pending.promise.unvoid();
        if (!proofs.isEmpty())
            promise.addListener(
                    done -> {
                        if (done.isSuccess() && ctx.channel().isActive() && !terminated) {
                            for (var proof : proofs) ledger.commit(proof);
                        }
                    });
        if (outgoing != pending.packet) ReferenceCountUtil.release(pending.packet);
        try {
            ctx.write(outgoing, promise);
        } catch (RuntimeException failure) {
            ReferenceCountUtil.release(outgoing);
            promise.tryFailure(failure);
        }
    }

    private void report(Throwable failure) {
        Consumer<Throwable> target;
        synchronized (this) {
            target = errors;
        }
        if (target != null) {
            try {
                target.accept(failure);
            } catch (RuntimeException ignored) {
                /* Reporting cannot disrupt ordering. */
            }
        }
    }

    @Override
    public synchronized void channelRead(ChannelHandlerContext ctx, Object message) {
        if (terminated) {
            ReferenceCountUtil.release(message);
            return;
        }
        if (message instanceof ServerboundSetCreativeModeSlotPacket creative) {
            ItemStack item = creative.itemStack();
            ItemStack restored = null;
            boolean reject = DisplayLedger.hasNestedMarker(item);
            if (!reject && DisplayLedger.marked(item)) {
                restored = ledger.restore(item);
                reject = restored == null;
            } else if (!reject && !item.isEmpty() && managed.test(item)) {
                reject = !ledger.knownOriginal(item);
            }
            if (reject) {
                ReferenceCountUtil.release(message);
                requestResync();
                return;
            }
            if (restored != null)
                message = new ServerboundSetCreativeModeSlotPacket(creative.slotNum(), restored);
        } else if (message instanceof ServerboundContainerClickPacket click && ledger.size() != 0) {
            var changes = new Int2ObjectOpenHashMap<HashedStack>();
            click.changedSlots()
                    .forEach((slot, claim) -> changes.put(slot, ledger.acceptSent(claim)));
            message =
                    new ServerboundContainerClickPacket(
                            click.containerId(),
                            click.stateId(),
                            click.slotNum(),
                            click.buttonNum(),
                            click.containerInput(),
                            changes,
                            ledger.acceptSent(click.carriedItem()));
        }
        ctx.fireChannelRead(message);
    }

    private void requestResync() {
        var main = scheduler;
        var refresh = resync;
        if (main == null || refresh == null || !resyncPending.compareAndSet(false, true)) return;
        try {
            main.accept(
                    () -> {
                        try {
                            if (!terminated && resyncPending.get()) refresh.run();
                        } finally {
                            resyncPending.set(false);
                        }
                    });
        } catch (RuntimeException failure) {
            resyncPending.set(false);
            report(failure);
        }
    }

    private void cancelFlight() {
        if (flight == null) return;
        flight.cancelled.set(true);
        if (flight.timeout != null) flight.timeout.cancel(false);
        flight = null;
    }

    private void terminate() {
        if (terminated) return;
        terminated = true;
        cancelFlight();
        while (!queue.isEmpty()) {
            Pending pending = queue.removeFirst();
            if (!pending.flush()) discard(pending.packet, pending.promise);
        }
        items = 0;
        ledger.clear();
        wanted = null;
        renderer = null;
        scheduler = null;
        errors = null;
        resync = null;
        resyncPending.set(false);
    }

    private static void discard(Object message, ChannelPromise promise) {
        ReferenceCountUtil.release(message);
        promise.tryFailure(new ClosedChannelException());
    }

    @Override
    public synchronized void channelInactive(ChannelHandlerContext ctx) {
        terminate();
        ctx.fireChannelInactive();
    }

    @Override
    public synchronized void handlerRemoved(ChannelHandlerContext ctx) {
        terminate();
    }
}

package dev.itemloom.paper.display;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
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

/**
 * One ordered queue per connection. Scripts are scheduled on the server thread; Netty never waits.
 * All later writes/flushes queue behind a display write, including non-item packets.
 * Queue pressure or a stalled server degrades this connection to canonical packets until reconnect.
 */
public final class OrderedDisplayHandler extends ChannelDuplexHandler {
    public record Rendered(Object packet, List<DisplayLedger.Sent> sent) {
        public static Rendered unchanged(Object packet) {
            return new Rendered(packet, List.of());
        }
    }

    private record Write(Object original, boolean render, int items, ChannelPromise promise) {
        boolean flush() {
            return promise == null;
        }
    }

    private record Delivery(Write write, Rendered result) {}

    private static final int MAX_WRITES = 256, MAX_ITEMS = 2048;

    private static final class Batch {
        final List<Write> writes;
        final AtomicBoolean cancelled = new AtomicBoolean();
        io.netty.util.concurrent.ScheduledFuture<?> timeout;

        Batch(List<Write> writes) {
            this.writes = writes;
        }
    }

    private Predicate<Object> needsRender;
    private BiFunction<Object, BooleanSupplier, Rendered> render;
    private Consumer<Runnable> main;
    private volatile Consumer<Throwable> error;
    private Runnable resync;
    private final Predicate<ItemStack> managed;
    private final DisplayLedger ledger;
    private final List<Write> waiting = new ArrayList<>();
    private final ArrayDeque<Delivery> ready = new ArrayDeque<>();
    private final AtomicBoolean resyncQueued = new AtomicBoolean();
    private Batch inFlight;
    private int writes, items;
    private boolean passthrough, dispatchQueued, delivering, terminated;

    public OrderedDisplayHandler(
            Predicate<Object> needsRender,
            BiFunction<Object, BooleanSupplier, Rendered> render,
            Consumer<Runnable> main,
            Consumer<Throwable> error,
            Runnable resync,
            Predicate<ItemStack> managed,
            DisplayLedger ledger) {
        this.needsRender = needsRender;
        this.render = render;
        this.main = main;
        this.error = error;
        this.resync = resync;
        this.managed = managed;
        this.ledger = ledger;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (terminated) {
            ReferenceCountUtil.release(message);
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        if (passthrough) {
            pass(ctx, message, promise);
            return;
        }
        boolean wanted;
        try {
            wanted = needsRender.test(message);
        } catch (RuntimeException failure) {
            fallback(ctx, failure);
            pass(ctx, message, promise);
            return;
        }
        if (!wanted && inFlight == null && waiting.isEmpty() && !delivering) {
            ctx.write(message, promise);
            return;
        }
        if (writes >= MAX_WRITES) {
            fallback(
                    ctx,
                    new IllegalStateException("Display queue exceeded " + MAX_WRITES + " writes"));
            pass(ctx, message, promise);
            return;
        }
        Object snapshot = message;
        int stackCount = 0;
        if (wanted) {
            int[] count = {0};
            try {
                snapshot =
                        ItemPackets.map(
                                message,
                                (stack, ordinary) -> {
                                    if (++count[0] + items > MAX_ITEMS)
                                        throw new IllegalStateException(
                                                "Display queue exceeded "
                                                        + MAX_ITEMS
                                                        + " item stacks");
                                    return ProofItemCopies.copy(stack);
                                });
            } catch (RuntimeException failure) {
                fallback(ctx, failure);
                pass(ctx, message, promise);
                return;
            }
            items += count[0];
            stackCount = count[0];
        }
        waiting.add(new Write(snapshot, wanted, stackCount, promise));
        writes++;
        dispatch(ctx);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (inFlight == null && waiting.isEmpty() && !delivering) {
            ctx.flush();
            return;
        }
        if (waiting.isEmpty() || !waiting.getLast().flush())
            waiting.add(new Write(null, false, 0, null));
    }

    private void dispatch(ChannelHandlerContext ctx) {
        if (dispatchQueued || inFlight != null || waiting.isEmpty() || delivering) return;
        dispatchQueued = true;
        ctx.executor()
                .execute(
                        () -> {
                            dispatchQueued = false;
                            if (waiting.isEmpty() || !ctx.channel().isActive()) return;
                            if (passthrough || waiting.stream().noneMatch(Write::render)) {
                                waiting.forEach(
                                        write ->
                                                ready.addLast(
                                                        new Delivery(
                                                                write,
                                                                Rendered.unchanged(
                                                                        write.original()))));
                                waiting.clear();
                                deliver(ctx);
                                return;
                            }
                            Batch batch = new Batch(List.copyOf(waiting));
                            waiting.clear();
                            inFlight = batch;
                            batch.timeout =
                                    ctx.executor()
                                            .schedule(
                                                    () -> {
                                                        if (inFlight == batch)
                                                            fallback(
                                                                    ctx,
                                                                    new IllegalStateException(
                                                                            "Display rendering exceeded 250 ms; using canonical packets"));
                                                    },
                                                    250,
                                                    TimeUnit.MILLISECONDS);
                            try {
                                var renderer = render;
                                main.accept(
                                        () -> {
                                            List<Rendered> output =
                                                    new ArrayList<>(batch.writes.size());
                                            for (Write write : batch.writes) {
                                                if (batch.cancelled.get()) return;
                                                if (write.flush() || !write.render()) {
                                                    output.add(
                                                            Rendered.unchanged(write.original()));
                                                    continue;
                                                }
                                                try {
                                                    output.add(
                                                            renderer.apply(
                                                                    write.original(),
                                                                    () -> !batch.cancelled.get()));
                                                } catch (RuntimeException failure) {
                                                    report(failure);
                                                    output.add(
                                                            Rendered.unchanged(write.original()));
                                                }
                                            }
                                            if (!batch.cancelled.get())
                                                ctx.executor()
                                                        .execute(
                                                                () -> complete(ctx, batch, output));
                                        });
                            } catch (RuntimeException failure) {
                                fallback(ctx, failure);
                            }
                        });
    }

    private void complete(ChannelHandlerContext ctx, Batch batch, List<Rendered> output) {
        if (inFlight != batch || batch.cancelled.get()) return;
        batch.timeout.cancel(false);
        inFlight = null;
        for (int i = 0; i < batch.writes.size(); i++)
            ready.addLast(new Delivery(batch.writes.get(i), output.get(i)));
        deliver(ctx);
    }

    /** Remove ownership before invoking downstream handlers: their completion listeners may reenter or close. */
    private void deliver(ChannelHandlerContext ctx) {
        if (delivering) return;
        delivering = true;
        try {
            while (!ready.isEmpty()) {
                Delivery delivery = ready.removeFirst();
                Write write = delivery.write();
                if (write.flush()) {
                    ctx.flush();
                    continue;
                }
                writes--;
                items -= write.items();
                Rendered rendered = delivery.result();
                if (passthrough || rendered.sent().isEmpty()) {
                    writeCanonical(
                            ctx,
                            passthrough ? write.original() : rendered.packet(),
                            write.promise());
                } else {
                    ChannelPromise promise = write.promise().unvoid();
                    promise.addListener(
                            future -> {
                                if (future.isSuccess() && ctx.channel().isActive())
                                    rendered.sent().forEach(ledger::commit);
                            });
                    ctx.write(rendered.packet(), promise);
                }
            }
        } finally {
            delivering = false;
        }
        if (waiting.isEmpty() && inFlight == null) {
            writes = 0;
            items = 0;
        } else dispatch(ctx);
    }

    private void fallback(ChannelHandlerContext ctx, Throwable cause) {
        if (passthrough) return;
        passthrough = true;
        report(cause);
        if (inFlight != null) {
            inFlight.cancelled.set(true);
            inFlight.timeout.cancel(false);
            for (Write write : inFlight.writes)
                ready.addLast(new Delivery(write, Rendered.unchanged(write.original())));
            inFlight = null;
        }
        for (Write write : waiting)
            ready.addLast(new Delivery(write, Rendered.unchanged(write.original())));
        waiting.clear();
        deliver(ctx);
    }

    private void report(Throwable cause) {
        var sink = error;
        if (sink != null && cause != null) sink.accept(cause);
    }

    private void pass(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (!delivering && waiting.isEmpty()) {
            writeCanonical(ctx, message, promise);
            return;
        }
        if (writes >= MAX_WRITES) {
            ReferenceCountUtil.release(message);
            promise.tryFailure(new IllegalStateException("Recursive outbound queue overflow"));
            ctx.close();
            return;
        }
        waiting.add(new Write(message, false, 0, promise));
        writes++;
        dispatch(ctx);
    }

    private void writeCanonical(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
        List<DisplayLedger.Sent> originals = new ArrayList<>();
        ItemPackets.any(
                packet,
                (stack, inventory) -> {
                    if (!stack.isEmpty() && !DisplayLedger.marked(stack) && managed.test(stack))
                        originals.add(DisplayLedger.canonical(stack));
                    return false;
                });
        if (!originals.isEmpty()) {
            promise = promise.unvoid();
            promise.addListener(
                    future -> {
                        if (future.isSuccess() && ctx.channel().isActive())
                            originals.forEach(ledger::commit);
                    });
        }
        ctx.write(packet, promise);
    }

    /** Keep the minimal return guard until disconnect, including late creative packets after disable. */
    public void stopRendering(ChannelHandlerContext ctx) {
        fallback(ctx, null);
        needsRender = null;
        render = null;
        main = null;
        error = null;
        resync = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof ServerboundSetCreativeModeSlotPacket packet) {
            ItemStack incoming = packet.itemStack();
            if (DisplayLedger.hasNestedMarker(incoming)) {
                requestResync();
                return;
            }
            if (DisplayLedger.marked(incoming)) {
                ItemStack original = ledger.restore(incoming);
                if (original == null) {
                    requestResync();
                    return;
                }
                message = new ServerboundSetCreativeModeSlotPacket(packet.slotNum(), original);
            } else if (managed.test(incoming) && !ledger.knownOriginal(incoming)) {
                requestResync();
                return;
            }
        } else if (message instanceof ServerboundContainerClickPacket packet
                && ledger.size() != 0) {
            var slots = new Int2ObjectOpenHashMap<HashedStack>();
            packet.changedSlots()
                    .int2ObjectEntrySet()
                    .forEach(
                            entry ->
                                    slots.put(
                                            entry.getIntKey(),
                                            ledger.acceptSent(entry.getValue())));
            message =
                    new ServerboundContainerClickPacket(
                            packet.containerId(),
                            packet.stateId(),
                            packet.slotNum(),
                            packet.buttonNum(),
                            packet.containerInput(),
                            slots,
                            ledger.acceptSent(packet.carriedItem()));
        }
        ctx.fireChannelRead(message);
    }

    private void requestResync() {
        Consumer<Runnable> scheduler = main;
        Runnable refresh = resync;
        if (scheduler == null || refresh == null || !resyncQueued.compareAndSet(false, true))
            return;
        try {
            scheduler.accept(
                    () -> {
                        resyncQueued.set(false);
                        refresh.run();
                    });
        } catch (RuntimeException ignored) {
            resyncQueued.set(false);
        }
    }

    private void discard() {
        passthrough = true;
        terminated = true;
        var failure = new ClosedChannelException();
        if (inFlight != null) {
            inFlight.cancelled.set(true);
            inFlight.timeout.cancel(false);
            inFlight.writes.forEach(write -> fail(write, failure));
            inFlight = null;
        }
        waiting.forEach(write -> fail(write, failure));
        waiting.clear();
        ready.forEach(delivery -> fail(delivery.write(), failure));
        ready.clear();
        writes = 0;
        items = 0;
        ledger.clear();
    }

    private static void fail(Write write, Throwable failure) {
        if (!write.flush()) {
            ReferenceCountUtil.release(write.original());
            write.promise().tryFailure(failure);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        discard();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        discard();
    }
}

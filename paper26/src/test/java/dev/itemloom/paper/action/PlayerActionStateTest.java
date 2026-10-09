package dev.itemloom.paper.action;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlayerActionStateTest {
    @Test
    void reloadPolicyAndReconnectPreserveOnlyThePromisedState() {
        var clock = new MutableClock();
        UUID id = UUID.randomUUID();
        try (var state = new PlayerActionState(clock)) {
            assertEquals(Long.MAX_VALUE, state.checkCooldown(id, "key", 0));
            state.join(id);
            assertEquals(0, state.checkCooldown(id, "key", 100));
            clock.now += 30;
            assertEquals(70, state.checkCooldown(id, "key", 100));
            assertEquals(0, state.checkCooldown(id, "key", -1));
            assertEquals(70, state.getCooldown(id, "key"));
            Object metadata = new Object();
            state.setMetadata(id, "meta", metadata);
            state.configure(false);
            state.quit(id);
            state.join(id);
            assertSame(metadata, state.getMetadata(id, "meta", null));
            assertEquals(70, state.getCooldown(id, "key"));
            clock.now += 70;
            assertEquals(0, state.checkCooldown(id, "key", 200));
            assertEquals(200, state.getCooldown(id, "key"));
            state.configure(true);
            assertSame(metadata, state.getMetadata(id, "meta", null));
            state.quit(id);
            state.join(id);
            assertFalse(state.hasMetadata(id, "meta"));
            assertEquals(0, state.getCooldown(id, "key"));
            state.close();
            state.join(id);
            assertEquals(Long.MAX_VALUE, state.getCooldown(id, "key"));
        }
    }

    @Test
    void concurrentGroupReservationHasOneWinner() throws Exception {
        UUID id = UUID.randomUUID();
        try (var state = new PlayerActionState(new MutableClock());
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            state.join(id);
            var start = new CountDownLatch(1);
            var ready = new CountDownLatch(32);
            var winners = new AtomicInteger();
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 32; i++)
                tasks.add(
                        threads.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    if (state.checkCooldown(id, "shared", 5000) == 0)
                                        winners.incrementAndGet();
                                    return null;
                                }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, winners.get());
        }
    }

    @Test
    void concurrentMetadataInitializationKeepsOneSharedHistory() throws Exception {
        UUID id = UUID.randomUUID();
        try (var state = new PlayerActionState();
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            state.join(id);
            var start = new CountDownLatch(1);
            var created = new AtomicInteger();
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 32; i++)
                tasks.add(
                        threads.submit(
                                () -> {
                                    start.await();
                                    return state.metadataIfAbsent(
                                            id,
                                            "Combo-test",
                                            () -> {
                                                created.incrementAndGet();
                                                return new java.util.ArrayList<>();
                                            });
                                }));
            start.countDown();
            Object history = tasks.getFirst().get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (var task : tasks)
                assertSame(history, task.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, created.get());
            assertSame(history, state.getMetadata(id, "Combo-test", null));
        }
    }

    private static final class MutableClock extends Clock {
        long now = 1_000;

        @Override
        public long millis() {
            return now;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

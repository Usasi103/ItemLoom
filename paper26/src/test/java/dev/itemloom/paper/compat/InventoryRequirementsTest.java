package dev.itemloom.paper.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InventoryRequirementsTest {
    private record Stack(String id, int amount) {}

    private static final List<Stack> INVENTORY =
            List.of(new Stack("A", 2), new Stack("A", 3), new Stack("B", 1));

    @Test
    void requirementsUseLastPositivePairAndBothSeparators() {
        for (String payload :
                List.of(
                        "A_5_B_1",
                        "A\\5_B\\1",
                        "A_9_A_4",
                        "A_4_A_0",
                        "A_+5",
                        "A_2147483648",
                        "",
                        "trailing",
                        "A_0",
                        "A_-1",
                        "A_ 1",
                        "A_5_trailing")) {
            assertTrue(fulfilled(payload), payload);
        }
        for (String payload :
                List.of("A_6", "A_9_A_invalid", "A_9_A_-1", "a_1", "A_9_A_2147483648")) {
            assertFalse(fulfilled(payload), payload);
        }
        assertTrue(
                InventoryRequirements.fulfilled(
                        "_2", List.of(new Stack("", 2)), Stack::id, Stack::amount));
        assertFalse(InventoryRequirements.fulfilled("_2", INVENTORY, Stack::id, Stack::amount));
    }

    @Test
    void everyIdentityIsReadOnceButOnlyUnfulfilledMatchesReadAmounts() {
        List<String> identities = new ArrayList<>();
        List<Stack> amounts = new ArrayList<>();
        boolean fulfilled =
                InventoryRequirements.fulfilled(
                        "A_2",
                        INVENTORY,
                        stack -> {
                            identities.add(stack.id());
                            return stack.id();
                        },
                        stack -> {
                            amounts.add(stack);
                            return stack.amount();
                        });
        assertTrue(fulfilled);
        assertEquals(List.of("A", "A", "B"), identities);
        assertEquals(List.of(INVENTORY.getFirst()), amounts);
        AtomicInteger reads = new AtomicInteger();
        assertTrue(
                InventoryRequirements.fulfilled(
                        "invalid",
                        INVENTORY,
                        stack -> {
                            reads.incrementAndGet();
                            return stack.id();
                        },
                        stack -> {
                            throw new AssertionError("irrelevant amount read");
                        }));
        assertEquals(INVENTORY.size(), reads.get());
        assertEquals(List.of(new Stack("A", 2), new Stack("A", 3), new Stack("B", 1)), INVENTORY);
    }

    @Test
    void identityFailureAfterSatisfactionStillPropagates() {
        RuntimeException expected = new IllegalStateException("identity failed");
        assertSame(
                expected,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                InventoryRequirements.fulfilled(
                                        "A_1",
                                        INVENTORY,
                                        stack -> {
                                            if (stack.id().equals("B")) throw expected;
                                            return stack.id();
                                        },
                                        Stack::amount)));
    }

    private static boolean fulfilled(String payload) {
        return InventoryRequirements.fulfilled(payload, INVENTORY, Stack::id, Stack::amount);
    }
}

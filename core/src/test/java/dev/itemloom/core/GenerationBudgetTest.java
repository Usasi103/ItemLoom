package dev.itemloom.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GenerationBudgetTest {
    @Test
    void discardedWorkDoesNotConsumeRewardAllowance() {
        var budget = new GenerationBudget(4, 64, 1);
        budget.work(2);
        budget.work(2);
        budget.retain(64, 1);
        assertThrows(IllegalArgumentException.class, () -> budget.work(1));
    }

    @Test
    void overflowAndNegativeReservationsCannotRestoreCredit() {
        var budget = new GenerationBudget(Long.MAX_VALUE, 1, 1);
        budget.work(Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> budget.work(1));
        assertThrows(IllegalArgumentException.class, () -> budget.work(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.retain(0, 0));
    }

    @Test
    void stackAndQuantityLimitsAreIndependentAndExhaustionIsSticky() {
        var stacks = new GenerationBudget(10, 4096, 1);
        assertThrows(IllegalArgumentException.class, () -> stacks.retain(2, 2));
        assertThrows(IllegalArgumentException.class, () -> stacks.work(0));
        var items = new GenerationBudget(10, 1, 256);
        assertThrows(IllegalArgumentException.class, () -> items.retain(2, 1));
        assertThrows(IllegalArgumentException.class, () -> items.retain(1, 1));
    }
}

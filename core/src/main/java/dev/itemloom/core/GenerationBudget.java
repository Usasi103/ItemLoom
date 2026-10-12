package dev.itemloom.core;

public final class GenerationBudget {
    private long workRemaining;
    private long itemsRemaining;
    private long stacksRemaining;
    private boolean exhausted;

    public GenerationBudget(long work, long items, long stacks) {
        if (work < 0 || items < 0 || stacks < 0) {
            throw new IllegalArgumentException("Generation limits cannot be negative");
        }
        workRemaining = work;
        itemsRemaining = items;
        stacksRemaining = stacks;
    }

    public synchronized void work(long count) {
        if (exhausted || count < 0 || count > workRemaining) {
            fail();
        }
        workRemaining -= count;
    }

    public synchronized void retain(long itemCount, long stackCount) {
        if (exhausted
                || itemCount < 0
                || stackCount < 0
                || itemCount > itemsRemaining
                || stackCount > stacksRemaining) {
            fail();
        }
        itemsRemaining -= itemCount;
        stacksRemaining -= stackCount;
    }

    private void fail() {
        exhausted = true;
        throw new IllegalArgumentException("Generation budget exhausted");
    }
}

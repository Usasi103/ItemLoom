package dev.itemloom.core;

/** One bounded generation request. Exhaustion is sticky, even if a caller catches the exception. */
public final class GenerationBudget {
    private long work, items, stacks;
    private boolean exhausted;

    public GenerationBudget(long work, long items, long stacks) {
        if (work < 0 || items < 0 || stacks < 0)
            throw new IllegalArgumentException("Negative generation budget");
        this.work = work;
        this.items = items;
        this.stacks = stacks;
    }

    /** Reserve loop/callback work before starting it; this cannot interrupt arbitrary user code. */
    public void work(long count) {
        if (exhausted || count < 0 || count > work) fail();
        work -= count;
    }

    /** Count only retained rewards, including the stacks needed after normalizing their sizes. */
    public void retain(long itemCount, long stackCount) {
        if (exhausted
                || itemCount < 0
                || stackCount < 0
                || itemCount > items
                || stackCount > stacks) fail();
        items -= itemCount;
        stacks -= stackCount;
    }

    private void fail() {
        exhausted = true;
        throw new IllegalArgumentException("Loot generation budget exceeded");
    }
}

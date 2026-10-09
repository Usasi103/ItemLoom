package dev.itemloom.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Successful full configuration publication only (including initial load); never fired for rollback. */
public final class ItemsReloadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final long revision;
    private final boolean initial;

    public ItemsReloadEvent(long revision, boolean initial) {
        this.revision = revision;
        this.initial = initial;
    }

    public long getRevision() {
        return revision;
    }

    public boolean isInitial() {
        return initial;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

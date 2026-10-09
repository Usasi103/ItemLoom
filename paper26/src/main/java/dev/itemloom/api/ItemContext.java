package dev.itemloom.api;

import dev.itemloom.core.GenerationContext;
import org.bukkit.OfflinePlayer;

public final class ItemContext {
    public static final GenerationContext.Key<OfflinePlayer> VIEWER =
            new GenerationContext.Key<>(OfflinePlayer.class);

    private ItemContext() {}
}

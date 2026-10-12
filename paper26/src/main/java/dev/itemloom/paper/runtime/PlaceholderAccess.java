package dev.itemloom.paper.runtime;

import org.bukkit.OfflinePlayer;

public interface PlaceholderAccess {
    Object publicationToken();

    String request(OfflinePlayer viewer, String parameters);
}

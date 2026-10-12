package dev.itemloom.paper.runtime;

import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import java.util.Map;

public interface ItemDefinitions {
    ItemPublication publication();

    ItemStack create(String id, OfflinePlayer viewer, Map<String, String> rolls);
}

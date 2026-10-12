package dev.itemloom.paper.runtime;

import dev.itemloom.core.GenerationBudget;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

public interface PackAccess {
    Set<String> ids();

    List<ItemStack> create(
            String id, OfflinePlayer viewer, Map<String, String> rolls, GenerationBudget budget);
}

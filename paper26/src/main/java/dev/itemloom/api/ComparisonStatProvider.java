package dev.itemloom.api;

import java.math.BigDecimal;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Main-thread stat reader. Configured provider-key/base-key map public keys to tooltip keys. */
@FunctionalInterface
public interface ComparisonStatProvider {
    /** Null falls back to item PDC; an empty map explicitly means no comparable stats. */
    Map<String, BigDecimal> stats(Player viewer, String slot, ItemStack item);

    /** Raw fixed base totals, before percentage multipliers. */
    default Map<String, BigDecimal> base(Player viewer) {
        return Map.of();
    }
}

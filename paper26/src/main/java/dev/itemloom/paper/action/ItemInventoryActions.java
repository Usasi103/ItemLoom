package dev.itemloom.paper.action;

import java.util.logging.Logger;
import dev.itemloom.compat.ni.action.NiValues;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.paper.compat.NiItemNodes;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** NI take-ni-item contract, accepting both the independent and legacy item identities. */
final class ItemInventoryActions {
    private ItemInventoryActions() {}

    static Result take(Player player, String content, Logger logger) {
        if (player == null) return Result.CONTINUE;
        String[] args = content.split(" ", 2);
        if (args.length < 2) return Result.CONTINUE;
        Integer parsed = NiValues.strictInteger(args[1]);
        int remaining = parsed == null ? 0 : parsed;
        // NI accidentally increased the first matching stack for a negative amount.
        // This explicitly rejected input must never mint items in the independent engine.
        if (remaining < 0) {
            logger.warning("Action take-ni-item rejected negative amount: " + content);
            return Result.STOP;
        }
        if (remaining == 0) return Result.CONTINUE;
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (!args[0].equals(NiItemNodes.itemId(item))) continue;
            int removed = Math.min(remaining, item.getAmount());
            remaining -= removed;
            item.setAmount(item.getAmount() - removed);
            inventory.setItem(slot, item.isEmpty() ? null : item);
        }
        // The old action consumes all matches even when fewer than requested exist.
        return Result.CONTINUE;
    }
}

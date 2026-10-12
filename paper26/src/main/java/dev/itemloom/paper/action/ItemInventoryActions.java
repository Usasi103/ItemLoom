package dev.itemloom.paper.action;

import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.paper.nms.ItemStateCodec;
import java.math.BigInteger;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class ItemInventoryActions {
    private ItemInventoryActions() {}

    static Result take(Player player, String content, Logger logger) {
        if (player == null || content == null) return Result.CONTINUE;
        int split = content.indexOf(' ');
        if (split < 0) return Result.CONTINUE;
        String id = content.substring(0, split);
        int requested = quantity(content.substring(split + 1));
        if (requested < 0) {
            logger.warning("take-ni-item rejected negative amount: " + content);
            return Result.STOP;
        }
        ItemStateCodec codec = new ItemStateCodec();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && requested > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty() || !id.equals(codec.readId(item))) continue;
            int removed = Math.min(requested, item.getAmount());
            requested -= removed;
            item.setAmount(item.getAmount() - removed);
            player.getInventory().setItem(slot, item.isEmpty() ? null : item);
        }
        return Result.CONTINUE;
    }

    private static int quantity(String value) {
        if (!value.matches("[+-]?[0-9]+")) return 0;
        BigInteger parsed = new BigInteger(value);
        if (parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) return Integer.MAX_VALUE;
        if (parsed.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0) return Integer.MIN_VALUE;
        return parsed.intValue();
    }
}

package dev.itemloom.paper.compat;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;

public final class NiTranslations implements Function<ItemStack, String> {
    private final Map<String, String> values;

    public NiTranslations(String language, Logger logger) {
        Map<String, String> loaded = new HashMap<>();
        Path root = Path.of("lang", Bukkit.getMinecraftVersion()).toAbsolutePath().normalize();
        Path file = root.resolve(language + ".json").normalize();
        if (!file.startsWith(root))
            throw new IllegalArgumentException("Language path escapes cache: " + language);
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json =
                        JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                for (var entry : json.entrySet())
                    loaded.put(entry.getKey(), entry.getValue().getAsString());
            } catch (Exception error) {
                logger.warning("Cannot read language " + language + ": " + error.getMessage());
            }
        }
        values = Map.copyOf(loaded);
    }

    public String translate(String key) {
        String value = values.get(key);
        return value == null ? new TranslatableComponent(key).toLegacyText() : value;
    }

    @Override
    public String apply(ItemStack item) {
        var handle = CraftItemStack.asNMSCopy(item);
        if (handle.getCustomName() != null) return handle.getCustomName().getString();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull
                && skull.getOwningPlayer() != null
                && skull.getOwningPlayer().getName() != null) {
            String template = translate("block.minecraft.player_head.named");
            int marker = template.indexOf("%s");
            if (marker >= 0)
                return template.substring(0, marker)
                        + skull.getOwningPlayer().getName()
                        + template.substring(marker + 2);
            return template;
        }
        if (meta instanceof BookMeta book && book.hasTitle()) return book.getTitle();
        if (meta instanceof CompassMeta compass && compass.hasLodestone())
            return translate("item.minecraft.lodestone_compass");
        return translate(handle.getItem().getDescriptionId());
    }
}

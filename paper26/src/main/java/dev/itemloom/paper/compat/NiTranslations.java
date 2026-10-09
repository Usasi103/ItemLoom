package dev.itemloom.paper.compat;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import net.minecraft.core.component.DataComponents;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Reads the existing shared Minecraft language cache; custom display names need no asset file. */
public final class NiTranslations implements Function<ItemStack, String> {
    private final Map<String, String> values;

    public NiTranslations(String language, Logger logger) {
        Map<String, String> loaded = new HashMap<>();
        Path root = Path.of("lang", Bukkit.getMinecraftVersion()).toAbsolutePath().normalize();
        Path file = root.resolve(language + ".json").normalize();
        if (!file.startsWith(root))
            throw new IllegalArgumentException("Invalid language cache path: " + language);
        if (Files.isRegularFile(file)) {
            try {
                JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject()
                        .entrySet()
                        .forEach(
                                entry ->
                                        loaded.put(entry.getKey(), entry.getValue().getAsString()));
            } catch (Exception error) {
                logger.warning(
                        "Cannot read Minecraft language cache " + file + ": " + error.getMessage());
            }
        }
        values = Map.copyOf(loaded);
    }

    @SuppressWarnings("deprecation")
    public String translate(String key) {
        String value = values.get(key);
        return value == null
                ? new net.md_5.bungee.api.chat.TranslatableComponent(key).toLegacyText()
                : value;
    }

    @Override
    @SuppressWarnings("deprecation")
    public String apply(ItemStack item) {
        var nms = CraftItemStack.asNMSCopy(item);
        var customName = nms.get(DataComponents.CUSTOM_NAME);
        if (customName != null) return customName.getString();
        var meta = item.getItemMeta();
        String key = nms.getItem().getDescriptionId();
        if (meta instanceof SkullMeta skull
                && skull.getOwnerProfile() != null
                && skull.getOwnerProfile().getName() != null)
            return translate(key + ".named")
                    .replaceFirst(
                            "%s",
                            java.util.regex.Matcher.quoteReplacement(
                                    skull.getOwnerProfile().getName()));
        if (item.getType() == Material.WRITTEN_BOOK
                && meta instanceof BookMeta book
                && book.hasTitle()) return book.getTitle();
        if (item.getType() == Material.COMPASS && nms.has(DataComponents.LODESTONE_TRACKER))
            return translate("item.minecraft.lodestone_compass");
        return translate(key);
    }
}

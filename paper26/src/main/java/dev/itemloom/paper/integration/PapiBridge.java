package dev.itemloom.paper.integration;

import dev.itemloom.compat.ni.action.NiActionText;
import java.util.function.BiFunction;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/** Loaded by the plugin only after PlaceholderAPI is known to be enabled. */
public final class PapiBridge implements BiFunction<Object, String, String> {
    public static String itemSections(String text) {
        return NiActionText.replacePlaceholders(
                text,
                token -> {
                    int underscore = token.indexOf('_');
                    String namespace = underscore < 0 ? token : token.substring(0, underscore);
                    if (!PlaceholderAPI.isRegistered(namespace.toLowerCase())) return null;
                    return "<papi::" + token + (underscore < 0 ? "_" : "") + ">";
                });
    }

    @Override
    public String apply(Object player, String parameters) {
        if (!(player instanceof OfflinePlayer viewer)) return null;
        String token = "%" + parameters + "%";
        String result = PlaceholderAPI.setPlaceholders(viewer, token);
        return token.equals(result) ? null : result;
    }
}

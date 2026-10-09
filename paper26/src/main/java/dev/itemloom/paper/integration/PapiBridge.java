package dev.itemloom.paper.integration;

import java.util.function.BiFunction;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/** Loaded only when the optional dependency is enabled. */
public final class PapiBridge implements BiFunction<Object, String, String> {
    /** NI converts registered placeholders in item YAML before parsing, without writing it back. */
    public static String itemSections(String source) {
        return dev.itemloom.compat.ni.action.NiActionText.replacePlaceholders(
                source,
                token -> {
                    int separator = token.indexOf('_');
                    String identifier = separator < 0 ? token : token.substring(0, separator);
                    if (!PlaceholderAPI.isRegistered(
                            identifier.toLowerCase(java.util.Locale.getDefault()))) return null;
                    return "<papi::" + token + (separator < 0 ? "_" : "") + ">";
                });
    }

    @Override
    public String apply(Object viewer, String parameters) {
        if (!(viewer instanceof OfflinePlayer player)) return null;
        String token = "%" + parameters + "%";
        String value = PlaceholderAPI.setPlaceholders(player, token);
        return value.equals(token) ? null : value;
    }
}

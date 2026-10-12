package dev.itemloom.compat.ni.script;

import dev.itemloom.compat.ni.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class LegacySectionUtils {
    public interface ItemView {
        Object getItemTag();

        Map<String, String> getData();
    }

    public static final LegacySectionUtils INSTANCE = new LegacySectionUtils();

    private LegacySectionUtils() {}

    private static String evaluate(
            String text,
            boolean expression,
            Map<String, String> cache,
            Object player,
            ConfigurationSection sections) {
        NiEvaluation evaluation =
                NiEvaluation.current()
                        .legacyContext(
                                cache,
                                player,
                                sections == null ? null : NiYaml.fromSection(sections));
        try {
            String value = expression ? evaluation.value(text) : evaluation.text(text);
            return expression && value == null ? "<" + text + ">" : value;
        } finally {
            if (cache != null && cache != evaluation.generation().rolls()) {
                cache.clear();
                cache.putAll(evaluation.generation().rolls());
            }
        }
    }

    public static String parseSection(
            String text, Map<String, String> cache, Object player, ConfigurationSection sections) {
        return evaluate(text, false, cache, player, sections);
    }

    public static String parseSection(
            String text,
            boolean parse,
            Map<String, String> cache,
            Object player,
            ConfigurationSection sections) {
        return parse ? parseSection(text, cache, player, sections) : text;
    }

    public static String parseSection(String text) {
        return parseSection(text, null, null, null);
    }

    public static String parseSection(String text, Map<String, String> cache) {
        return parseSection(text, cache, null, null);
    }

    public static String getSection(
            String text, Map<String, String> cache, Object player, ConfigurationSection sections) {
        return evaluate(text, true, cache, player, sections);
    }

    public static String parseItemSection(String text, ItemStack item, Object nbt, Object player) {
        return nbt instanceof ItemView view
                ? parseItemSection(text, item, view, player, null, null)
                : parseItemSection(text, item, nbt, null, player, null, null);
    }

    public static String parseItemSection(
            String text,
            ItemStack item,
            ItemView info,
            Object player,
            Map<String, String> cache,
            ConfigurationSection sections) {
        return parseItemSection(
                text, item, info.getItemTag(), info.getData(), player, cache, sections);
    }

    public static String parseItemSection(
            String text, ItemStack item, Object nbt, Map<String, String> data, Object player) {
        return parseItemSection(text, item, nbt, data, player, null, null);
    }

    public static String parseItemSection(
            String text,
            ItemStack item,
            Object nbt,
            Map<String, String> data,
            Object player,
            Map<String, String> cache,
            ConfigurationSection sections) {
        return NiTemplate.compile(text)
                .render(
                        expression ->
                                getItemSection(
                                        expression, item, nbt, data, player, cache, sections));
    }

    public static String getItemSection(
            String text,
            ItemStack item,
            Object nbt,
            Map<String, String> data,
            Object player,
            Map<String, String> cache,
            ConfigurationSection sections) {
        int split = text.indexOf("::");
        if (split < 0) return getSection(text, cache, player, sections);
        String type = text.substring(0, split).toLowerCase(Locale.ROOT);
        if (!Set.of("nbt", "data", "amount", "type", "name", "damage").contains(type))
            return getSection(text, null, player, null);
        NiEvaluation.Host host = NiEvaluation.current().host();
        String result =
                host == null
                        ? null
                        : host.legacyItemValue(type, text.substring(split + 2), item, nbt, data);
        return result == null ? "<" + text + ">" : result;
    }
}

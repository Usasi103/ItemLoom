package dev.itemloom.compat.ni.script;

import java.util.Map;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.compat.ni.NiYaml;
import org.bukkit.configuration.ConfigurationSection;

/** NI names are resolved to this adapter only inside a script engine. */
public final class LegacySectionUtils {
    public interface ItemView {
        Object getItemTag();

        Map<String, String> getData();
    }

    public static final LegacySectionUtils INSTANCE = new LegacySectionUtils();

    private LegacySectionUtils() {}

    public static String parseSection(
            String text, Map<String, String> cache, Object player, ConfigurationSection sections) {
        return evaluate(text, cache, player, sections, true);
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
        return evaluate(text, cache, player, sections, false);
    }

    public static String parseItemSection(
            String text, org.bukkit.inventory.ItemStack item, Object nbt, Object player) {
        if (nbt instanceof ItemView info)
            return parseItemSection(text, item, info.getItemTag(), info.getData(), player);
        return parseItemSection(text, item, nbt, null, player);
    }

    public static String parseItemSection(
            String text,
            org.bukkit.inventory.ItemStack item,
            ItemView info,
            Object player,
            Map<String, String> cache,
            ConfigurationSection sections) {
        return parseItemSection(
                text, item, info.getItemTag(), info.getData(), player, cache, sections);
    }

    public static String parseItemSection(
            String text,
            org.bukkit.inventory.ItemStack item,
            Object nbt,
            Map<String, String> data,
            Object player) {
        return parseItemSection(text, item, nbt, data, player, null, null);
    }

    public static String parseItemSection(
            String text,
            org.bukkit.inventory.ItemStack item,
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
            org.bukkit.inventory.ItemStack item,
            Object nbt,
            Map<String, String> data,
            Object player,
            Map<String, String> cache,
            ConfigurationSection sections) {
        int split = text.indexOf("::");
        if (split < 0) return getSection(text, cache, player, sections);
        String type = text.substring(0, split).toLowerCase(java.util.Locale.ROOT);
        String parameters = text.substring(split + 2);
        if (!java.util.Set.of("nbt", "data", "amount", "type", "name", "damage").contains(type))
            return getSection(text, null, player, null);
        NiEvaluation active = NiEvaluation.current();
        String value =
                active.host() == null
                        ? null
                        : active.host().legacyItemValue(type, parameters, item, nbt, data);
        return value == null ? "<" + text + ">" : value;
    }

    private static String evaluate(
            String text,
            Map<String, String> cache,
            Object player,
            ConfigurationSection sections,
            boolean template) {
        NiEvaluation active = NiEvaluation.current();
        NiConfig nodes = sections == null ? new NiConfig(Map.of()) : NiYaml.fromSection(sections);
        NiEvaluation evaluation = active.legacyContext(cache, player, nodes);
        try {
            if (template) return evaluation.text(text);
            String result = evaluation.value(text);
            return result == null ? "<" + text + ">" : result;
        } finally {
            if (cache != null && cache != evaluation.generation().rolls()) {
                cache.clear();
                cache.putAll(evaluation.generation().rolls());
            }
        }
    }
}

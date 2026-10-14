package dev.itemloom.paper.compare;

import dev.itemloom.core.compare.Comparison;
import dev.itemloom.core.compare.ComparisonModel.Attribute;
import dev.itemloom.core.compare.ComparisonModel.Bar;
import dev.itemloom.core.compare.ComparisonModel.Settings;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Independent generic configuration: no server artwork, attribute names or accessory positions. */
public final class ComparisonConfig {
    private ComparisonConfig() {}

    public static Settings read(JavaPlugin plugin) throws Exception {
        File file = new File(plugin.getDataFolder(), "comparison.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        Map<String, String> slots = new LinkedHashMap<>();
        ConfigurationSection positions = config.getConfigurationSection("slots");
        if (positions == null)
            throw new IllegalArgumentException("comparison.yml: slots must be a map");
        Set<String> sources =
                Set.of("head", "chest", "legs", "feet", "mainhand", "offhand", "provider");
        for (String id : positions.getKeys(false)) {
            String source = positions.getString(id);
            if (!Comparison.SLOT.matcher(id).matches() || !sources.contains(source)) {
                throw new IllegalArgumentException("comparison.yml: invalid slots." + id);
            }
            slots.put(id, source);
        }
        Map<String, Attribute> attributes = new LinkedHashMap<>();
        ConfigurationSection definitions = config.getConfigurationSection("attributes");
        if (definitions != null) {
            for (String id : definitions.getKeys(false)) {
                ConfigurationSection definition = definitions.getConfigurationSection(id);
                if (!Comparison.KEY.matcher(id).matches() || definition == null) {
                    throw new IllegalArgumentException("comparison.yml: invalid attributes." + id);
                }
                String provider = definition.getString("provider-key", id);
                String base = definition.getString("base-key", provider);
                boolean percent = definition.getBoolean("percent", false);
                if (provider.isBlank() || base.isBlank())
                    throw new IllegalArgumentException(
                            "comparison.yml: empty attribute key: " + id);
                attributes.put(id, new Attribute(provider, percent, base));
            }
        }
        String empty = config.getString("empty-slot", "compare");
        if (!Set.of("compare", "hide").contains(empty))
            throw new IllegalArgumentException("comparison.yml: invalid empty-slot");
        int segments = config.getInt("bar.segments", 10);
        int width = config.getInt("bar.width", 40);
        int segment = config.getInt("bar.segment", 4);
        int sweep = config.getInt("sweep-ticks", 40);
        if (segments < 1
                || segments > 25
                || width < 1
                || width > 65536
                || segment < 1
                || segment > 65536
                || sweep < 0) {
            throw new IllegalArgumentException(
                    "comparison.yml: invalid bar dimensions or sweep-ticks");
        }
        Set<String> lower = Set.copyOf(config.getStringList("lower-is-better"));
        if (lower.stream().anyMatch(key -> !Comparison.KEY.matcher(key).matches()))
            throw new IllegalArgumentException("comparison.yml: invalid lower-is-better key");
        return new Settings(
                slots,
                attributes,
                lower,
                empty.equals("hide"),
                config.getString("format.better", "<#6FDC6F>▲{diff}"),
                config.getString("format.worse", "<#FF6B6B>▼{diff}"),
                config.getString("format.same", ""),
                config.getString("format.same-item", ""),
                config.getString("format.equipped", "Equipped"),
                sweep,
                new Bar(
                        config.getBoolean("bar.enabled", false),
                        config.getString("bar.up", ""),
                        config.getString("bar.down", ""),
                        width,
                        segment,
                        segments));
    }
}

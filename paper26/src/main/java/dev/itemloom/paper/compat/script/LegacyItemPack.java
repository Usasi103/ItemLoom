package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiInheritance;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.core.GenerationBudget;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.NiConfigViews;
import dev.itemloom.paper.compat.NiItemOperations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** A pack fixes its serialized document while keeping its section values live. */
public final class LegacyItemPack {
    private final NiItemOperations owner;
    private final String id;
    private final ConfigurationSection source;
    private final List<String> items;
    private final ConfigurationSection sections;
    private final String configString;
    private final ConfigurationSection fancyDrop;
    private final String offsetX;
    private final String offsetY;
    private final String angle;
    private String previousText;
    private Plan cached;

    public LegacyItemPack(NiItemOperations owner, String id, ConfigurationSection source) {
        ItemsService.requireThread();
        this.owner = Objects.requireNonNull(owner, "owner");
        this.id = Objects.requireNonNull(id, "id");
        this.source = Objects.requireNonNull(source, "source");
        NiConfig imported =
                new NiInheritance(owner.catalog().input())
                        .packGlobals(new LegacyConfigReader.BukkitReader(source).view());
        NiConfig importedSections = imported.section("sections");
        if (importedSections != null)
            source.createSection("sections", NiConfigViews.map(importedSections));
        source.set("globalsections", null);
        sections = source.getConfigurationSection("sections");
        items = source.getStringList("Items");
        source.set("sections", null);
        YamlConfiguration document = new YamlConfiguration();
        document.set(id, source);
        configString = document.saveToString();
        fancyDrop = source.getConfigurationSection("FancyDrop");
        offsetX = fancyDrop == null ? null : fancyDrop.getString("offset.x");
        offsetY = fancyDrop == null ? null : fancyDrop.getString("offset.y");
        angle = fancyDrop == null ? null : fancyDrop.getString("angle.type");
    }

    public boolean ownedBy(NiItemOperations value) {
        return owner == value;
    }

    public String getId() {
        return id;
    }

    public ConfigurationSection getConfigSection() {
        return source;
    }

    public List<String> getItems() {
        return items;
    }

    public ConfigurationSection getSections() {
        return sections;
    }

    public String getConfigString() {
        return configString;
    }

    public ConfigurationSection getFancyDropConfig() {
        return fancyDrop;
    }

    public boolean getFancyDrop() {
        return fancyDrop != null;
    }

    public String getOffsetXString() {
        return offsetX;
    }

    public String getOffsetYString() {
        return offsetY;
    }

    public String getAngleType() {
        return angle;
    }

    public ConfigurationSection getSection() {
        return getSection(null, (Map<String, String>) null);
    }

    public ConfigurationSection getSection(OfflinePlayer player) {
        return getSection(player, (Map<String, String>) null);
    }

    public ConfigurationSection getSection(OfflinePlayer player, String data) {
        return getSection(player, LegacyItemManager.parseData(data));
    }

    public ConfigurationSection getSection(OfflinePlayer player, Map<String, String> data) {
        ConfigurationSection result = parse(render(player, data));
        return result == null ? new YamlConfiguration() : result;
    }

    public List<ItemStack> getItemStacks() {
        return getItemStacks(null, (Map<String, String>) null);
    }

    public List<ItemStack> getItemStacks(OfflinePlayer player) {
        return getItemStacks(player, (Map<String, String>) null);
    }

    public List<ItemStack> getItemStacks(OfflinePlayer player, String data) {
        return getItemStacks(player, LegacyItemManager.parseData(data));
    }

    public List<ItemStack> getItemStacks(OfflinePlayer player, Map<String, String> data) {
        return getItemStacks(player, data, null);
    }

    public List<ItemStack> getItemStacks(
            OfflinePlayer player, Map<String, String> data, GenerationBudget budget) {
        String text = render(player, data);
        Plan plan;
        if (cached != null && text.equals(previousText)) {
            plan = cached;
        } else {
            ConfigurationSection parsed = parse(text);
            if (parsed == null) {
                previousText = null;
                cached = null;
                return new ArrayList<>();
            }
            plan = Plan.of(parsed);
            cached = text.length() <= 16 * 1024 && text.equals(previousText) ? plan : null;
            previousText = text.length() <= 16 * 1024 ? text : null;
        }
        List<ItemStack> result =
                PackSelection.select(
                        plan.lines(),
                        plan.minimum(),
                        plan.maximum(),
                        line -> new ItemInfo(owner, line),
                        ItemInfo::getProbability,
                        ItemInfo::setProbability,
                        entry -> entry.draw(player, budget),
                        batch -> retain(budget, batch),
                        budget);
        owner.ensureActive();
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String render(OfflinePlayer player, Map<String, String> data) {
        owner.ensureActive();
        var context = owner.catalog().actionContext(player, null);
        context.set(
                NiContextKeys.SECTIONS,
                sections == null ? null : new LegacyConfigReader.BukkitReader(sections));
        context.set(
                NiContextKeys.SECTION_CACHE,
                (Map) (data == null ? new HashMap<String, String>() : data));
        String text = context.parse(configString);
        owner.ensureActive();
        return text;
    }

    private ConfigurationSection parse(String text) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(text);
            ConfigurationSection section = parsed.getConfigurationSection(id);
            return section == null ? new YamlConfiguration() : section;
        } catch (InvalidConfigurationException failure) {
            owner.catalog()
                    .plugin()
                    .getLogger()
                    .warning(
                            "Invalid generated item pack YAML " + id + ": " + failure.getMessage());
            return null;
        }
    }

    private static void retain(GenerationBudget budget, List<ItemStack> batch) {
        if (budget == null) return;
        long units = 0;
        long stacks = 0;
        for (ItemStack item : batch) {
            long amount = Math.max(0, item.getAmount());
            int maximum = Math.max(1, item.getMaxStackSize());
            units = Math.addExact(units, amount);
            stacks = Math.addExact(stacks, (amount + maximum - 1) / maximum);
        }
        budget.retain(units, stacks);
    }

    public static ArrayList<ItemStack> split(ItemStack item, Integer amount) {
        ArrayList<ItemStack> result = new ArrayList<>();
        if (amount == null) {
            result.add(item);
            return result;
        }
        int maximum = Math.max(1, item.getMaxStackSize());
        for (int count = 0; count < amount / maximum; count++) {
            ItemStack copy = item.clone();
            copy.setAmount(maximum);
            result.add(copy);
        }
        int remainder = amount % maximum;
        if (remainder != 0) {
            ItemStack copy = item.clone();
            copy.setAmount(remainder);
            result.add(copy);
        }
        return result;
    }

    private record Plan(List<String> lines, Integer minimum, Integer maximum) {
        private static Plan of(ConfigurationSection source) {
            List<String> lines = List.copyOf(source.getStringList("Items"));
            Integer minimum =
                    source.get("MinItems") instanceof Integer value && value > 0
                            ? Math.min(value, lines.size())
                            : null;
            Integer maximum = source.get("MaxItems") instanceof Integer value ? value : null;
            if (maximum != null && maximum > 0 && maximum >= lines.size()) maximum = null;
            return new Plan(lines, minimum, maximum);
        }
    }

    public static final class ItemInfo {
        private final NiItemOperations owner;
        private final String info;
        private final List<String> args;
        private String id;
        private int amount;
        private double probability;
        private boolean random;
        private String data;

        public ItemInfo(NiItemOperations owner, String info) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.info = Objects.requireNonNull(info, "info");
            args = Arrays.asList(info.split(" ", 5));
            id = args.getFirst();
            amount = args.size() > 1 ? amount(args.get(1)) : 1;
            probability = args.size() > 2 ? probability(args.get(2)) : 1;
            random = args.size() <= 3 || !args.get(3).equals("false");
            data = args.size() > 4 ? args.get(4) : null;
        }

        public String getInfo() {
            return info;
        }

        public List<String> getArgs() {
            return args;
        }

        public String getId() {
            return id;
        }

        public void setId(String value) {
            id = value;
        }

        public int getAmount() {
            return amount;
        }

        public void setAmount(int value) {
            amount = value;
        }

        public double getProbability() {
            return probability;
        }

        public void setProbability(double value) {
            probability = value;
        }

        public boolean getRandom() {
            return random;
        }

        public void setRandom(boolean value) {
            random = value;
        }

        public String getData() {
            return data;
        }

        public void setData(String value) {
            data = value;
        }

        public ArrayList<ItemStack> getItemStacks() {
            return getItemStacks(null);
        }

        public ArrayList<ItemStack> getItemStacks(OfflinePlayer player) {
            return draw(player, null);
        }

        private ArrayList<ItemStack> draw(OfflinePlayer player, GenerationBudget budget) {
            owner.ensureActive();
            if (ThreadLocalRandom.current().nextDouble() > probability) return new ArrayList<>();
            return generate(player, budget);
        }

        private ArrayList<ItemStack> generate(OfflinePlayer player, GenerationBudget budget) {
            owner.ensureActive();
            boolean local = owner.catalog().registry().generators().containsKey(id);
            int draws = Math.max(0, amount);
            if (budget != null) budget.work(local && random || !local && !random ? draws : 1);
            ArrayList<ItemStack> result = new ArrayList<>();
            if (local && random) {
                for (int count = 0; count < draws; count++) {
                    ItemStack item = owner.create(id, player, LegacyItemManager.parseData(data));
                    if (item != null) result.add(item);
                }
            } else {
                ItemStack prototype =
                        local
                                ? owner.create(id, player, LegacyItemManager.parseData(data))
                                : owner.catalog().itemSources().getHookedItem(args.getFirst());
                if (prototype != null) {
                    if (local || random) {
                        if (budget != null) {
                            long maximum = Math.max(1, prototype.getMaxStackSize());
                            budget.work((Math.max(0L, amount) + maximum - 1) / maximum);
                        }
                        result = split(prototype, amount);
                    } else {
                        for (int count = 0; count < draws; count++) result.add(prototype.clone());
                    }
                }
            }
            owner.ensureActive();
            return result;
        }

        static ArrayList<ItemStack> load(
                NiItemOperations owner, String line, OfflinePlayer player) {
            owner.ensureActive();
            String[] parts = line.split(" ", 5);
            double probability = parts.length > 2 ? probability(parts[2]) : 1;
            if (ThreadLocalRandom.current().nextDouble() > probability) return new ArrayList<>();
            return new ItemInfo(owner, line).generate(player, null);
        }

        private static double probability(String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }

        private static int amount(String text) {
            int dash = text.indexOf('-');
            if (dash < 0) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    return 1;
                }
            }
            int low;
            int high;
            try {
                low = Integer.parseInt(text.substring(0, dash));
                high = Integer.parseInt(text.substring(dash + 1));
            } catch (NumberFormatException ignored) {
                return 1;
            }
            return ThreadLocalRandom.current().nextInt(low, Math.addExact(high, 1));
        }
    }
}

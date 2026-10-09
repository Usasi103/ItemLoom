package dev.itemloom.paper.compat.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import dev.itemloom.compat.ni.NiInheritance;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.core.GenerationBudget;
import dev.itemloom.paper.compat.NiItemOperations;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** The original pack vocabulary on independent item generators, with no NI runtime types. */
public final class LegacyItemPack {
    private static final int MAX_CACHE_CHARACTERS = 16 * 1024;

    private record Plan(List<String> lines, Integer minimum, Integer maximum) {}

    private record CachedPlan(String source, Plan plan) {}

    private final NiItemOperations owner;
    private final String id, configString;
    private final ConfigurationSection configSection, sections, fancyDropConfig;
    private final List<String> items;
    private final String offsetXString, offsetYString, angleType;
    private String candidate;
    private CachedPlan cached;

    public LegacyItemPack(NiItemOperations owner, String id, ConfigurationSection source) {
        dev.itemloom.paper.ItemsService.requireThread();
        this.owner = Objects.requireNonNull(owner);
        this.id = Objects.requireNonNull(id);
        configSection = Objects.requireNonNull(source);
        var imported =
                NiYaml.toSection(
                        new NiInheritance(owner.catalog().input())
                                .packGlobals(NiYaml.fromSection(source)));
        configSection.set("sections", imported.getConfigurationSection("sections"));
        configSection.set("globalsections", null);
        sections = configSection.getConfigurationSection("sections");
        items = configSection.getStringList("Items");
        configSection.set("sections", null);
        YamlConfiguration wrapper = new YamlConfiguration();
        wrapper.set(id, configSection);
        configString = wrapper.saveToString();
        fancyDropConfig = configSection.getConfigurationSection("FancyDrop");
        offsetXString = fancyDropConfig == null ? null : fancyDropConfig.getString("offset.x");
        offsetYString = fancyDropConfig == null ? null : fancyDropConfig.getString("offset.y");
        angleType = fancyDropConfig == null ? null : fancyDropConfig.getString("angle.type");
    }

    public boolean ownedBy(NiItemOperations value) {
        return owner == value;
    }

    public String getId() {
        return id;
    }

    public ConfigurationSection getConfigSection() {
        return configSection;
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
        return fancyDropConfig;
    }

    public boolean getFancyDrop() {
        return fancyDropConfig != null;
    }

    public String getOffsetXString() {
        return offsetXString;
    }

    public String getOffsetYString() {
        return offsetYString;
    }

    public String getAngleType() {
        return angleType;
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
        ConfigurationSection result = readSection(render(player, data));
        return result == null ? new YamlConfiguration() : result;
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
                (Map) (data == null ? new java.util.HashMap<>() : data));
        String parsed = context.parse(configString);
        owner.ensureActive();
        return parsed;
    }

    private ConfigurationSection readSection(String parsed) {
        YamlConfiguration result = new YamlConfiguration();
        try {
            result.loadFromString(parsed);
        } catch (InvalidConfigurationException invalid) {
            owner.catalog()
                    .plugin()
                    .getLogger()
                    .warning(
                            "Invalid generated item pack YAML " + id + ": " + invalid.getMessage());
            return null;
        }
        ConfigurationSection section = result.getConfigurationSection(id);
        return section == null ? new YamlConfiguration() : section;
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

    /** Only bounded callers opt in. Public legacy entry points keep their original limits/ordering. */
    public List<ItemStack> getItemStacks(
            OfflinePlayer player, Map<String, String> data, GenerationBudget budget) {
        String parsed = render(player, data);
        Plan plan = cached != null && cached.source().equals(parsed) ? cached.plan() : null;
        List<String> lines;
        Integer minimum, maximum;
        if (plan != null) {
            lines = plan.lines();
            minimum = plan.minimum();
            maximum = plan.maximum();
        } else {
            ConfigurationSection section = readSection(parsed);
            if (section == null) {
                cached = null;
                candidate = null;
                return new ArrayList<>();
            }
            lines = section.getStringList("Items");
            minimum =
                    section.get("MinItems") instanceof Integer number && number > 0
                            ? Math.min(number, lines.size())
                            : null;
            maximum = section.get("MaxItems") instanceof Integer number ? number : null;
            if (maximum != null && maximum > 0 && maximum >= lines.size()) maximum = null;
            remember(parsed, lines, minimum, maximum);
        }
        ArrayList<ItemStack> result = new ArrayList<>();
        if (maximum != null && maximum <= 0) return result;
        if (minimum == null) {
            int successes = 0;
            for (String raw : lines)
                for (String line : raw.split("\n", -1)) {
                    if (budget != null) budget.work(1);
                    var generated = new ItemInfo(owner, line).getItemStacks(player, budget);
                    if (maximum == null) retain(result, generated, budget);
                    else if (!generated.isEmpty()) {
                        // NI evaluates one extra successful entry before stopping at the maximum.
                        if (successes >= maximum) return result;
                        retain(result, generated, budget);
                        successes++;
                    }
                }
            return result;
        }
        // NI traverses an identity-keyed HashMap in this branch. Preserving input order
        // would bias a capped pack toward its first line even when every line succeeds.
        Map<ItemInfo, Double> candidates = new HashMap<>();
        for (String raw : lines)
            for (String line : raw.split("\n", -1)) {
                if (budget != null) budget.work(1);
                ItemInfo entry = new ItemInfo(owner, line);
                if (maximum == null || entry.probability > 0)
                    candidates.put(entry, entry.probability * 100000);
            }
        var remaining = new HashMap<>(candidates);
        double total = remaining.values().stream().mapToDouble(Double::doubleValue).sum();
        for (int pick = 0; pick < minimum; pick++) {
            if (budget != null) budget.work(remaining.size());
            double point = ThreadLocalRandom.current().nextDouble() * total, weight = 0;
            ItemInfo selected = null;
            for (var entry : remaining.entrySet()) {
                weight += entry.getValue();
                if (point <= weight) {
                    selected = entry.getKey();
                    break;
                }
            }
            if (selected != null) {
                total -= remaining.remove(selected);
                selected.probability = 1;
            }
        }
        int successes = 0;
        for (ItemInfo entry : candidates.keySet()) {
            var generated = entry.getItemStacks(player, budget);
            if (maximum == null) retain(result, generated, budget);
            else if (!generated.isEmpty() && successes < maximum) {
                retain(result, generated, budget);
                successes++;
            }
        }
        return result;
    }

    /** Never share Bukkit sections or randomized ItemInfo instances with the next request. */
    private void remember(String parsed, List<String> lines, Integer minimum, Integer maximum) {
        if (parsed.length() <= MAX_CACHE_CHARACTERS) {
            // Unique expansions use their local list directly: only admitted plans need a detached
            // copy.
            cached =
                    parsed.equals(candidate)
                            ? new CachedPlan(parsed, new Plan(List.copyOf(lines), minimum, maximum))
                            : null;
            candidate = parsed;
        } else {
            cached = null;
            candidate = null;
        }
    }

    private static void retain(
            List<ItemStack> result, List<ItemStack> additions, GenerationBudget budget) {
        if (budget != null)
            for (ItemStack item : additions) {
                long count = Math.max(0, item.getAmount()),
                        size = Math.max(1, item.getMaxStackSize());
                budget.retain(count, (count + size - 1) / size);
            }
        result.addAll(additions);
    }

    public static final class ItemInfo {
        private final NiItemOperations owner;
        private final String info;
        private final List<String> args;
        private String id, data;
        private int amount;
        private double probability;
        private boolean random;

        public ItemInfo(NiItemOperations owner, String info) {
            this.owner = owner;
            this.info = info;
            args = Arrays.asList(info.split(" ", 5));
            id = args.getFirst();
            amount = amount(arg(1));
            try {
                probability = arg(2) == null ? 1 : Double.parseDouble(arg(2));
            } catch (NumberFormatException invalid) {
                probability = 1;
            }
            random = !"false".equals(arg(3));
            data = arg(4);
        }

        private String arg(int index) {
            return index < args.size() ? args.get(index) : null;
        }

        private static int amount(String text) {
            if (text == null) return 1;
            int separator = text.indexOf('-');
            if (separator < 0) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException invalid) {
                    return 1;
                }
            }
            int min, max;
            try {
                min = Integer.parseInt(text.substring(0, separator));
                max = Integer.parseInt(text.substring(separator + 1));
            } catch (NumberFormatException invalid) {
                return 1;
            }
            return ThreadLocalRandom.current().nextInt(min, max + 1);
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
            return getItemStacks(player, null);
        }

        private ArrayList<ItemStack> getItemStacks(OfflinePlayer player, GenerationBudget budget) {
            owner.ensureActive();
            if (ThreadLocalRandom.current().nextDouble() > probability) return new ArrayList<>();
            return generate(player, budget);
        }

        /** ItemUtils checks probability before parsing the amount, unlike the pack constructor. */
        static ArrayList<ItemStack> load(
                NiItemOperations owner, String line, OfflinePlayer player) {
            owner.ensureActive();
            String[] args = line.split(" ", 5);
            if (args.length > 2) {
                try {
                    if (ThreadLocalRandom.current().nextDouble() > Double.parseDouble(args[2]))
                        return new ArrayList<>();
                } catch (NumberFormatException ignored) {
                }
            }
            return new ItemInfo(owner, line).generate(player, null);
        }

        private ArrayList<ItemStack> generate(OfflinePlayer player, GenerationBudget budget) {
            ArrayList<ItemStack> result = new ArrayList<>();
            if (owner.catalog().registry().generators().containsKey(id)) {
                if (random) {
                    if (budget != null) budget.work(Math.max(0, amount));
                    for (int i = 0; i < amount; i++) {
                        ItemStack item =
                                owner.create(id, player, LegacyItemManager.parseData(data));
                        if (item != null) result.add(item);
                    }
                } else {
                    if (budget != null) budget.work(1);
                    ItemStack item = owner.create(id, player, LegacyItemManager.parseData(data));
                    if (item != null) result.addAll(split(item, amount, budget));
                }
            } else {
                // NI's fallback deliberately uses the original argument even after setId().
                if (budget != null) budget.work(1);
                ItemStack item = owner.catalog().itemSources().getHookedItem(args.getFirst());
                if (item != null) {
                    if (random) result.addAll(split(item, amount, budget));
                    else {
                        if (budget != null) budget.work(Math.max(0, amount));
                        for (int i = 0; i < amount; i++) result.add(item.clone());
                    }
                }
            }
            owner.ensureActive();
            return result;
        }
    }

    /** Independent stack copies prevent one list entry's later edit changing its siblings. */
    public static ArrayList<ItemStack> split(ItemStack item, Integer amount) {
        return split(item, amount, null);
    }

    private static ArrayList<ItemStack> split(
            ItemStack item, Integer amount, GenerationBudget budget) {
        ArrayList<ItemStack> result = new ArrayList<>();
        if (amount == null) {
            result.add(item);
            return result;
        }
        int maximum = Math.max(1, item.getMaxStackSize());
        if (budget != null) budget.work((Math.max(0L, amount.longValue()) + maximum - 1) / maximum);
        for (int i = 0; i < amount / maximum; i++) {
            ItemStack stack = item.clone();
            stack.setAmount(maximum);
            result.add(stack);
        }
        if (amount % maximum != 0) {
            ItemStack stack = item.clone();
            stack.setAmount(amount % maximum);
            result.add(stack);
        }
        return result;
    }
}

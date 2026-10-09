package dev.itemloom.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemPack;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Pack semantics on actual Paper, optionally compared with NI through its own class loader. */
final class ItemPackProbe {
    private static final String PACK =
            """
            base:
              Items: ['<material> 130 1 true']
              sections: {material: STONE}
              FancyDrop: {offset: {x: '0.1-0.2', y: '0.3'}, angle: {type: round}}
            """;

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin, Plugin reference) {
        Checks c = new Checks(reference);
        c.group(
                "sections-and-aliases",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        sections(c, f);
                    }
                });
        c.group(
                "item-lines",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        lines(c, f);
                    }
                });
        c.group(
                "load-items",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        loaders(c, f);
                    }
                });
        c.group(
                "limits",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        limits(c, f);
                    }
                });
        c.group(
                "cache-and-budget",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        cacheAndBudget(c, f);
                    }
                });
        c.group(
                "registry",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        registry(c, f);
                    }
                });
        c.group(
                "service",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        service(c, f);
                    }
                });
        c.group(
                "providers",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        providers(c, f);
                    }
                });
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        try {
            Fixture f = new Fixture(plugin);
            var pack = f.catalog.packs().getItemPack("base");
            var map = f.catalog.packs().getItemPacks();
            Bukkit.getScheduler()
                    .runTaskAsynchronously(
                            plugin,
                            () -> {
                                boolean rejected =
                                        rejects(pack::getItemStacks)
                                                && rejects(pack::getSection)
                                                && rejects(f.catalog.packs()::reload)
                                                && rejects(() -> map.put("worker", pack));
                                Bukkit.getScheduler()
                                        .runTask(
                                                plugin,
                                                () -> {
                                                    c.group(
                                                            "worker-and-close",
                                                            () -> {
                                                                c.that(
                                                                        rejected
                                                                                && !map.containsKey(
                                                                                        "worker"),
                                                                        "worker generation, reload and map writes reject without blocking");
                                                                f.catalog.close();
                                                                c.that(
                                                                        rejects(pack::getItemStacks)
                                                                                && rejects(
                                                                                        f.catalog
                                                                                                        .packs()
                                                                                                ::reload)
                                                                                && rejects(
                                                                                        () ->
                                                                                                map
                                                                                                        .put(
                                                                                                                "closed",
                                                                                                                pack)),
                                                                        "retained pack and map reject after owner close");
                                                            });
                                                    c.group("cleanup", f::close);
                                                    result.complete(c.report());
                                                });
                            });
        } catch (Throwable error) {
            c.failures.put("async-setup", error.toString());
            result.complete(c.report());
        }
        return result;
    }

    private static void sections(Checks c, Fixture f) throws Exception {
        var pack = f.catalog.packs().getItemPack("base");
        c.that(
                pack.getId().equals("base")
                        && pack.getItems().equals(List.of("<material> 130 1 true")),
                "pack getters retain original item lines");
        c.that(
                pack.getFancyDrop()
                        && pack.getOffsetXString().equals("0.1-0.2")
                        && pack.getOffsetYString().equals("0.3")
                        && pack.getAngleType().equals("round"),
                "FancyDrop exposes original parameters without applying a drop");
        c.that(
                pack.getConfigSection().get("sections") == null
                        && pack.getSections().getString("material").equals("STONE"),
                "sections are retained separately from the rendered pack configuration");
        c.that(
                pack.getSection().getStringList("Items").equals(List.of("STONE 130 1 true")),
                "no-player section renders action nodes");
        var cache = new HashMap<String, String>();
        pack.getSection(null, cache);
        c.that(
                cache.get("material").equals("STONE"),
                "legacy map overload fills caller section cache");
        c.that(
                pack.getSection(null, "{\"material\":\"DIRT\"}")
                        .getStringList("Items")
                        .equals(List.of("DIRT 130 1 true")),
                "JSON cache overrides the node");
        pack.getSections().set("material", "DIAMOND");
        pack.getConfigSection().set("Items", List.of("APPLE"));
        c.that(
                pack.getSection().getStringList("Items").equals(List.of("DIAMOND 130 1 true")),
                "mutable sections are live but rendered YAML is fixed at construction");
        var imported =
                f.pack(
                        "Items: ['<shared>']\nglobalsections: [shared, missing]\nsections: {shared: local}\n");
        c.that(
                imported.getSection().getStringList("Items").equals(List.of("global-value"))
                        && imported.getConfigSection().get("globalsections") == null,
                "global import overrides local node and missing pack import is ignored");
        c.that(
                f.pack("Items: [STONE]\n")
                        .getSection()
                        .getStringList("Items")
                        .equals(List.of("STONE")),
                "absent sections is valid");
        c.that(
                f.pack("Items: ['<bad>']\nsections: {bad: '['}\n")
                        .getSection()
                        .getKeys(false)
                        .isEmpty(),
                "invalid generated YAML returns an empty section");
        Object aliases =
                f.catalog
                        .actionContext(null, Map.of("configuration", yaml("Items: [STONE]\n")))
                        .evaluate(
                                """
                var Pack = Java.type('pers.neige.neigeitems.item.ItemPack');
                var Info = Java.type('pers.neige.neigeitems.item.ItemPack$ItemInfo');
                var manager = Java.type('pers.neige.neigeitems.manager.ItemPackManager');
                var made = new Pack('custom', configuration);
                made.getItemStacks().size() == 1 && manager.INSTANCE == manager
                    && manager.getItemPack('base') != null && manager.getItemPack('custom') == null
                    && new Info('STONE 2').getAmount() == 2
                    && new Pack.ItemInfo('STONE 3').getAmount() == 3
                    && new Packages.pers.neige.neigeitems.item.ItemPack.ItemInfo('STONE 4').getAmount() == 4;
                """);
        c.that(
                Boolean.TRUE.equals(aliases),
                "Java.type, Packages, nested ItemInfo and singleton manager work in unmodified JS imports");
        c.that(
                Files.readString(f.packFile).equals(PACK),
                "construction and rendering preserve source bytes");
    }

    private static void lines(Checks c, Fixture f) throws Exception {
        for (String line :
                List.of(
                        "STONE",
                        "vn:STONE 130 1 true",
                        "vanilla:STONE 3 1 false",
                        "STONE 0",
                        "STONE bad nope FALSE",
                        "STONE -5",
                        "STONE 2-2",
                        "STONE 1 -1",
                        "STONE 2 NaN",
                        "STONE 2 Infinity",
                        "not_an_item")) {
            var actual = new LegacyItemPack.ItemInfo(f.catalog.items(), line).getItemStacks();
            if (c.reference != null) {
                Class<?> type =
                        c.reference
                                .getClass()
                                .getClassLoader()
                                .loadClass("pers.neige.neigeitems.item.ItemPack$ItemInfo");
                Object old = type.getConstructor(String.class).newInstance(line);
                c.same(
                        actual,
                        (List<?>) type.getMethod("getItemStacks").invoke(old),
                        "ItemInfo " + line);
            } else c.that(actual != null, "independent ItemInfo " + line);
        }
        c.that(
                rejects(() -> new LegacyItemPack.ItemInfo(f.catalog.items(), "STONE 5-2")),
                "valid numbers in a reversed range reject");
        var split = new LegacyItemPack.ItemInfo(f.catalog.items(), "STONE 130").getItemStacks();
        c.that(
                signature(split).equals(List.of("STONE:64", "STONE:64", "STONE:2")),
                "external random=true splits requested count");
        split.getFirst().setAmount(7);
        c.that(
                split.get(1).getAmount() == 64 && split.get(2).getAmount() == 2,
                "explicit correction: full stacks are independent objects");
        var singles =
                new LegacyItemPack.ItemInfo(f.catalog.items(), "STONE 3 1 false").getItemStacks();
        singles.getFirst().setAmount(9);
        c.that(
                singles.size() == 3 && singles.get(1).getAmount() == 1,
                "external random=false preserves single-unit count with independent objects");
        var mutable = new LegacyItemPack.ItemInfo(f.catalog.items(), "STONE 2");
        mutable.setId("DIAMOND");
        mutable.setAmount(4);
        mutable.setProbability(1);
        mutable.setRandom(false);
        mutable.setData("{}");
        c.that(
                mutable.getInfo().equals("STONE 2")
                        && mutable.getArgs().equals(List.of("STONE", "2"))
                        && mutable.getId().equals("DIAMOND")
                        && mutable.getData().equals("{}")
                        && signature(mutable.getItemStacks())
                                .equals(List.of("STONE:1", "STONE:1", "STONE:1", "STONE:1")),
                "ItemInfo setters retain original fallback argument semantics");
        f.resetCount();
        var random =
                new LegacyItemPack.ItemInfo(f.catalog.items(), "counted 3 1 true").getItemStacks();
        c.that(
                random.size() == 3 && f.count() == 3,
                "NI item random=true generates once per requested unit");
        f.resetCount();
        var once =
                new LegacyItemPack.ItemInfo(f.catalog.items(), "counted 130 1 false")
                        .getItemStacks();
        c.that(
                signature(once).equals(List.of("STONE:64", "STONE:64", "STONE:2"))
                        && f.count() == 1,
                "NI item random=false generates once then splits");
        c.that(
                new LegacyItemPack.ItemInfo(f.catalog.items(), "STONE 0").getItemStacks().isEmpty(),
                "zero amount produces no stack");
    }

    private static void limits(Checks c, Fixture f) throws Exception {
        for (String config :
                List.of(
                        "Items: [STONE, DIRT]\n",
                        "Items: [STONE, DIRT]\nMaxItems: 0\n",
                        "Items: [STONE, DIRT, APPLE]\nMaxItems: 1\n",
                        "Items: [STONE, DIRT]\nMaxItems: '1'\n",
                        "Items: ['STONE 130', DIRT]\nMaxItems: 1\n",
                        "Items: ['STONE 2 1 false', DIRT]\nMaxItems: 1\n",
                        "Items: ['STONE 1 -1', 'DIRT 1 -1']\nMinItems: 2\nMaxItems: 1\n")) {
            var actual = f.pack(config).getItemStacks();
            if (c.reference != null) c.same(actual, reference(c.reference, config), config.trim());
            else c.that(actual != null, "independent limits " + config.trim());
        }
        c.that(
                f.pack("Items: ['STONE 1 0', 'DIRT 1 0']\nMinItems: 2\n").getItemStacks().size()
                        == 2,
                "minimum-only retains and guarantees zero-weight entries");
        c.that(
                f.pack("Items: ['STONE 1 0', 'DIRT 1 0']\nMinItems: 2\nMaxItems: 1\n")
                        .getItemStacks()
                        .isEmpty(),
                "minimum plus effective maximum excludes zero weights");
        c.that(
                f.pack("Items: [\"STONE 1 0\\nDIRT 1 0\"]\nMinItems: 2\n").getItemStacks().size()
                        == 1,
                "minimum clamps to raw list size before newline expansion");
        c.that(
                f.pack("Items: [\"STONE\\nDIRT\"]\nMaxItems: 1\n").getItemStacks().size() == 2,
                "maximum equal to raw size becomes unlimited before newline expansion");
        for (int i = 0; i < 100; i++) {
            int count =
                    f.pack(
                                    "Items: ['STONE 1 0.2', 'DIRT 1 0.6', 'APPLE 1 0.8']\nMinItems: 1\nMaxItems: 2\n")
                            .getItemStacks()
                            .size();
            if (count < 1 || count > 2) throw new AssertionError("weighted sample count=" + count);
        }
        c.that(true, "100 weighted samples stay within successful-entry limits");
        var cappedTypes = new java.util.HashSet<Material>();
        for (int i = 0; i < 128; i++)
            cappedTypes.add(
                    f.pack("Items: [STONE, DIRT, APPLE]\nMinItems: 1\nMaxItems: 1\n")
                            .getItemStacks()
                            .getFirst()
                            .getType());
        c.that(
                cappedTypes.size() > 1,
                "minimum plus cap preserves unordered candidate traversal instead of always choosing the first YAML line");
        f.resetCount();
        var limited = f.pack("Items: [counted, counted, counted]\nMaxItems: 1\n").getItemStacks();
        c.that(
                limited.size() == 1 && f.count() == 2,
                "maximum-only preserves NI extra successful generation before break");
        f.resetCount();
        limited =
                f.pack("Items: [counted, counted, counted]\nMinItems: 1\nMaxItems: 1\n")
                        .getItemStacks();
        c.that(
                limited.size() == 1 && f.count() == 3,
                "minimum branch evaluates every candidate even after maximum");
    }

    private static void cacheAndBudget(Checks c, Fixture f) throws Exception {
        var pack = f.pack("Items: ['<material> 2']\nsections: {material: STONE}\n");
        for (int i = 0; i < 4; i++) pack.getItemStacks();
        pack.getSection().set("Items", List.of("DIAMOND 999"));
        pack.getItemStacks().getFirst().setAmount(99);
        c.that(
                signature(pack.getItemStacks()).equals(List.of("STONE:2")),
                "cached plan shares neither public sections nor generated stacks");
        pack.getSections().set("material", "DIAMOND");
        c.that(
                signature(pack.getItemStacks()).equals(List.of("DIAMOND:2")),
                "live sections invalidate repeated expanded text");
        var cache = new HashMap<String, String>();
        pack.getItemStacks(null, cache);
        c.that(
                cache.get("material").equals("DIAMOND"),
                "warm plan still evaluates nodes and fills caller cache");
        c.that(
                signature(pack.getItemStacks(null, Map.of("material", "DIRT")))
                        .equals(List.of("DIRT:2")),
                "per-call node input survives cached plans");
        var structure = f.pack("Items:\n- <line>\n");
        c.that(
                signature(structure.getItemStacks(null, Map.of("line", "STONE\n  - DIRT")))
                        .equals(List.of("STONE:1", "DIRT:1")),
                "generated multiline YAML structure retains full-document parsing");
        var random = f.pack("Items: ['STONE 1-3']\n");
        var amounts = new java.util.HashSet<Integer>();
        for (int i = 0; i < 100; i++) amounts.add(random.getItemStacks().getFirst().getAmount());
        c.that(
                amounts.equals(java.util.Set.of(1, 2, 3)),
                "warm plan rerolls amount ranges for every request");
        f.resetCount();
        var huge = f.pack("Items: ['counted 1000000 1 true']\n");
        c.that(
                rejects(() -> huge.getItemStacks(null, null, budget())) && f.count() == 0,
                "huge native count rejected before first generator callback");
        for (String text :
                List.of(
                        "STONE 1000000000 1 true",
                        "STONE 1000000000 1 false",
                        "counted 1000000000 1 false")) {
            var value = f.pack("Items: ['" + text + "']\n");
            c.that(
                    rejects(() -> value.getItemStacks(null, null, budget())),
                    "bounded splitting/clone generation rejects " + text);
        }
        var capped = f.pack("Items: [counted, counted, counted]\nMaxItems: 1\n");
        f.resetCount();
        c.that(
                capped.getItemStacks(null, null, budget()).size() == 1 && f.count() == 2,
                "budget preserves extra successful evaluation without counting discarded reward");
        var regular = f.pack("Items: ['STONE 300 1 false']\n");
        c.that(
                regular.getItemStacks().size() == 300
                        && rejects(() -> regular.getItemStacks(null, null, budget())),
                "legacy API remains unlimited while bounded caller enforces final stacks");
        var large = f.pack("Items: [STONE]\npadding: '" + "x".repeat(17_000) + "'\n");
        large.getItemStacks();
        large.getItemStacks();
        large.getItemStacks();
        var field = LegacyItemPack.class.getDeclaredField("cached");
        field.setAccessible(true);
        c.that(field.get(large) == null, "large expanded documents do not remain cached");
    }

    private static dev.itemloom.core.GenerationBudget budget() {
        return new dev.itemloom.core.GenerationBudget(4096, 4096, 256);
    }

    private static void loaders(Checks c, Fixture f) throws Exception {
        var utils =
                new dev.itemloom.paper.compat.script.LegacyItemUtils(
                        item -> item.getType().name(), f.catalog.items());
        for (String line :
                List.of(
                        "STONE 130",
                        "STONE 3 1 false",
                        "STONE 3-1 -1",
                        "STONE bad bad",
                        "invalid_material")) {
            var actual = utils.loadItems(line);
            if (c.reference != null) {
                var type =
                        c.reference
                                .getClass()
                                .getClassLoader()
                                .loadClass("pers.neige.neigeitems.utils.ItemUtils");
                c.same(
                        actual,
                        (List<?>) type.getMethod("loadItems", String.class).invoke(null, line),
                        "loadItems " + line);
            } else c.that(actual != null, "independent loadItems " + line);
        }
        c.that(
                utils.loadItems(List.of("STONE\nDIRT")).size() == 2,
                "returned list loader splits newline entries");
        var cache = new HashMap<String, String>();
        var into = new ArrayList<ItemStack>();
        utils.loadItems(into, List.of("<material> 2"), null, cache, yaml("material: STONE"));
        c.that(
                signature(into).equals(List.of("STONE:2")) && cache.get("material").equals("STONE"),
                "append list loader parses sections and shares supplied cache");
        c.that(
                utils.loadItems(List.of("<material> 2")).isEmpty(),
                "returned list loader does not parse nodes by default");
        utils.loadItems(into, "DIRT");
        c.that(
                into.size() == 2 && into.getLast().getType() == Material.DIRT,
                "single line append preserves existing entries");
        c.that(
                Boolean.TRUE.equals(
                        f.catalog
                                .actionContext(null, null)
                                .evaluate(
                                        """
                var U = Java.type('pers.neige.neigeitems.utils.ItemUtils');
                var ArrayList = Java.type('java.util.ArrayList');
                var into = new ArrayList(); var lines = new ArrayList(); lines.add('STONE 3');
                U.loadItems(into, lines, null);
                U.loadItems(into, 'DIRT', null);
                U.loadItems('APPLE', null).size() == 1 && into.size() == 2;
                """)),
                "legacy JS loader overloads resolve with null viewer");
    }

    private static void registry(Checks c, Fixture f) throws Exception {
        var registry = f.catalog.packs();
        var map = registry.getItemPacks();
        var old = map.get("base");
        var publication = f.catalog.registry().publicationToken();
        Files.writeString(f.packFile, "replacement: {Items: [DIAMOND]}\nalpha: {Items: [APPLE]}\n");
        registry.reload();
        c.that(
                map == registry.itemPacks
                        && registry.getItemPackIds().equals(List.of("alpha", "replacement")),
                "local reload publishes into stable map and sorted IDs");
        c.that(
                f.catalog.registry().publicationToken() == publication
                        && old.getItemStacks().size() == 3,
                "local pack reload leaves item publication and old pack usable");
        var replacement = map.get("replacement");
        Files.writeString(f.packFile, "broken: [\n");
        c.that(
                rejects(registry::reload)
                        && map.get("replacement") == replacement
                        && map.size() == 2,
                "invalid source leaves previous complete pack directory");
        map.put("alias", old);
        c.that(
                registry.getItemPack("alias") == old,
                "same-owner dynamic map registration is visible");
        try (Fixture other = new Fixture(f.plugin)) {
            c.that(
                    rejects(() -> map.put("foreign", other.catalog.packs().getItemPack("base"))),
                    "cross-revision pack registration rejects");
        }
        c.that(
                Files.readString(f.packFile).equals("broken: [\n"),
                "reload failure does not repair or overwrite source");
    }

    private static void service(Checks c, Fixture f) throws Exception {
        try (ItemsService service =
                new ItemsService(f.plugin, f.root, (p, s) -> null, f.root.resolve("ledger.json"))) {
            c.that(
                    service.reload(Bukkit.getConsoleSender()) && service.packIds().contains("base"),
                    "independent API exposes loaded pack IDs");
            var cache = new HashMap<String, String>();
            c.that(
                    service.createPack("base", null, cache).size() == 3 && cache.isEmpty(),
                    "independent createPack isolates caller cache");
            c.that(
                    rejects(() -> service.createPack("missing", null, Map.of())),
                    "independent API reports unknown pack");
            Object revision = service.placeholderRevision();
            Files.writeString(f.packFile, "bad: [\n");
            c.that(
                    !service.reload(Bukkit.getConsoleSender())
                            && service.placeholderRevision() == revision
                            && service.createPack("base", null, Map.of()).size() == 3,
                    "full reload failure retains usable item and pack catalog");
        }
    }

    private static void providers(Checks c, Fixture f) {
        var sources = f.catalog.itemSources();
        c.that(
                sources.getHookedItem("stone").getType() == Material.STONE
                        && sources.getHookedItem("vn:DIRT").getType() == Material.DIRT
                        && sources.getHookedItem("vanilla:APPLE").getType() == Material.APPLE,
                "vanilla lookup supports bare and explicit namespaces");
        c.that(
                sources.getHookedItem("ti_probe_missing") == null,
                "missing provider item is absent");
        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            ItemStack item = sources.getHookedItem("mm:ILProbeStone");
            c.that(
                    item != null && item.getType() == Material.STONE,
                    "actual Mythic API resolves explicit mm namespace");
            item.setAmount(37);
            c.that(
                    sources.getHookedItem("ILProbeStone").getAmount() == 1,
                    "Mythic fallback and clone leave provider item untouched");
            c.that(
                    sources.getHookedItem("mythicmobs:ILProbeStone") != null,
                    "full Mythic namespace resolves");
        }
    }

    private static List<?> reference(Plugin plugin, String config) throws Exception {
        Class<?> type =
                plugin.getClass().getClassLoader().loadClass("pers.neige.neigeitems.item.ItemPack");
        Object pack =
                type.getConstructor(String.class, ConfigurationSection.class)
                        .newInstance("probe", yaml(config));
        return (List<?>) type.getMethod("getItemStacks").invoke(pack);
    }

    private static ConfigurationSection yaml(String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private static List<String> signature(List<?> items) {
        return items.stream()
                .map(
                        value -> {
                            var item = (ItemStack) value;
                            return item.getType() + ":" + item.getAmount();
                        })
                .toList();
    }

    private static boolean rejects(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static final class Checks {
        final Plugin reference;
        final List<String> assertions = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();
        int comparisons;

        Checks(Plugin reference) {
            this.reference = reference;
        }

        void that(boolean success, String message) {
            if (!success) throw new AssertionError(message);
            assertions.add(message);
        }

        void same(List<?> actual, List<?> expected, String message) {
            comparisons++;
            that(signature(actual).equals(signature(expected)), "NI comparison: " + message);
        }

        void group(String name, Checked operation) {
            try {
                operation.run();
            } catch (Throwable error) {
                failures.put(name, error.toString());
            }
        }

        Map<String, Object> report() {
            return Map.of(
                    "passed",
                    failures.isEmpty(),
                    "checks",
                    assertions.size(),
                    "assertions",
                    assertions,
                    "failures",
                    failures,
                    "referenceComparisons",
                    comparisons,
                    "referencePresent",
                    reference != null,
                    "realClient",
                    false,
                    "corrections",
                    List.of("Independent ItemStack copies replace NI shared references"),
                    "unverifiedProviders",
                    List.of("ItemsAdder", "Oraxen", "MagicGem"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Path root, packFile;
        final PlayerActionState players = new PlayerActionState();
        final NiCatalog catalog;
        final String key = "itemloom.pack.probe." + java.util.UUID.randomUUID();

        Fixture(JavaPlugin plugin) throws Exception {
            this.plugin = plugin;
            root =
                    Files.createTempDirectory("itemloom-item-pack-probe-")
                            .toAbsolutePath()
                            .normalize();
            packFile = Files.createDirectories(root.resolve("ItemPacks")).resolve("packs.yml");
            Files.writeString(packFile, PACK);
            Files.writeString(
                    Files.createDirectories(root.resolve("GlobalSections")).resolve("globals.yml"),
                    "shared: global-value\n");
            Files.writeString(
                    Files.createDirectories(root.resolve("Items")).resolve("items.yml"),
                    """
                    counted:
                      material: STONE
                      event:
                        post-generate:
                          actions: "js: var S = Java.type('java.lang.System'); S.setProperty('%s', String(Number(S.getProperty('%s', '0')) + 1));"
                    """
                            .formatted(key, key));
            catalog =
                    new NiCatalog(
                            1,
                            new NiRepository().read(root),
                            root,
                            plugin,
                            (p, s) -> null,
                            players);
        }

        LegacyItemPack pack(String text) throws Exception {
            return new LegacyItemPack(catalog.items(), "probe", yaml(text));
        }

        void resetCount() {
            System.clearProperty(key);
        }

        int count() {
            return Integer.parseInt(System.getProperty(key, "0"));
        }

        @Override
        public void close() throws Exception {
            catalog.close();
            players.close();
            resetCount();
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root))
                        throw new IllegalStateException("Cleanup escaped owned fixture");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}

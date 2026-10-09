package dev.itemloom.probe;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemConfig;
import dev.itemloom.paper.compat.script.LegacyItemConstructors;
import dev.itemloom.paper.compat.script.LegacyItemGenerateEvent;
import dev.itemloom.paper.compat.script.LegacyItemGenerator;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Independent dynamic registries, fixed generator ownership and actual public service visibility. */
final class ItemRegistryProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String YAML =
            """
            base:
              material: STONE
              name: '<seed>'
              sections: {seed: original}
              options: {update: {enable: true}}
              event:
                post-generate:
                  actions: "js: data.put('post_id', String(item.getId())); data.put('post_material', String(item.getConfigSection().getString('material')));"
            second:
              material: APPLE
            """;

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item registry probe requires the server thread");
        Checks checks = new Checks();
        checks.group(
                "separate-maps",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        separate(checks, f);
                    }
                });
        checks.group(
                "constructors",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        constructors(checks, f);
                    }
                });
        checks.group(
                "fixed-bindings",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        bindings(checks, f);
                    }
                });
        checks.group(
                "views",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        views(checks, f);
                    }
                });
        checks.group(
                "maintenance",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        maintenance(checks, f);
                    }
                });
        checks.group("service", () -> service(checks, plugin));
        checks.group("closed-and-foreign", () -> closed(checks, plugin));

        CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        Fixture worker = new Fixture(plugin);
        var map = worker.manager.getItems();
        var generator = map.get("base");
        var configFactory = LegacyItemConstructors.config(worker.catalog.items());
        var generatorFactory = LegacyItemConstructors.generator(worker.catalog.items());
        CompletableFuture<List<Boolean>> rejected = new CompletableFuture<>();
        Bukkit.getScheduler()
                .runTaskAsynchronously(
                        plugin,
                        () -> {
                            try {
                                rejected.complete(
                                        List.of(
                                                rejects(() -> map.put("worker", generator)),
                                                rejects(() -> map.entrySet().clear()),
                                                rejects(
                                                        () ->
                                                                worker.catalog.compile(
                                                                        generator.getItemConfig())),
                                                rejects(
                                                        () ->
                                                                generator.getItemStack(
                                                                        worker.player,
                                                                        new HashMap<>())),
                                                rejects(
                                                        () ->
                                                                configFactory.newObject(
                                                                        "base",
                                                                        generator.getFile())),
                                                rejects(
                                                        () ->
                                                                generatorFactory.newObject(
                                                                        generator
                                                                                .getItemConfig()))));
                            } catch (Throwable error) {
                                rejected.completeExceptionally(error);
                            }
                        });
        rejected.whenComplete(
                (values, error) ->
                        Bukkit.getScheduler()
                                .runTask(
                                        plugin,
                                        () -> {
                                            try {
                                                if (error != null)
                                                    checks.failures.put("worker", error.toString());
                                                else {
                                                    checks.that(
                                                            values.stream()
                                                                    .allMatch(
                                                                            Boolean::booleanValue),
                                                            "worker map writes, compilation, generation and old constructors reject without scheduling a blocking main-thread wait");
                                                    checks.that(
                                                            !map.containsKey("worker")
                                                                    && map.size() == 2,
                                                            "rejected worker operations leave the registry unchanged");
                                                }
                                            } catch (Throwable failure) {
                                                checks.failures.put("worker", failure.toString());
                                            } finally {
                                                worker.close();
                                            }
                                            report.complete(checks.report());
                                        }));
        return report;
    }

    private static void separate(Checks c, Fixture f) {
        var originals = f.manager.getItemConfigs();
        var generators = f.manager.getItems();
        var base = generators.get("base");
        c.that(
                originals == f.manager.getItemConfigs() && generators == f.manager.getItems(),
                "both maps are stable live views");
        c.that(
                base.getItemConfig() == originals.get("base")
                        && base.getFile().equals(f.root.resolve("Items/registry.yml").toFile()),
                "generator exposes the real original configuration object and supplied source-root file");
        c.that(
                originals.get("base").getConfigSection() == f.manager.getRealOriginConfig("base")
                        && "base".equals(originals.get("base").getConfigSection().getName()),
                "original configuration retains its named child section within the containing file");
        var removed = originals.remove("base");
        c.that(
                f.manager.hasItem("base")
                        && f.manager.getItemStack("base").getType() == Material.STONE
                        && !f.manager.getItemIds().contains("base")
                        && f.manager.getRealOriginConfig("base") == null,
                "removing original configuration changes original IDs without removing its live generator");
        originals.put("base", removed);
        generators.remove("base");
        c.that(
                !f.manager.hasItem("base")
                        && f.manager.getItemStack("base") == null
                        && f.manager.getItemIds().contains("base")
                        && f.manager.getItemAmount() == 2
                        && f.manager.getRealOriginConfig("base") != null,
                "removing a generator leaves its original configuration and original item count intact");
        c.that(
                base.getItemStack(f.player, new HashMap<>()).getType() == Material.STONE,
                "removed generator object remains usable within its active revision");
        generators.put("other-key", base);
        c.that(
                f.manager.hasItem("other-key")
                        && !f.manager.getItemIdsRaw().contains("other-key")
                        && f.manager.getRealOriginConfig("other-key") == null,
                "direct alias registration changes only the generator map");
        var copy = f.manager.getOriginConfig("base");
        copy.set("material", "DIRT");
        c.that(
                "STONE".equals(f.manager.getRealOriginConfig("base").getString("material")),
                "getOriginConfig is still a detached copy");
    }

    private static void constructors(Checks c, Fixture f) {
        ConfigurationSection containing =
                NiYaml.toSection(
                        NiYaml.read(
                                """
                dynamic:
                  inherit: base
                  material: DIAMOND
                  sections: {seed: dynamic-seed}
                """,
                                "memory/dynamic.yml"));
        File source = f.root.resolve("Items/dynamic.yml").toFile();
        var context =
                f.catalog.actionContext(
                        f.player, Map.of("rootConfig", containing, "sourceFile", source));
        Object result =
                context.evaluate(
                        """
                var Config = Java.type('pers.neige.neigeitems.item.ItemConfig');
                var Generator = Java.type('pers.neige.neigeitems.item.ItemGenerator');
                var source = new Config('dynamic', sourceFile, rootConfig);
                var direct = new Generator(source);
                var packageSource = new Packages.pers.neige.neigeitems.item.ItemConfig('dynamic', sourceFile, rootConfig);
                var packageGenerator = new Packages.pers.neige.neigeitems.item.ItemGenerator(packageSource);
                global.put('config', source); global.put('generator', direct);
                source instanceof Config && Config.class.isInstance(source)
                  && direct instanceof Generator && Generator.class.isInstance(direct)
                  && packageGenerator instanceof Generator && direct.getItemConfig() === source
                  && direct.getFile().equals(sourceFile);
                """);
        c.that(
                Boolean.TRUE.equals(result),
                "Java.type and Packages construct revision-bound ItemConfig/Generator with class and instanceof contracts");
        var origin = (LegacyItemConfig) context.getGlobal().get("config");
        var generator = (LegacyItemGenerator) context.getGlobal().get("generator");
        c.that(
                origin.getConfigSection() == containing.getConfigurationSection("dynamic"),
                "three-argument ItemConfig keeps the exact source child reference");
        c.that(
                !f.manager.hasItem("dynamic") && !f.manager.getItemConfigs().containsKey("dynamic"),
                "dynamic construction registers neither map implicitly");
        Map<String, String> rolls = new HashMap<>();
        c.that(
                generator.getItemStack(f.player, rolls).getType() == Material.DIAMOND
                        && "dynamic-seed".equals(rolls.get("seed"))
                        && "dynamic".equals(rolls.get("post_id")),
                "unregistered constructed generator inherits nodes and executes its own post-generation context");
        containing.set("dynamic.material", "GOLD_INGOT");
        c.that(
                generator.getItemStack(f.player, new HashMap<>()).getType() == Material.DIAMOND
                        && f.catalog
                                        .compile(origin)
                                        .getItemStack(f.player, new HashMap<>())
                                        .getType()
                                == Material.GOLD_INGOT,
                "source changes affect future construction while an existing generator retains compiled fields");
        var sameName = f.catalog.compile(origin("base", "inherit: base\nmaterial: EMERALD\n"));
        c.that(
                sameName.getItemStack(f.player, new HashMap<>()).getType() == Material.EMERALD
                        && f.manager.getItemStack("base").getType() == Material.STONE,
                "unregistered root may inherit its same-named original without overwriting it or inventing a cycle");
        f.manager.getRealOriginConfig("base").set("sections.seed", "edited-original");
        var later = f.catalog.compile(origin("child", "inherit: base\n"));
        Map<String, String> cache = new HashMap<>();
        later.getItemStack(f.player, cache);
        c.that(
                "edited-original".equals(cache.get("seed")),
                "new generators inherit from the current mutable original-config registry");
    }

    private static void bindings(Checks c, Fixture f) {
        var old = f.manager.getItem("base");
        var replacement =
                f.catalog.compile(
                        origin(
                                "base",
                                "material: DIAMOND\nname: '<seed>'\nsections: {seed: replacement}\n"
                                        + "event: {post-generate: {actions: \"js: data.put('post_id', 'new-post');\"}}\n"));
        var firstSnapshot = f.catalog.catalog();
        f.manager.getItems().put("base", replacement);
        c.that(
                f.catalog.catalog() != firstSnapshot
                        && f.catalog.catalog().revision() == firstSnapshot.revision()
                        && firstSnapshot.recipes().get("base") == old.compiledRecipe(),
                "registry writes publish a new immutable core snapshot without advancing the owner revision");
        c.that(
                rejects(() -> firstSnapshot.recipes().clear()),
                "already published core recipes remain immutable");
        Map<String, String> oldData = new HashMap<>(), newData = new HashMap<>();
        c.that(
                old.getItemStack(f.player, oldData).getType() == Material.STONE
                        && replacement.getItemStack(f.player, newData).getType() == Material.DIAMOND
                        && "STONE".equals(oldData.get("post_material"))
                        && "new-post".equals(newData.get("post_id")),
                "replaced generator retains its own recipe and post actions while the new binding runs independently");
        f.manager.getItems().put("alias", old);
        Map<String, String> aliasData = new HashMap<>();
        ItemStack aliased = f.manager.getItemStack("alias", f.player, aliasData);
        c.that(
                "base".equals(CODEC.read(aliased).orElseThrow().id())
                        && f.lastEvent.getId().equals("base")
                        && "base".equals(aliasData.get("post_id"))
                        && "STONE".equals(aliasData.get("post_material")),
                "alias key never replaces the generator's NBT ID, generation event ID, or post binding");
        old.getSections().set("seed", "mutable-old");
        var alias = f.manager.getItem("alias");
        c.that(
                alias.getSections() == old.getSections(),
                "aliases of a generator share its recipe-owned lazy sections");
        Map<String, String> mutable = new HashMap<>();
        alias.getItemStack(f.player, mutable);
        c.that(
                "mutable-old".equals(mutable.get("seed"))
                        && f.lastEvent.getSections() == old.getSections(),
                "generation events and future generation keep the same mutable sections after registry replacement");
        Map<String, String> independent = new HashMap<>();
        f.catalog.generate("alias", f.player, independent, false);
        c.that(
                independent.isEmpty(),
                "independent generation still isolates caller rolls while compatibility generation shares them");
        f.effect =
                event -> {
                    f.manager.getItems().remove("alias");
                    f.manager.getItems().put("base", replacement);
                };
        Map<String, String> reentrant = new HashMap<>();
        c.that(
                f.catalog.generate("alias", f.player, reentrant, true).getType() == Material.STONE
                        && "STONE".equals(reentrant.get("post_material")),
                "generation-event registry mutations cannot redirect the in-progress generator's post actions");
    }

    private static void views(Checks c, Fixture f) {
        var map = f.manager.getItems();
        var original = map.get("base");
        var second = map.get("second");
        c.that(
                map.put("base", second) == original && map.remove("base") == second,
                "put/remove return the actual previous generator objects");
        map.put("base", original);
        var entry =
                map.entrySet().stream()
                        .filter(value -> value.getKey().equals("base"))
                        .findFirst()
                        .orElseThrow();
        entry.setValue(second);
        c.that(
                map.get("base") == second
                        && f.catalog.catalog().recipes().get("base") == second.compiledRecipe(),
                "Entry.setValue publishes the replacement snapshot");
        map.compute("base", (key, value) -> original);
        c.that(map.get("base") == original, "Map.compute routes through the guarded mutation path");
        map.keySet().remove("second");
        c.that(
                !f.catalog.catalog().recipes().containsKey("second")
                        && f.manager.getItemConfigs().containsKey("second"),
                "keySet removal updates generation availability without changing origins");
        map.values().remove(original);
        c.that(
                map.isEmpty()
                        && f.catalog.catalog().recipes().isEmpty()
                        && f.manager.getItemAmount() == 2,
                "values removal updates the core snapshot while origin IDs survive");
        map.putAll(Map.of("base", original, "second", second));
        var iterator = map.entrySet().iterator();
        c.that(rejects(iterator::remove), "entry iterator rejects removal before advancing");
        String removed = iterator.next().getKey();
        iterator.remove();
        c.that(
                !map.containsKey(removed) && !f.catalog.catalog().recipes().containsKey(removed),
                "entry iterator removal publishes the runtime snapshot");
        map.clear();
        c.that(
                f.catalog.catalog().recipes().isEmpty() && f.manager.getItemAmount() == 2,
                "map clear empties only the generator registry");
    }

    private static void maintenance(Checks c, Fixture f) {
        ItemStack original = f.manager.getItemStack("base");
        var disabled =
                f.catalog.compile(
                        origin("base", "material: DIAMOND\noptions: {update: {enable: false}}\n"));
        f.manager.getItems().put("base", disabled);
        f.catalog.maintenance().check(f.player, original);
        c.that(
                original.getType() == Material.STONE
                        && f.catalog.registry().updateHash("base") == null,
                "replacement disabling updates removes the previously cached maintenance hash");
        var enabled =
                f.catalog.compile(
                        origin("base", "material: EMERALD\noptions: {update: {enable: true}}\n"));
        f.manager.getItems().put("base", enabled);
        f.catalog.maintenance().check(f.player, original);
        c.that(
                original.getType() == Material.EMERALD
                        && f.catalog.registry().updateHash("base").equals(enabled.getHashCode()),
                "maintenance uses a newly registered definition hash and material without plugin reload");
        f.manager.getItems().remove("base");
        c.that(
                f.catalog.registry().updateHash("base") == null,
                "removing the generator removes its maintenance hash atomically");
    }

    private static void service(Checks c, JavaPlugin plugin) throws Exception {
        Path root = Files.createTempDirectory("itemloom-items-registry-probe-");
        Path items = Files.createDirectory(root.resolve("Items"));
        Path functions = Files.createDirectory(root.resolve("Functions"));
        Path source = items.resolve("registry.yml"), actions = functions.resolve("registry.yml");
        try {
            Files.writeString(source, YAML);
            Files.writeString(
                    actions,
                    """
                    install: >-
                      js: var Generator = Java.type('pers.neige.neigeitems.item.ItemGenerator');
                      var manager = Java.type('pers.neige.neigeitems.manager.ItemManager');
                      if (!manager.getItem('base').getFile().equals(configuration.getFile())) throw new Error('Incorrect source root');
                      manager.getItems().put('dynamic-alias', new Generator(configuration)); true;
                    remove: "js: Java.type('pers.neige.neigeitems.manager.ItemManager').getItems().remove('dynamic-alias'); true;"
                    """);
            byte[] before = Files.readAllBytes(source);
            var read = new LegacyItemConfig("base", source.toFile());
            c.that(
                    read.getFile().equals(source.toFile())
                            && "STONE".equals(read.getConfigSection().getString("material")),
                    "two-argument ItemConfig reads its supplied file and selects the named child");
            try (ItemsService service =
                    new ItemsService(
                            plugin,
                            root,
                            (viewer, text) -> null,
                            root.resolve("return-ledger.json"))) {
                c.that(
                        service.reload(Bukkit.getConsoleSender()),
                        "actual service loads an isolated source directory");
                var stage =
                        service.runFunction("install", null, Map.of("configuration", read))
                                .toCompletableFuture();
                c.that(
                        stage.isDone()
                                && !stage.join().stopped()
                                && service.ids().contains("dynamic-alias"),
                        "public service IDs observe dynamic registry publication without reload");
                c.that(
                        service.create("dynamic-alias", null, Map.of()).getType() == Material.STONE,
                        "public service creation resolves a newly registered alias");
                service.runFunction("remove", null, null).toCompletableFuture().join();
                c.that(
                        !service.ids().contains("dynamic-alias")
                                && rejects(() -> service.create("dynamic-alias", null, Map.of())),
                        "public IDs and creation both observe dynamic removal");
            }
            c.that(
                    java.util.Arrays.equals(before, Files.readAllBytes(source)),
                    "construction and registry operations never rewrite original source files");
        } finally {
            Files.deleteIfExists(actions);
            Files.deleteIfExists(source);
            Files.deleteIfExists(root.resolve("return-ledger.json"));
            Files.deleteIfExists(functions);
            Files.deleteIfExists(items);
            Files.deleteIfExists(root);
        }
    }

    private static void closed(Checks c, JavaPlugin plugin) {
        try (Fixture first = new Fixture(plugin);
                Fixture other = new Fixture(plugin)) {
            var items = first.manager.getItems();
            var origins = first.manager.getItemConfigs();
            var generator = items.get("base");
            var entry = items.entrySet().iterator().next();
            var factory = LegacyItemConstructors.generator(first.catalog.items());
            c.that(
                    rejects(() -> items.put("foreign", other.manager.getItem("base")))
                            && !items.containsKey("foreign"),
                    "cross-revision generator insertion fails before changing either map or snapshot");
            first.close();
            c.that(
                    rejects(() -> generator.getItemStack(first.player, new HashMap<>()))
                            && rejects(() -> factory.newObject(generator.getItemConfig())),
                    "captured generator and constructor factory reject after their revision closes");
            c.that(
                    rejects(() -> items.put("late", generator))
                            && rejects(() -> origins.clear())
                            && rejects(() -> entry.setValue(generator)),
                    "retained map and entry views cannot bypass close gates");
            c.that(
                    other.manager.getItemStack("base").getType() == Material.STONE,
                    "closing the old revision leaves a different revision operational");
        }
    }

    private static LegacyItemConfig origin(String id, String yaml) {
        NiConfig child = NiYaml.read(yaml, "memory/dynamic.yml");
        return new LegacyItemConfig(
                id,
                new File("memory/dynamic.yml"),
                NiYaml.toSection(new NiConfig(Map.of(id, child.values()))));
    }

    private static boolean rejects(Runnable call) {
        try {
            call.run();
            return false;
        } catch (IllegalStateException
                | IllegalArgumentException
                | UnsupportedOperationException expected) {
            return true;
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static final class Checks {
        final List<String> passed = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        void that(boolean success, String description) {
            if (!success) throw new AssertionError(description);
            passed.add(description);
        }

        void group(String name, Checked call) {
            try {
                call.run();
            } catch (Throwable error) {
                failures.put(name, error.toString());
            }
        }

        Map<String, Object> report() {
            return Map.of(
                    "passed",
                    failures.isEmpty(),
                    "checks",
                    passed.size(),
                    "assertions",
                    List.copyOf(passed),
                    "failures",
                    Map.copyOf(failures),
                    "realClient",
                    false,
                    "referenceRequired",
                    false,
                    "boundary",
                    "registry and constructors require main thread; independent ItemConfigManager and disk save are covered by separate probes");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root =
                Path.of(System.getProperty("java.io.tmpdir"), "itemloom-items-registry-source")
                        .toAbsolutePath();
        final Player player = ProbePlayer.create("ItemRegistry" + java.util.UUID.randomUUID());
        final PlayerActionState players = new PlayerActionState();
        final Listener listener = new Listener() {};
        final NiCatalog catalog;
        final LegacyItemManager manager;
        LegacyItemGenerateEvent lastEvent;
        Consumer<LegacyItemGenerateEvent> effect;

        Fixture(JavaPlugin plugin) {
            players.join(player.getUniqueId());
            NiConfig yaml = NiYaml.read(YAML, "Items/registry.yml");
            Map<String, NiRepository.Definition> definitions = new LinkedHashMap<>();
            yaml.keys()
                    .forEach(
                            id ->
                                    definitions.put(
                                            id,
                                            new NiRepository.Definition(
                                                    id, "Items/registry.yml", yaml.section(id))));
            NiRepository.Input input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            definitions,
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());
            catalog = new NiCatalog(1, input, root, plugin, (viewer, text) -> null, players);
            manager = new LegacyItemManager(catalog.items());
            Bukkit.getPluginManager()
                    .registerEvent(
                            ItemGenerateEvent.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, event) -> {
                                if (((ItemGenerateEvent) event).getViewer() != player) return;
                                lastEvent = (LegacyItemGenerateEvent) event;
                                if (effect != null) effect.accept(lastEvent);
                            },
                            plugin);
        }

        @Override
        public void close() {
            HandlerList.unregisterAll(listener);
            catalog.close();
            players.close();
        }
    }
}

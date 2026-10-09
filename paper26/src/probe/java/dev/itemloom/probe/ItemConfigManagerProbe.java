package dev.itemloom.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemConfigManager;
import dev.itemloom.paper.compat.script.LegacyItemConstructors;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** File loading through the actual legacy script constructors, with no NI plugin. */
final class ItemConfigManagerProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) throws Exception {
        List<String> assertions = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        Path root = Files.createTempDirectory("itemloom-config-manager-probe-");
        Path items = Files.createDirectories(root.resolve("Items"));
        Path definitions = Files.createDirectories(root.resolve("Definitions/nested"));
        Files.writeString(items.resolve("base.yml"), "base: {material: STONE}\n");
        Path source = definitions.resolve("source.yml");
        String contents =
                "\uFEFF# unchanged\r\nfirst: {material: DIAMOND}\r\nsecond: {material: APPLE}\r\n";
        Files.writeString(source, contents);
        Files.writeString(definitions.resolve("ignored.yaml"), "ignored: {material: GOLD_INGOT}\n");
        Files.writeString(definitions.resolve("notes.txt"), "not an item\n");
        byte[] bytes = Files.readAllBytes(source);
        PlayerActionState players = new PlayerActionState();
        NiCatalog catalog =
                new NiCatalog(
                        1,
                        new NiRepository().read(root),
                        root,
                        plugin,
                        (viewer, text) -> null,
                        players);
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        LegacyItemConfigManager manager;
        try {
            var context = catalog.actionContext(null, Map.of("probePlugin", plugin));
            Object evaluated =
                    context.evaluate(
                            """
                    var Manager = Java.type('pers.neige.neigeitems.manager.ItemConfigManager');
                    var base = new Manager();
                    var custom = new Packages.pers.neige.neigeitems.manager.ItemConfigManager('Definitions');
                    var explicitDefault = new Manager(probePlugin);
                    var explicitCustom = new Manager(probePlugin, 'missing-config-manager-probe');
                    global.put('base', base); global.put('custom', custom);
                    custom instanceof Manager && Manager.class.isInstance(custom)
                      && base.getPlugin() === probePlugin && base.getDir() === 'Items'
                      && explicitDefault.getPlugin() === probePlugin
                      && explicitCustom.getDir() === 'missing-config-manager-probe';
                    """);
            check(
                    assertions,
                    Boolean.TRUE.equals(evaluated),
                    "all four constructors, Java.type, Packages, instanceof and class");
            var base = (LegacyItemConfigManager) context.getGlobal().get("base");
            manager = (LegacyItemConfigManager) context.getGlobal().get("custom");
            check(
                    assertions,
                    base.getItemIds().equals(List.of("base")),
                    "default constructor uses the actual input root");
            check(
                    assertions,
                    manager.getFiles().size() == 3
                            && manager.getItemIds().equals(List.of("first", "second")),
                    "recursive listing retains all files while only .yml definitions load");
            var origin = manager.getItemConfigs().get("first");
            check(
                    assertions,
                    origin.getFile().toPath().equals(source)
                            && "first".equals(origin.getConfigSection().getName()),
                    "configuration retains exact source file and named child");
            check(
                    assertions,
                    "DIAMOND".equals(origin.getConfigSection().getString("material")),
                    "BOM and CRLF parse correctly");
            check(
                    assertions,
                    Arrays.equals(bytes, Files.readAllBytes(source)),
                    "loading preserves every input byte");
            check(
                    assertions,
                    !catalog.registry().generators().containsKey("first")
                            && catalog.registry().configs().size() == 1,
                    "standalone managers do not register into either active map");
            var files = manager.getFiles();
            var configs = manager.getItemConfigs();
            configs.remove("first");
            check(
                    assertions,
                    manager.getItemIdsRaw().equals(List.of("second")),
                    "standalone config map stays live");
            manager.reloadItemConfigs();
            check(
                    assertions,
                    manager.getFiles() == files
                            && manager.getItemConfigs() == configs
                            && configs.containsKey("first"),
                    "reload repopulates the same files and config map objects");
            Path bad = definitions.resolve("broken.yml");
            Files.writeString(bad, "broken: [\n");
            Map<String, ?> before = new LinkedHashMap<>(configs);
            List<?> beforeFiles = new ArrayList<>(files);
            check(
                    assertions,
                    rejects(manager::reloadItemConfigs),
                    "malformed reload fails explicitly");
            check(
                    assertions,
                    before.equals(configs) && beforeFiles.equals(files),
                    "failed reload preserves both prior views");
            check(
                    assertions,
                    "broken: [\n".equals(Files.readString(bad)),
                    "failed reload leaves malformed file intact");
            Files.delete(bad);
            Files.writeString(source, "third: {material: EMERALD}\n");
            manager.reloadItemConfigs();
            check(
                    assertions,
                    manager.getItemIds().equals(List.of("third")),
                    "successful reload reflects deletions and new definitions");
            var empty = new LegacyItemConfigManager(catalog.items(), plugin, root, "absent");
            check(
                    assertions,
                    empty.getFiles().isEmpty()
                            && empty.getItemConfigs().isEmpty()
                            && !Files.exists(root.resolve("absent")),
                    "missing directory stays absent and reads as an empty manager");
        } catch (Throwable error) {
            failures.put("sync", error.toString());
            catalog.close();
            players.close();
            cleanup(root);
            return CompletableFuture.completedFuture(report(assertions, failures));
        }
        Bukkit.getScheduler()
                .runTaskAsynchronously(
                        plugin,
                        () -> {
                            boolean rejected =
                                    rejects(manager::reloadItemConfigs)
                                            && rejects(
                                                    () ->
                                                            LegacyItemConstructors.configManager(
                                                                            catalog.items())
                                                                    .newObject("Definitions"));
                            Bukkit.getScheduler()
                                    .runTask(
                                            plugin,
                                            () -> {
                                                try {
                                                    check(
                                                            assertions,
                                                            rejected,
                                                            "worker reload and constructor fail without blocking on server thread");
                                                    catalog.close();
                                                    check(
                                                            assertions,
                                                            rejects(manager::reloadItemConfigs),
                                                            "retained manager cannot reload after owner closes");
                                                } catch (Throwable error) {
                                                    failures.put("lifecycle", error.toString());
                                                } finally {
                                                    catalog.close();
                                                    players.close();
                                                    try {
                                                        cleanup(root);
                                                    } catch (Exception error) {
                                                        failures.put("cleanup", error.toString());
                                                    }
                                                }
                                                result.complete(report(assertions, failures));
                                            });
                        });
        return result;
    }

    private static void check(List<String> passed, boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed.add(label);
    }

    private static boolean rejects(Runnable call) {
        try {
            call.run();
            return false;
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return true;
        }
    }

    private static Map<String, Object> report(
            List<String> assertions, Map<String, String> failures) {
        return Map.of(
                "passed",
                failures.isEmpty(),
                "checks",
                assertions.size(),
                "assertions",
                assertions,
                "failures",
                failures,
                "realClient",
                false,
                "referenceRequired",
                false);
    }

    private static void cleanup(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }
}

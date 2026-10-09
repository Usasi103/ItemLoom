package dev.itemloom.probe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Local script reloads are tested against real item files and the active service facade. */
final class ItemManagerReloadProbe {
    private static final String INITIAL =
            """
            base:
              material: STONE
              name: '<seed>'
              sections: {seed: old}
              options: {update: {enable: true}}
              event:
                post-generate:
                  actions: "js: data.put('post', 'old');"
            """;
    private static final String UPDATED = INITIAL.replace("STONE", "DIAMOND").replace("old", "new");
    private static final String FUNCTIONS =
            """
            capture: "js: result.put('manager', Java.type('pers.neige.neigeitems.manager.ItemManager')); true;"
            local-reload: >-
              js: var m = Java.type('pers.neige.neigeitems.manager.ItemManager');
              m.reload(); result.put('item', m.getItemStack('base')); result.put('manager', m); true;
            delayed:
              - "js: trace.add('before'); true;"
              - 'delay: 4'
              - "js: trace.add('after'); true;"
            """;

    static Map<String, Object> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item manager reload probe requires the server thread");
        Checks c = new Checks();
        c.group(
                "sources-and-generators",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        refresh(c, f);
                    }
                });
        c.group(
                "failure-preserves-state",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        failures(c, f);
                    }
                });
        c.group(
                "input-policy",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        policy(c, f);
                    }
                });
        c.group(
                "unreadable-input",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        unreadable(c, f);
                    }
                });
        c.group(
                "source-fence",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        sourceFence(c, f);
                    }
                });
        c.group(
                "generation-reentry",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        generation(c, f);
                    }
                });
        c.group(
                "public-service",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        service(c, f);
                    }
                });
        c.group(
                "empty-and-close",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        var old = f.manager.getItem("base");
                        Files.delete(f.base);
                        f.manager.reload();
                        c.that(
                                f.manager.getItems().isEmpty()
                                        && f.manager.getItemConfigs().isEmpty()
                                        && f.manager.getFiles().isEmpty()
                                        && f.catalog.catalog().recipes().isEmpty(),
                                "an intentionally empty Items directory replaces both registries and the public catalog");
                        c.that(
                                old.getItemStack(null, new HashMap<>()).getType() == Material.STONE,
                                "removed generator remains usable in the active owner");
                        f.catalog.close();
                        c.that(
                                rejects(f.manager::reload) && rejects(f.manager::reloadItemConfigs),
                                "both local reload APIs reject after their owner closes");
                    }
                });
        return c.report();
    }

    private static void refresh(Checks c, Fixture f) throws Exception {
        var configs = f.manager.getItemConfigs();
        var generators = f.manager.getItems();
        var files = f.manager.getFiles();
        var original = configs.get("base");
        var old = generators.get("base");
        generators.put("runtime-alias", old);
        var published = f.catalog.catalog();
        Integer hash = f.catalog.registry().updateHash("base");
        Files.writeString(f.base, UPDATED);
        Files.writeString(f.items.resolve("a-child.yml"), "child:\n  inherit: fresh-parent\n");
        Files.writeString(
                f.items.resolve("z-parent.yml"),
                "fresh-parent:\n  material: EMERALD\n  sections: {seed: new-parent}\n");
        byte[] before = Files.readAllBytes(f.base);
        Object first =
                f.catalog
                        .actionContext(null, null)
                        .evaluate(
                                """
                var m = Java.type('pers.neige.neigeitems.manager.ItemManager');
                m.reloadItemConfigs(); m.getRealOriginConfig('base').getString('material') == 'DIAMOND'
                  && String(m.getItemStack('base').getType()) == 'STONE';
                """);
        c.that(
                Boolean.TRUE.equals(first),
                "actual script reloadItemConfigs updates source config while generation still uses the old recipe");
        c.that(
                configs == f.manager.getItemConfigs()
                        && generators == f.manager.getItems()
                        && files == f.manager.getFiles()
                        && configs.get("base") != original
                        && generators.get("base") == old
                        && generators.containsKey("runtime-alias"),
                "source reload preserves all captured views and every existing generator object");
        c.that(
                f.catalog.catalog() == published
                        && hash.equals(f.catalog.registry().updateHash("base"))
                        && configs.containsKey("child")
                        && !generators.containsKey("child")
                        && files.size() == 3,
                "source-only reload leaves the exact Published catalog and update hashes untouched");
        c.that(
                f.catalog
                                .compile(configs.get("child"))
                                .getItemStack(null, new HashMap<>())
                                .getType()
                        == Material.EMERALD,
                "new detached generation after source reload inherits the freshly loaded parent");
        Object second =
                f.catalog
                        .actionContext(null, null)
                        .evaluate(
                                """
                var m = Packages.pers.neige.neigeitems.manager.ItemManager.INSTANCE;
                m.reload(); String(m.getItemStack('base').getType()) == 'DIAMOND'
                  && String(m.getItemStack('child').getType()) == 'EMERALD';
                """);
        c.that(
                Boolean.TRUE.equals(second),
                "actual script continues after reload and generates from the new base and cross-file parent in the same evaluation");
        c.that(
                configs == f.manager.getItemConfigs()
                        && generators == f.manager.getItems()
                        && files == f.manager.getFiles()
                        && generators.get("base") != old
                        && !generators.containsKey("runtime-alias"),
                "full item reload keeps map/files identities while replacing their contents and removing runtime-only aliases");
        c.that(
                f.catalog.catalog() != published
                        && f.catalog.catalog().revision() == published.revision()
                        && !hash.equals(f.catalog.registry().updateHash("base")),
                "full item reload publishes new recipes and maintenance hashes without advancing owner revision");
        Map<String, String> oldData = new HashMap<>(), newData = new HashMap<>();
        c.that(
                old.getItemStack(null, oldData).getType() == Material.STONE
                        && f.manager.getItemStack("base", newData).getType() == Material.DIAMOND
                        && oldData.get("post").equals("old")
                        && newData.get("post").equals("new"),
                "retained old generator keeps its fixed recipe and post action after replacement");
        c.that(
                Arrays.equals(before, Files.readAllBytes(f.base))
                        && f.count("enable") == 1
                        && f.count("disable") == 0,
                "local reloads neither rewrite item source nor restart extension lifecycle");
    }

    private static void failures(Checks c, Fixture f) throws Exception {
        var published = f.catalog.catalog();
        var origin = f.manager.getItemConfigs().get("base");
        var generator = f.manager.getItem("base");
        var files = List.copyOf(f.manager.getFiles());
        Files.writeString(f.base, "base: [\n");
        byte[] broken = Files.readAllBytes(f.base);
        c.that(
                rejects(f.manager::reload) && rejects(f.manager::reloadItemConfigs),
                "both local APIs reject invalid YAML");
        c.that(
                f.catalog.catalog() == published
                        && f.manager.getItemConfigs().get("base") == origin
                        && f.manager.getItem("base") == generator
                        && f.manager.getFiles().equals(files)
                        && Arrays.equals(broken, Files.readAllBytes(f.base)),
                "read failure preserves the old objects, file view, catalog and original broken bytes");
        Files.writeString(f.base, UPDATED);
        Path badAction = f.items.resolve("z-invalid-action.yml");
        Files.writeString(
                badAction,
                "bad-action:\n  material: EMERALD\n  event:\n    post-generate:\n      actions: 'js: function ('\n");
        byte[] invalidAction = Files.readAllBytes(badAction);
        c.that(
                rejects(f.manager::reload)
                        && f.catalog.catalog() == published
                        && f.manager.getItemConfigs().get("base") == origin
                        && f.manager.getItem("base") == generator
                        && !f.manager.getItemConfigs().containsKey("bad-action")
                        && f.manager.getFiles().equals(files)
                        && Arrays.equals(invalidAction, Files.readAllBytes(badAction)),
                "a later item's action syntax failure does not publish an earlier successfully compiled replacement or rewrite invalid input");
        Files.delete(badAction);
        Files.writeString(f.base, "base:\n  inherit: missing-parent\n");
        c.that(
                rejects(f.manager::reload)
                        && f.manager.getItemConfigs().get("base") == origin
                        && f.catalog.catalog() == published,
                "missing inherited parent rejects the whole reload before publishing candidate sources");
        Files.writeString(f.base, "base:\n  inherit: loop\nloop:\n  inherit: base\n");
        f.manager.reloadItemConfigs();
        var cyclicOrigin = f.manager.getItemConfigs().get("base");
        c.that(
                cyclicOrigin != origin
                        && f.manager.getItemConfigs().containsKey("loop")
                        && f.catalog.catalog() == published,
                "source-only reload does not eagerly validate inheritance or rebuild generators");
        c.that(
                rejects(f.manager::reload)
                        && f.manager.getItemConfigs().get("base") == cyclicOrigin
                        && f.manager.getItem("base") == generator
                        && f.catalog.catalog() == published,
                "subsequent cyclic reload preserves the already installed source-only state and old generators");
        c.that(
                f.manager.getItemStack("base").getType() == Material.STONE,
                "generation remains available after failed local reloads");
    }

    private static void policy(Checks c, Fixture f) throws Exception {
        Files.writeString(f.items.resolve("extension.yaml"), "yaml-item:\n  material: PAPER\n");
        Files.writeString(f.items.resolve("ignored.txt"), "[invalid yaml");
        Files.writeString(f.functions, "invalid: [\n");
        Files.writeString(f.expansion, "this is not valid javascript }");
        Files.writeString(f.root.resolve("config.yml"), "bad: [\n");
        f.manager.reload();
        c.that(
                f.manager.hasItem("yaml-item") && f.manager.getFiles().size() == 3,
                "main manager preserves IL .yaml support and lists ignored files without parsing them");
        c.that(
                f.count("enable") == 1 && f.count("disable") == 0 && f.catalog.active(),
                "malformed unrelated settings, Functions and Expansions do not participate in an item-only reload");
        var published = f.catalog.catalog();
        var original = f.manager.getItemConfigs().get("base");
        Files.writeString(f.items.resolve("duplicate.yml"), "base:\n  material: GOLD_INGOT\n");
        c.that(
                rejects(f.manager::reloadItemConfigs)
                        && rejects(f.manager::reload)
                        && f.catalog.catalog() == published
                        && f.manager.getItemConfigs().get("base") == original,
                "main manager retains IL strict duplicate-ID rejection for both local APIs");
    }

    private static void unreadable(Checks c, Fixture f) throws Exception {
        var published = f.catalog.catalog();
        var origin = f.manager.getItemConfigs().get("base");
        var generator = f.manager.getItem("base");
        var files = List.copyOf(f.manager.getFiles());
        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        Files.write(f.base, malformed);
        c.that(
                rejects(f.manager::reload)
                        && rejects(f.manager::reloadItemConfigs)
                        && f.catalog.catalog() == published
                        && f.manager.getItemConfigs().get("base") == origin
                        && f.manager.getItem("base") == generator
                        && f.manager.getFiles().equals(files)
                        && Arrays.equals(malformed, Files.readAllBytes(f.base)),
                "invalid UTF-8 cannot silently replace the registry or overwrite its original bytes");
        Files.delete(f.base);
        Files.delete(f.items);
        Files.writeString(f.items, "item directory replaced by a regular file");
        c.that(
                rejects(f.manager::reload)
                        && rejects(f.manager::reloadItemConfigs)
                        && f.catalog.catalog() == published
                        && f.manager.getItemConfigs().get("base") == origin
                        && f.manager.getItem("base") == generator
                        && f.manager.getFiles().equals(files)
                        && Files.readString(f.items)
                                .equals("item directory replaced by a regular file"),
                "an invalid Items path is a read failure, not an empty successful reload");
    }

    private static void sourceFence(Checks c, Fixture f) throws Exception {
        NiRepository repository = new NiRepository();
        var loaded =
                repository.readItems(
                        f.root,
                        text -> {
                            try {
                                Files.writeString(f.base, UPDATED);
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            }
                            return text;
                        });
        c.that(
                rejectsIo(loaded.source()::verifyUnchanged),
                "source verification rejects bytes changed after the read snapshot was captured");
        var next = repository.readItems(f.root, java.util.function.UnaryOperator.identity());
        Files.writeString(f.base, "\uFEFF" + UPDATED);
        c.that(
                rejectsIo(next.source()::verifyUnchanged),
                "source verification also detects BOM-only changes in raw item text");
        var listing = repository.readItems(f.root, java.util.function.UnaryOperator.identity());
        Files.writeString(f.items.resolve("new.txt"), "new file");
        c.that(
                rejectsIo(listing.source()::verifyUnchanged),
                "source verification detects file-list changes even for unparsed files");
        c.that(
                f.manager.getItemStack("base").getType() == Material.STONE,
                "source snapshot preparation never mutates the active generator registry");
    }

    private static void generation(Checks c, Fixture f) throws Exception {
        Files.writeString(f.base, UPDATED);
        Listener listener = new Listener() {};
        boolean[] once = {false};
        Bukkit.getPluginManager()
                .registerEvent(
                        ItemGenerateEvent.class,
                        listener,
                        EventPriority.NORMAL,
                        (ignored, event) -> {
                            if (!once[0] && ((ItemGenerateEvent) event).getId().equals("base")) {
                                once[0] = true;
                                f.manager.reload();
                            }
                        },
                        f.plugin);
        try {
            Map<String, String> data = new HashMap<>();
            c.that(
                    f.manager.getItemStack("base", data).getType() == Material.STONE
                            && "old".equals(data.get("post"))
                            && f.manager.getItemStack("base").getType() == Material.DIAMOND,
                    "generation-event local reload does not redirect the in-progress generator or its post action");
        } finally {
            HandlerList.unregisterAll(listener);
        }
    }

    private static void service(Checks c, Fixture f) throws Exception {
        try (ItemsService service =
                new ItemsService(
                        f.plugin,
                        f.root,
                        (viewer, text) -> null,
                        f.root.resolve("return-ledger.json"))) {
            c.that(
                    service.reload(Bukkit.getConsoleSender()),
                    "actual service loads the isolated fixture");
            Map<String, Object> values = new HashMap<>();
            service.runFunction("capture", null, Map.of("result", values))
                    .toCompletableFuture()
                    .join();
            LegacyItemManager captured = (LegacyItemManager) values.get("manager");
            Files.writeString(f.base, UPDATED);
            Files.writeString(
                    f.items.resolve("service-new.yml"), "service-new:\n  material: EMERALD\n");
            var result =
                    service.runFunction("local-reload", null, Map.of("result", values))
                            .toCompletableFuture();
            c.that(
                    result.isDone()
                            && !result.join().stopped()
                            && values.get("manager") == captured
                            && ((ItemStack) values.get("item")).getType() == Material.DIAMOND,
                    "public service action calls local reload and continues with the same captured manager");
            c.that(
                    service.ids().contains("service-new")
                            && service.create("service-new", null, Map.of()).getType()
                                    == Material.EMERALD,
                    "public service IDs and creation observe the local catalog publication immediately");
            c.that(
                    service.reload(Bukkit.getConsoleSender())
                            && rejects(captured::reload)
                            && rejects(captured::reloadItemConfigs),
                    "a later full service reload still fences off the captured old manager instead of redirecting it");
        }
    }

    static CompletionStage<Map<String, Object>> runDelayed(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Delayed reload probe requires the server thread");
        Checks c = new Checks();
        CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        Fixture fixture;
        try {
            fixture = new Fixture(plugin);
        } catch (Exception error) {
            c.failures.put("setup", error.toString());
            return CompletableFuture.completedFuture(c.report());
        }
        AtomicReference<BukkitTask> timeout = new AtomicReference<>();
        java.util.function.Consumer<Throwable> finish =
                error -> {
                    if (report.isDone()) return;
                    if (error != null) c.failures.put("delayed", error.toString());
                    try {
                        fixture.close();
                    } catch (Exception failure) {
                        c.failures.put("cleanup", failure.toString());
                    }
                    if (timeout.get() != null) timeout.get().cancel();
                    report.complete(c.report());
                };
        try {
            List<String> trace = new ArrayList<>();
            var waiting =
                    fixture.catalog
                            .runFunction("delayed", null, Map.of("trace", trace))
                            .toCompletableFuture();
            c.that(
                    trace.equals(List.of("before")) && !waiting.isDone(),
                    "an action has actually entered a pending server-tick delay");
            Files.writeString(fixture.base, UPDATED);
            fixture.manager.reloadItemConfigs();
            fixture.manager.reload();
            c.that(
                    !waiting.isDone() && fixture.catalog.active(),
                    "both local reloads retain the already pending action and its active owner");
            CompletableFuture<Boolean> worker = new CompletableFuture<>();
            Bukkit.getScheduler()
                    .runTaskAsynchronously(
                            plugin,
                            () -> {
                                try {
                                    worker.complete(
                                            rejects(fixture.manager::reload)
                                                    && rejects(fixture.manager::reloadItemConfigs));
                                } catch (Throwable failure) {
                                    worker.completeExceptionally(failure);
                                }
                            });
            timeout.set(
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () ->
                                            finish.accept(
                                                    new AssertionError(
                                                            "Reload delay probe exceeded 100 ticks")),
                                    100));
            CompletableFuture.allOf(waiting, worker)
                    .whenComplete(
                            (ignored, failure) ->
                                    Bukkit.getScheduler()
                                            .runTask(
                                                    plugin,
                                                    () -> {
                                                        if (report.isDone()) return;
                                                        try {
                                                            if (failure != null)
                                                                throw new AssertionError(failure);
                                                            c.that(
                                                                    !waiting.join().stopped()
                                                                            && trace.equals(
                                                                                    List.of(
                                                                                            "before",
                                                                                            "after")),
                                                                    "the same delayed action really resumes and completes after both local reloads");
                                                            c.that(
                                                                    worker.join()
                                                                            && fixture.manager
                                                                                            .getItemStack(
                                                                                                    "base")
                                                                                            .getType()
                                                                                    == Material
                                                                                            .DIAMOND,
                                                                    "worker reload calls reject without replacing the valid main-thread result");
                                                            c.that(
                                                                    fixture.count("enable") == 1
                                                                            && fixture.count(
                                                                                            "disable")
                                                                                    == 0,
                                                                    "delayed continuation occurs without any extension disable/enable cycle");
                                                            finish.accept(null);
                                                        } catch (Throwable error) {
                                                            finish.accept(error);
                                                        }
                                                    }));
        } catch (Throwable error) {
            finish.accept(error);
        }
        return report;
    }

    private static boolean rejects(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (IllegalStateException | IllegalArgumentException | UncheckedIOException expected) {
            return true;
        }
    }

    private static boolean rejectsIo(Checked operation) {
        try {
            operation.run();
            return false;
        } catch (IOException expected) {
            return true;
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static final class Checks {
        final List<String> assertions = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        void that(boolean success, String message) {
            if (!success) throw new AssertionError(message);
            assertions.add(message);
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
                    List.copyOf(assertions),
                    "failures",
                    Map.copyOf(failures),
                    "referenceRequired",
                    false,
                    "realClient",
                    false,
                    "boundary",
                    "main manager preserves IL strict duplicates and .yaml support; standalone NI directory remains .yml/last-ID-wins; packet display is outside this probe");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Path root, items, base, functions, expansion;
        final String counter = "itemloom.reload.probe." + UUID.randomUUID();
        final PlayerActionState players = new PlayerActionState();
        final NiCatalog catalog;
        final LegacyItemManager manager;

        Fixture(JavaPlugin plugin) throws Exception {
            this.plugin = plugin;
            root =
                    Files.createTempDirectory("itemloom-items-local-reload-probe-")
                            .toAbsolutePath()
                            .normalize();
            items = Files.createDirectory(root.resolve("Items"));
            base = items.resolve("base.yml");
            functions = Files.createDirectory(root.resolve("Functions")).resolve("probe.yml");
            expansion = Files.createDirectory(root.resolve("Expansions")).resolve("probe.js");
            Files.writeString(base, INITIAL);
            Files.writeString(functions, FUNCTIONS);
            Files.writeString(
                    expansion,
                    "var System = Java.type('java.lang.System');\n"
                            + "function count(kind) { var key = '"
                            + counter
                            + ".' + kind; System.setProperty(key, String(Number(System.getProperty(key, '0')) + 1)); }\n"
                            + "function enable() { count('enable'); }\nfunction disable() { count('disable'); }\n");
            catalog =
                    new NiCatalog(
                            1,
                            new NiRepository().read(root),
                            root,
                            plugin,
                            (viewer, text) -> null,
                            players);
            manager = new LegacyItemManager(catalog.items());
        }

        int count(String event) {
            return Integer.parseInt(System.getProperty(counter + "." + event, "0"));
        }

        @Override
        public void close() throws IOException {
            catalog.close();
            players.close();
            System.clearProperty(counter + ".enable");
            System.clearProperty(counter + ".disable");
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!file.toAbsolutePath().normalize().startsWith(root))
                        throw new IOException("Probe cleanup escaped its owned root");
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}

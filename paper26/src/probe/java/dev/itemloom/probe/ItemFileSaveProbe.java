package dev.itemloom.probe;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.compat.script.LegacyItemManager.SaveResult;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Save APIs use real temporary files and reloads, without loading NI or touching deployed data. */
final class ItemFileSaveProbe {
    private static final String INITIAL =
            "\uFEFFbase:\r\n  material: STONE\r\nother:\r\n  material: APPLE\r\n";

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("File save probe requires the server thread");
        Checks c = new Checks();
        c.group(
                "early-return",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        early(c, f);
                    }
                });
        c.group(
                "save-and-cover",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        saves(c, f);
                    }
                });
        c.group(
                "config-overload",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        config(c, f);
                    }
                });
        c.group(
                "rollback",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        rollback(c, f);
                    }
                });
        c.group(
                "strict-read",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        strict(c, f);
                    }
                });
        c.group(
                "script-alias",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        scripts(c, f);
                    }
                });
        c.group(
                "real-reload-rich-data",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        reload(c, f, plugin);
                    }
                });
        c.group(
                "closed",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        f.catalog.close();
                        byte[] before = Files.readAllBytes(f.source);
                        c.that(
                                rejects(() -> f.manager.saveItem(stone(), "late", false)),
                                "closed revision rejects save before file writes");
                        c.that(
                                Arrays.equals(before, Files.readAllBytes(f.source))
                                        && !Files.exists(f.root.resolve("Items/late.yml")),
                                "closed save leaves disk unchanged");
                    }
                });
        CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        try {
            Fixture worker = new Fixture(plugin);
            CompletableFuture<Boolean> rejected = new CompletableFuture<>();
            Bukkit.getScheduler()
                    .runTaskAsynchronously(
                            plugin,
                            () -> {
                                try {
                                    rejected.complete(
                                            rejects(
                                                    () ->
                                                            worker.manager.saveItem(
                                                                    stone(), "worker", false)));
                                } catch (Throwable error) {
                                    rejected.completeExceptionally(error);
                                }
                            });
            rejected.whenComplete(
                    (value, error) ->
                            Bukkit.getScheduler()
                                    .runTask(
                                            plugin,
                                            () -> {
                                                c.group(
                                                        "worker",
                                                        () -> {
                                                            try (worker) {
                                                                if (error != null)
                                                                    throw new AssertionError(error);
                                                                c.that(
                                                                        Boolean.TRUE.equals(value),
                                                                        "asynchronous save rejects without waiting on the main thread");
                                                                c.that(
                                                                        !worker.manager.hasItem(
                                                                                        "worker")
                                                                                && !Files.exists(
                                                                                        worker.root
                                                                                                .resolve(
                                                                                                        "Items/worker.yml")),
                                                                        "worker rejection leaves both registry and disk unchanged");
                                                            }
                                                        });
                                                report.complete(c.report());
                                            }));
        } catch (Exception error) {
            c.failures.put("worker/setup", error.toString());
            report.complete(c.report());
        }
        return report;
    }

    private static void early(Checks c, Fixture f) throws Exception {
        byte[] before = Files.readAllBytes(f.source);
        c.that(
                f.manager.saveItem(null, null, (String) null, false) == SaveResult.AIR
                        && f.manager.saveItem(
                                        new ItemStack(Material.AIR),
                                        "empty",
                                        "absent/deep.yml",
                                        true)
                                == SaveResult.AIR,
                "null/AIR return without evaluating a path or creating directories");
        c.that(
                f.manager.saveItem(stone(), "base", (String) null, false) == SaveResult.CONFLICT,
                "registered conflict returns before evaluating a path");
        f.manager.getItems().remove("base");
        c.that(
                f.manager.saveItem(stone(), "base", "absent/deep.yml", false)
                        == SaveResult.CONFLICT,
                "an unregistered disk definition cannot be overwritten without cover");
        c.that(
                Arrays.equals(before, Files.readAllBytes(f.source))
                        && !Files.exists(f.root.resolve("Items/absent"))
                        && f.manager.getFiles().equals(new ArrayList<>(List.of(f.source.toFile()))),
                "AIR and CONFLICT preserve all original bytes and the directory listing");
        c.that(
                f.manager.getPlugin() == f.plugin && f.manager.getDir().equals("Items"),
                "inherited source getters expose the owning plugin and directory");
    }

    private static void saves(Checks c, Fixture f) throws Exception {
        c.that(
                f.manager.saveItem(new ItemStack(Material.DIAMOND), "new", false)
                        == SaveResult.SUCCESS,
                "default overload writes Items/id.yml and registers its generator");
        c.that(
                Files.isRegularFile(f.root.resolve("Items/new.yml"))
                        && f.manager.getItemStack("new").getType() == Material.DIAMOND,
                "saved item is immediately available without full reload");
        c.that(
                f.manager.saveItem(new ItemStack(Material.EMERALD), "base", "original.yml", true)
                        == SaveResult.SUCCESS,
                "same-file cover succeeds");
        YamlConfiguration same = read(f.source);
        c.that(
                same.getString("other.material").equals("APPLE")
                        && same.getString("base.material").equals("EMERALD"),
                "same-file cover preserves other definitions");
        Path duplicate = f.root.resolve("Items/duplicate.yml");
        Files.writeString(duplicate, "base:\n  material: GOLD_INGOT\nextra:\n  material: DIRT\n");
        c.that(
                f.manager.saveItem(
                                new ItemStack(Material.IRON_INGOT),
                                "base",
                                "nested/moved.yml",
                                true)
                        == SaveResult.SUCCESS,
                "cross-file cover writes a new destination and removes every old disk occurrence");
        c.that(
                !read(f.source).contains("base")
                        && read(f.source).contains("other")
                        && !read(duplicate).contains("base")
                        && read(duplicate).contains("extra")
                        && f.manager
                                .getItem("base")
                                .getFile()
                                .equals(f.root.resolve("Items/nested/moved.yml").toFile()),
                "cross-file cover keeps unrelated IDs and records the actual new source file");
        var loaded = new NiRepository().read(f.root);
        c.that(
                loaded.items().size() == 4
                        && loaded.items().get("base").source().equals("Items/nested/moved.yml"),
                "strict repository reload sees one saved ID after a multi-source cover");
    }

    private static void config(Checks c, Fixture f) throws Exception {
        YamlConfiguration caller = new YamlConfiguration();
        caller.set("caller-only.material", "PAPER");
        c.that(
                f.manager.saveItem(
                                new ItemStack(Material.DIAMOND),
                                "base",
                                f.source.toFile(),
                                caller,
                                true)
                        == SaveResult.SUCCESS,
                "explicit File/config overload writes successfully");
        c.that(
                read(f.source).contains("other")
                        && read(f.source).contains("caller-only")
                        && caller.contains("caller-only")
                        && !caller.contains("other")
                        && f.manager.getRealOriginConfig("base")
                                == caller.getConfigurationSection("base"),
                "explicit overload retains caller contents and child identity while preserving missing unrelated disk IDs");
        byte[] before = Files.readAllBytes(f.source);
        YamlConfiguration stale = new YamlConfiguration();
        stale.set("other.material", "DIRT");
        String staleBefore = stale.saveToString();
        c.that(
                rejects(() -> f.manager.saveItem(stone(), "base", f.source.toFile(), stale, true)),
                "stale caller cannot overwrite a different unrelated disk definition");
        c.that(
                Arrays.equals(before, Files.readAllBytes(f.source))
                        && staleBefore.equals(stale.saveToString()),
                "rejected explicit save changes neither caller config nor disk bytes");
    }

    private static void rollback(Checks c, Fixture f) throws Exception {
        byte[] original = Files.readAllBytes(f.source);
        Path destination = f.root.resolve("Items/target.yml");
        byte[] destinationOriginal =
                "\uFEFFuntouched:\r\n  material: PAPER\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(destination, destinationOriginal);
        var base = f.manager.getItem("base");
        var origin = f.manager.getItemConfigs().get("base");
        YamlConfiguration caller = new YamlConfiguration();
        String callerBefore = caller.saveToString();
        // Resolve the product's relocated guard: the probe must not create another static registry.
        Class<?> guard =
                Class.forName(
                        "dev.itemloom.internal.keystone.storage.WriteGuard",
                        true,
                        NiCatalog.class.getClassLoader());
        var block = guard.getMethod("block", File.class, String.class);
        var unblock = guard.getMethod("unblock", File.class);
        var isBlocked = guard.getMethod("isBlocked", File.class);
        // No global StorageWriter seam: block only this fixture's second write.
        block.invoke(null, f.source.toFile(), "file-save probe: refuse old-source removal");
        try {
            c.that(
                    Boolean.TRUE.equals(isBlocked.invoke(null, f.source.toFile())),
                    "failure injection uses the product's relocated write guard");
            c.that(
                    rejects(
                            () ->
                                    f.manager.saveItem(
                                            new ItemStack(Material.DIAMOND),
                                            "base",
                                            destination.toFile(),
                                            caller,
                                            true)),
                    "failure removing the old source aborts the multi-file save after its destination write");
            c.that(
                    Arrays.equals(original, Files.readAllBytes(f.source))
                            && Arrays.equals(destinationOriginal, Files.readAllBytes(destination)),
                    "multi-file rollback preserves both original byte sequences including BOM and CRLF");
            c.that(
                    f.manager.getItem("base") == base
                            && f.manager.getItemConfigs().get("base") == origin
                            && callerBefore.equals(caller.saveToString()),
                    "write failure leaves generator, origin and caller config untouched");
            c.that(
                    rejects(
                            () ->
                                    f.manager.saveItem(
                                            stone(), "base", "brand/new/target.yml", true)),
                    "failed cross-file cover also rolls back a newly created destination");
            c.that(
                    !Files.exists(f.root.resolve("Items/brand")),
                    "failed save removes only the new empty directory chain it created");
            c.that(
                    Files.notExists(Path.of(destination + ".tmp"))
                            && Files.notExists(Path.of(destination + ".previous")),
                    "verified rollback leaves no attempt-owned temporary recovery sidecars");
        } finally {
            unblock.invoke(null, f.source.toFile());
        }
        c.that(
                Boolean.FALSE.equals(isBlocked.invoke(null, f.source.toFile())),
                "probe releases its product write guard after rollback checks");
    }

    private static void strict(Checks c, Fixture f) throws Exception {
        byte[] original = Files.readAllBytes(f.source);
        Path target = f.root.resolve("Items/broken.yml");
        byte[] broken = "broken: [\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, broken);
        c.that(
                rejects(() -> f.manager.saveItem(stone(), "fresh", "broken.yml", true)),
                "invalid YAML refuses save instead of becoming empty data");
        c.that(
                Arrays.equals(broken, Files.readAllBytes(target))
                        && Arrays.equals(original, Files.readAllBytes(f.source))
                        && !f.manager.hasItem("fresh"),
                "invalid target bytes and registry survive failure");
        Files.delete(target);
        Path tmp = Path.of(f.source + ".tmp");
        Files.writeString(tmp, "pre-existing recovery payload");
        byte[] pending = Files.readAllBytes(tmp);
        c.that(
                rejects(() -> f.manager.saveItem(stone(), "base", "original.yml", true)),
                "pending recovery material refuses save without automatic recovery");
        c.that(
                Arrays.equals(pending, Files.readAllBytes(tmp))
                        && Arrays.equals(original, Files.readAllBytes(f.source)),
                "pre-existing sidecars remain byte-for-byte intact");
        Files.delete(tmp);
        c.that(
                rejects(() -> f.manager.saveItem(stone(), "escape", "../escape.yml", true))
                        && !Files.exists(f.root.resolve("escape.yml")),
                "relative path overload cannot escape Items");
        Files.write(target, new byte[] {(byte) 0xc3, (byte) 0x28});
        c.that(
                rejects(() -> f.manager.saveItem(stone(), "fresh", "broken.yml", true))
                        && Arrays.equals(
                                Files.readAllBytes(target), new byte[] {(byte) 0xc3, (byte) 0x28}),
                "malformed UTF-8 refuses save without replacement-character corruption");
    }

    private static void scripts(Checks c, Fixture f) {
        var context =
                f.catalog.actionContext(null, Map.of("source", new ItemStack(Material.PAPER)));
        Object result =
                context.evaluate(
                        """
                var manager = Java.type('pers.neige.neigeitems.manager.ItemManager');
                var Result = Java.type('pers.neige.neigeitems.manager.ItemManager$SaveResult');
                var r = manager.saveItem(source, 'script-saved', false);
                r === Result.SUCCESS && r === manager.SaveResult.SUCCESS
                  && manager.saveItem(source, 'script-saved', false) === Result.CONFLICT
                  && manager.saveItem(null, 'empty', false) === Result.AIR
                  && Packages.pers.neige.neigeitems.manager.ItemManager$SaveResult.SUCCESS === r;
                """);
        c.that(
                Boolean.TRUE.equals(result),
                "Java.type, Packages and manager.SaveResult expose the same result values through actual script saves");
    }

    private static void reload(Checks c, Fixture f, JavaPlugin plugin) throws Exception {
        ItemStack rich = CraftItemStack.asCraftCopy(new ItemStack(Material.PAPER, 17));
        var handle = CraftItemStack.unwrap(rich);
        handle.set(DataComponents.CUSTOM_NAME, Component.literal("literal <number::1_9> 保存"));
        handle.set(
                DataComponents.LORE,
                new ItemLore(
                        List.of(
                                Component.literal(""),
                                Component.literal("first\nsecond <js::1>"))));
        CompoundTag custom = new CompoundTag();
        custom.putString("literal.dot", "(Int) 42");
        custom.putByteArray("empty-array", new byte[0]);
        handle.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        var name = handle.get(DataComponents.CUSTOM_NAME);
        var lore = handle.get(DataComponents.LORE);
        c.that(
                f.manager.saveItem(rich, "rich", false) == SaveResult.SUCCESS,
                "rich text and foreign typed NBT save to a real file");
        try (ItemsService service =
                new ItemsService(
                        plugin,
                        f.root,
                        (viewer, text) -> null,
                        f.root.resolve("return-ledger.json"))) {
            c.that(
                    service.reload(Bukkit.getConsoleSender()),
                    "public service performs a real strict reload of saved files");
            ItemStack rebuilt = service.create("rich", null, Map.of());
            var restored = CraftItemStack.asNMSCopy(rebuilt);
            CompoundTag restoredData = restored.get(DataComponents.CUSTOM_DATA).copyTag();
            restoredData.remove(ItemStateCodec.KEY);
            c.that(
                    name.equals(restored.get(DataComponents.CUSTOM_NAME))
                            && lore.equals(restored.get(DataComponents.LORE))
                            && custom.equals(restoredData)
                            && rebuilt.getAmount() == 1,
                    "real disk reload preserves literal rich name, lore structure and opaque NBT while keeping save quantity semantics");
            c.that(
                    rich.getAmount() == 17
                            && custom.equals(handle.get(DataComponents.CUSTOM_DATA).copyTag()),
                    "save and reload leave the source item unchanged");
        }
    }

    private static ItemStack stone() {
        return new ItemStack(Material.STONE);
    }

    private static YamlConfiguration read(Path file) throws Exception {
        YamlConfiguration result = new YamlConfiguration();
        result.load(file.toFile());
        return result;
    }

    private static boolean rejects(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException expected) {
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

        void that(boolean value, String message) {
            if (!value) throw new AssertionError(message);
            passed.add(message);
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
                    passed.size(),
                    "assertions",
                    List.copyOf(passed),
                    "failures",
                    Map.copyOf(failures),
                    "referenceRequired",
                    false,
                    "realClient",
                    false,
                    "boundary",
                    "single-file StorageWriter commits; caught multi-file failures roll back; process crash between files and simultaneous external writes are not transactionally protected");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Path root;
        final Path source;
        final PlayerActionState players = new PlayerActionState();
        final NiCatalog catalog;
        final LegacyItemManager manager;

        Fixture(JavaPlugin plugin) throws Exception {
            this.plugin = plugin;
            root =
                    Files.createTempDirectory("itemloom-items-file-save-probe-")
                            .toAbsolutePath()
                            .normalize();
            source = Files.createDirectory(root.resolve("Items")).resolve("original.yml");
            Files.writeString(source, INITIAL);
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

        @Override
        public void close() throws IOException {
            catalog.close();
            players.close();
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

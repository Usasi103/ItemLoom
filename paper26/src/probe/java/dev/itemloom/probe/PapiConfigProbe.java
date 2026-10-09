package dev.itemloom.probe;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.action.NiActionText;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemSnapshot;
import dev.itemloom.paper.compat.script.LegacyItemConfigManager;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.compat.script.LegacyItemManager.SaveResult;
import dev.itemloom.paper.integration.PapiBridge;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Load this class only in the optional sandbox with real PlaceholderAPI 2.12.3 enabled. */
final class PapiConfigProbe {
    private static final String IDENTIFIER = "itemloomprobe";
    private static final String UNKNOWN = "itemloomprobeunknown";
    private static final String TOKEN = "%itemloomprobe_name%";
    private static final String NODE = "<papi::itemloomprobe_name>";
    private static final String TEMP_PREFIX = "itemloom-papi-config-probe-";
    private static final String DEFINITIONS =
            "\uFEFF# 保留原文与换行\r\n"
                    + "dynamic:\r\n  material: PAPER\r\n  name: '"
                    + TOKEN
                    + "'\r\n  lore:\r\n"
                    + "    - '%ITEMLOOMPROBE_name%'\r\n"
                    + "    - '%itemloomprobe_NaMe%'\r\n"
                    + "    - '%itemloomprobe%'\r\n"
                    + "    - '%itemloomprobe_words with spaces%'\r\n"
                    + "    - '%itemloomprobeunknown_name%'\r\n";

    /** Synchronous server-thread entry; the caller must gate class loading on PAPI availability. */
    static Map<String, Object> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("PAPI config probe requires the server thread");
        Checks c = new Checks();
        var provider = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (provider == null || !provider.isEnabled()) {
            c.failures.put(
                    "setup", "Real PlaceholderAPI 2.12.3 is required; this is not a no-PAPI probe");
            return c.report(null, List.of());
        }
        String version = provider.getPluginMeta().getVersion();
        ProbeExpansion expansion = new ProbeExpansion();
        Set<String> before = null;
        boolean registered = false;
        try {
            c.that(
                    "2.12.3".equals(version),
                    "the optional sandbox runs actual PlaceholderAPI 2.12.3");
            c.that(
                    !PlaceholderAPI.isRegistered(IDENTIFIER)
                            && !PlaceholderAPI.isRegistered(UNKNOWN),
                    "both controlled namespaces are initially absent; no existing expansion is replaced");
            before = Set.copyOf(PlaceholderAPI.getRegisteredIdentifiers());
            registered = expansion.register();
            c.that(
                    registered && currentExpansion() == expansion,
                    "the scoped expansion registers through the real PAPI API");
            c.group("scanner", () -> scanner(c, expansion));
            temporary(c, "read-and-generate", root -> readAndGenerate(c, plugin, expansion, root));
            temporary(c, "save-and-reload", root -> saveAndReload(c, plugin, expansion, root));
            temporary(
                    c,
                    "sx-config",
                    root -> {
                        Path source = root.resolve("SX-Item/Item/item.yml");
                        Files.createDirectories(source.getParent());
                        String text =
                                "SXPapi:\n  ID: PAPER\n  Name: '<l:name>'\n  Random:\n    name: '%itemloomprobe_name%'\n";
                        Files.writeString(source, text);
                        try (ItemsService service =
                                new ItemsService(
                                        plugin,
                                        root,
                                        new PapiBridge(),
                                        root.resolve("ledger.json"))) {
                            c.that(
                                    service.reload(Bukkit.getConsoleSender()),
                                    "SX-only input tree loads alongside real PlaceholderAPI");
                            var first =
                                    service.create(
                                            "SXPapi", ProbePlayer.create("FirstSX"), Map.of());
                            var second =
                                    service.create(
                                            "SXPapi", ProbePlayer.create("SecondSX"), Map.of());
                            c.that(
                                    first.getItemMeta().getDisplayName().equals("PAPI-FirstSX")
                                            && second.getItemMeta()
                                                    .getDisplayName()
                                                    .equals("PAPI-SecondSX"),
                                    "SX locks resolve real PAPI separately for each viewer");
                            c.that(
                                    new ItemStateCodec()
                                            .read(first)
                                            .orElseThrow()
                                            .rolls()
                                            .get("name")
                                            .equals("PAPI-FirstSX"),
                                    "SX saves the actual PAPI-expanded lock value");
                        }
                        c.that(
                                Files.readString(source).equals(text),
                                "SX PAPI generation does not rewrite original configuration");
                    });
        } catch (Throwable error) {
            c.failed("setup", error);
        } finally {
            if (registered || currentExpansion() == expansion) {
                Set<String> original = before;
                c.group(
                        "expansion-cleanup",
                        () -> {
                            c.that(
                                    currentExpansion() == expansion,
                                    "cleanup still owns the registered expansion instance");
                            c.that(
                                    expansion.unregister()
                                            && !PlaceholderAPI.isRegistered(IDENTIFIER),
                                    "the temporary expansion unregisters after all checks");
                            c.that(
                                    original != null
                                            && original.equals(
                                                    Set.copyOf(
                                                            PlaceholderAPI
                                                                    .getRegisteredIdentifiers())),
                                    "the original expansion registry is restored exactly");
                            c.that(
                                    TOKEN.equals(PapiBridge.itemSections(TOKEN)),
                                    "after unregister, the former namespace stays literal again");
                        });
            }
        }
        return c.report(version, expansion.requests);
    }

    private static PlaceholderExpansion currentExpansion() {
        return PlaceholderAPIPlugin.getInstance()
                .getLocalExpansionManager()
                .getExpansion(IDENTIFIER);
    }

    private static void scanner(Checks c, ProbeExpansion expansion) {
        int requests = expansion.requests.size();
        List<ScanCase> cases =
                List.of(
                        new ScanCase(TOKEN, NODE, NODE),
                        new ScanCase(
                                "%ITEMLOOMPROBE_NaMe%",
                                "<papi::ITEMLOOMPROBE_NaMe>", "<papi::ITEMLOOMPROBE_NaMe>"),
                        new ScanCase(
                                "%itemloomprobe%",
                                "<papi::itemloomprobe_>", "<papi::itemloomprobe_>"),
                        new ScanCase(
                                "%itemloomprobe_words with spaces%",
                                "<papi::itemloomprobe_words with spaces>",
                                "<papi::itemloomprobe_words with spaces>"),
                        new ScanCase(
                                "%itemloomprobe words%",
                                "%itemloomprobe words%", "%itemloomprobe words%"),
                        new ScanCase(
                                "% itemloomprobe_name%",
                                "% itemloomprobe_name%", "% itemloomprobe_name%"),
                        new ScanCase(
                                "%itemloomprobeunknown_name%",
                                "%itemloomprobeunknown_name%", "<papi::itemloomprobeunknown_name>"),
                        new ScanCase(
                                "%itemloomprobe_unsupported_parameter%",
                                "<papi::itemloomprobe_unsupported_parameter>",
                                "<papi::itemloomprobe_unsupported_parameter>"),
                        new ScanCase("%%", "%%", "<papi::_>"),
                        new ScanCase("100% complete", "100% complete", "100% complete"),
                        new ScanCase(
                                "%itemloomprobe_name",
                                "%itemloomprobe_name", "%itemloomprobe_name"),
                        new ScanCase(
                                TOKEN + "%itemloomprobe%",
                                NODE + "<papi::itemloomprobe_>",
                                NODE + "<papi::itemloomprobe_>"),
                        new ScanCase(NODE, NODE, NODE));
        for (int i = 0; i < cases.size(); i++) {
            ScanCase fixture = cases.get(i);
            c.that(
                    fixture.item().equals(PapiBridge.itemSections(fixture.source())),
                    "item scanner case " + i + ": " + fixture.source());
            c.that(
                    fixture.action().equals(NiActionText.placeholders(fixture.source())),
                    "existing action scanner case " + i + ": " + fixture.source());
        }
        c.that(
                requests == expansion.requests.size(),
                "config preprocessing checks registration without evaluating expansion requests");
    }

    private static void readAndGenerate(
            Checks c, JavaPlugin plugin, ProbeExpansion expansion, Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Items")).resolve("original.yml");
        Files.writeString(source, DEFINITIONS, StandardCharsets.UTF_8);
        Path settings = root.resolve("config.yml");
        Files.writeString(
                settings, "\uFEFFprobe-literal: '" + TOKEN + "'\r\n", StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(source),
                originalSettings = Files.readAllBytes(settings);
        var repository = new NiRepository();
        int requests = expansion.requests.size();
        var raw = repository.read(root);
        var input = repository.read(root, PapiBridge::itemSections);
        c.that(
                TOKEN.equals(raw.items().get("dynamic").config().string("name")),
                "plain NiRepository reading has no implicit PAPI conversion");
        c.that(
                NODE.equals(input.items().get("dynamic").config().string("name")),
                "the repository item transform converts registered placeholders before YAML parsing");
        c.that(
                TOKEN.equals(input.settings().string("probe-literal")),
                "the item transform does not reinterpret the main config file");
        c.that(
                DEFINITIONS.substring(1).equals(input.sources().get("Items/original.yml")),
                "repository source verification retains raw placeholders and CRLF after its normal BOM removal");
        repository.verifyUnchanged(root, input);
        c.that(
                Arrays.equals(original, Files.readAllBytes(source))
                        && Arrays.equals(originalSettings, Files.readAllBytes(settings)),
                "repository parsing and source verification preserve BOM, CRLF and all file bytes");
        var bridge = new PapiBridge();
        try (PlayerActionState players = new PlayerActionState();
                NiCatalog catalog = new NiCatalog(1, input, root, plugin, bridge, players)) {
            Object value =
                    catalog.actionContext(null, Map.of())
                            .evaluate(
                                    """
                    var Manager = Java.type('pers.neige.neigeitems.manager.ItemConfigManager');
                    new Manager('Items');
                    """);
            c.that(
                    value instanceof LegacyItemConfigManager,
                    "the unchanged legacy script constructor creates the standalone config manager");
            var manager = (LegacyItemConfigManager) value;
            var config = manager.getItemConfigs().get("dynamic").getConfigSection();
            c.that(
                    NODE.equals(config.getString("name"))
                            && config.getStringList("lore")
                                    .equals(
                                            List.of(
                                                    "<papi::ITEMLOOMPROBE_name>",
                                                    "<papi::itemloomprobe_NaMe>",
                                                    "<papi::itemloomprobe_>",
                                                    "<papi::itemloomprobe_words with spaces>",
                                                    "%itemloomprobeunknown_name%")),
                    "ItemConfigManager applies the same registration, casing, empty-parameter and space rules in memory");
            manager.reloadItemConfigs();
            c.that(
                    NODE.equals(
                            manager.getItemConfigs()
                                    .get("dynamic")
                                    .getConfigSection()
                                    .getString("name")),
                    "standalone manager reload applies the same transform");
            var mainManager = new LegacyItemManager(catalog.items());
            var oldGenerator = mainManager.getItem("dynamic");
            mainManager.reloadItemConfigs();
            c.that(
                    NODE.equals(mainManager.getRealOriginConfig("dynamic").getString("name"))
                            && mainManager.getItem("dynamic") == oldGenerator,
                    "main manager source-only reload transforms PAPI in memory and retains the generator");
            mainManager.reload();
            c.that(
                    NODE.equals(mainManager.getRealOriginConfig("dynamic").getString("name"))
                            && mainManager.getItem("dynamic") != oldGenerator,
                    "main manager item reload compiles the transformed configuration without restarting PAPI");
            c.that(
                    requests == expansion.requests.size(),
                    "repository, catalog preparation and manager loading do not request viewer values");
            c.that(
                    Arrays.equals(original, Files.readAllBytes(source)),
                    "legacy constructor and reload leave the BOM/CRLF source untouched");
        }
        OfflinePlayer first = ProbePlayer.create("PapiProbeFirst"),
                second = ProbePlayer.create("PapiProbeSecond");
        try (ItemsService service =
                new ItemsService(plugin, root, bridge, root.resolve("return-ledger.json"))) {
            c.that(
                    service.reload(Bukkit.getConsoleSender()),
                    "ItemsService performs a real initial reload with actual PAPI enabled");
            assertDynamic(
                    c, service.create("dynamic", first, Map.of()), first.getName(), "first viewer");
            assertDynamic(
                    c,
                    service.create("dynamic", second, Map.of()),
                    second.getName(),
                    "second viewer");
            c.that(
                    service.reload(Bukkit.getConsoleSender()),
                    "a second public reload validates the raw source snapshot successfully");
            assertDynamic(
                    c,
                    service.create("dynamic", first, Map.of()),
                    first.getName(),
                    "reloaded viewer");
        }
        c.that(
                expansion.requests.stream().anyMatch(call -> call.equals("PapiProbeFirst:name"))
                        && expansion.requests.stream()
                                .anyMatch(call -> call.equals("PapiProbeSecond:name")),
                "normal generation invokes the real expansion separately for both viewers");
        c.that(
                Arrays.equals(original, Files.readAllBytes(source))
                        && Arrays.equals(originalSettings, Files.readAllBytes(settings)),
                "service reloads and generation leave all original file bytes untouched");
    }

    private static void assertDynamic(Checks c, ItemStack item, String viewer, String phase) {
        var nativeItem = CraftItemStack.asNMSCopy(item);
        var name = nativeItem.get(DataComponents.CUSTOM_NAME);
        var lore = nativeItem.get(DataComponents.LORE);
        c.that(
                name != null && name.getString().equals("PAPI-" + viewer),
                phase + ": the configured name expands for its actual viewer");
        c.that(
                lore != null
                        && lore.lines().stream()
                                .map(Component::getString)
                                .toList()
                                .equals(
                                        List.of(
                                                "PAPI-" + viewer,
                                                "mixed-parameter-case",
                                                "empty-parameter",
                                                "words with spaces",
                                                "%itemloomprobeunknown_name%")),
                phase
                        + ": casing, no underscore, spaced parameters and unknown namespace survive the full generation path");
    }

    private static void saveAndReload(
            Checks c, JavaPlugin plugin, ProbeExpansion expansion, Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Items")).resolve("mixed.yml");
        Files.writeString(source, DEFINITIONS, StandardCharsets.UTF_8);
        ItemStack original = literalItem();
        var snapshot = NiItemSnapshot.capture(original);
        var bridge = new PapiBridge();
        var repository = new NiRepository();
        OfflinePlayer viewer = ProbePlayer.create("PapiSavedViewer");
        try (PlayerActionState players = new PlayerActionState();
                NiCatalog catalog =
                        new NiCatalog(
                                1,
                                repository.read(root, PapiBridge::itemSections),
                                root,
                                plugin,
                                bridge,
                                players)) {
            int requests = expansion.requests.size();
            var manager = new LegacyItemManager(catalog.items());
            c.that(
                    manager.saveItem(original, "literal", "mixed.yml", false) == SaveResult.SUCCESS,
                    "saveItem persists a literal snapshot beside an existing dynamic definition");
            assertLiteral(
                    c,
                    original,
                    catalog.generate("literal", viewer, Map.of(), false),
                    "immediate saved generator");
            c.that(
                    requests == expansion.requests.size(),
                    "saving and generating the static snapshot do not evaluate its literal placeholders");
            String saved = Files.readString(source, StandardCharsets.UTF_8);
            c.that(
                    saved.contains("\\u0025itemloomprobe_name\\u0025") && saved.contains(TOKEN),
                    "saved snapshot percent signs are YAML-escaped while the neighboring dynamic definition stays dynamic");
            var transformed = repository.read(root, PapiBridge::itemSections);
            c.that(
                    snapshot.values().equals(transformed.items().get("literal").config().values()),
                    "PAPI preprocessing followed by YAML decoding recovers the complete original snapshot definition");
            c.that(
                    NODE.equals(transformed.items().get("dynamic").config().string("name")),
                    "percent protection is confined to the saved item, preserving neighboring placeholder conversion");
            var standalone = new LegacyItemConfigManager(catalog.items(), plugin, root, "Items");
            c.that(
                    snapshot.values()
                            .equals(
                                    NiYaml.fromSection(
                                                    standalone
                                                            .getItemConfigs()
                                                            .get("literal")
                                                            .getConfigSection())
                                            .values()),
                    "ItemConfigManager also reads the escaped snapshot without reinterpreting literal component data");
        }
        byte[] savedBytes = Files.readAllBytes(source);
        try (ItemsService service =
                new ItemsService(plugin, root, bridge, root.resolve("return-ledger.json"))) {
            for (int pass = 1; pass <= 2; pass++) {
                c.that(
                        service.reload(Bukkit.getConsoleSender()),
                        "saved files pass real ItemsService reload " + pass);
                int requests = expansion.requests.size();
                assertLiteral(
                        c,
                        original,
                        service.create("literal", viewer, Map.of()),
                        "disk reload " + pass);
                c.that(
                        requests == expansion.requests.size(),
                        "disk reload " + pass + ": literal saved data never reaches the expansion");
                assertDynamic(
                        c,
                        service.create("dynamic", viewer, Map.of()),
                        viewer.getName(),
                        "saved-file neighbor " + pass);
            }
        }
        c.that(
                Arrays.equals(savedBytes, Files.readAllBytes(source)),
                "reading and rebuilding saved items never rewrites the emitted file");
        c.that(
                original.getAmount() == 17
                        && NiItemSnapshot.capture(original).values().equals(snapshot.values()),
                "save, preprocessing and generation leave the entire source snapshot and its quantity unchanged");
    }

    private static ItemStack literalItem() {
        ItemStack item = CraftItemStack.asCraftCopy(new ItemStack(Material.PAPER, 17));
        var handle = CraftItemStack.unwrap(item);
        handle.set(
                DataComponents.CUSTOM_NAME,
                Component.literal("literal " + TOKEN + " <number::1_9>"));
        handle.set(
                DataComponents.LORE,
                new ItemLore(
                        List.of(
                                Component.literal(TOKEN),
                                Component.literal(""),
                                Component.literal(
                                        "first\nsecond " + TOKEN + " [(Int) 1,(Long) 2]"))));
        CompoundTag data = new CompoundTag();
        data.putString("literal.dot", "(Int) 42 " + TOKEN);
        data.putString("typed-array-text", "[(Int) 1,(Long) 2] " + TOKEN);
        data.putString("percent." + TOKEN, TOKEN);
        data.putString("escaped-percent-text", "\\u0025itemloomprobe_name\\u0025");
        data.putByteArray("empty-array", new byte[0]);
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("(Int) 7"));
        strings.add(StringTag.valueOf(TOKEN));
        data.put("string-list", strings);
        handle.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return item;
    }

    private static void assertLiteral(
            Checks c, ItemStack original, ItemStack rebuilt, String phase) {
        var expected = CraftItemStack.asNMSCopy(original);
        var actual = CraftItemStack.asNMSCopy(rebuilt);
        c.that(
                expected.get(DataComponents.CUSTOM_NAME)
                                .equals(actual.get(DataComponents.CUSTOM_NAME))
                        && expected.get(DataComponents.LORE)
                                .equals(actual.get(DataComponents.LORE)),
                phase
                        + ": literal name, lore, newline structure and node-looking text survive unchanged");
        var stored = actual.get(DataComponents.CUSTOM_DATA);
        c.that(stored != null, phase + ": the rebuilt item retains custom_data");
        CompoundTag data = stored.copyTag();
        data.remove(ItemStateCodec.KEY);
        c.that(
                expected.get(DataComponents.CUSTOM_DATA).copyTag().equals(data),
                phase
                        + ": typed-looking strings, dotted/percent keys, lists, empty arrays and backslash-u text remain exact NBT values");
        c.that(
                rebuilt.getType() == original.getType() && rebuilt.getAmount() == 1,
                phase + ": the saved definition preserves material and creates one item");
    }

    private record ScanCase(String source, String item, String action) {}

    private static final class ProbeExpansion extends PlaceholderExpansion {
        final List<String> requests = new ArrayList<>();

        @Override
        public String getIdentifier() {
            return IDENTIFIER;
        }

        @Override
        public String getAuthor() {
            return "ItemLoom probe";
        }

        @Override
        public String getVersion() {
            return "1.0.0";
        }

        @Override
        public String onRequest(OfflinePlayer player, String parameters) {
            String name = player == null ? "<null>" : player.getName();
            requests.add(name + ':' + parameters);
            return switch (parameters) {
                case "name" -> "PAPI-" + name;
                case "NaMe" -> "mixed-parameter-case";
                case "" -> "empty-parameter";
                case "words with spaces" -> parameters;
                default -> null;
            };
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedPath {
        void run(Path root) throws Exception;
    }

    private static void temporary(Checks c, String group, CheckedPath operation) {
        c.group(
                group,
                () -> {
                    Path root = Files.createTempDirectory(TEMP_PREFIX).toAbsolutePath().normalize();
                    try {
                        operation.run(root);
                    } finally {
                        cleanup(root);
                    }
                    c.that(!Files.exists(root), group + ": all owned temporary files are removed");
                });
    }

    private static void cleanup(Path root) throws IOException {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!root.getParent().equals(temporary)
                || !root.getFileName().toString().startsWith(TEMP_PREFIX))
            throw new IOException(
                    "Refusing to clean a path outside this probe's temporary directory");
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.toAbsolutePath().normalize().startsWith(root))
                    throw new IOException("Probe cleanup escaped its owned root");
                Files.deleteIfExists(file);
            }
        }
    }

    private static final class Checks {
        int attempted;
        final List<String> passed = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        void that(boolean value, String message) {
            attempted++;
            if (!value) throw new AssertionError(message);
            passed.add(message);
        }

        void group(String name, Checked operation) {
            try {
                operation.run();
            } catch (Throwable error) {
                failed(name, error);
            }
        }

        void failed(String name, Throwable error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            failures.put(name, trace.toString());
        }

        Map<String, Object> report(String version, List<String> requests) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", failures.isEmpty());
            result.put("checks", attempted);
            result.put("passedChecks", passed.size());
            result.put("assertions", List.copyOf(passed));
            result.put("failures", Map.copyOf(failures));
            result.put("papiVersion", version == null ? "unavailable" : version);
            result.put("requests", List.copyOf(requests));
            result.put("referenceRequired", false);
            result.put("realClient", false);
            result.put(
                    "boundary",
                    "Real PAPI registration and expansion API, real temporary files, legacy script constructor and public reload/generation; viewers are synthetic, and no client rendering or arbitrary third-party expansion is tested");
            return result;
        }
    }
}

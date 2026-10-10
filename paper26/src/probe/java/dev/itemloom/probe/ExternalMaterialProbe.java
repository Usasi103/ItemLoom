package dev.itemloom.probe;

import dev.itemloom.api.ItemContext;
import dev.itemloom.compat.ni.*;
import dev.itemloom.compat.sx.*;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.paper.compat.NiPaperRecipe;
import dev.itemloom.paper.integration.OptionalItemSources;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import dev.itemloom.paper.sx.SxCatalog;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Complete ItemBridge prototypes through both configuration frontends, using a controlled provider. */
final class ExternalMaterialProbe {
    private final List<String> checks = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Player alice = ProbePlayer.create("BridgeAlice");
    private final Player bob = ProbePlayer.create("BridgeBob");
    private final ItemStack original = prototype();
    private final ItemStack snapshot = original.clone();
    private final ItemStateCodec codec = new ItemStateCodec();
    private final NamespacedKey marker = new NamespacedKey("example", "provider");

    static Map<String, Object> run(JavaPlugin plugin) {
        var probe = new ExternalMaterialProbe();
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            probe.checks(plugin);
            report.put("passed", true);
        } catch (Throwable error) {
            report.put("passed", false);
            report.put("failure", error.toString());
            plugin.getLogger()
                    .log(java.util.logging.Level.WARNING, "External prototype probe failed", error);
        }
        report.put("assertions", probe.checks.size());
        report.put("checks", probe.checks);
        report.put("providerCalls", probe.calls.get());
        report.put(
                "boundary",
                "Real Paper item components; synthetic provider and players; no client rendering");
        return report;
    }

    private void checks(JavaPlugin plugin) throws Exception {
        OptionalItemSources sources =
                ItemBridgeProbe.sources(
                        plugin,
                        "fixture",
                        (id, player, context) -> {
                            calls.incrementAndGet();
                            if (id.equals("example:missing")) return null;
                            if (id.equals("example:shared")) return original;
                            if (id.equals("example:identified"))
                                return codec.write(
                                        original,
                                        new dev.itemloom.core.ItemIdentity("PriorRecipe", Map.of()),
                                        new CompoundTag());
                            if (!id.equals("example:personal"))
                                throw new IllegalArgumentException("Unexpected ID: " + id);
                            ItemStack personal = original.clone();
                            var handle = CraftItemStack.unwrap(personal);
                            if (!context.isEmpty())
                                throw new AssertionError(
                                        "ItemLoom rolls must not leak into provider parameters");
                            handle.set(
                                    DataComponents.CUSTOM_NAME,
                                    Component.literal(player.getName()));
                            return personal;
                        });
        NiEvaluation.Host host =
                new NiEvaluation.Host() {
                    public String placeholder(Object viewer, String text) {
                        return text;
                    }

                    public String itemValue(String key, String parameters) {
                        return null;
                    }

                    public void check(Object actions, NiEvaluation evaluation, String value) {}
                };
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            NiPaperRecipe plain =
                    recipe(
                            "BridgeNI",
                            "material: itembridge:fixture:example:shared\n",
                            scripts,
                            host,
                            sources);
            ItemStack first = generate(plain, alice, "first");
            untouched(first, "NI absent overrides");
            equal(first.getAmount(), 3, "NI keeps prototype quantity");
            equal(codec.readId(first), "BridgeNI", "NI writes its own identity");
            first.setAmount(40);
            first.editMeta(meta -> meta.setDisplayName("mutated result"));
            for (int i = 0; i < 5; i++)
                untouched(generate(plain, bob, "next"), "NI fresh prototype " + i);
            equal(calls.get(), 6, "NI constant external material is never appearance-cached");
            check(original.equals(snapshot), "NI never mutates provider-owned prototype");
            NiPaperRecipe overlay =
                    recipe(
                            "BridgeOverlay",
                            """
                    material: itembridge:fixture:example:shared
                    static:
                      material: DIRT
                      lore: [static line]
                      nbt: {vendor: {staticValue: kept}}
                    name: ItemLoom title
                    lore: []
                    unbreakable: false
                    nbt: {vendor: {override: changed}}
                    components: {rarity: epic}
                    """,
                            scripts,
                            host,
                            sources);
            ItemStack changed = generate(overlay, alice, "x");
            equal(
                    changed.getType(),
                    Material.DIAMOND_SWORD,
                    "Dynamic external base overrides static vanilla material");
            equal(name(changed), "ItemLoom title", "NI explicit name wins");
            check(
                    CraftItemStack.unwrap(changed)
                            .getOrDefault(DataComponents.LORE, ItemLore.EMPTY)
                            .lines()
                            .isEmpty(),
                    "NI explicit empty lore clears provider lore");
            check(
                    !CraftItemStack.unwrap(changed).has(DataComponents.UNBREAKABLE),
                    "NI explicit false clears inherited unbreakable");
            equal(
                    CraftItemStack.unwrap(changed).get(DataComponents.RARITY).getSerializedName(),
                    "epic",
                    "NI explicit component wins");
            data(changed, "NI overlay");
            equal(
                    NmsItems.customData(changed)
                            .getCompoundOrEmpty("vendor")
                            .getString("staticValue")
                            .orElse(null),
                    "kept",
                    "NI static NBT overlays external base");
            NiPaperRecipe fixed =
                    recipe(
                            "BridgeStatic",
                            "static: {material: 'itembridge:fixture:example:shared'}\nname: static base\n",
                            scripts,
                            host,
                            sources);
            equal(
                    generate(fixed, alice, "x").getType(),
                    Material.DIAMOND_SWORD,
                    "NI static external material works");
            NiPaperRecipe personal =
                    recipe(
                            "BridgePersonal",
                            "material: itembridge:fixture:example:personal\n",
                            scripts,
                            host,
                            sources);
            equal(
                    name(generate(personal, alice, "A")),
                    "BridgeAlice",
                    "NI forwards viewer with isolated provider context");
            equal(
                    name(generate(personal, bob, "B")),
                    "BridgeBob",
                    "NI never reuses another viewer result");
            NiPaperRecipe missing =
                    recipe(
                            "BridgeMissing",
                            "material: itembridge:fixture:example:missing\n",
                            scripts,
                            host,
                            sources);
            reject(() -> generate(missing, alice, "x"), "Missing external item fails generation");
            NiPaperRecipe identified =
                    recipe(
                            "BridgeNewIdentity",
                            "material: itembridge:fixture:example:identified\n",
                            scripts,
                            host,
                            sources);
            ItemStack adopted = generate(identified, alice, "new");
            equal(
                    codec.readId(adopted),
                    "BridgeNewIdentity",
                    "NI replaces the reserved identity from external sources");
            data(adopted, "NI replaces only its own identity");
        }
        var config =
                SxRepository.parse(
                        "ID: itembridge:fixture:example:shared\nUpdate: true\n", "memory");
        int before = calls.get();
        try (SxCatalog catalog = new SxCatalog(input(config), plugin, (p, t) -> t, sources)) {
            equal(calls.get(), before, "SX prepare never invokes or caches provider");
            ItemStack first = catalog.generate("BridgeSX", alice, Map.of());
            untouched(first, "SX absent overrides");
            equal(first.getAmount(), 3, "SX keeps unspecified quantity");
            equal(codec.readId(first), "BridgeSX", "SX writes its own identity");
            first.editMeta(meta -> meta.setDisplayName("poison"));
            untouched(catalog.generate("BridgeSX", bob, Map.of()), "SX repeated fresh base");
            equal(calls.get(), before + 2, "SX calls provider for every request");
            var replacement =
                    SxRepository.parse(
                            """
                    ID: itembridge:fixture:example:shared
                    Name: updated SX
                    Lore: []
                    Unbreakable: false
                    Amount: 2
                    Update: true
                    NBT: {vendor: {override: changed}}
                    Components: {rarity: epic}
                    """,
                            "memory");
            try (SxCatalog updated =
                    new SxCatalog(input(replacement), plugin, (p, t) -> t, sources)) {
                ItemStack result = updated.generate("BridgeSX", alice, Map.of());
                equal(result.getAmount(), 2, "SX explicit quantity wins");
                equal(name(result), "updated SX", "SX explicit name wins");
                check(
                        CraftItemStack.unwrap(result)
                                .getOrDefault(DataComponents.LORE, ItemLore.EMPTY)
                                .lines()
                                .isEmpty(),
                        "SX explicit empty lore clears provider lore");
                check(
                        !CraftItemStack.unwrap(result).has(DataComponents.UNBREAKABLE),
                        "SX false clears provider unbreakable");
                data(result, "SX overlay");
                first.setAmount(9);
                check(updated.update(alice, first), "SX changed recipe updates existing item");
                equal(first.getAmount(), 9, "SX update preserves live stack count");
                data(first, "SX update");
                equal(name(first), "updated SX", "SX update applies new name");
            }
        }
        var personal = SxRepository.parse("ID: itembridge:fixture:example:personal\n", "memory");
        try (SxCatalog catalog = new SxCatalog(input(personal), plugin, (p, t) -> t, sources)) {
            equal(
                    name(catalog.generate("BridgeSX", alice, Map.of("seed", "A"))),
                    "BridgeAlice",
                    "SX forwards viewer with isolated provider context");
            equal(
                    name(catalog.generate("BridgeSX", bob, Map.of("seed", "B"))),
                    "BridgeBob",
                    "SX isolates viewer results");
        }
        check(
                original.equals(snapshot),
                "All generation and update paths leave provider object untouched");
        String craftEngineId = System.getProperty("itemloom.probe.craftengine-item");
        if (craftEngineId != null && !craftEngineId.isBlank()) {
            String material = "itembridge:craftengine:" + craftEngineId;
            var realSources = new OptionalItemSources();
            ItemStack expected = realSources.material(material, null, Map.of());
            try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
                ItemStack generated =
                        generate(
                                recipe(
                                        "ActualCE",
                                        "material: '" + material + "'\n",
                                        scripts,
                                        host,
                                        realSources),
                                null,
                                "CE");
                sameForeignPayload(
                        expected, generated, "NI retains the complete real CraftEngine item");
            }
            try (SxCatalog catalog =
                    new SxCatalog(
                            input(SxRepository.parse("ID: '" + material + "'\n", "memory")),
                            plugin,
                            (p, t) -> t,
                            realSources)) {
                sameForeignPayload(
                        expected,
                        catalog.generate("BridgeSX", null, Map.of()),
                        "SX retains the complete real CraftEngine item");
            }
        }
    }

    private void sameForeignPayload(ItemStack expected, ItemStack actual, String label) {
        CompoundTag custom = NmsItems.customData(actual);
        custom.remove(ItemStateCodec.KEY);
        var stripped = NmsItems.withCustomData(actual, custom);
        check(
                net.minecraft.world.item.ItemStack.matches(
                        CraftItemStack.asNMSCopy(expected), CraftItemStack.asNMSCopy(stripped)),
                label);
    }

    private void untouched(ItemStack item, String label) {
        equal(name(item), "Provider title", label + " name");
        equal(
                CraftItemStack.unwrap(item).get(DataComponents.LORE).lines().getFirst().getString(),
                "Provider lore",
                label + " lore");
        check(CraftItemStack.unwrap(item).has(DataComponents.UNBREAKABLE), label + " unbreakable");
        check(
                CraftItemStack.unwrap(item).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE),
                label + " unconfigured component");
        data(item, label);
    }

    private void data(ItemStack item, String label) {
        equal(
                NmsItems.customData(item)
                        .getCompoundOrEmpty("vendor")
                        .getString("keep")
                        .orElse(null),
                "original",
                label + " nested NBT retained");
        equal(
                item.getItemMeta()
                        .getPersistentDataContainer()
                        .get(marker, PersistentDataType.STRING),
                "foreign marker",
                label + " PDC retained");
    }

    private static ItemStack prototype() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD, 3);
        item.editMeta(
                meta ->
                        meta.getPersistentDataContainer()
                                .set(
                                        new NamespacedKey("example", "provider"),
                                        PersistentDataType.STRING,
                                        "foreign marker"));
        var handle = CraftItemStack.asNMSCopy(item);
        handle.set(DataComponents.CUSTOM_NAME, Component.literal("Provider title"));
        handle.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Provider lore"))));
        handle.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        handle.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        item = CraftItemStack.asCraftMirror(handle);
        CompoundTag custom = NmsItems.customData(item), vendor = new CompoundTag();
        vendor.putString("keep", "original");
        vendor.putString("override", "before");
        custom.put("vendor", vendor);
        return NmsItems.withCustomData(item, custom);
    }

    private static NiPaperRecipe recipe(
            String id,
            String yaml,
            NiScripts scripts,
            NiEvaluation.Host host,
            OptionalItemSources sources) {
        return new NiPaperRecipe(
                new NiCompiledItem(id, "memory", NiYaml.read(yaml, "memory")),
                new NiNodes(),
                scripts,
                host,
                Clock.systemUTC(),
                message -> {
                    throw new AssertionError(message);
                },
                sources);
    }

    private static ItemStack generate(NiPaperRecipe recipe, Player player, String seed) {
        var context = new GenerationContext(Map.of("seed", seed), new Random());
        context.put(ItemContext.VIEWER, player);
        return recipe.create(context);
    }

    private static SxRepository.Input input(SxConfig config) {
        return new SxRepository.Input(
                new SxConfig(Map.of()),
                Map.of("BridgeSX", new SxRepository.Definition("BridgeSX", "memory", config, null)),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private static String name(ItemStack item) {
        return CraftItemStack.unwrap(item).get(DataComponents.CUSTOM_NAME).getString();
    }

    private void equal(Object actual, Object expected, String label) {
        check(Objects.equals(actual, expected), label + " (" + actual + ")");
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks.add(label);
    }

    private void reject(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks.add(label);
            return;
        }
        throw new AssertionError(label);
    }
}

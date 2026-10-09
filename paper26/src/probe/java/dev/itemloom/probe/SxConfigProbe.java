package dev.itemloom.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.api.ItemProvider;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import dev.itemloom.paper.sx.SxCatalog;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual Paper/NMS items with synthetic viewer; no SX plugin, networking, or real client required. */
@SuppressWarnings("deprecation")
final class SxConfigProbe implements Listener {
    private final List<String> checks = new ArrayList<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private int generated;

    @EventHandler
    public void generate(ItemGenerateEvent event) {
        if (event.getId().startsWith("SX")) generated++;
    }

    static Map<String, Object> run(JavaPlugin plugin) {
        var probe = new SxConfigProbe();
        probe.runChecks(plugin);
        return Map.of(
                "passed",
                probe.failures.isEmpty(),
                "checks",
                probe.checks,
                "assertions",
                probe.checks.size(),
                "failures",
                probe.failures,
                "realClient",
                false,
                "sxPluginInstalled",
                Bukkit.getPluginManager().getPlugin("SX-Item") != null);
    }

    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks.add(name);
    }

    private void group(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            failures.put(name, error.toString());
        }
    }

    private void reject(Runnable action, String name) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            checks.add(name);
            return;
        }
        throw new AssertionError(name);
    }

    private static void write(Path root, String file, String text) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text);
    }

    private void runChecks(JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            Path root = plugin.getDataFolder().toPath().resolve("sx-" + System.nanoTime());
            Path sx = root.resolve("SX-Item");
            write(root, "Items/ni.yml", "NIControl:\n  material: PAPER\n  name: NI unchanged\n");
            write(sx, "Config.yml", "DecimalPrecision: 2\nProtectNBT: [protect.data]\n");
            write(
                    sx,
                    "RandomString/quality.yml",
                    "quality: rare\ngroup:\n  - - first\n    - '<b:no:yes>hidden'\n    - last\n");
            write(
                    sx,
                    "Scripts/Global/common.js",
                    "var prefix = 'script'; function label() { return prefix; }");
            write(
                    sx,
                    "Scripts/Example.js",
                    "function name(handler,args) { return [label(), handler.replace('<l:quality>'), args[0]]; }");
            write(sx, "Item/items.yml", SOURCE);
            write(sx, "Item/NoLoad_broken/a.yml", "broken: [");
            ItemStack prototype = new ItemStack(Material.EMERALD, 3);
            prototype.editMeta(meta -> meta.setDisplayName("Imported original"));
            YamlConfiguration serialized = new YamlConfiguration();
            serialized.set("SXImport.Type", "Import");
            serialized.set("SXImport.Item", prototype);
            write(sx, "Item/import.yml", serialized.saveToString());
            var viewer = ProbePlayer.create("SxViewer");
            java.util.function.BiFunction<Object, String, String> papi =
                    (player, token) -> token.equals("player_level") ? "12" : null;
            var repository = new SxRepository();
            var input = repository.read(sx);
            try (SxCatalog catalog = new SxCatalog(input, plugin, papi)) {
                group(
                        "default",
                        () -> {
                            ItemStack item = catalog.generate("SXBlade", viewer, Map.of());
                            check(
                                    item.getType() == Material.DIAMOND_SWORD
                                            && item.getAmount() == 2,
                                    "SX ID and Amount produce actual stack");
                            check(
                                    item.getItemMeta().getDisplayName().equals("§brare blade"),
                                    "Name expands locked global value");
                            check(
                                    item.getItemMeta()
                                            .getLore()
                                            .equals(
                                                    List.of(
                                                            "first",
                                                            "last",
                                                            "script",
                                                            "rare",
                                                            "argument",
                                                            "12")),
                                    "Lore expands groups/scripts/PAPI and filters deleted line");
                            check(
                                    item.getEnchantmentLevel(Enchantment.SHARPNESS) == 3,
                                    "legacy enchantment name resolves");
                            var state = new ItemStateCodec().read(item).orElseThrow();
                            check(
                                    state.id().equals("SXBlade")
                                            && state.rolls().get("quality").equals("rare"),
                                    "SX uses independent ItemLoom identity and saved locks");
                            var data = NmsItems.customData(item);
                            check(
                                    data.getInt("number").orElseThrow() == 7
                                            && data.getCompoundOrEmpty("protect")
                                                    .getString("data")
                                                    .orElseThrow()
                                                    .equals("initial"),
                                    "typed custom NBT and dotted paths survive");
                            check(
                                    !data.contains("NeigeItems") && !data.contains("SX-Item"),
                                    "new item has no NI or SX plugin identity marker");
                            var modifiers =
                                    (net.minecraft.nbt.ListTag) data.get("AttributeModifiers");
                            check(
                                    ((net.minecraft.nbt.CompoundTag) modifiers.getFirst())
                                                    .get("UUID")
                                            instanceof net.minecraft.nbt.IntArrayTag,
                                    "legacy AttributeModifiers UUID list becomes NBT int array");
                            ItemStack second =
                                    catalog.generate("SXBlade", viewer, Map.of("quality", "epic"));
                            check(
                                    second.getItemMeta().getDisplayName().contains("epic")
                                            && item.getItemMeta().getDisplayName().contains("rare"),
                                    "parameters and locks stay request-local");
                            check(
                                    new ItemStateCodec()
                                            .read(catalog.generate("SXAlias", viewer, Map.of()))
                                            .orElseThrow()
                                            .id()
                                            .equals("SXBlade"),
                                    "item alias resolves canonical target");
                        });
                group(
                        "appearance",
                        () -> {
                            var leather = catalog.generate("SXLeather", null, Map.of());
                            check(
                                    ((LeatherArmorMeta) leather.getItemMeta()).getColor().asRGB()
                                            == 0xAABBCC,
                                    "leather Color accepts hex");
                            check(
                                    leather.getItemMeta().isUnbreakable()
                                            && leather.getItemMeta().getCustomModelData() == 11,
                                    "Unbreakable and CustomModelData work");
                            var nms = CraftItemStack.asNMSCopy(leather);
                            check(
                                    nms.get(DataComponents.CUSTOM_NAME)
                                            .getString()
                                            .equals("component wins"),
                                    "explicit Components override Name");
                            var attr = catalog.generate("SXAttribute", null, Map.of());
                            check(
                                    attr.getItemMeta().hasAttributeModifiers(),
                                    "legacy Attributes translate to modern registry and slot");
                            var clear =
                                    CraftItemStack.asNMSCopy(
                                            catalog.generate("SXClear", null, Map.of()));
                            check(
                                    !clear.has(DataComponents.ATTRIBUTE_MODIFIERS),
                                    "ClearAttribute removes default component");
                            var head =
                                    CraftItemStack.asNMSCopy(
                                            catalog.generate("SXHead", null, Map.of()));
                            check(
                                    head.has(DataComponents.PROFILE)
                                            && head.get(DataComponents.TOOLTIP_DISPLAY)
                                                    .hiddenComponents()
                                                    .contains(DataComponents.PROFILE),
                                    "profile and HIDE_PROFILE survive component ordering");
                            var potion =
                                    (PotionMeta)
                                            catalog.generate("SXPotion", null, Map.of())
                                                    .getItemMeta();
                            check(
                                    potion.getCustomEffects().size() == 1
                                            && potion.getCustomEffects()
                                                    .getFirst()
                                                    .getType()
                                                    .equals(
                                                            org.bukkit.potion.PotionEffectType
                                                                    .SPEED),
                                    "Potion selects requested effect instead of upstream wrong-effect bug");
                            check(
                                    CraftItemStack.asNMSCopy(
                                                    catalog.generate("SXSkullName", null, Map.of()))
                                            .has(DataComponents.PROFILE),
                                    "SkullName accepts player name");
                            check(
                                    CraftItemStack.asNMSCopy(
                                                    catalog.generate("SXSkullUuid", null, Map.of()))
                                            .has(DataComponents.PROFILE),
                                    "SkullName accepts UUID");
                            check(
                                    catalog.generate("SXLargeAmount", null, Map.of()).getAmount()
                                            == 120,
                                    "Amount preserves source quantity above normal stack size");
                        });
                group(
                        "materials-import",
                        () -> {
                            check(
                                    catalog.generate("SXNumeric", null, Map.of()).getType()
                                            == Material.SHEARS,
                                    "pre-flattening numeric material ID accepted on 26.2");
                            check(
                                    catalog.generate("SXNumericColor", null, Map.of()).getType()
                                            == Material.RED_WOOL,
                                    "numeric material with legacy data value resolves color");
                            check(
                                    catalog.generate("SXLegacyName", null, Map.of()).getType()
                                            == Material.WOODEN_SWORD,
                                    "legacy material name resolves modern type");
                            var damaged =
                                    CraftItemStack.asNMSCopy(
                                            catalog.generate("SXDamage", null, Map.of()));
                            check(
                                    damaged.getDamageValue()
                                            == (short)
                                                    (Material.DIAMOND_SWORD.getMaxDurability()
                                                            * 0.8),
                                    "remaining durability percentage converted");
                            check(
                                    catalog.generate("SXCanonical", null, Map.of()).getType()
                                            == Material.APPLE,
                                    "canonical ID wins over Id/id aliases");
                            check(
                                    catalog.generate("SXLower", null, Map.of()).getType()
                                            == Material.PAPER,
                                    "lowercase id alias accepted");
                            var imported = catalog.generate("SXImport", null, Map.of());
                            imported.setAmount(50);
                            check(
                                    catalog.generate("SXImport", null, Map.of()).getAmount() == 3
                                            && prototype.getAmount() == 3,
                                    "Import clones serialized prototype independently");
                            check(
                                    new ItemStateCodec()
                                            .read(imported)
                                            .orElseThrow()
                                            .id()
                                            .equals("SXImport"),
                                    "Import receives ItemLoom identity");
                        });
                group(
                        "update",
                        () -> {
                            try {
                                var old =
                                        catalog.generate(
                                                "SXBlade", viewer, Map.of("quality", "epic"));
                                old.setAmount(7);
                                old.addUnsafeEnchantment(Enchantment.UNBREAKING, 2);
                                var custom = NmsItems.customData(old);
                                custom.getCompoundOrEmpty("protect")
                                        .putString("data", "player value");
                                old = NmsItems.withCustomData(old, custom);
                                write(sx, "Item/items.yml", SOURCE.replace("blade", "updated"));
                                try (SxCatalog updated =
                                        new SxCatalog(repository.read(sx), plugin, papi)) {
                                    check(
                                            updated.update(viewer, old),
                                            "Update detects changed item config");
                                    check(
                                            old.getAmount() == 7
                                                    && old.getItemMeta()
                                                            .getDisplayName()
                                                            .equals("§bepic updated"),
                                            "update retains stack amount and locked random value");
                                    check(
                                            old.getEnchantmentLevel(Enchantment.UNBREAKING) == 2,
                                            "ProtectNBT preserves whole enchantments component");
                                    check(
                                            NmsItems.customData(old)
                                                    .getCompoundOrEmpty("protect")
                                                    .getString("data")
                                                    .orElseThrow()
                                                    .equals("player value"),
                                            "global ProtectNBT preserves custom dotted path");
                                    check(
                                            !updated.update(viewer, old),
                                            "unchanged generation hash avoids repeat update");
                                }
                                write(sx, "Item/items.yml", SOURCE);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        });
                group(
                        "protection-exclusions",
                        () -> {
                            try {
                                var old = catalog.generate("SXBlade", viewer, Map.of());
                                var data = NmsItems.customData(old);
                                data.getCompoundOrEmpty("protect").putString("data", "old");
                                old = NmsItems.withCustomData(old, data);
                                write(
                                        sx,
                                        "Item/items.yml",
                                        SOURCE.replace(
                                                        "ProtectNBT: [components.minecraft:enchantments]",
                                                        "ProtectNBT: ['!protect.data', 'absent.path']")
                                                .replace(
                                                        "protect.data: initial",
                                                        "protect.data: changed\n    absent: scalar"));
                                try (SxCatalog next =
                                        new SxCatalog(repository.read(sx), plugin, papi)) {
                                    check(
                                            next.update(viewer, old),
                                            "per-item protection exclusion updates");
                                    check(
                                            NmsItems.customData(old)
                                                    .getCompoundOrEmpty("protect")
                                                    .getString("data")
                                                    .orElseThrow()
                                                    .equals("changed"),
                                            "!path disables global protection");
                                    check(
                                            NmsItems.customData(old)
                                                    .getString("absent")
                                                    .orElseThrow()
                                                    .equals("scalar"),
                                            "missing old protected child does not erase scalar parent");
                                }
                                write(sx, "Item/items.yml", SOURCE);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        });
                group(
                        "script-listener-lifecycle",
                        () -> {
                            var counter = new java.util.concurrent.atomic.AtomicInteger();
                            plugin.getConfig().set("sx_probe_counter", counter);
                            try {
                                Map<String, String> scripts = new LinkedHashMap<>(input.scripts());
                                scripts.put(
                                        "Hook.js",
                                        """
                            var Event = Java.type('dev.itemloom.api.ItemGenerateEvent').class;
                            var Priority = Java.type('org.bukkit.event.EventPriority');
                            var Executor = Java.extend(Java.type('org.bukkit.plugin.EventExecutor'), {
                                execute: function(listener, event) { SXItem.getInst().getConfig().get('sx_probe_counter').incrementAndGet(); }
                            });
                            Bukkit.getPluginManager().registerEvent(Event, listener, Priority.NORMAL, new Executor(), SXItem.getInst());
                            """);
                                var hooked =
                                        new SxRepository.Input(
                                                input.settings(),
                                                input.items(),
                                                input.random(),
                                                scripts,
                                                input.sources());
                                try (SxCatalog listenerOwner =
                                        new SxCatalog(hooked, plugin, papi)) {
                                    catalog.generate("SXLower", null, Map.of());
                                    check(
                                            counter.get() == 1,
                                            "common SX script bindings register working Bukkit event listener");
                                }
                                catalog.generate("SXLower", null, Map.of());
                                check(
                                        counter.get() == 1,
                                        "closing SX revision removes its script listeners");
                                scripts.put(
                                        "ZZFail.js",
                                        "throw new Error('deliberate candidate failure');");
                                var broken =
                                        new SxRepository.Input(
                                                input.settings(),
                                                input.items(),
                                                input.random(),
                                                scripts,
                                                input.sources());
                                reject(
                                        () -> new SxCatalog(broken, plugin, papi),
                                        "SX script error aborts candidate after earlier registration");
                                catalog.generate("SXLower", null, Map.of());
                                check(
                                        counter.get() == 1,
                                        "failed SX candidate unregisters earlier script listeners");
                            } finally {
                                plugin.getConfig().set("sx_probe_counter", null);
                            }
                        });
                check(generated > 10, "SX generation dispatches independent ItemGenerateEvent");
                repository.verifyUnchanged(sx, input);
                check(
                        Files.readString(sx.resolve("Item/items.yml")).equals(SOURCE),
                        "loading and generating preserve original config bytes");
            }
            try (ItemsService service =
                    new ItemsService(plugin, root, papi, root.resolve("ledger.json"))) {
                check(
                        service.reload(Bukkit.getConsoleSender()),
                        "combined NI/SX revision installs");
                check(
                        service.ids().containsAll(List.of("SXBlade", "SXImport", "NIControl")),
                        "public catalog lists both formats");
                check(
                        service.create("NIControl", null, Map.of()).getType() == Material.PAPER,
                        "NI generation remains available");
                check(
                        service.create("SXBlade", null, Map.of()).getType()
                                == Material.DIAMOND_SWORD,
                        "public API routes SX definitions");
                check(
                        service.save(new ItemStack(Material.PAPER), "SXBlade", "conflict.yml", true)
                                == ItemLoom.SaveResult.CONFLICT,
                        "NI save cannot overwrite SX item ID");
                Object oldRevision = service.placeholderRevision();
                write(sx, "Item/bad.yml", "NIControl:\n  ID: STONE\n");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "NI/SX ID collision rejects reload");
                check(
                        service.placeholderRevision() == oldRevision
                                && service.create("SXBlade", null, Map.of()).getType()
                                        == Material.DIAMOND_SWORD,
                        "failed collision reload preserves installed catalog");
                write(sx, "Item/bad.yml", "Unknown:\n  Type: SXAttributeExtension\n");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "external generator Type diagnosed rather than ignored");
                write(sx, "Item/bad.yml", "Loop: Other\nOther: Loop\n");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "cyclic aliases reject reload without stack overflow");
                write(sx, "Item/bad.yml", "bad: [\n");
                check(!service.reload(Bukkit.getConsoleSender()), "damaged SX YAML rejects reload");
                write(
                        sx,
                        "Item/bad.yml",
                        "Bad:\n  ID: PAPER\n  Components:\n    minecraft:unknown: value\n");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "invalid static component rejects reload before generation");
                write(
                        sx,
                        "Item/bad.yml",
                        "Bad:\n  ID: PAPER\n  ProtectNBT: [components.custom_data.child]\n");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "component alias cannot overwrite ItemLoom identity via protection");
                write(sx, "Item/bad.yml", "Bad:\n  ID: PAPER\n  UnknownExtension: value\n");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "unknown field diagnosed rather than silently ignored");
                write(sx, "Item/bad.yml", "Bad:\n  Type: Import\n  Item: wrong\n");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "malformed Import rejects reload");
                write(sx, "Item/bad.yml", "# repaired\n");
                write(sx, "Scripts/Bad.js", "function {");
                check(
                        !service.reload(Bukkit.getConsoleSender()),
                        "broken SX script leaves previous revision available");
                write(sx, "Scripts/Bad.js", "// repaired\n");
                write(sx, "Item/bad.yml", "'sxprobe:configured':\n  ID: PAPER\n");
                check(
                        service.reload(Bukkit.getConsoleSender()),
                        "valid reload recovers after multiple failures");
                reject(
                        () ->
                                service.registerProvider(
                                        plugin,
                                        "sxprobe",
                                        new ItemProvider(
                                                Map.of(
                                                        "configured",
                                                        context -> new ItemStack(Material.STONE)))),
                        "external provider cannot shadow SX ID");
                check(
                        service.identify(service.create("SXImport", null, Map.of()))
                                .orElseThrow()
                                .id()
                                .equals("SXImport"),
                        "service identifies SX import");
            }
        } catch (Throwable error) {
            failures.put("fixture", error.toString());
        } finally {
            HandlerList.unregisterAll(this);
        }
    }

    private static final String SOURCE =
            """
        SXBlade:
          ID: DIAMOND_SWORD
          Amount: 2
          Name: '&b<l:quality> blade'
          Lore: ['<s:group>', '<j:Example.name#argument>', '%player_level%']
          EnchantList: ['DAMAGE_ALL:3']
          Update: true
          ProtectNBT: [components.minecraft:enchantments]
          NBT:
            number: '[int]<i:7_7>'
            protect.data: initial
            AttributeModifiers:
              - UUID: [1, 2, 3, 4]
        SXAlias: SXBlade
        SXLeather:
          ID: LEATHER_BOOTS
          Name: original
          Color: aabbcc
          Unbreakable: true
          CustomModelData: '<c:int 5 + 6>'
          Components:
            minecraft:custom_name: component wins
        SXAttribute:
          ID: DIAMOND_SWORD
          Attributes: ['GENERIC_ATTACK_SPEED:-0.7:1:HAND']
        SXClear:
          ID: DIAMOND_SWORD
          ClearAttribute: true
        SXHead:
          ID: PLAYER_HEAD
          ItemFlagList: [HIDE_PROFILE]
          Components:
            minecraft:profile:
              name: SxViewer
        SXPotion:
          ID: POTION
          Potion:
            SPEED:
              duration: '<c:int 20 * 10>'
              amplifier: 2
        SXNumeric:
          ID: '359:<0'
        SXDamage:
          ID: DIAMOND_SWORD
          Durability: 20%
        SXCanonical:
          id: PAPER
          Id: STONE
          ID: APPLE
        SXLower:
          id: PAPER
        SXSkullName:
          ID: PLAYER_HEAD
          SkullName: SxViewer
        SXSkullUuid:
          ID: PLAYER_HEAD
          SkullName: 00000000-0000-0000-0000-000000000123
        SXNumericColor:
          ID: '35:14'
        SXLegacyName:
          ID: WOOD_SWORD
        SXLargeAmount:
          ID: PAPER
          Amount: 120
        """;
}

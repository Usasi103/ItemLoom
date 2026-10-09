package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.compat.script.LegacyItemUpdateEvent;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** In-memory update/rebuild contracts; no player inventory, source file, or reference NI is used. */
final class ItemRevisionProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String YAML =
            """
            revision:
              material: IRON_SWORD
              name: generated-name
              lore: ['<keep>|<reroll>|<write>|<echo>']
              components:
                minecraft:damage: 2
                minecraft:repair_cost: 5
              options:
                id-section: item_id
                charge: 10
                durability: 80
                update:
                  enable: true
                  protect:
                    - PublicBukkitValues.itemloomrefine:data
                    - PublicBukkitValues.itemloomsoulbind:owner
                    - PublicBukkitValues.itemloomsoulbind:owner_name
                    - PublicBukkitValues.itemloom:equipment_id
                    - NeigeItems.owner
                    - 'escaped\\.key'
                  protect-components: [minecraft:damage, minecraft:repair_cost]
                  rebuild:
                    write: '<seed>'
                    echo: '<write>'
                    reroll: discarded-by-refresh
                    nested: {roll: '<keep>'}
                    ignored_number: 6
                  refresh: [reroll]
              sections:
                keep: fallback-keep
                reroll: fresh-roll
                seed: new-write
                write: fallback-write
                echo: fallback-echo
              event:
                post-generate:
                  actions: >-
                    js: data.put('post_id', String(item.getId()));
                    data.put('post_section', String(item.getSections().getString('keep')));
                    var revisionClose = player == null ? null : Java.type('pers.neige.neigeitems.utils.PlayerUtils').getMetadataEZ(player, 'item-revision-probe-close', null);
                    if (revisionClose != null) revisionClose.run();
            disabled:
              material: STONE
              options: {update: {enable: false}}
            failing:
              material: '<mat>'
              options: {update: {enable: true}}
              sections: {mat: STONE}
            """;
    private static final String SCRIPT =
            """
            function closeFromNode() {
                Java.type('pers.neige.neigeitems.utils.PlayerUtils').getMetadataEZ(player, 'item-revision-probe-close', null).run();
                return 'reroll';
            }
            function managerContract(player) {
                var A = Java.type('pers.neige.neigeitems.manager.ItemManager');
                var B = Packages.pers.neige.neigeitems.manager.ItemManager.INSTANCE;
                var HashMap = Java.type('java.util.HashMap');
                var rolls = new HashMap();
                rolls.put('keep', 'script-keep');
                var first = A.getItemStack('revision', player, rolls);
                var second = B.getItem('revision').getItemStack(player, '{"keep":"json-keep"}');
                return first != null && second != null && rolls.containsKey('reroll')
                    && A.getItemId(first) == 'revision' && A.INSTANCE == B
                    && ItemManager == B
                    && ItemEditorManager == Java.type('pers.neige.neigeitems.manager.ItemEditorManager').INSTANCE
                    && A.getItemStack('missing') == null && B.getItemAmount() == 3;
            }
            """;

    static Map<String, Object> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item revision probe requires the server thread");
        Checks checks = new Checks();
        checks.group(
                "manager",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        manager(checks, f);
                    }
                });
        checks.group(
                "hash-gates",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        hashGates(checks, f);
                    }
                });
        checks.group(
                "mutable-update-sections",
                () -> {
                    try (Fixture f =
                            new Fixture(
                                    plugin,
                                    source ->
                                            source.with(
                                                    "revision.options.update.rebuild.write",
                                                    "<changed-seed>"))) {
                        var sections = f.manager.getItem("revision").getSections();
                        sections.set("changed-seed", "script-seed");
                        sections.set("reroll", "script-roll");
                        ItemStack item = f.old("revision", true);
                        f.manager.update(f.player, item);
                        checks.that(
                                "script-seed".equals(rolls(item).get("write")),
                                "update rebuild expressions use the shared mutable sections");
                        checks.that(
                                "script-roll".equals(rolls(item).get("reroll")),
                                "update regeneration uses the same modified sections");
                    }
                });
        checks.group(
                "generation-event-close",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        f.generatedEffect = event -> f.catalog.close();
                        checks.reject(
                                () -> f.manager.getItemStack("revision", f.player),
                                "generation listener close rejects returning a stale item");
                        checks.that(
                                f.generated == 1 && !f.catalog.active(),
                                "generation close is observed before post-generation scripts run");
                    }
                });
        checks.group(
                "update-generation-close",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        ItemStack item = f.old("revision", true);
                        byte[] before = item.serializeAsBytes();
                        f.generatedEffect = event -> f.catalog.close();
                        checks.reject(
                                () -> f.manager.update(f.player, item),
                                "generation listener close rejects an in-progress update");
                        checks.that(
                                Arrays.equals(before, item.serializeAsBytes()) && f.post == 0,
                                "closed generation does not commit or fire the later update gate");
                    }
                });
        checks.group(
                "post-generation-close",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        f.state.setMetadata(
                                f.player.getUniqueId(),
                                "item-revision-probe-close",
                                (Runnable) f.catalog::close);
                        checks.reject(
                                () -> f.manager.getItemStack("revision", f.player),
                                "synchronous post-generation close rejects returning a stale item");
                        checks.that(
                                !f.catalog.active(),
                                "post-generation script closed its own catalog");
                    }
                });
        checks.group(
                "update-node-close",
                () -> {
                    try (Fixture f =
                            new Fixture(
                                    plugin,
                                    source ->
                                            source.with(
                                                    "revision.options.update.refresh",
                                                    List.of("<js::manager.js::closeFromNode>")))) {
                        ItemStack item = f.old("revision", true);
                        byte[] before = item.serializeAsBytes();
                        f.state.setMetadata(
                                f.player.getUniqueId(),
                                "item-revision-probe-close",
                                (Runnable) f.catalog::close);
                        checks.reject(
                                () -> f.manager.update(f.player, item),
                                "update node close rejects continuing with a stale revision");
                        checks.that(
                                f.pre == 0
                                        && f.post == 0
                                        && f.generated == 0
                                        && Arrays.equals(before, item.serializeAsBytes()),
                                "update node close fires no later gates and leaves the old item unchanged");
                    }
                });
        checks.group(
                "empty-candidate",
                () -> {
                    try (Fixture f =
                            new Fixture(
                                    plugin, source -> source.with("disabled.material", "AIR"))) {
                        ItemStack item = f.old("disabled", true);
                        var inventoryHandle = CraftItemStack.unwrap(item);
                        f.manager.rebuild(item, f.player, new HashMap<>());
                        checks.that(
                                item.isEmpty()
                                        && inventoryHandle.isEmpty()
                                        && inventoryHandle.getCount() == 0,
                                "rebuild to AIR clears the aliased inventory handle before detaching its Craft mirror");
                    }
                });
        for (boolean craft : List.of(false, true)) {
            String kind = craft ? "craft" : "bukkit";
            checks.group(
                    kind + "/update",
                    () -> {
                        try (Fixture f = new Fixture(plugin)) {
                            update(checks, f, craft);
                        }
                    });
            checks.group(
                    kind + "/rebuild-refresh",
                    () -> {
                        try (Fixture f = new Fixture(plugin)) {
                            rebuildRefresh(checks, f, craft);
                        }
                    });
            checks.group(
                    kind + "/cancellation",
                    () -> {
                        try (Fixture f = new Fixture(plugin)) {
                            cancellation(checks, f, craft);
                        }
                    });
            checks.group(
                    kind + "/failed-generation",
                    () -> {
                        try (Fixture f = new Fixture(plugin)) {
                            failedGeneration(checks, f, craft);
                        }
                    });
        }
        checks.group(
                "closed",
                () -> {
                    Fixture f = new Fixture(plugin);
                    var generator = f.manager.getItem("revision");
                    ItemStack item = f.old("revision", true);
                    f.close();
                    checks.reject(
                            () -> generator.getItemStack(f.player, Map.of()),
                            "closed generator rejects generation");
                    checks.reject(
                            () -> f.manager.update(f.player, item, true),
                            "closed manager rejects update");
                });
        return Map.of(
                "passed",
                checks.failures.isEmpty(),
                "checks",
                checks.count,
                "verified",
                List.copyOf(checks.passed),
                "failures",
                Map.copyOf(checks.failures),
                "referenceRequired",
                false,
                "realClient",
                false);
    }

    private static void manager(Checks checks, Fixture f) {
        checks.that(
                f.manager.getItemIds().equals(List.of("disabled", "failing", "revision")),
                "manager lists sorted ids");
        checks.that(
                f.manager.getItem("missing") == null && f.manager.getItemStack("missing") == null,
                "unknown legacy ids return null");
        checks.that(
                f.manager.isNiItem(new ItemStack(Material.STONE)) == null,
                "ordinary items have no legacy identity");
        Map<String, String> data = new HashMap<>();
        data.put("keep", "caller-keep");
        ItemStack created = f.manager.getItemStack("revision", f.player, data);
        checks.that(
                created.getAmount() == 1 && "caller-keep".equals(rolls(created).get("keep")),
                "manager honors saved rolls");
        checks.that(
                "fresh-roll".equals(data.get("reroll")) && "revision".equals(data.get("item_id")),
                "generation fills the caller's mutable roll map");
        checks.that(
                "revision".equals(data.get("post_id"))
                        && "fallback-keep".equals(data.get("post_section")),
                "post-generation item exposes legacy getters and data mutations reach the caller's cache");
        checks.that(
                f.manager.getItemStack("revision") != null
                        && f.manager.getItemStack("revision", f.player) != null
                        && f.manager.getItemStack("revision", "{}") != null
                        && f.manager.getItemStack("revision", new HashMap<>()) != null
                        && f.manager.getItemStack("revision", f.player, "{}") != null,
                "legacy generation overloads remain callable");
        var origin = f.manager.getOriginConfig("revision");
        origin.set("name", "local-copy");
        checks.that(
                "generated-name".equals(f.manager.getOriginConfig("revision").getString("name")),
                "origin copies do not mutate the source mirror");
        checks.that(
                f.manager.getRealOriginConfig("revision")
                        == f.manager.getRealOriginConfig("revision"),
                "real-origin script mirror is stable within a revision");
        var evaluation = f.catalog.actionContext(f.player, Map.of()).evaluation();
        Object aliases =
                evaluation.scoped(
                        () ->
                                evaluation
                                        .scripts()
                                        .invoke(
                                                "manager.js",
                                                "managerContract",
                                                Map.of(),
                                                f.player));
        checks.that(
                Boolean.TRUE.equals(aliases),
                "Java.type, Packages and bare manager globals dispatch without old classes");
    }

    private static void hashGates(Checks checks, Fixture f) {
        ItemStack current = f.manager.getItemStack("revision", f.player);
        byte[] before = current.serializeAsBytes();
        f.reset();
        f.manager.update(f.player, current);
        checks.that(
                f.pre == 0 && f.generated == 0 && Arrays.equals(before, current.serializeAsBytes()),
                "equal definition hash skips regeneration");
        new LegacyNbtItemStack(current)
                .getOrCreateTag()
                .getCompound("NeigeItems")
                .remove("hashCode");
        before = current.serializeAsBytes();
        f.manager.update(f.player, current);
        checks.that(
                f.pre == 0 && Arrays.equals(before, current.serializeAsBytes()),
                "missing historical hash is treated as current");
        f.manager.update(f.player, current, true);
        checks.that(f.pre == 1 && f.post == 1 && f.generated == 1, "force bypasses the hash gate");
        ItemStack disabled = f.old("disabled", true);
        before = disabled.serializeAsBytes();
        f.reset();
        f.manager.update(f.player, disabled, true);
        checks.that(
                f.pre == 0 && Arrays.equals(before, disabled.serializeAsBytes()),
                "force does not bypass update.enable=false");
    }

    private static void update(Checks checks, Fixture f, boolean craft) {
        ItemStack item = f.old("revision", craft);
        f.manager.update(f.player, item);
        checks.that(
                item.getAmount() == 7 && item.getType() == Material.IRON_SWORD,
                "update retains amount while applying the new material");
        Map<String, String> data = rolls(item);
        checks.that(
                "saved-keep".equals(data.get("keep")) && "fresh-roll".equals(data.get("reroll")),
                "update preserves unselected rolls and refreshes selected rolls");
        checks.that(
                "new-write".equals(data.get("write")) && "old-write".equals(data.get("echo")),
                "all rebuild expressions resolve against the pre-mutation cache");
        checks.that(
                "saved-keep".equals(data.get("nested.roll")) && !data.containsKey("ignored_number"),
                "update flattens nested string rebuild leaves and ignores numeric leaves");
        CompoundTag properties = CODEC.properties(item);
        checks.that(
                properties.getIntOr("charge", -1) == 13
                        && properties.getIntOr("durability", -1) == 91
                        && properties.getIntOr("maxCharge", -1) == 10
                        && properties.getIntOr("maxDurability", -1) == 80,
                "update retains existing charge and durability without silently clamping them");
        CompoundTag custom = NmsItems.customData(item),
                pdc = custom.getCompoundOrEmpty("PublicBukkitValues");
        checks.that(
                "refine-payload".equals(pdc.getStringOr("itemloomrefine:data", ""))
                        && "owner-id".equals(pdc.getStringOr("itemloomsoulbind:owner", ""))
                        && "owner-name".equals(pdc.getStringOr("itemloomsoulbind:owner_name", ""))
                        && "equipment-id".equals(pdc.getStringOr("itemloom:equipment_id", "")),
                "all four live equipment PDC protection paths survive");
        checks.that(
                "item-owner".equals(properties.getStringOr("owner", ""))
                        && custom.getIntOr("escaped.key", -1) == 42,
                "legacy property paths and escaped-dot NBT paths are protected");
        var nms = CraftItemStack.unwrap(item);
        checks.that(
                Integer.valueOf(9).equals(nms.get(DataComponents.DAMAGE))
                        && nms.get(DataComponents.REPAIR_COST) == null,
                "protected components retain old values and old absence");
        checks.that(
                "generated-name".equals(nms.get(DataComponents.CUSTOM_NAME).getString()),
                "unprotected display components update");
        checks.that(
                properties.getIntOr("hashCode", 0) == f.manager.getItem("revision").getHashCode()
                        && !custom.contains("NeigeItems"),
                "update writes the new hash only into independent storage");
        checks.that(
                f.pre == 1 && f.post == 1 && f.generated == 1,
                "update fires both update gates and normal generation exactly once");
    }

    private static void rebuildRefresh(Checks checks, Fixture f, boolean craft) {
        ItemStack rebuilt = f.old("revision", craft);
        Map<String, String> patch = new HashMap<>();
        patch.put("keep", "manual-keep");
        patch.put("reroll", null);
        checks.that(
                f.manager.rebuild(
                        rebuilt,
                        f.player,
                        patch,
                        List.of("PublicBukkitValues.itemloomrefine:data")),
                "map rebuild reports success");
        checks.that(
                "manual-keep".equals(rolls(rebuilt).get("keep"))
                        && "fresh-roll".equals(rolls(rebuilt).get("reroll"))
                        && "old-write".equals(rolls(rebuilt).get("write")),
                "map rebuild applies overrides and null deletion without update policy");
        checks.that(
                rebuilt.getAmount() == 7
                        && "generated-name"
                                .equals(
                                        CraftItemStack.unwrap(rebuilt)
                                                .get(DataComponents.CUSTOM_NAME)
                                                .getString()),
                "rebuild replaces appearance without resetting amount");
        checks.that(
                "refine-payload"
                        .equals(
                                NmsItems.customData(rebuilt)
                                        .getCompoundOrEmpty("PublicBukkitValues")
                                        .getStringOr("itemloomrefine:data", "")),
                "rebuild applies explicit protection paths");
        ItemStack selected = f.old("revision", craft);
        f.manager.rebuild(selected, f.player, List.of("keep", "missing"), null);
        checks.that(
                "saved-keep".equals(rolls(selected).get("keep"))
                        && "fallback-write".equals(rolls(selected).get("write")),
                "list rebuild retains only the named existing roll values");
        ItemStack refreshed = f.old("revision", craft);
        f.operations.refresh(
                f.player, refreshed, List.of("reroll"), Map.of("keep", "refresh-keep"), null);
        var nms = CraftItemStack.unwrap(refreshed);
        checks.that(
                "refresh-keep".equals(rolls(refreshed).get("keep"))
                        && "fresh-roll".equals(rolls(refreshed).get("reroll")),
                "refresh updates stored rolls");
        checks.that(
                "old-name".equals(nms.get(DataComponents.CUSTOM_NAME).getString())
                        && Integer.valueOf(9).equals(nms.get(DataComponents.DAMAGE))
                        && nms.get(DataComponents.REPAIR_COST) == null,
                "legacy refresh retains old non-custom-data components");
        checks.that(
                refreshed.getAmount() == 7
                        && CODEC.properties(refreshed).getIntOr("charge", -1) == 13,
                "refresh retains amount and charge");
        checks.that(
                !f.manager.rebuild(new ItemStack(Material.AIR), f.player, Map.of())
                        && f.manager.rebuild(new ItemStack(Material.STONE), f.player, Map.of()),
                "rebuild retains legacy empty and ordinary-item result values");
    }

    private static void cancellation(Checks checks, Fixture f, boolean craft) {
        ItemStack item = f.old("revision", craft);
        byte[] before = item.serializeAsBytes();
        f.preEffect =
                event -> {
                    event.getData().put("keep", "cancelled-change");
                    event.setCancelled(true);
                };
        f.manager.update(f.player, item);
        checks.that(
                f.pre == 1
                        && f.generated == 0
                        && f.post == 0
                        && Arrays.equals(before, item.serializeAsBytes()),
                "pre cancellation leaves the old item byte-identical");
        f.reset();
        f.postEffect =
                event -> {
                    event.getNewItem().setAmount(99);
                    event.setCancelled(true);
                };
        f.manager.update(f.player, item);
        checks.that(
                f.pre == 1
                        && f.generated == 1
                        && f.post == 1
                        && Arrays.equals(before, item.serializeAsBytes()),
                "post cancellation leaves the old item byte-identical");
        f.reset();
        f.preEffect = event -> event.getData().put("keep", "listener-keep");
        f.postEffect =
                event -> {
                    event.getNewItem().setAmount(99);
                    new LegacyNbtItemStack(event.getNewItem())
                            .getOrCreateTag()
                            .putString("event-marker", "kept");
                };
        f.manager.update(f.player, item);
        checks.that(
                "listener-keep".equals(rolls(item).get("keep"))
                        && "kept".equals(NmsItems.customData(item).getStringOr("event-marker", ""))
                        && item.getAmount() == 7,
                "event edits to new rolls and data survive while old amount is retained");
    }

    private static void failedGeneration(Checks checks, Fixture f, boolean craft) {
        ItemStack item = f.old("failing", craft);
        byte[] before = item.serializeAsBytes();
        checks.that(
                f.manager.getItemStack(
                                "failing",
                                f.player,
                                new HashMap<>(Map.of("mat", "NOT_A_REAL_MATERIAL")))
                        == null,
                "legacy creation returns null for an invalid generated material");
        f.manager.rebuild(item, f.player, Map.of("mat", "NOT_A_REAL_MATERIAL"));
        checks.that(
                Arrays.equals(before, item.serializeAsBytes()),
                "failed rebuild leaves the old item byte-identical");
        new LegacyNbtItemStack(item)
                .getOrCreateTag()
                .getCompound("NeigeItems")
                .putString("data", "{\"mat\":\"NOT_A_REAL_MATERIAL\"}");
        before = item.serializeAsBytes();
        f.manager.update(f.player, item);
        checks.that(
                Arrays.equals(before, item.serializeAsBytes()),
                "failed update leaves the old item byte-identical");
    }

    private static Map<String, String> rolls(ItemStack item) {
        return CODEC.read(item).orElseThrow().rolls();
    }

    private static final class Fixture implements AutoCloseable {
        final Player player = ProbePlayer.create("ItemRevisionProbe");
        final PlayerActionState state = new PlayerActionState();
        final Listener listener = new Listener() {};
        final NiCatalog catalog;
        final NiItemOperations operations;
        final LegacyItemManager manager;
        int pre, post, generated;
        Consumer<LegacyItemUpdateEvent.PreGenerate> preEffect;
        Consumer<LegacyItemUpdateEvent.PostGenerate> postEffect;
        Consumer<ItemGenerateEvent> generatedEffect;

        Fixture(JavaPlugin plugin) {
            this(plugin, java.util.function.UnaryOperator.identity());
        }

        Fixture(JavaPlugin plugin, java.util.function.UnaryOperator<NiConfig> configuration) {
            state.join(player.getUniqueId());
            Map<String, NiRepository.Definition> definitions = new LinkedHashMap<>();
            NiConfig source = configuration.apply(NiYaml.read(YAML, "memory/Items/revision.yml"));
            source.keys()
                    .forEach(
                            id ->
                                    definitions.put(
                                            id,
                                            new NiRepository.Definition(
                                                    id,
                                                    "memory/Items/revision.yml",
                                                    source.section(id))));
            NiRepository.Input input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            definitions,
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of("manager.js", SCRIPT),
                            Map.of(),
                            Map.of());
            try {
                catalog = new NiCatalog(1, input, plugin, (viewer, text) -> null, state);
            } catch (Throwable error) {
                state.close();
                throw error;
            }
            operations = new NiItemOperations(catalog);
            manager = new LegacyItemManager(operations);
            Bukkit.getPluginManager()
                    .registerEvent(
                            LegacyItemUpdateEvent.PreGenerate.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, raw) -> {
                                var event = (LegacyItemUpdateEvent.PreGenerate) raw;
                                if (event.getPlayer() != player) return;
                                pre++;
                                if (preEffect != null) preEffect.accept(event);
                            },
                            plugin);
            Bukkit.getPluginManager()
                    .registerEvent(
                            LegacyItemUpdateEvent.PostGenerate.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, raw) -> {
                                var event = (LegacyItemUpdateEvent.PostGenerate) raw;
                                if (event.getPlayer() != player) return;
                                post++;
                                if (postEffect != null) postEffect.accept(event);
                            },
                            plugin);
            Bukkit.getPluginManager()
                    .registerEvent(
                            ItemGenerateEvent.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, raw) -> {
                                ItemGenerateEvent event = (ItemGenerateEvent) raw;
                                if (event.getViewer() != player) return;
                                generated++;
                                if (generatedEffect != null) generatedEffect.accept(event);
                            },
                            plugin);
        }

        ItemStack old(String id, boolean craft) {
            CompoundTag properties = new CompoundTag();
            properties.putInt("hashCode", manager.getItem(id).getHashCode() ^ 0x40000000);
            properties.putInt("charge", 13);
            properties.putInt("maxCharge", 20);
            properties.putInt("durability", 91);
            properties.putInt("maxDurability", 100);
            properties.putString("owner", "item-owner");
            Map<String, String> rolls =
                    new HashMap<>(
                            Map.of(
                                    "keep",
                                    "saved-keep",
                                    "reroll",
                                    "old-roll",
                                    "write",
                                    "old-write",
                                    "echo",
                                    "old-echo",
                                    "seed",
                                    "new-write",
                                    "mat",
                                    "STONE"));
            ItemStack item =
                    CODEC.write(
                            new ItemStack(Material.DIAMOND_SWORD, 7),
                            new ItemIdentity(id, rolls),
                            properties);
            var tag = new LegacyNbtItemStack(item).getOrCreateTag();
            LegacyNbt.Compound pdc = tag.getOrCreateCompound("PublicBukkitValues");
            pdc.putString("itemloomrefine:data", "refine-payload");
            pdc.putString("itemloomsoulbind:owner", "owner-id");
            pdc.putString("itemloomsoulbind:owner_name", "owner-name");
            pdc.putString("itemloom:equipment_id", "equipment-id");
            tag.putInt("escaped.key", 42);
            var nms = CraftItemStack.unwrap(item);
            nms.set(DataComponents.CUSTOM_NAME, Component.literal("old-name"));
            nms.set(DataComponents.DAMAGE, 9);
            nms.remove(DataComponents.REPAIR_COST);
            reset();
            ItemStack result = craft ? item : new ItemStack(item);
            if ((result instanceof CraftItemStack) != craft)
                throw new AssertionError("Wrong fixture item implementation");
            return result;
        }

        void reset() {
            pre = 0;
            post = 0;
            generated = 0;
            preEffect = null;
            postEffect = null;
            generatedEffect = null;
        }

        @Override
        public void close() {
            HandlerList.unregisterAll(listener);
            try {
                catalog.close();
            } finally {
                state.close();
            }
        }
    }

    private static final class Checks {
        int count;
        String group;
        final List<String> passed = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        void group(String name, Runnable test) {
            group = name;
            try {
                test.run();
            } catch (Throwable error) {
                failures.put(name, error.toString());
            }
        }

        void that(boolean condition, String message) {
            count++;
            if (!condition) throw new AssertionError(message);
            passed.add(group + ": " + message);
        }

        void reject(Runnable operation, String message) {
            boolean failed = false;
            try {
                operation.run();
            } catch (RuntimeException expected) {
                failed = true;
            }
            that(failed, message);
        }
    }
}

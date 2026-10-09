package dev.itemloom.probe;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.api.ItemActionEvent;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.ItemListeners;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyActionTrigger;
import dev.itemloom.paper.compat.script.LegacyItemActionEvent;
import dev.itemloom.paper.compat.script.LegacyItemActionType;
import dev.itemloom.paper.compat.script.LegacyItemInfo;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Synthetic events and players exercise the real trigger runtime, not a Minecraft client. */
final class ItemTriggerProbe {
    private static final String RECORDS = "item-trigger-probe-records";
    private static final String CLOSE = "item-trigger-probe-close";
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String ACTIONS =
            """
            pair:
              right:
                group: shared-click
                cooldown: 60000
                consume: {amount: '1'}
                sync: 'probe-record: right'
              left:
                group: shared-click
                cooldown: 60000
                consume: {amount: '1'}
                sync: 'probe-record: left'
              all:
                group: shared-click
                cooldown: 60000
                consume: {amount: '2'}
                sync: 'probe-record: all'
            cancelled:
              right:
                group: cancelled
                cooldown: 60000
                consume: {amount: '1'}
                sync: 'probe-record: cancelled-body'
            gate_mutation:
              right:
                cooldown: 0
                consume: {amount: '1'}
                sync: 'probe-record: gate-right'
              all:
                cooldown: 0
                consume: {amount: '1'}
                sync: 'probe-record: gate-all'
              eat:
                cooldown: 0
                consume: {amount: '1'}
                sync: 'probe-record: gate-eat'
              tick_hand:
                tick: 0
                sync: 'probe-record: gate-tick'
            pre:
              right:
                cooldown: 0
                consume:
                  pre: ['probe-record: pre', 'return: pre-stop', 'probe-record: forbidden-pre']
                  condition: 'true'
                  amount: '1'
                  deny: 'probe-record: denied-pre'
                sync: 'probe-record: after-pre'
            denied:
              right:
                cooldown: 0
                consume:
                  pre: 'probe-record: denied-pre'
                  condition: 'true'
                  amount: '2'
                  deny: 'probe-record: denied'
                sync: 'probe-record: forbidden-denied'
            charge:
              right:
                cooldown: 0
                consume: {amount: '2'}
                sync: 'probe-record: charge'
            eat:
              eat:
                cooldown: 0
                consume: {amount: '2'}
                sync: ['probe-record: eat', 'probe-close: now']
            ticks:
              tick_hand:
                group: default-tick
                consume: {amount: '1'}
                sync: 'probe-record: tick'
            shared:
              tick_hand:
                group: shared-tick
                tick: 2
                sync: 'probe-record: hand'
              tick_offhand:
                group: shared-tick
                tick: 2
                sync: 'probe-record: offhand'
            scan_bad:
              tick_0:
                group: scan-bad
                tick: 2
                sync: 'probe-record: forbidden-bad-counter'
            scan_good:
              tick_1:
                tick: 0
                sync: 'probe-record: scan-good'
            branches:
              right:
                cooldown: 0
                actions: ['probe-record: actions', 'return: actions-stop', 'probe-record: forbidden-actions']
                async: ['probe-record: async', 'return: async-stop', 'probe-record: forbidden-async']
                sync: 'probe-record: sync'
            """;
    private static final String EXPANSION =
            """
            var manager = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
            var PlayerUtils = Java.type('pers.neige.neigeitems.utils.PlayerUtils');
            var Bukkit = Java.type('org.bukkit.Bukkit');
            function enable() {
                manager.addConsumer('probe-record', true, function(context, text) {
                    PlayerUtils.getMetadataEZ(context.getPlayer(), 'item-trigger-probe-records', null)
                        .add(String(text) + '|' + Bukkit.isPrimaryThread());
                });
                manager.addConsumer('probe-close', false, function(context, text) {
                    PlayerUtils.getMetadataEZ(context.getPlayer(), 'item-trigger-probe-close', null).run();
                });
            }
            """;

    static CompletionStage<Map<String, Object>> run(JavaPlugin probe) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Run the item trigger probe on the server thread");
        Runner runner = new Runner(probe);
        return runner.run();
    }

    private static final class Runner {
        private final JavaPlugin plugin;
        private final List<String> checks = new ArrayList<>();
        private final List<Fixture> fixtures = new ArrayList<>();
        private final List<BukkitTask> tasks = new ArrayList<>();
        private final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        CompletionStage<Map<String, Object>> run() {
            try {
                Fixture fixture = fixture("ItemTriggerProbe");
                for (int slot = -1; slot <= 41; slot++)
                    check(
                            fixture.catalog.triggers().hasTickSlot(slot)
                                    == fixture.catalog.triggers().hasKey("tick_" + slot),
                            "tick slot index agrees at " + slot);
                for (String type :
                        List.of(
                                "tick_hand",
                                "tick_offhand",
                                "tick_head",
                                "tick_chest",
                                "tick_legs",
                                "tick_feet"))
                    check(
                            fixture.catalog.triggers().hasTickEquipment(type)
                                    == fixture.catalog.triggers().hasKey(type),
                            "equipment tick index agrees for " + type);
                paired(fixture);
                cancelled(fixture);
                gateMutations();
                gateClose();
                preAndDeny(fixture);
                chargeConsumption(fixture);
                ticks(fixture);
                unmatchedItems();
                tickIsolation();
                publicReturns();
                legacyContracts()
                        .thenCompose(ignored -> branches(fixture))
                        .thenCompose(ignored -> eating())
                        .whenComplete(
                                (ignored, error) -> {
                                    if (error != null) finish(error);
                                    else finish(null);
                                });
                if (!result.isDone())
                    tasks.add(
                            Bukkit.getScheduler()
                                    .runTaskLater(
                                            plugin,
                                            () ->
                                                    finish(
                                                            new AssertionError(
                                                                    "Item trigger probe timed out")),
                                            200));
            } catch (Throwable error) {
                finish(error);
            }
            return result;
        }

        private Fixture fixture(String name) {
            Fixture fixture = new Fixture(plugin, name);
            fixtures.add(fixture);
            return fixture;
        }

        private void paired(Fixture fixture) {
            fixture.clearRecords();
            ItemStack item = fixture.hold("pair", 4, null);
            fixture.player.setSneaking(true);
            PlayerInteractEvent shifted = click(fixture.player, item, Action.RIGHT_CLICK_AIR);
            fixture.catalog.triggers().interact(fixture.player, item, shifted);
            check(
                    !shifted.isCancelled()
                            && fixture.records.isEmpty()
                            && fixture.gates.isEmpty()
                            && item.getAmount() == 4,
                    "shift-right without shift actions does not fall back");
            fixture.player.setSneaking(false);
            PlayerInteractEvent right = click(fixture.player, item, Action.RIGHT_CLICK_AIR);
            fixture.catalog.triggers().interact(fixture.player, item, right);
            check(right.isCancelled(), "right trigger cancels the vanilla interaction");
            check(
                    item.getAmount() == 3 && fixture.labels().equals(List.of("right", "all")),
                    "right plus all execute both actions and consume once using right's amount");
            check(
                    fixture.gates.equals(List.of("right", "all")),
                    "right and all each emit a trigger gate");
            check(
                    fixture.state.getCooldown(fixture.player.getUniqueId(), "ni:shared-click")
                            == 60000,
                    "click cooldown reserves the configured group");
            PlayerInteractEvent left = click(fixture.player, item, Action.LEFT_CLICK_AIR);
            fixture.catalog.triggers().interact(fixture.player, item, left);
            check(
                    left.isCancelled()
                            && item.getAmount() == 3
                            && fixture.labels().equals(List.of("right", "all"))
                            && fixture.gates.size() == 2,
                    "shared cooldown blocks the other direction before gates or consume");
        }

        private void cancelled(Fixture fixture) {
            fixture.clearRecords();
            ItemStack item = fixture.hold("cancelled", 3, null);
            fixture.cancelGate = true;
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    fixture.gates.equals(List.of("right"))
                            && item.getAmount() == 3
                            && fixture.records.isEmpty(),
                    "cancelled ItemActionEvent prevents consumption and execution");
            check(
                    fixture.state.getCooldown(fixture.player.getUniqueId(), "ni:cancelled")
                            == 60000,
                    "cancelled ItemActionEvent retains its cooldown reservation");
            fixture.cancelGate = false;
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    fixture.gates.size() == 1 && fixture.records.isEmpty() && item.getAmount() == 3,
                    "removing cancellation does not bypass the already reserved cooldown");
        }

        private void preAndDeny(Fixture fixture) {
            fixture.clearRecords();
            ItemStack item = fixture.hold("pre", 3, null);
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    item.getAmount() == 2 && fixture.labels().equals(List.of("pre", "after-pre")),
                    "pre STOP stops its own sequence but does not gate successful consumption");
            fixture.clearRecords();
            item = fixture.hold("denied", 1, null);
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    item.getAmount() == 1
                            && fixture.labels().equals(List.of("denied-pre", "denied")),
                    "insufficient quantity runs deny without consuming or executing the main action");
        }

        private void gateMutations() {
            Fixture fixture = fixture("ItemTriggerGateProbe");
            List<GateMutation> mutations =
                    List.of(
                            new GateMutation("cleared stack", item -> item.setAmount(0), 0),
                            new GateMutation(
                                    "removed identity",
                                    item ->
                                            editCustomData(
                                                    item, data -> data.remove(ItemStateCodec.KEY)),
                                    3),
                            new GateMutation(
                                    "replaced id",
                                    item ->
                                            editCustomData(
                                                    item,
                                                    data ->
                                                            data.getCompoundOrEmpty(
                                                                            ItemStateCodec.KEY)
                                                                    .putString("id", "pre")),
                                    3),
                            new GateMutation(
                                    "changed saved rolls",
                                    item ->
                                            editCustomData(
                                                    item,
                                                    data ->
                                                            data.getCompoundOrEmpty(
                                                                            ItemStateCodec.KEY)
                                                                    .getCompoundOrEmpty("rolls")
                                                                    .putString("seed", "changed")),
                                    3),
                            new GateMutation(
                                    "malformed legacy JSON",
                                    item ->
                                            editCustomData(
                                                    item,
                                                    data -> {
                                                        data.remove(ItemStateCodec.KEY);
                                                        CompoundTag legacy = new CompoundTag();
                                                        legacy.putString("id", "gate_mutation");
                                                        legacy.putString("data", "{invalid-json");
                                                        data.put("NeigeItems", legacy);
                                                    }),
                                    3),
                            new GateMutation(
                                    "malformed identity",
                                    item ->
                                            editCustomData(
                                                    item,
                                                    data ->
                                                            data.getCompoundOrEmpty(
                                                                            ItemStateCodec.KEY)
                                                                    .putInt("schema", 999)),
                                    3));
            for (GateMutation mutation : mutations) {
                fixture.gateEffect = event -> mutation.change.accept(event.getItemStack());
                fixture.clearRecords();
                ItemStack item = fixture.hold("gate_mutation", 3, null);
                fixture.catalog
                        .triggers()
                        .interact(
                                fixture.player,
                                item,
                                click(fixture.player, item, Action.RIGHT_CLICK_AIR));
                check(
                        item.getAmount() == mutation.amount
                                && fixture.records.isEmpty()
                                && fixture.gates.equals(List.of("right")),
                        "click gate with "
                                + mutation.name
                                + " stops before the all gate or consumption");

                fixture.clearRecords();
                item = fixture.hold("gate_mutation", 3, null);
                PlayerItemConsumeEvent event =
                        new PlayerItemConsumeEvent(fixture.player, item, EquipmentSlot.HAND);
                ItemStack snapshot = event.getItem();
                try {
                    fixture.catalog
                            .triggers()
                            .handle("eat", fixture.player, snapshot, event, true, true, true, true);
                } finally {
                    fixture.catalog.triggers().finishEvent();
                }
                check(
                        snapshot.getAmount() == mutation.amount
                                && fixture.records.isEmpty()
                                && fixture.gates.equals(List.of("eat")),
                        "eat gate with " + mutation.name + " stops without consuming or executing");

                fixture.clearRecords();
                item = fixture.hold("gate_mutation", 3, null);
                fixture.catalog.triggers().tick("tick_hand", fixture.player, item);
                check(
                        item.getAmount() == mutation.amount
                                && fixture.records.isEmpty()
                                && fixture.gates.equals(List.of("tick_hand")),
                        "tick gate with "
                                + mutation.name
                                + " stops without building an invalid context");
            }
            fixture.clearRecords();
            fixture.gateEffect =
                    event ->
                            editCustomData(
                                    event.getItemStack(),
                                    data -> {
                                        CompoundTag legacy = new CompoundTag();
                                        legacy.putString("id", "conflicting-id");
                                        data.put("NeigeItems", legacy);
                                    });
            ItemStack conflicting = fixture.hold("gate_mutation", 3, null);
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            conflicting,
                            click(fixture.player, conflicting, Action.RIGHT_CLICK_AIR));
            check(
                    conflicting.getAmount() == 3 && fixture.records.isEmpty(),
                    "a gate introducing conflicting legacy identity stops before context use or consumption");
            fixture.clearRecords();
            fixture.gateEffect =
                    event ->
                            editCustomData(
                                    event.getItemStack(),
                                    data -> data.putString("gate-marker", "kept"));
            ItemStack item = fixture.hold("gate_mutation", 3, null);
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    item.getAmount() == 2
                            && fixture.labels().equals(List.of("gate-right", "gate-all"))
                            && NmsItems.customData(item)
                                    .getString("gate-marker")
                                    .orElse("")
                                    .equals("kept"),
                    "gate edits outside identity remain visible and permit the configured trigger");
            fixture.gateEffect = null;
        }

        private void gateClose() {
            Fixture fixture = fixture("ItemTriggerGateCloseProbe");
            fixture.gateEffect = event -> fixture.catalog.close();
            ItemStack item = fixture.hold("gate_mutation", 3, null);
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    item.getAmount() == 3
                            && fixture.records.isEmpty()
                            && fixture.gates.equals(List.of("right")),
                    "closing the catalog inside its first gate prevents the next gate, consumption and actions");
        }

        private void chargeConsumption(Fixture fixture) {
            fixture.clearRecords();
            ItemStack item = fixture.hold("charge", 4, 5);
            ItemStack original = item.clone();
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    item.getAmount() == 1 && charge(item) == 3,
                    "charge consumption updates only one item in a stack");
            ItemStack returned = fixture.player.getInventory().getItem(1);
            check(
                    returned != null
                            && returned.getAmount() == 3
                            && charge(returned) == 5
                            && inventoryAmount(fixture.player) == 4,
                    "unchanged charged stack remainder is returned immediately");
            CompoundTag expected = NmsItems.customData(original);
            expected.getCompoundOrEmpty(ItemStateCodec.KEY)
                    .getCompoundOrEmpty("properties")
                    .putInt("charge", 3);
            check(
                    NmsItems.customData(item).equals(expected)
                            && NmsItems.customData(returned).equals(NmsItems.customData(original)),
                    "charge split preserves saved rolls, compound roll projection, unknown state and foreign NBT");
            var components =
                    CraftItemStack.asNMSCopy(original)
                            .getComponentsPatch()
                            .forget(type -> type == DataComponents.CUSTOM_DATA);
            check(
                    CraftItemStack.asNMSCopy(item)
                                    .getComponentsPatch()
                                    .forget(type -> type == DataComponents.CUSTOM_DATA)
                                    .equals(components)
                            && CraftItemStack.asNMSCopy(returned)
                                    .getComponentsPatch()
                                    .forget(type -> type == DataComponents.CUSTOM_DATA)
                                    .equals(components),
                    "charge split preserves every non-custom-data component");
            check(
                    CODEC.read(item)
                                    .orElseThrow()
                                    .rolls()
                                    .equals(CODEC.read(original).orElseThrow().rolls())
                            && !NmsItems.customData(item).contains("NeigeItems"),
                    "charge consumption keeps independent identity without rerolling");
        }

        private void ticks(Fixture fixture) {
            fixture.clearRecords();
            ItemStack item = fixture.hold("ticks", 5, null);
            fixture.catalog.triggers().tick("tick_hand", fixture.player, item);
            check(
                    fixture.labels().equals(List.of("tick")),
                    "default tick trigger fires on its first visit");
            for (int visit = 0; visit < 10; visit++)
                fixture.catalog.triggers().tick("tick_hand", fixture.player, item);
            check(
                    fixture.labels().equals(List.of("tick")),
                    "default tick interval skips the next ten visits");
            fixture.catalog.triggers().tick("tick_hand", fixture.player, item);
            check(
                    fixture.labels().equals(List.of("tick", "tick")) && item.getAmount() == 5,
                    "default tick interval fires again after eleven visits and ignores consume");
            fixture.clearRecords();
            ItemStack hand = fixture.hold("shared", 1, null),
                    offhand = fixture.item("shared", 1, null);
            fixture.player.getInventory().setItemInOffHand(offhand);
            fixture.catalog.triggers().tick("tick_hand", fixture.player, hand);
            fixture.catalog.triggers().tick("tick_offhand", fixture.player, offhand);
            fixture.catalog.triggers().tick("tick_hand", fixture.player, hand);
            check(
                    fixture.labels().equals(List.of("hand")),
                    "two slots decrement one shared tick group counter");
            fixture.catalog.triggers().tick("tick_offhand", fixture.player, offhand);
            check(
                    fixture.labels().equals(List.of("hand", "offhand")),
                    "shared tick group can next fire from the other slot");
        }

        private CompletionStage<Void> branches(Fixture fixture) {
            fixture.clearRecords();
            ItemStack item = fixture.hold("branches", 1, null);
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            return until(
                            () -> fixture.labels().containsAll(List.of("actions", "async", "sync")),
                            80)
                    .thenCompose(ignored -> later(2))
                    .thenRun(
                            () -> {
                                check(
                                        fixture.records.size() == 3
                                                && fixture.labels()
                                                        .containsAll(
                                                                List.of(
                                                                        "actions", "async",
                                                                        "sync")),
                                        "STOP in actions and async does not cancel other trigger branches");
                                List<String> records =
                                        fixture.records.stream().map(Object::toString).toList();
                                check(
                                        records.contains("actions|false")
                                                && records.contains("async|false")
                                                && records.contains("sync|true"),
                                        "actions and async run on a worker while sync runs on the server thread");
                            });
        }

        private void unmatchedItems() {
            Fixture fixture = fixture("ItemTriggerUnmatchedProbe");
            ItemStack item = fixture.hold("no-actions", 2, null);
            editCustomData(
                    item,
                    tag ->
                            tag.getCompoundOrEmpty(ItemStateCodec.KEY)
                                    .putString("rolls", "malformed-unused-rolls"));
            byte[] before = item.serializeAsBytes();
            check(
                    "no-actions".equals(fixture.catalog.triggers().itemId(item)),
                    "ID-only gates do not parse unrelated saved rolls");
            var interaction = click(fixture.player, item, Action.RIGHT_CLICK_AIR);
            fixture.catalog.triggers().interact(fixture.player, item, interaction);
            fixture.catalog.triggers().tick("tick_hand", fixture.player, item);
            var consume = new PlayerItemConsumeEvent(fixture.player, item, EquipmentSlot.HAND);
            fixture.catalog
                    .triggers()
                    .handle("eat", fixture.player, item, consume, true, true, true, true);
            check(
                    !interaction.isCancelled()
                            && !consume.isCancelled()
                            && fixture.records.isEmpty()
                            && fixture.gates.isEmpty()
                            && java.util.Arrays.equals(before, item.serializeAsBytes()),
                    "unmatched interaction, tick and eat skip full item parsing without mutations");
        }

        private void tickIsolation() {
            Fixture fixture = fixture("ItemTriggerScanProbe");
            fixture.hold("scan_bad", 1, null);
            fixture.player.getInventory().setItem(1, fixture.item("scan_good", 1, null));
            fixture.state.setMetadata(
                    fixture.player.getUniqueId(), "TICK-scan-bad", "invalid-counter");
            Player other = ProbePlayer.create("ItemTriggerLaterPlayer");
            fixture.state.join(other);
            fixture.state.setMetadata(other.getUniqueId(), RECORDS, fixture.records);
            other.getInventory().setItem(0, new ItemStack(Material.AIR));
            other.getInventory().setItem(1, fixture.item("scan_good", 1, null));
            List<LogRecord> warnings = new ArrayList<>();
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(java.util.logging.Level.ALL);
            logger.addHandler(
                    new Handler() {
                        @Override
                        public void publish(LogRecord record) {
                            warnings.add(record);
                        }

                        @Override
                        public void flush() {}

                        @Override
                        public void close() {}
                    });
            ItemListeners listeners = new ItemListeners(fixture.catalog::triggers, logger);
            listeners.tick(List.of(fixture.player, other));
            check(
                    fixture.labels().equals(List.of("scan-good", "scan-good")),
                    "a bad tick counter does not skip the next slot or the next player");
            listeners.tick(List.of(fixture.player, other));
            check(
                    fixture.labels()
                                    .equals(
                                            List.of(
                                                    "scan-good",
                                                    "scan-good",
                                                    "scan-good",
                                                    "scan-good"))
                            && warnings.size() == 1
                            && warnings.getFirst().getThrown() instanceof ClassCastException,
                    "repeated bad tick slots remain isolated and their warning is rate limited");
        }

        private CompletionStage<Void> legacyContracts() {
            Fixture fixture = fixture("ItemTriggerLegacyProbe");
            NiActionContext script =
                    fixture.catalog.actionContext(
                            fixture.player,
                            Map.of(
                                    "probeListener",
                                    fixture.legacyListener,
                                    "probeCalls",
                                    fixture.legacyEvents));
            check(
                    Boolean.TRUE.equals(
                            script.evaluate(
                                    """
                    var Types = Java.type('pers.neige.neigeitems.item.action.ItemActionType');
                    var constants = Types.values();
                    var valid = constants.length === 80 && Types.valueOf('RIGHT') === Types.RIGHT
                        && Types.RIGHT.getType() === 'right' && Types.matchType(null) === null
                        && Types.matchType('RIGHT') === null && Types.matchType('custom') === null;
                    for (var i = 0; i < constants.length; i++) {
                        valid = valid && Types.matchType(constants[i].getType()) === constants[i]
                            && Types.valueOf(constants[i].name()) === constants[i];
                    }
                    valid && Packages.pers.neige.neigeitems.item.action.ItemActionType.RIGHT === Types.RIGHT;
                    """)),
                    "all 80 legacy enum constants support exact lookup, valueOf, values and Packages aliases");
            script.evaluate(
                    """
                    (function(calls, who, listener) {
                        var Event = Java.type('pers.neige.neigeitems.event.ItemActionEvent');
                        var Bukkit = Java.type('org.bukkit.Bukkit');
                        var Priority = Java.type('org.bukkit.event.EventPriority');
                        Bukkit.getPluginManager().registerEvent(Event.class, listener, Priority.HIGH,
                            function(ignored, event) {
                                if (event.getPlayer().getUniqueId().equals(who)) calls.add(event);
                            }, plugin, false);
                    })(probeCalls, player.getUniqueId(), probeListener);
                    """);
            fixture.cancelGate = true;
            ItemStack item = fixture.hold("gate_mutation", 3, null);
            fixture.catalog
                    .triggers()
                    .interact(
                            fixture.player,
                            item,
                            click(fixture.player, item, Action.RIGHT_CLICK_AIR));
            check(
                    fixture.apiEvents.size() == 1
                            && fixture.legacyEvents.size() == 1
                            && fixture.apiEvents.getFirst() == fixture.legacyEvents.getFirst(),
                    "independent and old scripted listeners receive one shared event instance");
            LegacyItemActionEvent emitted = fixture.legacyEvents.getFirst();
            check(
                    emitted.isCancelled()
                            && item.getAmount() == 3
                            && fixture.records.isEmpty()
                            && emitted.getHandlers() == ItemActionEvent.getHandlerList()
                            && LegacyItemActionEvent.getHandlerList()
                                    == ItemActionEvent.getHandlerList(),
                    "legacy and independent listeners share cancellation and the same HandlerList");
            check(
                    emitted.getType() == LegacyItemActionType.RIGHT
                            && emitted.getTriggerKey().equals("right")
                            && emitted.getItemInfo().getItemStack() == item
                            && emitted.getItemInfo().getId().equals("gate_mutation")
                            && emitted.getTrigger().getId().equals("gate_mutation")
                            && emitted.getTrigger().getType().equals("right"),
                    "emitted legacy event exposes its real item info, enum and compiled trigger");

            var config =
                    NiYaml.toSection(
                            NiYaml.read(
                                    """
                    group: legacy-probe
                    cooldown: 'js: context.getGlobal().get("duration")'
                    tick: ['js: null', 'raw: 7']
                    consume:
                      pre: 'probe-record: legacy-pre'
                      condition: 'true'
                      amount: '2'
                      deny: 'probe-record: legacy-deny'
                    actions: ['probe-record: legacy-actions', 'return: stop', 'probe-record: forbidden-legacy-actions']
                    async: 'probe-record: legacy-async'
                    sync: 'probe-record: legacy-sync'
                    """,
                                    "memory/legacy-trigger.yml"));
            NiActionContext constructors =
                    fixture.catalog.actionContext(fixture.player, Map.of("probeConfig", config));
            Object created =
                    constructors.evaluate(
                            """
                    var Trigger = Java.type('pers.neige.neigeitems.item.action.ActionTrigger');
                    var Consume = Java.type('pers.neige.neigeitems.item.action.ConsumeInfo');
                    var value = new Trigger('script-id', 'RIGHT', probeConfig);
                    var fromPackages = new Packages.pers.neige.neigeitems.item.action.ActionTrigger('packages-id', 'right', probeConfig);
                    var consume = new Consume(probeConfig.getConfigurationSection('consume'));
                    var packagesConsume = new Packages.pers.neige.neigeitems.item.action.ConsumeInfo(probeConfig.getConfigurationSection('consume'));
                    global.put('constructorsValid', value instanceof Trigger && Trigger.class.isInstance(value)
                        && fromPackages instanceof Trigger && consume instanceof Consume && packagesConsume instanceof Consume
                        && Consume.class.isInstance(consume) && value.getCooldown().getManager() === manager
                        && value.getTick().getManager() === manager && value.getActions().getManager() === manager
                        && value.getConsume().getPre().getManager() === manager);
                    global.put('constructedConsume', consume);
                    value;
                    """);
            check(
                    created instanceof LegacyActionTrigger
                            && Boolean.TRUE.equals(
                                    constructors.getGlobal().get("constructorsValid")),
                    "revision factories preserve Java.type and Packages constructors, instanceof, class and manager identity");
            LegacyActionTrigger trigger = (LegacyActionTrigger) created;
            constructors.getGlobal().put("duration", 4321L);
            check(
                    trigger.getId().equals("script-id")
                            && trigger.getType().equals("RIGHT")
                            && trigger.getConfig() == config
                            && trigger.getGroup().equals("legacy-probe")
                            && trigger.getCooldown().getType() == Long.class
                            && trigger.getCooldown().get(constructors) == 4321L
                            && trigger.getTick().get(constructors) == 7L,
                    "trigger getters expose the original configuration and executable long evaluators");
            constructors.getGlobal().remove("duration");
            check(
                    trigger.getCooldown().get(constructors) == null
                            && trigger.getCooldown().getOrDefault(constructors, 99L) == 99L,
                    "a null legacy evaluator result preserves get and getOrDefault behavior");
            config.set("group", "changed-after-compilation");
            check(
                    trigger.getConfig().getString("group").equals("changed-after-compilation")
                            && trigger.getGroup().equals("legacy-probe"),
                    "getConfig retains its mutable source while compiled trigger fields remain stable");
            check(
                    trigger.getConsume().getCondition().equals("true")
                            && trigger.getConsume().getAmount().equals("2")
                            && trigger.getConsume().getPre() != null
                            && trigger.getConsume().getDeny() != null,
                    "consume exposes its condition, amount and actual compiled actions");

            fixture.clearRecords();
            NiActionContext calls =
                    fixture.catalog.actionContext(
                            fixture.player,
                            Map.of(
                                    "probeItem",
                                    item,
                                    "probeInfo",
                                    LegacyItemInfo.inspect(item),
                                    "probeTrigger",
                                    trigger));
            check(
                    Boolean.FALSE.equals(
                                    calls.evaluate(
                                            """
                    var Event = Java.type('pers.neige.neigeitems.event.ItemActionEvent');
                    var Type = Java.type('pers.neige.neigeitems.item.action.ItemActionType');
                    var event = new Event(player, probeItem, probeInfo, Type.RIGHT, probeTrigger);
                    global.put('constructedEvent', event);
                    event.call();
                    """))
                            && fixture.apiEvents.size() == 1
                            && fixture.legacyEvents.size() == 1
                            && fixture.apiEvents.getFirst()
                                    == calls.getGlobal().get("constructedEvent"),
                    "old five-argument event construction and call propagate the real cancellation result once");
            fixture.cancelGate = false;
            fixture.clearRecords();
            trigger.getConsume().getPre().eval(constructors);
            ((LegacyActionTrigger.ConsumeInfo) constructors.getGlobal().get("constructedConsume"))
                    .getDeny()
                    .eval(constructors);
            check(
                    fixture.labels().equals(List.of("legacy-pre", "legacy-deny")),
                    "trigger and separately constructed consume actions execute their real steps");
            fixture.clearRecords();
            trigger.run(constructors);
            return until(
                            () ->
                                    fixture.labels()
                                            .containsAll(
                                                    List.of(
                                                            "legacy-actions",
                                                            "legacy-async",
                                                            "legacy-sync")),
                            80)
                    .thenCompose(ignored -> later(2))
                    .thenRun(
                            () -> {
                                check(
                                        fixture.records.size() == 3
                                                && fixture.records.contains("legacy-actions|false")
                                                && fixture.records.contains("legacy-async|false")
                                                && fixture.records.contains("legacy-sync|true"),
                                        "script-constructed trigger run preserves branch threads and independent STOP behavior");
                            });
        }

        private void publicReturns() {
            Fixture fixture = fixture("ItemTriggerPublicReturnProbe");
            fixture.hold("charge", 1, 5);
            ItemStack remainder = fixture.item("charge", 3, 5);
            fixture.catalog.triggers().returnLater(fixture.player, remainder);
            remainder.setAmount(20);
            fixture.catalog.close();
            check(
                    inventoryAmount(fixture.player) == 4
                            && fixture.player.getInventory().getItem(1).getAmount() == 3,
                    "public delayed returns own a copy and flush the registered amount on close");
            fixture.catalog.triggers().flushReturns(fixture.player);
            check(
                    inventoryAmount(fixture.player) == 4,
                    "flushing a completed public return does not deliver it twice");
        }

        private CompletionStage<Void> eating() {
            Fixture fixture = fixture("ItemTriggerEatProbe");
            ItemStack original = fixture.hold("eat", 4, 5);
            fixture.state.setMetadata(
                    fixture.player.getUniqueId(), CLOSE, (Runnable) fixture.catalog::close);
            PlayerItemConsumeEvent event =
                    new PlayerItemConsumeEvent(fixture.player, original, EquipmentSlot.HAND);
            new ItemListeners(fixture.catalog::triggers, plugin.getLogger()).consume(event);
            check(
                    event.isCancelled()
                            && fixture.player.getInventory().getItemInMainHand().getAmount() == 1
                            && charge(fixture.player.getInventory().getItemInMainHand()) == 3,
                    "eat listener commits the consumed source and cancels vanilla consumption");
            check(
                    fixture.labels().equals(List.of("eat")),
                    "eat listener runs its body before closing the revision");
            check(
                    inventoryAmount(fixture.player) == 4
                            && charge(fixture.player.getInventory().getItemInMainHand()) == 3
                            && fixture.player.getInventory().getItem(1).getAmount() == 3
                            && charge(fixture.player.getInventory().getItem(1)) == 5,
                    "finishing the closed eat event returns every pending item exactly once");
            return later(3).thenRun(
                            () ->
                                    check(
                                            inventoryAmount(fixture.player) == 4,
                                            "cancelled delayed eat return produces no duplicate after catalog close"));
        }

        private void check(boolean condition, String name) {
            if (!condition) throw new AssertionError(name);
            checks.add(name);
        }

        private CompletionStage<Void> later(long ticks) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            tasks.add(
                    Bukkit.getScheduler().runTaskLater(plugin, () -> future.complete(null), ticks));
            return future;
        }

        private CompletionStage<Void> until(BooleanSupplier condition, int remaining) {
            if (condition.getAsBoolean()) return CompletableFuture.completedFuture(null);
            if (remaining <= 0)
                return CompletableFuture.failedFuture(
                        new AssertionError("Asynchronous trigger branches did not complete"));
            return later(1).thenCompose(ignored -> until(condition, remaining - 1));
        }

        private void finish(Throwable error) {
            if (result.isDone()) return;
            Throwable failure = error;
            for (Fixture fixture : fixtures) {
                try {
                    fixture.close();
                } catch (Throwable closeError) {
                    if (failure == null) failure = closeError;
                    else failure.addSuppressed(closeError);
                }
            }
            tasks.forEach(BukkitTask::cancel);
            if (failure != null) result.completeExceptionally(failure);
            else
                result.complete(
                        Map.of(
                                "checks",
                                checks.size(),
                                "passed",
                                true,
                                "assertions",
                                List.copyOf(checks),
                                "syntheticPlayers",
                                true,
                                "realClient",
                                false,
                                "referenceRequired",
                                false));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Player player;
        final PlayerActionState state =
                new PlayerActionState(Clock.fixed(Instant.ofEpochMilli(10000), ZoneOffset.UTC));
        final List<Object> records = new CopyOnWriteArrayList<>();
        final List<String> gates = new ArrayList<>();
        final List<ItemActionEvent> apiEvents = new ArrayList<>();
        final List<LegacyItemActionEvent> legacyEvents = new ArrayList<>();
        final Listener listener = new Listener() {};
        final Listener legacyListener = new Listener() {};
        final NiCatalog catalog;
        boolean cancelGate;
        Consumer<ItemActionEvent> gateEffect;

        Fixture(JavaPlugin plugin, String name) {
            player = ProbePlayer.create(name);
            state.join(player);
            state.setMetadata(player.getUniqueId(), RECORDS, records);
            Map<String, Object> actions =
                    NiYaml.read(ACTIONS, "memory/ItemActions/probe.yml").values();
            Map<String, NiRepository.Definition> items = new LinkedHashMap<>();
            actions.keySet()
                    .forEach(
                            id ->
                                    items.put(
                                            id,
                                            new NiRepository.Definition(
                                                    id,
                                                    "memory/Items/probe.yml",
                                                    new NiConfig(Map.of("material", "STONE")))));
            NiRepository.Input input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            items,
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            actions,
                            Map.of(),
                            Map.of(),
                            Map.of("probe.js", EXPANSION),
                            Map.of());
            try {
                catalog = new NiCatalog(1, input, plugin, (viewer, text) -> null, state);
            } catch (Throwable error) {
                state.close();
                throw error;
            }
            Bukkit.getPluginManager()
                    .registerEvent(
                            ItemActionEvent.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, event) -> {
                                ItemActionEvent trigger = (ItemActionEvent) event;
                                if (!trigger.getPlayer().getUniqueId().equals(player.getUniqueId()))
                                    return;
                                apiEvents.add(trigger);
                                gates.add(trigger.getTriggerKey());
                                if (cancelGate) trigger.setCancelled(true);
                                if (gateEffect != null) gateEffect.accept(trigger);
                            },
                            plugin);
        }

        void clearRecords() {
            records.clear();
            gates.clear();
            apiEvents.clear();
            legacyEvents.clear();
        }

        List<String> labels() {
            return records.stream()
                    .map(Object::toString)
                    .map(value -> value.substring(0, value.lastIndexOf('|')))
                    .toList();
        }

        ItemStack hold(String id, int amount, Integer charge) {
            for (int slot = 0; slot < 41; slot++)
                player.getInventory().setItem(slot, new ItemStack(Material.AIR));
            ItemStack item = item(id, amount, charge);
            player.getInventory().setItemInMainHand(item);
            return item;
        }

        ItemStack item(String id, int amount, Integer charge) {
            CompoundTag properties = new CompoundTag();
            if (charge != null) properties.putInt("charge", charge);
            properties.putString("itemTime", "fixture-time");
            CompoundTag state =
                    CODEC.encode(
                            new ItemIdentity(id, Map.of("saved.roll", "17.125", "seed", "keep")),
                            properties);
            state.putString("future_field", "keep-independent-state");
            CompoundTag rolls = new CompoundTag(), nested = new CompoundTag();
            nested.putDouble("roll", 17.125);
            rolls.put("saved", nested);
            rolls.putString("seed", "keep");
            state.put("compat_ni_rolls", rolls);
            CompoundTag custom = new CompoundTag(), foreign = new CompoundTag();
            foreign.putString("marker", "keep-foreign-data");
            custom.put(ItemStateCodec.KEY, state);
            custom.put("other_plugin", foreign);
            ItemStack item = NmsItems.withCustomData(new ItemStack(Material.APPLE, amount), custom);
            var nms = CraftItemStack.unwrap(item);
            nms.set(DataComponents.CUSTOM_NAME, Component.literal("触发测试物品"));
            nms.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("保留原组件"))));
            nms.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            nms.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);
            return item;
        }

        @Override
        public void close() {
            HandlerList.unregisterAll(listener);
            HandlerList.unregisterAll(legacyListener);
            try {
                catalog.close();
            } finally {
                state.close();
            }
        }
    }

    private record GateMutation(String name, Consumer<ItemStack> change, int amount) {}

    private static void editCustomData(ItemStack item, Consumer<CompoundTag> edit) {
        CompoundTag data = NmsItems.customData(item);
        edit.accept(data);
        CraftItemStack.unwrap(item).set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private static PlayerInteractEvent click(Player player, ItemStack item, Action action) {
        PlayerInteractEvent event =
                new PlayerInteractEvent(
                        player, action, item, null, BlockFace.SELF, EquipmentSlot.HAND);
        event.setCancelled(false);
        return event;
    }

    private static int charge(ItemStack item) {
        return CODEC.properties(item).getInt("charge").orElse(-1);
    }

    private static int inventoryAmount(Player player) {
        int amount = 0;
        for (ItemStack item : player.getInventory().getContents())
            if (item != null && !item.isEmpty()) amount += item.getAmount();
        return amount;
    }
}

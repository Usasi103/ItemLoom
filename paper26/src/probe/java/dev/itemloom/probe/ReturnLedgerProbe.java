package dev.itemloom.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.ItemListeners;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.action.ReturnLedger;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Real scheduler/serialization with synthetic sessions and NMS-backed inventory slots. */
final class ReturnLedgerProbe {
    private static final String CALLBACKS = "return-ledger-probe-callbacks";
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String EXPANSION =
            """
            var manager = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
            var PlayerUtils = Java.type('pers.neige.neigeitems.utils.PlayerUtils');
            function enable() {
                manager.addConsumer('ledger-probe', false, function(context, text) {
                    PlayerUtils.getMetadataEZ(context.getPlayer(), 'return-ledger-probe-callbacks', null)
                        .get(String(text)).accept(context);
                });
            }
            """;
    private static final String ACTIONS =
            """
            food:
              eat:
                cooldown: 0
                consume: {pre: 'ledger-probe: pre', amount: '2'}
                sync: 'ledger-probe: body'
              right:
                cooldown: 0
                consume: {pre: 'ledger-probe: pre', amount: '2'}
                sync: 'ledger-probe: body'
            """;

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Return ledger probe requires the server thread");
        try {
            return new Runner(plugin).run();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static final class Runner {
        final JavaPlugin plugin;
        final Path root;
        final List<Fixture> fixtures = new ArrayList<>();
        final List<PlayerActionState> restored = new ArrayList<>();
        final List<String> checks = new ArrayList<>();
        final List<Runnable> nextTick = new ArrayList<>();
        final List<Runnable> settled = new ArrayList<>();
        final List<BukkitTask> tasks = new ArrayList<>();
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        Throwable assertionFailure;

        Runner(JavaPlugin plugin) throws IOException {
            this.plugin = plugin;
            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (temp.startsWith(Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize()))
                throw new IllegalStateException(
                        "Ledger probe temp directory must be outside the server");
            root = Files.createTempDirectory(temp, "itemloom-items-return-ledger-probe-");
        }

        CompletionStage<Map<String, Object>> run() {
            try {
                revisionAndReconnect();
                stagedAndReconnect();
                unknownDoesNotRetry();
                quitDuringInventory();
                nestedSources();
                persistence();
                closeReentrancy();
                closeDuringDrop();
                corruptArchives();
                int before = Bukkit.getCurrentTick();
                later(
                        1,
                        () -> {
                            check(
                                    Bukkit.getCurrentTick() > before,
                                    "deferred checks execute on a real later server tick");
                            nextTick.forEach(Runnable::run);
                            later(
                                    3,
                                    () -> {
                                        settled.forEach(Runnable::run);
                                        finish(null);
                                    });
                        });
            } catch (Throwable error) {
                finish(error);
            }
            return result;
        }

        Fixture fixture(String name, boolean persistent, boolean service) throws IOException {
            Fixture fixture = new Fixture(plugin, root.resolve(name), name, persistent, service);
            fixtures.add(fixture);
            return fixture;
        }

        void revisionAndReconnect() throws IOException {
            Fixture f = fixture("revision-reconnect", false, false);
            Endpoint old = f.initial;
            ItemStack supplied = food(3, 5);
            f.catalog.triggers().returnLater(old.player, supplied);
            supplied.setAmount(30);
            var before = only(f.state.returnLedger());
            check(
                    before.status() == ReturnLedger.Status.READY && old.total() == 0,
                    "caller return takes a snapshot and remains READY until a delivery boundary");
            f.state.quit(old.id);
            f.reload();
            var after = only(f.state.returnLedger());
            check(
                    after.transaction().equals(before.transaction())
                            && after.owner().equals(before.owner())
                            && after.session() == before.session()
                            && old.addCalls == 0,
                    "closing the old catalog preserves the service-owned transaction while its session is absent");
            f.catalog.triggers().flushReturns(old.player);
            check(
                    old.player.isOnline() && old.total() == 0 && old.addCalls == 0,
                    "an obsolete Player reporting online cannot receive returns after quit");
            Endpoint fresh = f.reconnect("revision-reconnect-new");
            var rebound = only(f.state.returnLedger());
            check(
                    rebound.transaction().equals(before.transaction())
                            && rebound.session() != before.session()
                            && fresh.total() == 0
                            && fresh.addCalls == 0,
                    "new session adopts the same READY transaction and schedules rather than immediately issuing it");
            nextTick.add(
                    () ->
                            check(
                                    old.addCalls == 0
                                            && fresh.total() == 3
                                            && fresh.addCalls == 1
                                            && f.state.returnLedger().diagnostics().isEmpty(),
                                    "next tick delivers the original snapshot once to the new Player across catalog revisions"));
            settled.add(
                    () ->
                            check(
                                    old.total() == 0 && fresh.total() == 3 && fresh.addCalls == 1,
                                    "old and rebound scheduler callbacks cannot duplicate a completed transaction"));
        }

        void stagedAndReconnect() throws IOException {
            Fixture f = fixture("staged-reconnect", false, false);
            f.initial.put(0, food(4, 5));
            f.initial.beforeWrite =
                    () -> {
                        var record = only(f.state.returnLedger());
                        check(
                                record.status() == ReturnLedger.Status.STAGED
                                        && record.phase() == ReturnLedger.Phase.SOURCE_COMMIT,
                                "source setter observes a STAGED record with before/prepared snapshots");
                        check(
                                decode(record.before()).getAmount() == 4
                                        && decode(record.prepared()).getAmount() == 1,
                                "source diagnostics preserve the pre-commit and prepared source quantities");
                        f.catalog.triggers().flushReturns(f.initial.player);
                        f.state.quit(f.initial.id);
                        f.state.returnLedger().flush(f.initial.player);
                        check(
                                f.initial.addCalls == 0,
                                "flush cannot deliver an uncommitted STAGED return");
                    };
            f.eat();
            check(
                    only(f.state.returnLedger()).status() == ReturnLedger.Status.READY
                            && f.initial.total() == 1
                            && f.initial.addCalls == 0
                            && f.bodyCalls == 0,
                    "a confirmed source commit retains its ready remainder and skips body after its session quits inside the setter");
            Endpoint fresh = f.reconnect("staged-reconnect-new");
            nextTick.add(
                    () ->
                            check(
                                    fresh.total() == 3
                                            && fresh.addCalls == 1
                                            && f.initial.addCalls == 0,
                                    "reconnect authorizes delivery only after the staged source became READY"));
        }

        void unknownDoesNotRetry() throws IOException {
            Fixture f = fixture("unknown-reconnect", false, false);
            f.initial.insertThenThrow = true;
            f.catalog.triggers().returnLater(f.initial.player, food(3, 5));
            f.state.returnLedger().flush(f.initial.player);
            var unknown = only(f.state.returnLedger());
            check(
                    unknown.status() == ReturnLedger.Status.UNKNOWN
                            && unknown.phase() == ReturnLedger.Phase.INVENTORY_ADD
                            && f.initial.total() == 3
                            && f.initial.addCalls == 1,
                    "inventory insertion followed by an exception records UNKNOWN instead of guessing whether to retry");
            f.initial.insertThenThrow = false;
            f.state.quit(f.initial.id);
            f.reload();
            Endpoint fresh = f.reconnect("unknown-reconnect-new");
            f.state.returnLedger().flush(fresh.player);
            var unchanged = only(f.state.returnLedger());
            check(
                    unchanged.status() == ReturnLedger.Status.UNKNOWN
                            && unchanged.session() == unknown.session()
                            && unchanged.transaction().equals(unknown.transaction())
                            && fresh.addCalls == 0,
                    "new sessions and catalog revisions do not adopt or automatically retry UNKNOWN records");
            settled.add(
                    () ->
                            check(
                                    f.initial.total() == 3
                                            && f.initial.addCalls == 1
                                            && fresh.total() == 0
                                            && fresh.addCalls == 0
                                            && only(f.state.returnLedger()).status()
                                                    == ReturnLedger.Status.UNKNOWN,
                                    "scheduled callbacks preserve UNKNOWN diagnostics without a second inventory write"));
        }

        void quitDuringInventory() throws IOException {
            Fixture f = fixture("quit-during-add", false, false);
            f.initial.leftoversOnly = true;
            f.initial.onAdd = () -> f.state.quit(f.initial.id);
            f.catalog.triggers().returnLater(f.initial.player, food(2, 5));
            f.state.returnLedger().flush(f.initial.player);
            var record = only(f.state.returnLedger());
            check(
                    record.status() == ReturnLedger.Status.READY
                            && !record.inventoryPending()
                            && f.initial.addCalls == 1
                            && f.initial.dropCalls == 0,
                    "a quit during addItem preserves its known leftovers and forbids a world drop through the old Player");
            Endpoint fresh = f.reconnect("quit-during-add-new");
            nextTick.add(
                    () ->
                            check(
                                    fresh.addCalls == 0
                                            && fresh.dropCalls == 1
                                            && fresh.droppedAmount() == 2
                                            && f.initial.dropCalls == 0
                                            && f.state.returnLedger().diagnostics().isEmpty(),
                                    "reconnected session completes a known leftover by dropping it once without repeating addItem"));
            settled.add(
                    () ->
                            check(
                                    fresh.dropCalls == 1 && f.initial.dropCalls == 0,
                                    "completed leftover has no delayed duplicate world drop"));
        }

        void nestedSources() throws IOException {
            Fixture f = fixture("nested-sources", false, false);
            f.initial.put(0, food(2, 5));
            f.initial.put(40, food(2, 5));
            f.pre =
                    context -> {
                        if (((PlayerInteractEvent) context.getEvent()).getHand()
                                == EquipmentSlot.HAND) f.interact(EquipmentSlot.OFF_HAND);
                    };
            f.interact(EquipmentSlot.HAND);
            check(
                    f.bodyCalls == 2
                            && f.initial.total() == 3
                            && f.initial.addCalls == 1
                            && only(f.state.returnLedger()).status() == ReturnLedger.Status.READY,
                    "nested independent hand transactions retain the inner remainder while the outer owner lease is active");
            nextTick.add(
                    () ->
                            check(
                                    f.initial.total() == 4
                                            && f.initial.addCalls == 2
                                            && f.state.returnLedger().diagnostics().isEmpty(),
                                    "inner physical-source remainder completes next tick without requiring quit, reload or a manual flush"));
            settled.add(
                    () ->
                            check(
                                    f.initial.total() == 4 && f.initial.addCalls == 2,
                                    "nested physical-source returns each issue exactly once"));
        }

        void persistence() throws IOException {
            Fixture f = fixture("persisted", true, false);
            f.initial.insertThenThrow = true;
            f.catalog.triggers().returnLater(f.initial.player, food(2, 7));
            f.state.returnLedger().flush(f.initial.player);
            f.initial.insertThenThrow = false;
            f.catalog.triggers().returnLater(f.initial.player, food(3, 9));
            f.state.quit(f.initial.id);
            f.state.returnLedger().save().join();
            List<ReturnLedger.Diagnostic> original = f.state.returnLedger().diagnostics();
            check(
                    original.size() == 2
                            && original.stream()
                                    .anyMatch(d -> d.status() == ReturnLedger.Status.READY)
                            && original.stream()
                                    .anyMatch(d -> d.status() == ReturnLedger.Status.UNKNOWN),
                    "persistent ledger contains both deliverable and uncertain transaction evidence");
            f.close();
            JsonArray disk = records(f.file);
            check(
                    disk.size() == 2
                            && disk.asList().stream()
                                    .allMatch(
                                            record -> record.getAsJsonObject().has("transaction")),
                    "close writes versioned records with transaction identities to the explicit external file");
            PlayerActionState restoredState = new PlayerActionState();
            restoredState.initializeReturns(plugin, f.file);
            restored.add(restoredState);
            List<ReturnLedger.Diagnostic> recovered = restoredState.returnLedger().diagnostics();
            check(
                    recovered.size() == 2
                            && recovered.stream()
                                    .allMatch(
                                            d -> d.status() == ReturnLedger.Status.RECOVERY_REVIEW)
                            && recovered.stream()
                                    .map(ReturnLedger.Diagnostic::transaction)
                                    .toList()
                                    .equals(
                                            original.stream()
                                                    .map(ReturnLedger.Diagnostic::transaction)
                                                    .toList()),
                    "a new ledger instance quarantines every previous-process status as RECOVERY_REVIEW with original transaction IDs");
            check(
                    recovered.stream()
                                            .map(ReturnLedger.Diagnostic::item)
                                            .map(ReturnLedgerProbe::decode)
                                            .mapToInt(ItemStack::getAmount)
                                            .sum()
                                    == 5
                            && recovered.stream()
                                    .allMatch(d -> d.failure().contains("Previous process")),
                    "recovery retains encoded items and the prior process/status for manual diagnosis");
            Endpoint fresh = new Endpoint("persisted-new", f.initial.id);
            restoredState.join(fresh.player);
            restoredState.returnLedger().flush(fresh.player);
            restoredState.returnLedger().flushAll();
            check(
                    fresh.addCalls == 0 && fresh.dropCalls == 0,
                    "restart quarantine does not infer delivery permission from a saved READY record");
            settled.add(
                    () -> {
                        check(
                                fresh.addCalls == 0
                                        && fresh.dropCalls == 0
                                        && restoredState.returnLedger().diagnostics().size() == 2,
                                "recovery review records remain inert across real scheduled ticks");
                        restoredState.close();
                        check(
                                records(f.file).asList().stream()
                                        .allMatch(
                                                record ->
                                                        record.getAsJsonObject()
                                                                .get("status")
                                                                .getAsString()
                                                                .equals("RECOVERY_REVIEW")),
                                "closing a restored ledger preserves quarantine records instead of saving an empty document");
                        PlayerActionState twice = new PlayerActionState();
                        restored.add(twice);
                        twice.initializeReturns(plugin, f.file);
                        check(
                                twice.returnLedger().diagnostics().stream()
                                        .map(ReturnLedger.Diagnostic::failure)
                                        .toList()
                                        .equals(
                                                recovered.stream()
                                                        .map(ReturnLedger.Diagnostic::failure)
                                                        .toList()),
                                "reopening an existing review record preserves its diagnostic text without stacking process prefixes");
                        twice.close();
                    });
        }

        void closeReentrancy() throws IOException {
            for (String phase : List.of("body", "setter", "setter-unknown")) {
                Fixture f = fixture("close-" + phase, true, true);
                f.initial.put(0, food(4, 5));
                if (phase.equals("body"))
                    f.body =
                            ignored -> {
                                f.service.close();
                                check(
                                        only(f.state.returnLedger()).status()
                                                == ReturnLedger.Status.READY,
                                        "service close inside synchronous body retains its lease-owned READY transaction");
                            };
                else
                    f.initial.beforeWrite =
                            () -> {
                                f.service.close();
                                check(
                                        records(f.file)
                                                .get(0)
                                                .getAsJsonObject()
                                                .get("status")
                                                .getAsString()
                                                .equals("STAGED"),
                                        phase
                                                + " close first saves the still-staged source evidence");
                                f.initial.throwAfterWrite = phase.equals("setter-unknown");
                            };
                f.eat();
                var terminal = only(f.state.returnLedger());
                ReturnLedger.Status expected =
                        phase.equals("setter-unknown")
                                ? ReturnLedger.Status.UNKNOWN
                                : ReturnLedger.Status.READY;
                JsonObject saved = records(f.file).get(0).getAsJsonObject();
                check(
                        terminal.status() == expected
                                && saved.get("status").getAsString().equals(expected.name())
                                && saved.get("transaction")
                                        .getAsString()
                                        .equals(terminal.transaction().toString()),
                        phase
                                + " terminal state after reentrant service close is synchronously saved when the final lease ends");
                check(
                        f.initial.total() == 1
                                && f.initial.addCalls == 0
                                && f.initial.dropCalls == 0,
                        phase
                                + " service shutdown never writes a delayed return through the now-closed player state");
                settled.add(
                        () ->
                                check(
                                        f.initial.addCalls == 0 && records(f.file).size() == 1,
                                        phase
                                                + " cancelled scheduler callbacks cannot lose or deliver the retained shutdown record"));
            }
        }

        void closeDuringDrop() throws IOException {
            Fixture f = fixture("close-during-drop", true, true);
            f.initial.leftoversOnly = true;
            f.initial.onDrop =
                    () -> {
                        f.service.close();
                        var current = only(f.state.returnLedger());
                        check(
                                current.status() == ReturnLedger.Status.UNKNOWN
                                        && current.phase() == ReturnLedger.Phase.WORLD_DROP,
                                "reentrant close during world drop saves the still-unconfirmed delivery attempt");
                        check(
                                records(f.file).size() == 1,
                                "world-drop close retains evidence until the drop result returns");
                    };
            f.catalog.triggers().returnLater(f.initial.player, food(2, 5));
            f.state.returnLedger().flush(f.initial.player);
            // Await the write already queued by the late terminal transition; do not call save
            // here, since that would hide a missing automatic write after reentrant close.
            field(f.state.returnLedger(), "saving", CompletableFuture.class).join();
            check(
                    f.initial.dropCalls == 1
                            && f.initial.droppedAmount() == 2
                            && f.state.returnLedger().diagnostics().isEmpty()
                            && records(f.file).isEmpty(),
                    "successful world-drop return after close removes and persists the terminal record without an extra save call");
            settled.add(
                    () ->
                            check(
                                    f.initial.dropCalls == 1 && records(f.file).isEmpty(),
                                    "late completed world drop remains settled after all scheduled callbacks"));
        }

        void corruptArchives() throws IOException {
            String valid =
                    Files.readString(
                            fixtures.stream()
                                    .filter(
                                            f ->
                                                    f.file.getParent()
                                                            .getFileName()
                                                            .toString()
                                                            .equals("persisted"))
                                    .findFirst()
                                    .orElseThrow()
                                    .file,
                            StandardCharsets.UTF_8);
            JsonObject duplicate = JsonParser.parseString(valid).getAsJsonObject();
            duplicate
                    .getAsJsonArray("records")
                    .add(duplicate.getAsJsonArray("records").get(0).deepCopy());
            JsonObject invalidItem = JsonParser.parseString(valid).getAsJsonObject();
            invalidItem
                    .getAsJsonArray("records")
                    .get(0)
                    .getAsJsonObject()
                    .addProperty("item", "%%%bad-base64%%%");
            Map<String, byte[]> corrupt = new LinkedHashMap<>();
            corrupt.put(
                    "truncated", "{\"schema\":1,\"records\":[".getBytes(StandardCharsets.UTF_8));
            corrupt.put(
                    "wrong-schema",
                    ("{\"schema\":99,\"process\":\"" + UUID.randomUUID() + "\",\"records\":[]}")
                            .getBytes(StandardCharsets.UTF_8));
            corrupt.put("duplicate-id", duplicate.toString().getBytes(StandardCharsets.UTF_8));
            corrupt.put("invalid-item", invalidItem.toString().getBytes(StandardCharsets.UTF_8));
            corrupt.put("invalid-utf8", new byte[] {(byte) 0xc3, (byte) 0x28});
            for (var example : corrupt.entrySet()) {
                Path file = root.resolve("corrupt-" + example.getKey()).resolve("ledger.json");
                Files.createDirectories(file.getParent());
                byte[] original = example.getValue();
                Files.write(file, original);
                PlayerActionState rejected = new PlayerActionState();
                boolean failed = false;
                try {
                    rejected.initializeReturns(plugin, file);
                } catch (IllegalStateException expected) {
                    failed = true;
                } finally {
                    rejected.close();
                }
                check(
                        failed && Arrays.equals(original, Files.readAllBytes(file)),
                        example.getKey()
                                + " archive fails loading and preserves its exact original bytes");
                check(
                        writeBlocked(file),
                        example.getKey()
                                + " unreadable archive is write-blocked rather than treated as empty");
                try (var siblings = Files.list(file.getParent())) {
                    check(
                            siblings.anyMatch(
                                    path -> !path.equals(file) && sameBytes(path, original)),
                            example.getKey()
                                    + " archive retains a byte-identical backup for diagnosis");
                }
            }
        }

        void check(boolean condition, String description) {
            if (!condition) {
                AssertionError error = new AssertionError(description);
                if (assertionFailure == null) assertionFailure = error;
                throw error;
            }
            checks.add(description);
        }

        void later(long ticks, Runnable body) {
            tasks.add(
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> {
                                        try {
                                            body.run();
                                        } catch (Throwable error) {
                                            finish(error);
                                        }
                                    },
                                    ticks));
        }

        void finish(Throwable failure) {
            if (result.isDone()) return;
            if (failure == null) failure = assertionFailure;
            for (Fixture fixture : fixtures) {
                if (failure == null) failure = fixture.callbackFailure;
                try {
                    fixture.close();
                } catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            for (PlayerActionState state : restored) {
                try {
                    state.close();
                } catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            tasks.forEach(BukkitTask::cancel);
            if (failure != null) result.completeExceptionally(failure);
            else
                result.complete(
                        Map.of(
                                "passed",
                                true,
                                "checks",
                                checks.size(),
                                "assertions",
                                List.copyOf(checks),
                                "syntheticPlayers",
                                true,
                                "realClient",
                                false,
                                "realScheduler",
                                true,
                                "externalEvidenceDirectory",
                                root.toString(),
                                "restartBoundary",
                                "new ledger instance reading persisted bytes",
                                "persistenceGuarantee",
                                "diagnostic quarantine; not an exactly-once crash-recovery delivery protocol"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Path file;
        final PlayerActionState state;
        final Endpoint initial;
        final NiRepository.Input input;
        final List<NiCatalog> revisions = new ArrayList<>();
        final ItemListeners listeners;
        final ItemsService service;
        NiCatalog catalog;
        Consumer<NiActionContext> pre = ignored -> {}, body = ignored -> {};
        Throwable callbackFailure;
        int bodyCalls;
        boolean closed;

        Fixture(
                JavaPlugin plugin,
                Path directory,
                String name,
                boolean persistent,
                boolean serviceOwned)
                throws IOException {
            this.plugin = plugin;
            Files.createDirectories(directory);
            file = directory.resolve("ledger.json");
            initial =
                    new Endpoint(
                            name, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
            input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            Map.of(
                                    "food",
                                    new NiRepository.Definition(
                                            "food",
                                            "memory/food.yml",
                                            new NiConfig(Map.of("material", "APPLE")))),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            dev.itemloom.compat.ni.NiYaml.read(ACTIONS, "memory/actions.yml")
                                    .values(),
                            Map.of(),
                            Map.of(),
                            Map.of("probe.js", EXPANSION),
                            Map.of());
            if (serviceOwned) {
                Path inputRoot = directory.resolve("input");
                for (String child : List.of("Items", "ItemActions", "Expansions"))
                    Files.createDirectories(inputRoot.resolve(child));
                Files.writeString(
                        inputRoot.resolve("Items/food.yml"),
                        "food:\n  material: APPLE\n",
                        StandardCharsets.UTF_8);
                Files.writeString(
                        inputRoot.resolve("ItemActions/food.yml"), ACTIONS, StandardCharsets.UTF_8);
                Files.writeString(
                        inputRoot.resolve("Expansions/probe.js"),
                        EXPANSION,
                        StandardCharsets.UTF_8);
                service = new ItemsService(plugin, inputRoot, (viewer, text) -> null, file);
                if (!service.reload(null))
                    throw new AssertionError("Service ledger fixture did not reload");
                state = field(service, "players", PlayerActionState.class);
                catalog = activeCatalog(service);
                revisions.add(catalog);
            } else {
                service = null;
                state = new PlayerActionState();
                if (persistent) state.initializeReturns(plugin, file);
                reload();
            }
            state.join(initial.player);
            bind(initial.player);
            listeners = new ItemListeners(() -> catalog.triggers(), plugin.getLogger());
        }

        void reload() {
            NiCatalog previous = catalog;
            catalog =
                    new NiCatalog(
                            revisions.size() + 1, input, plugin, (viewer, text) -> null, state);
            revisions.add(catalog);
            if (previous != null) previous.close();
        }

        void bind(Player player) {
            Map<String, Consumer<NiActionContext>> callbacks = new LinkedHashMap<>();
            callbacks.put("pre", context -> callback(pre, context));
            callbacks.put(
                    "body",
                    context -> {
                        bodyCalls++;
                        callback(body, context);
                    });
            state.setMetadata(player.getUniqueId(), CALLBACKS, callbacks);
        }

        Endpoint reconnect(String name) {
            Endpoint fresh = new Endpoint(name, initial.id);
            state.join(fresh.player);
            bind(fresh.player);
            return fresh;
        }

        void eat() {
            listeners.consume(
                    new PlayerItemConsumeEvent(
                            initial.player,
                            initial.inventory.getItemInMainHand(),
                            EquipmentSlot.HAND));
        }

        void interact(EquipmentSlot slot) {
            listeners.interact(
                    new PlayerInteractEvent(
                            initial.player,
                            Action.RIGHT_CLICK_AIR,
                            initial.inventory.getItem(slot),
                            null,
                            BlockFace.SELF,
                            slot));
        }

        void callback(Consumer<NiActionContext> consumer, NiActionContext context) {
            try {
                consumer.accept(context);
            } catch (RuntimeException | Error failure) {
                callbackFailure = failure;
                throw failure;
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (service != null) service.close();
            else {
                revisions.forEach(NiCatalog::close);
                state.close();
            }
        }
    }

    private static final class Endpoint {
        final UUID id;
        final Player player;
        final PlayerInventory inventory;
        final net.minecraft.world.item.ItemStack[] contents =
                new net.minecraft.world.item.ItemStack[41];
        final List<ItemStack> dropped = new ArrayList<>();
        int addCalls, dropCalls;
        boolean insertThenThrow, leftoversOnly, throwAfterWrite;
        Runnable beforeWrite, onAdd, onDrop;

        Endpoint(String name, UUID id) {
            this.id = id;
            Arrays.fill(contents, net.minecraft.world.item.ItemStack.EMPTY);
            Player delegate = ProbePlayer.create(name);
            inventory =
                    (PlayerInventory)
                            Proxy.newProxyInstance(
                                    PlayerInventory.class.getClassLoader(),
                                    new Class<?>[] {PlayerInventory.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getItem" -> mirror(slot(args[0]));
                                                case "getItemInMainHand" -> mirror(0);
                                                case "getItemInOffHand" -> mirror(40);
                                                case "getHeldItemSlot" -> 0;
                                                case "setItem" -> {
                                                    write(slot(args[0]), (ItemStack) args[1]);
                                                    yield null;
                                                }
                                                case "setItemInMainHand" -> {
                                                    write(0, (ItemStack) args[0]);
                                                    yield null;
                                                }
                                                case "setItemInOffHand" -> {
                                                    write(40, (ItemStack) args[0]);
                                                    yield null;
                                                }
                                                case "getContents" ->
                                                        Arrays.stream(contents)
                                                                .map(CraftItemStack::asCraftMirror)
                                                                .toArray(ItemStack[]::new);
                                                case "addItem" -> {
                                                    addCalls++;
                                                    if (onAdd != null) onAdd.run();
                                                    ItemStack[] additions = (ItemStack[]) args[0];
                                                    Map<Integer, ItemStack> left =
                                                            new LinkedHashMap<>();
                                                    for (int i = 0; i < additions.length; i++) {
                                                        int slot = 0;
                                                        while (slot < 36
                                                                && !contents[slot].isEmpty())
                                                            slot++;
                                                        if (leftoversOnly || slot == 36)
                                                            left.put(i, additions[i].clone());
                                                        else put(slot, additions[i]);
                                                    }
                                                    if (insertThenThrow)
                                                        throw new IllegalStateException(
                                                                "Expected ledger probe exception after inventory insertion");
                                                    yield left;
                                                }
                                                default ->
                                                        invoke(
                                                                method,
                                                                delegate.getInventory(),
                                                                args);
                                            });
            World world =
                    (World)
                            Proxy.newProxyInstance(
                                    World.class.getClassLoader(),
                                    new Class<?>[] {World.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("dropItem")) {
                                            dropCalls++;
                                            if (onDrop != null) onDrop.run();
                                            dropped.add(((ItemStack) args[1]).clone());
                                            return Proxy.newProxyInstance(
                                                    Item.class.getClassLoader(),
                                                    new Class<?>[] {Item.class},
                                                    (item, call, values) ->
                                                            switch (call.getName()) {
                                                                case "isValid" -> true;
                                                                case "isDead" -> false;
                                                                case "getItemStack" ->
                                                                        ((ItemStack) args[1])
                                                                                .clone();
                                                                case "toString" ->
                                                                        "ReturnLedgerProbeDrop["
                                                                                + name
                                                                                + "]";
                                                                default -> null;
                                                            });
                                        }
                                        return invoke(method, delegate.getWorld(), args);
                                    });
            player =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class<?>[] {Player.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getUniqueId" -> id;
                                                case "getInventory" -> inventory;
                                                case "getWorld" -> world;
                                                case "getLocation" ->
                                                        new Location(world, 12, 64, 8);
                                                case "getPlayer" -> proxy;
                                                case "isOnline" -> true;
                                                default -> invoke(method, delegate, args);
                                            });
        }

        int slot(Object value) {
            if (value instanceof Integer index) return index;
            return switch ((EquipmentSlot) value) {
                case HAND -> 0;
                case OFF_HAND -> 40;
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported ledger probe slot " + value);
            };
        }

        void write(int slot, ItemStack item) {
            Runnable hook = beforeWrite;
            beforeWrite = null;
            if (hook != null) hook.run();
            put(slot, item);
            if (throwAfterWrite)
                throw new IllegalStateException(
                        "Expected ledger probe source failure after shutdown and write");
        }

        void put(int slot, ItemStack item) {
            contents[slot] = CraftItemStack.asNMSCopy(item);
        }

        ItemStack mirror(int slot) {
            return CraftItemStack.asCraftMirror(contents[slot]);
        }

        int total() {
            return Arrays.stream(contents)
                    .filter(item -> !item.isEmpty())
                    .mapToInt(net.minecraft.world.item.ItemStack::getCount)
                    .sum();
        }

        int droppedAmount() {
            return dropped.stream().mapToInt(ItemStack::getAmount).sum();
        }
    }

    private static ReturnLedger.Diagnostic only(ReturnLedger ledger) {
        List<ReturnLedger.Diagnostic> entries = ledger.diagnostics();
        if (entries.size() != 1)
            throw new AssertionError("Expected one ledger record, found " + entries.size());
        return entries.getFirst();
    }

    private static ItemStack food(int amount, int charge) {
        CompoundTag properties = new CompoundTag();
        properties.putInt("charge", charge);
        CompoundTag custom = new CompoundTag();
        custom.put(
                ItemStateCodec.KEY,
                CODEC.encode(new ItemIdentity("food", Map.of("seed", "preserve")), properties));
        return NmsItems.withCustomData(new ItemStack(Material.APPLE, amount), custom);
    }

    private static ItemStack decode(String item) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(item));
    }

    private static JsonArray records(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonArray("records");
        } catch (IOException failure) {
            throw new AssertionError("Cannot read saved ledger " + file, failure);
        }
    }

    private static boolean sameBytes(Path path, byte[] expected) {
        try {
            return Files.isRegularFile(path) && Arrays.equals(expected, Files.readAllBytes(path));
        } catch (IOException error) {
            throw new AssertionError("Cannot inspect ledger backup", error);
        }
    }

    private static boolean writeBlocked(Path file) {
        try {
            Class<?> guard =
                    Class.forName(
                            "dev.itemloom.internal.keystone.storage.WriteGuard",
                            true,
                            NiCatalog.class.getClassLoader());
            return (boolean)
                    guard.getMethod("isBlocked", java.io.File.class).invoke(null, file.toFile());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect packaged ledger write guard", failure);
        }
    }

    private static NiCatalog activeCatalog(ItemsService service) {
        try {
            var method = ItemsService.class.getDeclaredMethod("activeNi");
            method.setAccessible(true);
            return (NiCatalog) method.invoke(service);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect service-owned catalog fixture", failure);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect service-owned ledger fixture", failure);
        }
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}

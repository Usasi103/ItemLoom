package dev.itemloom.probe;

import dev.itemloom.paper.action.ReturnLedger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.api.ItemActionEvent;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.ItemListeners;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** The actual food listener with synthetic players and fresh mirrors of real NMS stacks. */
final class ConsumeCommitProbe {
    private static final String CALLBACKS = "consume-commit-probe-callbacks";
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String EXPANSION =
            """
            var manager = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
            var PlayerUtils = Java.type('pers.neige.neigeitems.utils.PlayerUtils');
            function enable() {
                manager.addConsumer('consume-probe', false, function(context, text) {
                    PlayerUtils.getMetadataEZ(context.getPlayer(), 'consume-commit-probe-callbacks', null)
                        .get(String(text)).accept(context);
                });
            }
            """;

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Consume probe requires the server thread");
        Runner runner = new Runner(plugin);
        return runner.run();
    }

    private static final class Runner {
        final JavaPlugin plugin;
        final List<String> checks = new ArrayList<>();
        final List<Fixture> fixtures = new ArrayList<>();
        Throwable assertionFailure;

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        CompletionStage<Map<String, Object>> run() {
            CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
            try {
                ordinary();
                preMoves();
                gateChanges();
                bodyMoves();
                nested();
                lifecycle();
                commitFailures();
                // Delayed tasks run after every source transaction, including aborted and
                // uncertain writes; these checks catch an accidentally scheduled duplicate.
                Bukkit.getScheduler()
                        .runTaskLater(
                                plugin,
                                () -> {
                                    try {
                                        for (Fixture fixture : fixtures)
                                            fixture.verifySettled.run();
                                        finish(result, null);
                                    } catch (Throwable error) {
                                        finish(result, error);
                                    }
                                },
                                4);
            } catch (Throwable error) {
                finish(result, error);
            }
            return result;
        }

        Fixture fixture(String name) {
            Fixture fixture = new Fixture(plugin, name, true);
            fixtures.add(fixture);
            return fixture;
        }

        void ordinary() {
            Fixture fixture = fixture("ConsumeNormal");
            fixture.pre =
                    context -> {
                        context.getData().put("pre-only", "retain");
                        context.getGlobal().put("pre-data", context.getData());
                    };
            fixture.body =
                    context -> {
                        ItemStack actual = fixture.inventory.getItem(0);
                        check(
                                context.getItemStack() != actual
                                        && CraftItemStack.unwrap(context.getItemStack())
                                                == CraftItemStack.unwrap(actual),
                                "body item context is a fresh mirror of the committed inventory handle");
                        check(
                                context.getData() == context.getGlobal().get("pre-data")
                                        && context.getData().get("pre-only").equals("retain"),
                                "rebinding the committed item retains pre's mutable data map and invocation globals");
                        check(
                                actual.getAmount() == 1
                                        && charge(actual) == 3
                                        && fixture.total() == 1,
                                "source charge is committed before body and remainder waits for delivery");
                        context.getItemStack().setAmount(2);
                        ((PlayerItemConsumeEvent) context.getEvent()).setCancelled(false);
                    };
            PlayerItemConsumeEvent event = fixture.eat();
            check(
                    event.isCancelled() && fixture.inventory.getItem(0).getAmount() == 2,
                    "body item writes survive and clearing cancellation cannot restore vanilla consumption");
            fixture.flush();
            check(
                    fixture.total() == 5 && fixture.amountWithCharge(5) == 3,
                    "committed remainder is returned once without replacing the body-edited stack");
            fixture.verifySettled =
                    () ->
                            check(
                                    fixture.total() == 5,
                                    "normal delayed task cannot repeat a flushed return");

            Fixture exhausted = fixture("ConsumeExhausted");
            exhausted.put(0, food(4, 2));
            exhausted.body =
                    context ->
                            check(
                                    context.getItemStack().isEmpty() && context.getNbt() == null,
                                    "fully exhausted body sees the actual empty slot without stale NBT");
            check(
                    exhausted.eat().isCancelled(),
                    "fully exhausted charge cancels vanilla consumption");
            exhausted.flush();
            check(
                    exhausted.total() == 3 && exhausted.amountWithCharge(2) == 3,
                    "fully exhausted charged item removes one source and conserves untouched remainder");

            Fixture counted = fixture("ConsumeCounted");
            counted.put(0, food(4, null));
            counted.eat();
            counted.flush();
            check(
                    counted.total() == 2 && counted.bodyCalls == 1,
                    "ordinary food count commits once without a split return");

            Fixture offhand = fixture("ConsumeOffhand");
            offhand.put(0, new ItemStack(Material.DIAMOND, 7));
            offhand.put(40, food(4, 5));
            offhand.pre = context -> offhand.inventory.setHeldItemSlot(3);
            offhand.eat(EquipmentSlot.OFF_HAND);
            offhand.flush();
            check(
                    offhand.inventory.getItem(0).getAmount() == 7
                            && charge(offhand.inventory.getItem(40)) == 3
                            && offhand.amountWithCharge(5) == 3
                            && offhand.bodyCalls == 1,
                    "offhand source commits to slot 40 independently of a changed hotbar selection");

            Fixture noTrigger = fixture("ConsumeNoTrigger");
            noTrigger.put(0, new ItemStack(Material.APPLE, 4));
            check(
                    !noTrigger.eat().isCancelled()
                            && noTrigger.total() == 4
                            && noTrigger.preCalls == 0,
                    "ordinary vanilla food without an eat trigger is untouched");

            Fixture noConsume = new Fixture(plugin, "ConsumeNoDeduction", false);
            fixtures.add(noConsume);
            check(
                    noConsume.eat().isCancelled()
                            && noConsume.total() == 4
                            && noConsume.preCalls == 0
                            && noConsume.bodyCalls == 1
                            && noConsume.addCalls == 0,
                    "eat actions without consume preserve their configured zero-deduction behavior");
        }

        void preMoves() {
            Fixture moved = fixture("ConsumePreMove");
            moved.pre = context -> moved.move(0, 7);
            check(
                    moved.eat().isCancelled() && moved.total() == 4 && moved.bodyCalls == 0,
                    "pre moving the original stack aborts source commit and suppresses body");
            moved.flush();
            check(
                    moved.inventory.getItem(0).isEmpty() && moved.amountWithCharge(5) == 4,
                    "pre moved source remains unchanged without a split remainder");
            moved.verifySettled =
                    () ->
                            check(
                                    moved.total() == 4,
                                    "pre move never schedules a duplicate remainder");

            Fixture swapped = fixture("ConsumePreSwap");
            swapped.pre =
                    context -> {
                        swapped.move(0, 7);
                        swapped.put(0, new ItemStack(Material.DIAMOND, 2));
                    };
            swapped.eat();
            swapped.flush();
            check(
                    swapped.inventory.getItem(0).getType() == Material.DIAMOND
                            && swapped.total() == 6
                            && swapped.amountWithCharge(5) == 4
                            && swapped.bodyCalls == 0,
                    "pre replacement hand and relocated original survive without duplicate food");

            Fixture equal = fixture("ConsumeEqualReplace");
            equal.pre = context -> equal.put(0, equal.inventory.getItem(0).clone());
            equal.eat();
            equal.flush();
            check(
                    equal.total() == 4 && equal.amountWithCharge(5) == 4 && equal.bodyCalls == 0,
                    "equal-value replacement with a different NMS source handle invalidates the transaction");

            Fixture selected = fixture("ConsumeEqualHotbar");
            selected.put(1, selected.inventory.getItem(0).clone());
            selected.pre = context -> selected.inventory.setHeldItemSlot(1);
            selected.eat();
            selected.flush();
            check(
                    selected.total() == 8
                            && selected.amountWithCharge(5) == 8
                            && selected.bodyCalls == 0
                            && selected.inventory.getHeldItemSlot() == 1,
                    "switching to an equal hotbar stack cannot commit into either physical slot");

            Fixture changed = fixture("ConsumeSourceMutation");
            changed.pre = context -> changed.inventory.getItem(0).setAmount(6);
            changed.eat();
            changed.flush();
            check(
                    changed.total() == 6
                            && changed.amountWithCharge(5) == 6
                            && changed.bodyCalls == 0,
                    "same-reference source value changes made by pre remain intact");

            Fixture candidate = fixture("ConsumeCandidateMutation");
            candidate.pre = context -> context.getItemStack().setAmount(2);
            candidate.eat();
            candidate.flush();
            check(
                    candidate.total() == 2
                            && candidate.amountWithCharge(3) == 1
                            && candidate.amountWithCharge(5) == 1,
                    "pre can deliberately edit the isolated candidate before its valid commit");
        }

        void gateChanges() {
            Fixture gate = fixture("ConsumeGateMove");
            gate.gate = event -> gate.move(0, 8);
            gate.eat();
            gate.flush();
            check(
                    gate.total() == 4 && gate.amountWithCharge(5) == 4 && gate.bodyCalls == 0,
                    "ItemActionEvent source movement aborts the eventual candidate commit");
            Fixture cancelled = fixture("ConsumeGateCancel");
            cancelled.gate = event -> event.setCancelled(true);
            check(
                    cancelled.eat().isCancelled()
                            && cancelled.preCalls == 0
                            && cancelled.total() == 4,
                    "cancelled ItemActionEvent neither consumes nor permits vanilla fallback");
        }

        void bodyMoves() {
            Fixture moved = fixture("ConsumeBodyMove");
            moved.body = context -> moved.move(0, 8);
            moved.eat();
            moved.flush();
            check(
                    moved.total() == 4
                            && moved.amountWithCharge(3) == 1
                            && moved.amountWithCharge(5) == 3
                            && charge(moved.inventory.getItem(8)) == 3,
                    "body moves the committed item without a finally write restoring it in hand");

            Fixture swapped = fixture("ConsumeBodySwap");
            swapped.body =
                    context -> {
                        swapped.move(0, 8);
                        swapped.put(0, new ItemStack(Material.DIAMOND, 2));
                    };
            swapped.eat();
            swapped.flush();
            check(
                    swapped.inventory.getItem(0).getType() == Material.DIAMOND
                            && swapped.total() == 6
                            && swapped.amountWithCharge(3) == 1
                            && swapped.amountWithCharge(5) == 3,
                    "body replacement hand survives alongside committed food and its remainder");

            Fixture selected = fixture("ConsumeBodySelect");
            selected.put(1, food(4, 5));
            selected.body = context -> selected.inventory.setHeldItemSlot(1);
            selected.eat();
            selected.flush();
            check(
                    selected.total() == 8
                            && charge(selected.inventory.getItem(0)) == 3
                            && selected.inventory.getItem(1).getAmount() == 4
                            && charge(selected.inventory.getItem(1)) == 5,
                    "body hotbar switch cannot redirect a trailing source write into the new selected slot");
        }

        void nested() {
            Fixture pre = fixture("ConsumeNestedPre");
            pre.pre =
                    context ->
                            check(
                                    pre.eat().isCancelled(),
                                    "same-slot nested pre event is cancelled immediately");
            pre.eat();
            pre.flush();
            check(
                    pre.preCalls == 1
                            && pre.bodyCalls == 1
                            && pre.total() == 4
                            && pre.amountWithCharge(3) == 1,
                    "same-slot pre nesting executes consume and body only once");

            Fixture body = fixture("ConsumeNestedBody");
            body.body =
                    context ->
                            check(
                                    body.eat().isCancelled(),
                                    "same-slot nested body event is cancelled immediately");
            body.eat();
            body.flush();
            check(
                    body.preCalls == 1
                            && body.bodyCalls == 1
                            && body.amountWithCharge(3) == 1
                            && body.total() == 4,
                    "same-slot body nesting cannot consume the already committed source a second time");

            Fixture different = fixture("ConsumeNestedOtherHand");
            different.put(40, food(4, 5));
            different.pre =
                    context -> {
                        if (((PlayerItemConsumeEvent) context.getEvent()).getHand()
                                == EquipmentSlot.HAND) different.eat(EquipmentSlot.OFF_HAND);
                    };
            different.eat();
            different.flush();
            check(
                    different.total() == 8
                            && different.amountWithCharge(3) == 2
                            && different.amountWithCharge(5) == 6
                            && different.bodyCalls == 2,
                    "different physical hands may nest while each source conserves its own remainder");
        }

        void lifecycle() {
            for (String phase : List.of("pre", "body")) {
                for (String operation : List.of("close", "quit", "reload")) {
                    Fixture fixture = fixture("Consume" + phase + operation);
                    Consumer<NiActionContext> interrupt =
                            context -> {
                                switch (operation) {
                                    case "close" -> fixture.catalog.close();
                                    case "quit" -> {
                                        fixture.catalog.triggers().flushReturns(fixture.player);
                                        fixture.state.quit(fixture.player.getUniqueId());
                                        fixture.online = false;
                                    }
                                    case "reload" -> fixture.reload();
                                    default -> throw new AssertionError(operation);
                                }
                            };
                    if (phase.equals("pre")) fixture.pre = interrupt;
                    else fixture.body = interrupt;
                    check(
                            fixture.eat().isCancelled(),
                            phase + " " + operation + " keeps vanilla consumption cancelled");
                    fixture.flush();
                    boolean beforeCommit = phase.equals("pre");
                    if (!beforeCommit && operation.equals("quit")) {
                        check(
                                fixture.total() == 1
                                        && fixture.addCalls == 0
                                        && fixture.state.returnLedger().diagnostics().stream()
                                                        .filter(
                                                                record ->
                                                                        record.status()
                                                                                == dev.itemloom
                                                                                        .paper
                                                                                        .action
                                                                                        .ReturnLedger
                                                                                        .Status
                                                                                        .READY)
                                                        .count()
                                                == 1,
                                "body quit retains a ready remainder without writing through the departed Player");
                        fixture.online = true;
                        fixture.state.join(fixture.player);
                        fixture.flush();
                    }
                    check(
                            fixture.total() == 4
                                    && fixture.bodyCalls == (beforeCommit ? 0 : 1)
                                    && fixture.amountWithCharge(5) == (beforeCommit ? 4 : 3)
                                    && fixture.amountWithCharge(3) == (beforeCommit ? 0 : 1),
                            phase
                                    + " "
                                    + operation
                                    + " preserves exactly one source result and its authorized remainder");
                    fixture.verifySettled =
                            () ->
                                    check(
                                            fixture.total() == 4,
                                            phase
                                                    + " "
                                                    + operation
                                                    + " cannot repeat an old scheduled return");
                }
            }
            Fixture retained = fixture("ConsumeQuitRetainedState");
            retained.state.configure(false);
            retained.pre =
                    context -> {
                        retained.catalog.triggers().flushReturns(retained.player);
                        retained.state.quit(retained.player.getUniqueId());
                    };
            retained.eat();
            retained.flush();
            check(
                    retained.state.contains(retained.player.getUniqueId())
                            && retained.total() == 4
                            && retained.amountWithCharge(5) == 4
                            && retained.bodyCalls == 0,
                    "quit invalidates an in-flight source even when cooldown metadata is retained and isOnline is still true");
        }

        void commitFailures() {
            Fixture flushing = fixture("ConsumeFlushDuringWrite");
            flushing.beforeWrite =
                    () -> {
                        flushing.catalog.triggers().flushReturns(flushing.player);
                        flushing.catalog.close();
                        check(
                                flushing.total() == 4 && flushing.addCalls == 0,
                                "quit and close during source write cannot flush its not-ready remainder");
                    };
            flushing.eat();
            flushing.flush();
            check(
                    flushing.total() == 4
                            && flushing.amountWithCharge(3) == 1
                            && flushing.amountWithCharge(5) == 3
                            && flushing.bodyCalls == 0
                            && flushing.addCalls == 1,
                    "a known successful write enables its remainder once even when the revision closed inside the setter");

            for (boolean afterWrite : List.of(false, true)) {
                Fixture fixture = fixture("ConsumeCommitThrow" + afterWrite);
                fixture.throwBeforeWrite = !afterWrite;
                fixture.throwAfterWrite = afterWrite;
                PlayerItemConsumeEvent event = fixture.eat();
                fixture.throwBeforeWrite = false;
                fixture.throwAfterWrite = false;
                fixture.flush();
                fixture.catalog.close();
                fixture.flush();
                int expected = afterWrite ? 1 : 4;
                check(
                        event.isCancelled()
                                && fixture.bodyCalls == 0
                                && fixture.total() == expected
                                && fixture.addCalls == 0
                                && fixture.unknownReturns() == 1,
                        "source commit exception "
                                + (afterWrite ? "after" : "before")
                                + " write cancels body and forbids an uncertain remainder retry");
                fixture.verifySettled =
                        () ->
                                check(
                                        fixture.total() == expected && fixture.addCalls == 0,
                                        "uncertain "
                                                + (afterWrite ? "post-write" : "pre-write")
                                                + " commit stays non-retriable after scheduled ticks");
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

        void finish(CompletableFuture<Map<String, Object>> result, Throwable failure) {
            if (failure == null) failure = assertionFailure;
            for (Fixture fixture : fixtures) {
                if (failure == null) failure = fixture.callbackFailure;
                try {
                    fixture.close();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
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
                                "listenerPath",
                                "ItemListeners.consume",
                                "uncertainCommitPolicy",
                                "service-owned diagnostics; unknown outcomes never automatically retry"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Player player;
        final PlayerInventory inventory;
        final net.minecraft.world.item.ItemStack[] contents =
                new net.minecraft.world.item.ItemStack[41];
        final PlayerActionState state = new PlayerActionState();
        final NiRepository.Input input;
        final List<NiCatalog> revisions = new ArrayList<>();
        final Listener gateListener = new Listener() {};
        final ItemListeners listeners;
        NiCatalog catalog;
        Consumer<NiActionContext> pre = ignored -> {}, body = ignored -> {};
        Consumer<ItemActionEvent> gate = ignored -> {};
        Runnable verifySettled = () -> {};
        Runnable beforeWrite;
        Throwable callbackFailure;
        int selected, preCalls, bodyCalls, addCalls;
        boolean online = true, throwBeforeWrite, throwAfterWrite;

        Fixture(JavaPlugin plugin, String name, boolean consume) {
            this.plugin = plugin;
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
                                                case "getItemInMainHand" -> mirror(selected);
                                                case "getItemInOffHand" -> mirror(40);
                                                case "getHeldItemSlot" -> selected;
                                                case "setHeldItemSlot" -> {
                                                    selected = (int) args[0];
                                                    yield null;
                                                }
                                                case "setItem" -> {
                                                    write(slot(args[0]), (ItemStack) args[1]);
                                                    yield null;
                                                }
                                                case "setItemInMainHand" -> {
                                                    write(selected, (ItemStack) args[0]);
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
                                                    Map<Integer, ItemStack> left = new HashMap<>();
                                                    ItemStack[] additions = (ItemStack[]) args[0];
                                                    for (int i = 0; i < additions.length; i++) {
                                                        int slot = 0;
                                                        while (slot < 36
                                                                && !contents[slot].isEmpty())
                                                            slot++;
                                                        if (slot < 36) write(slot, additions[i]);
                                                        else left.put(i, additions[i].clone());
                                                    }
                                                    yield left;
                                                }
                                                case "toString" ->
                                                        "ConsumeProbeInventory[" + name + "]";
                                                default ->
                                                        invoke(
                                                                method,
                                                                delegate.getInventory(),
                                                                args);
                                            });
            player =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class<?>[] {Player.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getInventory" -> inventory;
                                                case "isOnline" -> online;
                                                case "getPlayer" -> proxy;
                                                default -> invoke(method, delegate, args);
                                            });
            state.join(player);
            Map<String, Consumer<NiActionContext>> callbacks = new LinkedHashMap<>();
            callbacks.put(
                    "pre",
                    context -> {
                        preCalls++;
                        callback(pre, context);
                    });
            callbacks.put(
                    "body",
                    context -> {
                        bodyCalls++;
                        callback(body, context);
                    });
            state.setMetadata(player.getUniqueId(), CALLBACKS, callbacks);
            Map<String, Object> triggers = new LinkedHashMap<>();
            triggers.put("cooldown", 0);
            triggers.put("sync", "consume-probe: body");
            if (consume)
                triggers.put("consume", Map.of("pre", "consume-probe: pre", "amount", "2"));
            input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            Map.of(
                                    "food",
                                    new NiRepository.Definition(
                                            "food",
                                            "memory/food.yml",
                                            NiYaml.read("material: APPLE", "memory/food.yml"))),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of("food", Map.of("eat", triggers)),
                            Map.of(),
                            Map.of(),
                            Map.of("probe.js", EXPANSION),
                            Map.of());
            reload();
            listeners = new ItemListeners(() -> catalog.triggers(), plugin.getLogger());
            Bukkit.getPluginManager()
                    .registerEvent(
                            ItemActionEvent.class,
                            gateListener,
                            EventPriority.NORMAL,
                            (ignored, event) -> {
                                ItemActionEvent action = (ItemActionEvent) event;
                                if (action.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                                    try {
                                        gate.accept(action);
                                    } catch (RuntimeException | Error error) {
                                        callbackFailure = error;
                                        throw error;
                                    }
                                }
                            },
                            plugin);
            put(0, food(4, 5));
        }

        void reload() {
            NiCatalog previous = catalog;
            catalog =
                    new NiCatalog(
                            revisions.size() + 1, input, plugin, (viewer, text) -> null, state);
            revisions.add(catalog);
            if (previous != null) previous.close();
        }

        PlayerItemConsumeEvent eat() {
            return eat(EquipmentSlot.HAND);
        }

        PlayerItemConsumeEvent eat(EquipmentSlot hand) {
            PlayerItemConsumeEvent event =
                    new PlayerItemConsumeEvent(player, inventory.getItem(hand), hand);
            listeners.consume(event);
            return event;
        }

        void flush() {
            revisions.forEach(revision -> revision.triggers().flushReturns(player));
        }

        void put(int slot, ItemStack item) {
            contents[slot] = CraftItemStack.asNMSCopy(item);
        }

        void move(int from, int to) {
            contents[to] = contents[from];
            contents[from] = net.minecraft.world.item.ItemStack.EMPTY;
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

        int amountWithCharge(int expected) {
            int total = 0;
            for (var item : contents)
                if (!item.isEmpty() && charge(CraftItemStack.asCraftMirror(item)) == expected)
                    total += item.getCount();
            return total;
        }

        int slot(Object slot) {
            if (slot instanceof Integer index) return index;
            return switch ((EquipmentSlot) slot) {
                case HAND -> selected;
                case OFF_HAND -> 40;
                default -> throw new IllegalArgumentException("Unsupported probe slot: " + slot);
            };
        }

        void write(int slot, ItemStack item) {
            Runnable hook = beforeWrite;
            beforeWrite = null;
            if (hook != null) hook.run();
            if (throwBeforeWrite)
                throw new IllegalStateException(
                        "Expected probe source commit failure before write");
            put(slot, item);
            if (throwAfterWrite)
                throw new IllegalStateException("Expected probe source commit failure after write");
        }

        void callback(Consumer<NiActionContext> callback, NiActionContext context) {
            try {
                callback.accept(context);
            } catch (RuntimeException | Error error) {
                callbackFailure = error;
                throw error;
            }
        }

        int unknownReturns() {
            return (int)
                    state.returnLedger().diagnostics().stream()
                            .filter(record -> record.status() == ReturnLedger.Status.UNKNOWN)
                            .count();
        }

        @Override
        public void close() {
            HandlerList.unregisterAll(gateListener);
            revisions.forEach(NiCatalog::close);
            state.close();
        }
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static ItemStack food(int amount, Integer charge) {
        CompoundTag properties = new CompoundTag();
        if (charge != null) properties.putInt("charge", charge);
        CompoundTag custom = new CompoundTag();
        custom.put(
                ItemStateCodec.KEY,
                CODEC.encode(new ItemIdentity("food", Map.of("seed", "preserve")), properties));
        return NmsItems.withCustomData(new ItemStack(Material.APPLE, amount), custom);
    }

    private static int charge(ItemStack item) {
        return CODEC.properties(item).getInt("charge").orElse(-1);
    }
}

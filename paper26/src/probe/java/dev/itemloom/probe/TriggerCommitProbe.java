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
import net.minecraft.world.entity.item.ItemEntity;
import dev.itemloom.api.ItemActionEvent;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.ItemListeners;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Real listener paths, Craft mirrors/setters and item entities; no connected client or vanilla continuation. */
final class TriggerCommitProbe {
    private static final String CALLBACKS = "trigger-commit-probe-callbacks";
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String EXPANSION =
            """
            var manager = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
            var PlayerUtils = Java.type('pers.neige.neigeitems.utils.PlayerUtils');
            function enable() {
                manager.addConsumer('source-probe', false, function(context, text) {
                    PlayerUtils.getMetadataEZ(context.getPlayer(), 'trigger-commit-probe-callbacks', null)
                        .get(String(text)).accept(context);
                });
            }
            """;
    private static final String AMOUNT_SCRIPT =
            """
            var PlayerUtils = Java.type('pers.neige.neigeitems.utils.PlayerUtils');
            function value() {
                PlayerUtils.getMetadataEZ(this.player, 'trigger-commit-probe-callbacks', null).get('amount').accept(null);
                return 2;
            }
            """;

    private enum Source {
        CURSOR,
        CLICKED,
        HAND,
        HEAD,
        INTERACT,
        DROP,
        PICK
    }

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Trigger commit probe requires the server thread");
        return new Runner(plugin).run();
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
                callbackMoves();
                ownership();
                bodyChanges();
                entities();
                nested();
                lifecycle();
                commitFailures();
                Bukkit.getScheduler()
                        .runTaskLater(
                                plugin,
                                () -> {
                                    try {
                                        for (Fixture fixture : fixtures)
                                            fixture.verifySettled.run();
                                        finish(result, null);
                                    } catch (Throwable failure) {
                                        finish(result, failure);
                                    }
                                },
                                4);
            } catch (Throwable failure) {
                finish(result, failure);
            }
            return result;
        }

        Fixture fixture(Source source) {
            Fixture fixture = new Fixture(plugin, "TriggerCommit" + fixtures.size(), source);
            fixtures.add(fixture);
            return fixture;
        }

        void ordinary() {
            for (Source source : Source.values()) {
                Fixture fixture = fixture(source);
                fixture.pre =
                        context -> {
                            context.getData().put("pre-only", "retained");
                            context.getGlobal().put("pre-data", context.getData());
                        };
                fixture.body =
                        context -> {
                            check(
                                    CraftItemStack.unwrap(context.getItemStack())
                                            == CraftItemStack.unwrap(fixture.source()),
                                    source + " body sees the committed source handle");
                            if (source != Source.PICK)
                                check(
                                        context.getData() == context.getGlobal().get("pre-data")
                                                && "retained"
                                                        .equals(context.getData().get("pre-only")),
                                        source + " retains pre data and globals");
                            ((LegacyNbt.Compound) context.getNbt())
                                    .putString("body-marker", "live");
                        };
                fixture.fire();
                fixture.flush();
                check(
                        fixture.total() == 2
                                && fixture.bodyCalls == 1
                                && charge(fixture.source()) == (source == Source.PICK ? 5 : 3),
                        source + " commits once with exact charged remainder conservation");
                check(
                        "live"
                                .equals(
                                        NmsItems.customData(fixture.source())
                                                .getString("body-marker")
                                                .orElse("")),
                        source + " body NBT writes reach the physical source");
            }
            Fixture ordinary = fixture(Source.CURSOR);
            ordinary.setSource(item(4, null));
            ordinary.fire();
            check(
                    ordinary.total() == 2
                            && ordinary.source().getAmount() == 2
                            && ordinary.addCalls == 0,
                    "ordinary quantities consume exactly two without a charged remainder");
            Fixture exhausted = fixture(Source.INTERACT);
            exhausted.setSource(item(1, null));
            exhausted.fire();
            check(
                    exhausted.total() == 1 && exhausted.bodyCalls == 0,
                    "insufficient ordinary quantity remains untouched");
            exhausted.setSource(item(2, null));
            exhausted.body =
                    context ->
                            check(
                                    context.getItemStack() == null
                                            || context.getItemStack().isEmpty(),
                                    "exhausted source context is empty before any remainder can refill the slot");
            exhausted.fire();
            check(
                    exhausted.total() == 0 && exhausted.bodyCalls == 1,
                    "exhausted inventory mirror clears its original NMS handle");
        }

        void callbackMoves() {
            for (Source source : Source.values()) {
                Fixture gate = fixture(source);
                gate.gate = event -> gate.moveSource(9);
                gate.fire();
                gate.flush();
                check(
                        gate.total() == 2
                                && gate.amountWithCharge(5) == 2
                                && gate.bodyCalls == 0
                                && gate.preCalls == 0,
                        source
                                + " gate source move cannot consume or duplicate the detached source");
                if (source == Source.PICK) continue;
                Fixture pre = fixture(source);
                pre.pre = context -> pre.moveSource(9);
                pre.fire();
                pre.flush();
                check(
                        pre.total() == 2
                                && pre.amountWithCharge(5) == 2
                                && pre.bodyCalls == 0
                                && pre.amountCalls == 0,
                        source
                                + " pre source move stops before amount evaluation and remainder delivery");
            }
            for (String phase : List.of("condition", "amount")) {
                Fixture fixture = fixture(Source.CURSOR);
                if (phase.equals("condition")) fixture.condition = context -> fixture.moveSource(9);
                else fixture.amount = context -> fixture.moveSource(9);
                fixture.fire();
                fixture.flush();
                check(
                        fixture.total() == 2
                                && fixture.amountWithCharge(5) == 2
                                && fixture.bodyCalls == 0,
                        phase
                                + " callback source movement invalidates the candidate before deduction");
            }
        }

        void ownership() {
            for (Source source : List.of(Source.CURSOR, Source.CLICKED, Source.HAND, Source.DROP)) {
                Fixture fixture = fixture(source);
                fixture.pre = context -> fixture.setSource(fixture.source().clone());
                fixture.fire();
                check(
                        fixture.total() == 2
                                && fixture.amountWithCharge(5) == 2
                                && fixture.bodyCalls == 0,
                        source
                                + " equal-valued replacement handle does not inherit source ownership");
            }
            Fixture selected = fixture(Source.INTERACT);
            selected.pre = context -> selected.selected = 1;
            selected.fire();
            check(
                    selected.total() == 2
                            && selected.amountWithCharge(5) == 2
                            && selected.bodyCalls == 0,
                    "hotbar selection change aborts the fixed main-hand source");
            for (Source source : List.of(Source.CURSOR, Source.CLICKED)) {
                Fixture menu = fixture(source);
                menu.pre = context -> menu.view = menu.newView();
                menu.fire();
                check(
                        menu.total() == 2 && menu.amountWithCharge(5) == 2 && menu.bodyCalls == 0,
                        source
                                + " menu replacement invalidates its old cursor or container source");
            }
        }

        void bodyChanges() {
            for (Source source : Source.values()) {
                Fixture fixture = fixture(source);
                fixture.body =
                        context -> {
                            fixture.moveSource(9);
                            fixture.setSource(new ItemStack(Material.DIAMOND, 3));
                            if (context.getEvent() instanceof Cancellable cancellable)
                                cancellable.setCancelled(true);
                        };
                fixture.fire();
                fixture.flush();
                check(
                        fixture.total() == 5
                                && fixture.source().getType() == Material.DIAMOND
                                && fixture.inventory.getItem(9).getType() == Material.APPLE
                                && fixture.amountWithCharge(source == Source.PICK ? 5 : 3)
                                        == (source == Source.PICK ? 2 : 1),
                        source
                                + " body move and replacement survive without an old-value tail write");
            }
            Fixture swap = fixture(Source.PICK);
            swap.put(9, new ItemStack(Material.DIAMOND, 3));
            swap.body =
                    context -> {
                        ItemStack ground = swap.source();
                        swap.setSource(swap.inventory.getItem(9));
                        swap.inventory.setItem(9, ground);
                        ((Cancellable) context.getEvent()).setCancelled(true);
                    };
            swap.fire();
            check(
                    swap.total() == 5
                            && swap.source().getType() == Material.DIAMOND
                            && swap.inventory.getItem(9).getType() == Material.APPLE
                            && swap.amountWithCharge(5) == 2,
                    "cancelled pickup swaps ground A and stored B without duplicating A or losing B");
        }

        void entities() {
            Fixture fixture = fixture(Source.PICK);
            fixture.gate = event -> mark(event.getItemStack(), "gate-marker", "candidate");
            fixture.body =
                    context -> {
                        fixture.entityHandle.getEntityData().packDirty();
                        ((LegacyNbt.Compound) context.getNbt()).putString("body-marker", "live");
                    };
            fixture.fire();
            CompoundTag actual = NmsItems.customData(fixture.source());
            check(
                    fixture.preCalls == 0
                            && fixture.total() == 2
                            && actual.getString("gate-marker").orElse("").equals("candidate")
                            && actual.getString("body-marker").orElse("").equals("live"),
                    "consume=false pickup commits legal gate NBT and synchronous live body NBT");
            check(
                    fixture.entityHandle.getEntityData().isDirty(),
                    "pickup body NBT refreshes actual entity metadata");
            Fixture removed = fixture(Source.DROP);
            removed.body = context -> removed.entity.remove();
            check(
                    removed.fire().isCancelled()
                            && removed.entity.isDead()
                            && removed.inventoryTotal() == 1,
                    "removed drop entity is never restored and only its already authorized remainder survives");
            Fixture empty = fixture(Source.DROP);
            empty.setSource(item(2, null));
            check(
                    empty.fire().isCancelled() && empty.entity.isDead() && empty.total() == 0,
                    "fully consumed dropped source is removed and cannot re-enter vanilla drop handling");
        }

        void nested() {
            Fixture nested = fixture(Source.CURSOR);
            nested.pre = context -> nested.fire();
            nested.body = context -> nested.fire();
            nested.fire();
            check(
                    nested.preCalls == 1
                            && nested.bodyCalls == 1
                            && nested.total() == 2
                            && nested.amountWithCharge(3) == 1,
                    "same cursor nested pre and body callbacks share a single source commit");
            Fixture sourceToEat = fixture(Source.INTERACT);
            sourceToEat.pre =
                    context -> {
                        check(
                                sourceToEat.eat().isCancelled(),
                                "nested eat respects the active interaction hand lock");
                        InventoryClickEvent clickedHand =
                                new InventoryClickEvent(
                                        sourceToEat.view,
                                        InventoryType.SlotType.CONTAINER,
                                        9,
                                        ClickType.LEFT,
                                        InventoryAction.NOTHING);
                        sourceToEat.listeners.click(clickedHand);
                        check(
                                clickedHand.isCancelled(),
                                "inventory-view access to the same player slot shares its equipment source lock");
                    };
            sourceToEat.fire();
            check(
                    sourceToEat.preCalls == 1
                            && sourceToEat.bodyCalls == 1
                            && sourceToEat.total() == 2,
                    "interaction-to-eat nesting does not charge the physical hand twice");
            Fixture eatToSource = fixture(Source.INTERACT);
            eatToSource.pre = context -> eatToSource.fire();
            eatToSource.eat();
            eatToSource.flush();
            check(
                    eatToSource.preCalls == 1
                            && eatToSource.bodyCalls == 1
                            && eatToSource.total() == 2,
                    "eat-to-interaction nesting respects the food transaction lock");
        }

        void lifecycle() {
            for (String phase : List.of("pre", "body")) {
                for (String operation : List.of("close", "reload", "quit")) {
                    Fixture fixture = fixture(Source.CURSOR);
                    Consumer<NiActionContext> interrupt =
                            context -> {
                                switch (operation) {
                                    case "close" -> fixture.catalog.close();
                                    case "reload" -> fixture.reload();
                                    case "quit" -> {
                                        fixture.catalog.triggers().flushReturns(fixture.player);
                                        fixture.state.quit(fixture.player.getUniqueId());
                                        fixture.online = false;
                                    }
                                    default -> throw new AssertionError(operation);
                                }
                            };
                    if (phase.equals("pre")) fixture.pre = interrupt;
                    else fixture.body = interrupt;
                    fixture.fire();
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
                                "body quit retains the ready source remainder without writing through the departed Player");
                        fixture.online = true;
                        fixture.state.join(fixture.player);
                        fixture.flush();
                    }
                    check(
                            fixture.total() == 2
                                    && fixture.bodyCalls == (beforeCommit ? 0 : 1)
                                    && fixture.amountWithCharge(5) == (beforeCommit ? 2 : 1),
                            phase
                                    + " "
                                    + operation
                                    + " preserves only the authorized source result and remainder");
                    fixture.verifySettled =
                            () ->
                                    check(
                                            fixture.total() == 2,
                                            phase
                                                    + " "
                                                    + operation
                                                    + " cannot duplicate a return after delayed tasks run");
                }
            }
        }

        void commitFailures() {
            Fixture interrupted = fixture(Source.DROP);
            interrupted.pre =
                    context ->
                            interrupted.beforeEntityWrite =
                                    () -> {
                                        interrupted
                                                .catalog
                                                .triggers()
                                                .flushReturns(interrupted.player);
                                        interrupted.catalog.close();
                                        check(
                                                interrupted.total() == 2
                                                        && interrupted.addCalls == 0,
                                                "quit and close during source setter cannot flush an uncommitted remainder");
                                    };
            interrupted.fire();
            interrupted.flush();
            check(
                    interrupted.total() == 2
                            && interrupted.amountWithCharge(3) == 1
                            && interrupted.amountWithCharge(5) == 1
                            && interrupted.bodyCalls == 0
                            && interrupted.addCalls == 1,
                    "known successful source write after close delivers its authorized remainder once and skips body");
            for (String failure : List.of("before", "after", "readback")) {
                Fixture fixture = fixture(Source.DROP);
                fixture.pre = context -> fixture.writeFailure = failure;
                fixture.fire();
                fixture.writeFailure = null;
                fixture.badReadback = false;
                fixture.flush();
                fixture.catalog.close();
                fixture.flush();
                int expected = failure.equals("before") ? 2 : 1;
                check(
                        fixture.total() == expected
                                && fixture.bodyCalls == 0
                                && fixture.addCalls == 0
                                && fixture.unknownReturns() == 1,
                        failure
                                + " entity commit failure retains unknown outcome and forbids automatic remainder retry");
                fixture.verifySettled =
                        () ->
                                check(
                                        fixture.total() == expected && fixture.addCalls == 0,
                                        failure
                                                + " uncertain source write stays non-retriable after scheduled ticks");
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
                                "realCraftMirrorsAndEntities",
                                true,
                                "vanillaContinuation",
                                false,
                                "uncertainCommitPolicy",
                                "retain diagnostics; no automatic retry"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Source kind;
        final Player player;
        final PlayerInventory inventory;
        final Inventory top = Bukkit.createInventory(null, 9);
        final net.minecraft.world.item.ItemStack[] contents =
                new net.minecraft.world.item.ItemStack[41];
        final PlayerActionState state = new PlayerActionState();
        final NiRepository.Input input;
        final List<NiCatalog> revisions = new ArrayList<>();
        final Listener gateListener = new Listener() {};
        final ItemListeners listeners;
        final ItemEntity entityHandle;
        final Item entity;
        net.minecraft.world.item.ItemStack cursor = net.minecraft.world.item.ItemStack.EMPTY;
        InventoryView view;
        NiCatalog catalog;
        Consumer<NiActionContext> pre = ignored -> {},
                condition = ignored -> {},
                amount = ignored -> {},
                body = ignored -> {};
        Consumer<ItemActionEvent> gate = ignored -> {};
        Runnable verifySettled = () -> {};
        Runnable beforeEntityWrite;
        Throwable callbackFailure;
        String writeFailure;
        int selected, preCalls, bodyCalls, amountCalls, addCalls;
        boolean online = true, badReadback;

        Fixture(JavaPlugin plugin, String name, Source kind) {
            this.plugin = plugin;
            this.kind = kind;
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
                                                    put(slot(args[0]), (ItemStack) args[1]);
                                                    yield null;
                                                }
                                                case "setItemInMainHand" -> {
                                                    put(selected, (ItemStack) args[0]);
                                                    yield null;
                                                }
                                                case "setItemInOffHand" -> {
                                                    put(40, (ItemStack) args[0]);
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
                                                        if (slot < 36) put(slot, additions[i]);
                                                        else left.put(i, additions[i].clone());
                                                    }
                                                    yield left;
                                                }
                                                case "toString" ->
                                                        "TriggerCommitInventory[" + name + "]";
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
                                                case "getOpenInventory" -> view;
                                                case "getItemOnCursor" ->
                                                        CraftItemStack.asCraftMirror(cursor);
                                                case "setItemOnCursor" -> {
                                                    cursor =
                                                            CraftItemStack.asNMSCopy(
                                                                    (ItemStack) args[0]);
                                                    yield null;
                                                }
                                                case "isOnline" -> online;
                                                case "getPlayer" -> proxy;
                                                default -> invoke(method, delegate, args);
                                            });
            view = newView();
            entityHandle =
                    new ItemEntity(
                            ((CraftWorld) player.getWorld()).getHandle(),
                            12,
                            64,
                            8,
                            CraftItemStack.asNMSCopy(new ItemStack(Material.APPLE)));
            Item realEntity = (Item) entityHandle.getBukkitEntity();
            entity =
                    (Item)
                            Proxy.newProxyInstance(
                                    Item.class.getClassLoader(),
                                    new Class<?>[] {Item.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("getItemStack") && badReadback)
                                            return CraftItemStack.asCraftCopy(
                                                    new ItemStack(Material.DIAMOND));
                                        if (method.getName().equals("setItemStack")) {
                                            Runnable hook = beforeEntityWrite;
                                            beforeEntityWrite = null;
                                            if (hook != null) hook.run();
                                            if ("before".equals(writeFailure))
                                                throw new IllegalStateException(
                                                        "Expected entity failure before write");
                                            Object result = invoke(method, realEntity, args);
                                            if ("after".equals(writeFailure))
                                                throw new IllegalStateException(
                                                        "Expected entity failure after write");
                                            if ("readback".equals(writeFailure)) badReadback = true;
                                            return result;
                                        }
                                        return invoke(method, realEntity, args);
                                    });
            state.join(player);
            Map<String, Consumer<NiActionContext>> callbacks = new LinkedHashMap<>();
            callbacks.put(
                    "pre",
                    context -> {
                        preCalls++;
                        context.getGlobal()
                                .put(
                                        "conditionHook",
                                        (Consumer<NiActionContext>)
                                                current -> callback(condition, current));
                        callback(pre, context);
                    });
            callbacks.put(
                    "body",
                    context -> {
                        bodyCalls++;
                        callback(body, context);
                    });
            callbacks.put(
                    "amount",
                    context -> {
                        amountCalls++;
                        callback(amount, context);
                    });
            state.setMetadata(player.getUniqueId(), CALLBACKS, callbacks);
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("cooldown", 0);
            trigger.put("sync", "source-probe: body");
            trigger.put(
                    "consume",
                    Map.of(
                            "pre",
                            "source-probe: pre",
                            "amount",
                            "<js::amount.js::value>",
                            "condition",
                            "context.getGlobal().get('conditionHook').accept(context); true;"));
            Map<String, Object> configured = new LinkedHashMap<>();
            for (String type :
                    List.of(
                            "click",
                            "beclicked",
                            "right",
                            "damage",
                            "damaged_head",
                            "drop",
                            "pick",
                            "eat")) configured.put(type, trigger);
            input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            Map.of(
                                    "source",
                                    new NiRepository.Definition(
                                            "source",
                                            "memory/source.yml",
                                            new NiConfig(Map.of("material", "APPLE")))),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of("source", configured),
                            Map.of(),
                            Map.of("amount.js", AMOUNT_SCRIPT),
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
            setSource(item(2, 5));
        }

        InventoryView newView() {
            return (InventoryView)
                    Proxy.newProxyInstance(
                            InventoryView.class.getClassLoader(),
                            new Class<?>[] {InventoryView.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "getPlayer" -> player;
                                        case "getTopInventory" -> top;
                                        case "getBottomInventory" -> inventory;
                                        case "getType" -> InventoryType.CHEST;
                                        case "getCursor" -> CraftItemStack.asCraftMirror(cursor);
                                        case "setCursor" -> {
                                            cursor = CraftItemStack.asNMSCopy((ItemStack) args[0]);
                                            yield null;
                                        }
                                        case "getInventory" ->
                                                (int) args[0] < 0
                                                        ? null
                                                        : (int) args[0] < 9 ? top : inventory;
                                        case "convertSlot" ->
                                                (int) args[0] < 9 ? args[0] : (int) args[0] - 9;
                                        case "getItem" ->
                                                (int) args[0] < 9
                                                        ? top.getItem((int) args[0])
                                                        : inventory.getItem((int) args[0] - 9);
                                        case "setItem" -> {
                                            if ((int) args[0] < 9)
                                                top.setItem((int) args[0], (ItemStack) args[1]);
                                            else
                                                inventory.setItem(
                                                        (int) args[0] - 9, (ItemStack) args[1]);
                                            yield null;
                                        }
                                        case "getSlotType" -> InventoryType.SlotType.CONTAINER;
                                        case "countSlots" -> 50;
                                        case "getTitle", "getOriginalTitle" ->
                                                "Trigger commit probe";
                                        case "toString" -> "TriggerCommitView";
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == args[0];
                                        default -> null;
                                    });
        }

        void reload() {
            NiCatalog previous = catalog;
            catalog =
                    new NiCatalog(
                            revisions.size() + 1, input, plugin, (viewer, text) -> null, state);
            revisions.add(catalog);
            if (previous != null) previous.close();
        }

        @SuppressWarnings("deprecation")
        Cancellable fire() {
            return switch (kind) {
                case CURSOR, CLICKED -> {
                    InventoryClickEvent event =
                            new InventoryClickEvent(
                                    view,
                                    InventoryType.SlotType.CONTAINER,
                                    0,
                                    ClickType.LEFT,
                                    InventoryAction.NOTHING);
                    listeners.click(event);
                    yield event;
                }
                case HAND -> {
                    EntityDamageByEntityEvent event =
                            new EntityDamageByEntityEvent(
                                    player,
                                    ProbePlayer.create("TriggerVictim"),
                                    EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                                    1);
                    listeners.attack(event);
                    yield event;
                }
                case HEAD -> {
                    EntityDamageEvent event =
                            new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 1);
                    listeners.damaged(event);
                    yield event;
                }
                case INTERACT -> {
                    PlayerInteractEvent event =
                            new PlayerInteractEvent(
                                    player,
                                    Action.RIGHT_CLICK_AIR,
                                    inventory.getItemInMainHand(),
                                    null,
                                    BlockFace.SELF,
                                    EquipmentSlot.HAND);
                    event.setCancelled(false);
                    listeners.interact(event);
                    yield event;
                }
                case DROP -> {
                    PlayerDropItemEvent event = new PlayerDropItemEvent(player, entity);
                    listeners.drop(event);
                    yield event;
                }
                case PICK -> {
                    EntityPickupItemEvent event = new EntityPickupItemEvent(player, entity, 0);
                    listeners.pickup(event);
                    yield event;
                }
            };
        }

        PlayerItemConsumeEvent eat() {
            PlayerItemConsumeEvent event =
                    new PlayerItemConsumeEvent(
                            player, inventory.getItemInMainHand(), EquipmentSlot.HAND);
            listeners.consume(event);
            return event;
        }

        ItemStack source() {
            return switch (kind) {
                case CURSOR -> CraftItemStack.asCraftMirror(cursor);
                case CLICKED -> top.getItem(0);
                case HAND, INTERACT -> mirror(0);
                case HEAD -> mirror(39);
                case DROP, PICK -> entity.getItemStack();
            };
        }

        void setSource(ItemStack item) {
            switch (kind) {
                case CURSOR -> view.setCursor(item);
                case CLICKED -> top.setItem(0, item);
                case HAND, INTERACT -> inventory.setItem(0, item);
                case HEAD -> inventory.setItem(39, item);
                case DROP, PICK -> entity.setItemStack(item);
            }
        }

        void moveSource(int slot) {
            inventory.setItem(slot, source());
            setSource(new ItemStack(Material.AIR));
        }

        void flush() {
            revisions.forEach(revision -> revision.triggers().flushReturns(player));
        }

        void put(int slot, ItemStack item) {
            contents[slot] = CraftItemStack.asNMSCopy(item);
        }

        ItemStack mirror(int slot) {
            return CraftItemStack.asCraftMirror(contents[slot]);
        }

        int inventoryTotal() {
            return Arrays.stream(contents)
                    .filter(item -> !item.isEmpty())
                    .mapToInt(net.minecraft.world.item.ItemStack::getCount)
                    .sum();
        }

        List<ItemStack> all() {
            List<ItemStack> stacks = new ArrayList<>(Arrays.asList(inventory.getContents()));
            stacks.add(CraftItemStack.asCraftMirror(cursor));
            stacks.addAll(Arrays.asList(top.getContents()));
            if ((kind == Source.DROP || kind == Source.PICK) && !entity.isDead())
                stacks.add(entity.getItemStack());
            return stacks;
        }

        int total() {
            return all().stream()
                    .filter(item -> item != null && !item.isEmpty())
                    .mapToInt(ItemStack::getAmount)
                    .sum();
        }

        int amountWithCharge(int expected) {
            return all().stream()
                    .filter(item -> item != null && !item.isEmpty() && charge(item) == expected)
                    .mapToInt(ItemStack::getAmount)
                    .sum();
        }

        int slot(Object slot) {
            if (slot instanceof Integer index) return index;
            return switch ((EquipmentSlot) slot) {
                case HAND -> selected;
                case OFF_HAND -> 40;
                case HEAD -> 39;
                case CHEST -> 38;
                case LEGS -> 37;
                case FEET -> 36;
                default -> throw new IllegalArgumentException("Unsupported probe slot: " + slot);
            };
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
            entity.remove();
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

    private static ItemStack item(int amount, Integer charge) {
        CompoundTag properties = new CompoundTag();
        if (charge != null) properties.putInt("charge", charge);
        CompoundTag data = new CompoundTag();
        data.put(
                ItemStateCodec.KEY,
                CODEC.encode(new ItemIdentity("source", Map.of("seed", "preserve")), properties));
        return NmsItems.withCustomData(new ItemStack(Material.APPLE, amount), data);
    }

    private static void mark(ItemStack item, String key, String value) {
        CompoundTag data = NmsItems.customData(item);
        data.putString(key, value);
        NmsItems.replace(item, NmsItems.withCustomData(item, data));
    }

    private static int charge(ItemStack item) {
        return CODEC.properties(item).getInt("charge").orElse(-1);
    }
}

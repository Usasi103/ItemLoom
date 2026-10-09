package dev.itemloom.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.DropOwnership;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.LegacyItemUtils;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

/** Real entities and scheduler; players/events remain controlled fixtures with no connected client. */
final class ItemDropProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        Checks c = new Checks();
        c.group(
                "drop-payload",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        payload(c, f);
                    }
                });
        c.group(
                "fancy-and-cancel",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        fancy(c, f);
                    }
                });
        c.group(
                "ownership",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        ownership(c, f);
                    }
                });
        c.group(
                "real-listeners",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        listeners(c, f);
                    }
                });
        c.group(
                "pending-close",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        pending(c, f);
                    }
                });
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        try {
            Fixture f = new Fixture(plugin);
            var timeout =
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> {
                                        c.failures.put("async", "Timed out after 100 ticks");
                                        c.group("cleanup", f::close);
                                        result.complete(c.report());
                                    },
                                    100);
            ItemStack stack = f.stack("AsyncOwner", false, null);
            List<Boolean> callbackThreads = new ArrayList<>();
            var context =
                    f.catalog.actionContext(
                            null, Map.of("at", f.at, "stack", stack, "threads", callbackThreads));
            Bukkit.getScheduler()
                    .runTaskAsynchronously(
                            plugin,
                            () -> {
                                try {
                                    Object future =
                                            context.evaluate(
                                                    """
                            var Items = Java.type('pers.neige.neigeitems.utils.ItemUtils');
                            Items.dropNiItem(at, stack).thenApply(function(item) {
                                threads.add(Java.type('org.bukkit.Bukkit').isPrimaryThread());
                                return item;
                            });
                            """);
                                    ((CompletableFuture<?>) future)
                                            .whenComplete(
                                                    (value, failure) ->
                                                            Bukkit.getScheduler()
                                                                    .runTask(
                                                                            plugin,
                                                                            () -> {
                                                                                if (result.isDone())
                                                                                    return;
                                                                                c.group(
                                                                                        "async-drop",
                                                                                        () -> {
                                                                                            if (failure
                                                                                                    != null)
                                                                                                throw new AssertionError(
                                                                                                        failure);
                                                                                            Item
                                                                                                    item =
                                                                                                            f
                                                                                                                    .keep(
                                                                                                                            (Item)
                                                                                                                                    value);
                                                                                            c.that(
                                                                                                    item
                                                                                                                    != null
                                                                                                            && "AsyncOwner"
                                                                                                                    .equals(
                                                                                                                            DropOwnership
                                                                                                                                    .owner(
                                                                                                                                            item)),
                                                                                                    "worker script schedules actual owned drop on server thread");
                                                                                            c.that(
                                                                                                    callbackThreads
                                                                                                            .equals(
                                                                                                                    List
                                                                                                                            .of(
                                                                                                                                    true)),
                                                                                                    "thenApply preserves script context and completes on main thread");
                                                                                        });
                                                                                c.group(
                                                                                        "close-before-dispatch",
                                                                                        () -> {
                                                                                            var
                                                                                                    closed =
                                                                                                            f
                                                                                                                    .catalog
                                                                                                                    .drops();
                                                                                            f
                                                                                                    .catalog
                                                                                                    .close();
                                                                                            c.that(
                                                                                                    closed.drop(
                                                                                                                    f.at,
                                                                                                                    f
                                                                                                                            .stack(
                                                                                                                                    "Closed",
                                                                                                                                    false,
                                                                                                                                    null),
                                                                                                                    null)
                                                                                                            .isCancelled(),
                                                                                                    "closed catalog refuses a new scheduled drop");
                                                                                        });
                                                                                timeout.cancel();
                                                                                c.group(
                                                                                        "cleanup",
                                                                                        f::close);
                                                                                result.complete(
                                                                                        c.report());
                                                                            }));
                                } catch (Throwable error) {
                                    Bukkit.getScheduler()
                                            .runTask(
                                                    plugin,
                                                    () -> {
                                                        if (result.isDone()) return;
                                                        c.failures.put(
                                                                "async-script", error.toString());
                                                        timeout.cancel();
                                                        c.group("cleanup", f::close);
                                                        result.complete(c.report());
                                                    });
                                }
                            });
        } catch (Throwable error) {
            c.failures.put("async-setup", error.toString());
            result.complete(c.report());
        }
        return result;
    }

    private static void payload(Checks c, Fixture f) throws Exception {
        ItemStack source = f.stack("Alice", true, null);
        Item item = f.keep(f.catalog.drops().drop(f.at, source, null).join());
        c.that(
                item != null && !item.isDead() && "Alice".equals(DropOwnership.owner(item)),
                "accepted actual entity receives ownership before spawn");
        c.that(
                item.getScoreboardTags().containsAll(List.of("NI-Hide", "NeigeItems", "ItemLoom")),
                "old script markers and independent marker are present");
        c.that(
                new LegacyNbtItemStack(source).getTag().getCompound("NeigeItems").getString("owner")
                                == null
                        && new LegacyNbtItemStack(item.getItemStack())
                                        .getTag()
                                        .getCompound("NeigeItems")
                                        .getString("owner")
                                == null,
                "single drop removes transferred owner from source and entity stack");
        c.that(
                !NmsItems.customData(item.getItemStack()).contains("NeigeItems"),
                "drop keeps independent storage envelope");
        item.removeMetadata("NI-Owner", f.plugin);
        c.that(
                "Alice".equals(DropOwnership.owner(item)),
                "persistent owner works without transient metadata");
        byte[] serialized = item.getPersistentDataContainer().serializeToBytes();
        Item restored = f.keep(f.at.getWorld().dropItem(f.at, new ItemStack(Material.STONE)));
        restored.getPersistentDataContainer().readFromBytes(serialized, true);
        c.that(
                "Alice".equals(DropOwnership.owner(restored)),
                "owner survives real persistent-data serialization and reload into another entity");
        ItemStack multiple = f.stack("BatchOwner", false, null);
        List<Item> batch = f.keepAll(f.catalog.drops().amount(f.at, multiple, 130, null).join());
        c.that(
                batch.stream()
                        .map(value -> value.getItemStack().getAmount())
                        .toList()
                        .equals(List.of(64, 64, 2)),
                "drop amount splits 130 into 64,64,2");
        c.that(
                batch.stream().allMatch(value -> "BatchOwner".equals(DropOwnership.owner(value))),
                "explicit correction: every split entity retains ownership");
        c.that(
                new LegacyNbtItemStack(multiple)
                        .getTag()
                        .getCompound("NeigeItems")
                        .getString("owner")
                        .equals("BatchOwner"),
                "multi-drop modifies copies and preserves input owner");
        multiple.setAmount(12);
        c.that(
                f.keepAll(f.catalog.drops().amount(f.at, multiple, null, null).join())
                                .getFirst()
                                .getItemStack()
                                .getAmount()
                        == 1,
                "dropNiItems null amount means one, not input stack count");
        c.that(
                f.catalog.drops().amount(f.at, multiple, 0, null).join().isEmpty(),
                "zero drop amount creates no entity");
        var tag = new LegacyNbtItemStack(f.stack("Detached", false, null)).getTag().clone();
        ItemStack tagged = new ItemStack(Material.STONE);
        Item detached =
                f.keep(
                        f.catalog
                                .drops()
                                .drop(f.at, tagged, null, tag, tag.getCompound("NeigeItems"))
                                .join());
        c.that(
                "Detached".equals(DropOwnership.owner(detached)),
                "explicit detached NBT overload transfers owner");
        var craft =
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftCopy(
                        f.stack("Craft", false, null));
        c.that(
                "Craft"
                                .equals(
                                        DropOwnership.owner(
                                                f.keep(
                                                        f.catalog
                                                                .drops()
                                                                .drop(f.at, craft, null)
                                                                .join())))
                        && new LegacyNbtItemStack(craft)
                                        .getTag()
                                        .getCompound("NeigeItems")
                                        .getString("owner")
                                == null,
                "Craft source live view commits owner removal");
        c.that(
                f.catalog
                                .drops()
                                .drop(
                                        new Location(null, 1, 2, 3),
                                        f.stack("Void", false, null),
                                        null)
                                .join()
                        == null,
                "null world returns null without creating entity");
        var api = new LegacyItemUtils(stack -> "name", f.catalog.items());
        c.that(
                f.keep(api.dropNiItem(f.at, new ItemStack(Material.APPLE)).join())
                                .getItemStack()
                                .getType()
                        == Material.APPLE,
                "legacy helper delegates to independent drop service");
        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            var trigger = f.at.getWorld().spawn(f.at, org.bukkit.entity.ArmorStand.class);
            try {
                Item skilled =
                        f.keep(
                                f.catalog
                                        .drops()
                                        .drop(
                                                f.at,
                                                f.stack(null, false, "ItemLoomDropProbe"),
                                                trigger)
                                        .join());
                c.that(
                        skilled.getScoreboardTags().contains("itemloom_drop_skill")
                                && trigger.getScoreboardTags().contains("itemloom_drop_trigger"),
                        "real Mythic dropSkill receives item caster and distinct trigger");
            } finally {
                trigger.remove();
            }
        }
    }

    private static void fancy(Checks c, Fixture f) {
        List<ItemStack> stacks =
                List.of(
                        new ItemStack(Material.STONE),
                        new ItemStack(Material.DIRT),
                        new ItemStack(Material.APPLE),
                        new ItemStack(Material.DIAMOND));
        List<Item> circle =
                f.keepAll(f.catalog.drops().list(stacks, f.at, null, "0.5", "0.3", "round").join());
        c.that(
                circle.size() == 4
                        && close(circle.get(0).getVelocity().getX(), .5)
                        && close(circle.get(1).getVelocity().getZ(), -.5)
                        && close(circle.get(2).getVelocity().getX(), -.5)
                        && close(circle.get(3).getVelocity().getZ(), .5),
                "round velocities use equally spaced angles");
        c.that(
                circle.stream().allMatch(item -> close(item.getVelocity().getY(), .3)),
                "all fancy drops preserve configured vertical velocity");
        Item fallback =
                f.keepAll(
                                f.catalog
                                        .drops()
                                        .list(
                                                List.of(new ItemStack(Material.STONE)),
                                                f.at,
                                                null,
                                                "bad",
                                                "-2",
                                                "other")
                                        .join())
                        .getFirst();
        c.that(
                close(fallback.getVelocity().getX(), .1)
                        && close(fallback.getVelocity().getY(), .1)
                        && close(fallback.getVelocity().getZ(), 0),
                "invalid and leading-minus offsets keep NI fallback with unknown angle type");
        c.that(
                fails(f.catalog.drops().list(stacks, f.at, null, "2-1", "0", "round")),
                "invalid numeric range propagates future failure before dropping");
        c.that(
                f.keepAll(f.catalog.drops().list(stacks, f.at, null, "2-1", null, "round").join())
                                .size()
                        == 4,
                "any absent fancy parameter selects natural drop without parsing offsets");
        AtomicInteger observed = new AtomicInteger();
        Listener canceller = new Listener() {};
        Bukkit.getPluginManager()
                .registerEvent(
                        ItemSpawnEvent.class,
                        canceller,
                        EventPriority.HIGHEST,
                        (ignored, event) -> {
                            ItemSpawnEvent spawn = (ItemSpawnEvent) event;
                            if ("Cancelled".equals(DropOwnership.owner(spawn.getEntity()))) {
                                observed.incrementAndGet();
                                spawn.setCancelled(true);
                            }
                        },
                        f.plugin);
        try {
            c.that(
                    f.catalog.drops().drop(f.at, f.stack("Cancelled", false, null), null).join()
                                    == null
                            && observed.get() == 1,
                    "cancelled real spawn is reported absent and never retried");
        } finally {
            HandlerList.unregisterAll(canceller);
        }
    }

    private static void ownership(Checks c, Fixture f) {
        var guard =
                new DropOwnership(
                        () ->
                                new NiConfig(
                                        Map.of(
                                                "Messages",
                                                Map.of("invalidOwnerMessage", "owned by {name}"),
                                                "ItemOwner",
                                                Map.of("messageType", "message"))));
        Item alice =
                f.keep(f.catalog.drops().drop(f.at, f.stack("Alice", false, null), null).join());
        Item alsoAlice =
                f.keep(f.catalog.drops().drop(f.at, f.stack("Alice", false, null), null).join());
        Item bob = f.keep(f.catalog.drops().drop(f.at, f.stack("Bob", false, null), null).join());
        Item unowned =
                f.keep(f.catalog.drops().drop(f.at, new ItemStack(Material.STONE), null).join());
        var allowed = new EntityPickupItemEvent(ProbePlayer.create("Alice"), alice, 0);
        guard.pickup(allowed);
        var blocked = new EntityPickupItemEvent(ProbePlayer.create("Bob"), alice, 0);
        guard.pickup(blocked);
        c.that(
                !allowed.isCancelled() && blocked.isCancelled(),
                "exact owner name may pick up; other player is cancelled");
        var caseChanged = new EntityPickupItemEvent(ProbePlayer.create("alice"), alice, 0);
        guard.pickup(caseChanged);
        c.that(caseChanged.isCancelled(), "owner matching retains NI case-sensitive name rule");
        var same = new ItemMergeEvent(alice, alsoAlice);
        guard.merge(same);
        var different = new ItemMergeEvent(alice, bob);
        guard.merge(different);
        var intoPublic = new ItemMergeEvent(alice, unowned);
        guard.merge(intoPublic);
        var intoOwned = new ItemMergeEvent(unowned, alice);
        guard.merge(intoOwned);
        c.that(
                !same.isCancelled()
                        && different.isCancelled()
                        && intoPublic.isCancelled()
                        && intoOwned.isCancelled(),
                "merge protects both different owners and owned/unowned boundaries in either direction");
        alsoAlice.addScoreboardTag("NI-Hide");
        var hidden = new ItemMergeEvent(alice, alsoAlice);
        guard.merge(hidden);
        c.that(hidden.isCancelled(), "merge cannot discard a different visibility marker");
        unowned.setMetadata("NI-Owner", new FixedMetadataValue(f.plugin, "Legacy"));
        c.that(
                "Legacy".equals(DropOwnership.owner(unowned)),
                "pre-existing NI metadata-only entity is recognized");
        var helpers = new dev.itemloom.paper.compat.script.LegacyPlayerUtils(f.plugin, f.players);
        helpers.setMetadataEZ(alice, "NI-Owner", "Changed");
        alice.removeMetadata("NI-Owner", f.plugin);
        c.that(
                "Changed".equals(helpers.getMetadataEZ(alice, "NI-Owner", "absent")),
                "legacy metadata setter updates persisted owner and getter works after transient metadata disappears");
        helpers.setMetadataEZ(alice, "NI-Owner", null);
        c.that(
                DropOwnership.owner(alice) == null,
                "explicit null owner clears this plugin's metadata and persistent owner");
    }

    private static void listeners(Checks c, Fixture f) throws Exception {
        try (ItemsService service =
                new ItemsService(f.plugin, f.root, (p, s) -> null, f.root.resolve("ledger.json"))) {
            c.that(
                    service.reload(Bukkit.getConsoleSender()),
                    "service installs ownership listeners");
            var item =
                    f.keep(
                            f.catalog
                                    .drops()
                                    .drop(f.at, f.stack("Owner", false, null), null)
                                    .join());
            var intruder = ProbePlayer.create("Intruder");
            var owner = ProbePlayer.create("Owner");
            service.joined(
                    new org.bukkit.event.player.PlayerJoinEvent(
                            intruder, (net.kyori.adventure.text.Component) null));
            service.joined(
                    new org.bukkit.event.player.PlayerJoinEvent(
                            owner, (net.kyori.adventure.text.Component) null));
            var event = new EntityPickupItemEvent(intruder, item, 0);
            Bukkit.getPluginManager().callEvent(event);
            c.that(
                    event.isCancelled() && System.getProperty(f.key) == null,
                    "actual event dispatch cancels foreign pickup and skips HIGH pick actions");
            var allowed = new EntityPickupItemEvent(owner, item, 0);
            Bukkit.getPluginManager().callEvent(allowed);
            c.that(
                    "picked".equals(System.getProperty(f.key)),
                    "positive control: same owner's actual pickup dispatch executes configured pick action");
            service.close();
            var after = new EntityPickupItemEvent(ProbePlayer.create("Intruder"), item, 0);
            // The main plugin also protects owned items; inspect registration ownership directly.
            c.that(
                    java.util.Arrays.stream(after.getHandlers().getRegisteredListeners())
                            .noneMatch(
                                    listener ->
                                            listener.getPlugin() == f.plugin
                                                    && listener.getListener()
                                                            instanceof DropOwnership),
                    "service close unregisters only its ownership listener");
        }
    }

    private static void pending(Checks c, Fixture f) throws Exception {
        ItemStack source = f.stack("Pending", false, null);
        var scheduler = java.util.concurrent.Executors.newSingleThreadExecutor();
        CompletableFuture<Item> pending;
        try {
            pending =
                    scheduler
                            .submit(() -> f.catalog.drops().drop(f.at, source, null))
                            .get(3, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            scheduler.shutdownNow();
        }
        c.that(!pending.isDone(), "worker submits drop without waiting for main thread");
        f.catalog.close();
        c.that(
                pending.isCancelled()
                        && "Pending"
                                .equals(
                                        new LegacyNbtItemStack(source)
                                                .getTag()
                                                .getCompound("NeigeItems")
                                                .getString("owner")),
                "closing before dispatch cancels queued drop without reading or mutating source");
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-8;
    }

    private static boolean fails(CompletableFuture<?> value) {
        try {
            value.join();
            return false;
        } catch (java.util.concurrent.CompletionException expected) {
            return true;
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static final class Checks {
        final List<String> assertions = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        void that(boolean success, String text) {
            if (!success) throw new AssertionError(text);
            assertions.add(text);
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
                    assertions,
                    "failures",
                    failures,
                    "realClient",
                    false,
                    "boundaries",
                    List.of(
                            "PDC byte round trip, not full process restart",
                            "Visibility has a separate drop-visibility probe",
                            "Synthetic player pickup events"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Path root;
        final NiCatalog catalog;
        final PlayerActionState players = new PlayerActionState();
        final List<Item> entities = new ArrayList<>();
        final Location at = Bukkit.getWorlds().getFirst().getSpawnLocation().add(0, 10, 0);
        final String key = "itemloom.drop.probe." + java.util.UUID.randomUUID();
        final boolean ticket;

        Fixture(JavaPlugin plugin) throws Exception {
            this.plugin = plugin;
            root =
                    Files.createTempDirectory("itemloom-item-drop-probe-")
                            .toAbsolutePath()
                            .normalize();
            ticket = at.getChunk().addPluginChunkTicket(plugin);
            Files.writeString(
                    Files.createDirectories(root.resolve("Items")).resolve("items.yml"),
                    """
                    probe:
                      material: STONE
                    """);
            Files.writeString(
                    Files.createDirectories(root.resolve("ItemActions")).resolve("actions.yml"),
                    """
                    probe:
                      pick:
                        sync: "js: Java.type('java.lang.System').setProperty('%s', 'picked');"
                    """
                            .formatted(key));
            catalog =
                    new NiCatalog(
                            1,
                            new NiRepository().read(root),
                            root,
                            plugin,
                            (p, s) -> null,
                            players);
        }

        ItemStack stack(String owner, boolean hide, String skill) {
            var tag = new net.minecraft.nbt.CompoundTag();
            var state = new net.minecraft.nbt.CompoundTag();
            state.putString("id", "probe");
            state.putString("data", "{}");
            if (owner != null) state.putString("owner", owner);
            if (hide) state.putBoolean("hide", true);
            if (skill != null) state.putString("dropSkill", skill);
            tag.put("NeigeItems", state);
            return NmsItems.withCustomData(
                    new ItemStack(Material.STONE),
                    new dev.itemloom.paper.compat.NiItemMigration().convert(tag));
        }

        Item keep(Item item) {
            if (item != null) entities.add(item);
            return item;
        }

        List<Item> keepAll(List<Item> items) {
            entities.addAll(items);
            return items;
        }

        @Override
        public void close() throws Exception {
            entities.forEach(Item::remove);
            catalog.close();
            players.close();
            System.clearProperty(key);
            if (ticket) at.getChunk().removePluginChunkTicket(plugin);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root))
                        throw new IllegalStateException("Cleanup escaped owned fixture");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}

package dev.itemloom.probe;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import dev.itemloom.api.ItemContext;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.api.ItemProvider;
import dev.itemloom.api.ItemsReloadEvent;
import dev.itemloom.api.ProviderRegistration;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.command.GiveRequest;
import dev.itemloom.paper.command.ItemCommands;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Independent provider lifecycle and real command tree; synthetic player has no client connection. */
final class ProviderAdminProbe implements Listener {
    private final JavaPlugin plugin;
    private final ItemsService items;
    private final ItemCommands batches;
    private final List<String> checks = new ArrayList<>();
    private final List<ProviderRegistration> registrations = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<Integer> ticks = new ArrayList<>();
    private final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Player player;
    private int reloads, generated;
    private Path conflict, saved;
    private boolean finished;

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        var probe = new ProviderAdminProbe(plugin);
        probe.start();
        return probe.report;
    }

    private ProviderAdminProbe(JavaPlugin plugin) {
        this.plugin = plugin;
        items = (ItemsService) Bukkit.getServicesManager().load(ItemLoom.class);
        batches = new ItemCommands(plugin, items);
        Player underlying = ProbePlayer.create("ProviderAdmin");
        player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (self, method, args) -> {
                                    if (method.getName().equals("hasPermission")) return true;
                                    if (method.getName().equals("sendMessage")) {
                                        messages.add(java.util.Arrays.deepToString(args));
                                        return null;
                                    }
                                    return method.invoke(underlying, args);
                                });
    }

    @EventHandler
    public void reload(ItemsReloadEvent event) {
        if (!event.isInitial()) reloads++;
    }

    @EventHandler
    public void generated(ItemGenerateEvent event) {
        if (event.getId().startsWith("apiprobe:")) generated++;
    }

    private ItemProvider definition() {
        return new ItemProvider(
                Map.of(
                        "stone",
                        context -> {
                            check(
                                    context.get(ItemContext.VIEWER) == player,
                                    "provider viewer belongs to request");
                            ticks.add(Bukkit.getCurrentTick());
                            ItemStack result = new ItemStack(Material.STONE, 9);
                            int call = calls.incrementAndGet();
                            result.editMeta(
                                    meta ->
                                            meta.displayName(
                                                    Component.text(
                                                            context.rolls()
                                                                            .getOrDefault(
                                                                                    "quality",
                                                                                    "plain")
                                                                    + ':'
                                                                    + call)));
                            context.rolls().put("generated", Integer.toString(call));
                            return result;
                        }),
                Map.of("triple", List.of("stone", "stone", "stone")));
    }

    private void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            var provider = definition();
            var first = items.registerProvider(plugin, "apiprobe", provider);
            registrations.add(first);
            check(
                    items.ids().contains("apiprobe:stone")
                            && items.groupIds().contains("apiprobe:triple"),
                    "namespaced items and groups published");
            rejects(
                    () -> items.registerProvider(plugin, "apiprobe", provider),
                    "duplicate namespace rejected");
            rejects(
                    () -> items.registerProvider(plugin, "BAD!", provider),
                    "invalid namespace rejected");
            rejects(
                    () ->
                            items.registerProvider(
                                    plugin,
                                    "badgroup",
                                    new ItemProvider(
                                            provider.items(), Map.of("broken", List.of("absent")))),
                    "unknown group member rejected");
            var parameters = Map.of("quality", "rare");
            ItemStack created = items.create("apiprobe:stone", player, parameters);
            check(
                    created.getAmount() == 9
                            && !parameters.containsKey("generated")
                            && generated == 1,
                    "independent generator dispatches event without mutating input parameters");
            check(
                    items.identify(created).isEmpty(),
                    "external provider preserves ownership of its own identity");
            List<ItemStack> group = items.createGroup("apiprobe:triple", player, parameters);
            check(
                    group.size() == 3
                            && group.stream()
                                            .map(item -> item.getItemMeta().displayName())
                                            .distinct()
                                            .count()
                                    == 3,
                    "ordered groups independently generate repeated members");
            // A provider-owned prototype must not be changed by event handlers or callers.
            ItemStack prototype = new ItemStack(Material.DIAMOND, 2);
            var copy =
                    items.registerProvider(
                            plugin,
                            "copyprobe",
                            new ItemProvider(Map.of("item", context -> prototype)));
            registrations.add(copy);
            items.create("copyprobe:item", player, Map.of()).setAmount(40);
            check(
                    prototype.getAmount() == 2
                            && items.create("copyprobe:item", player, Map.of()).getAmount() == 2,
                    "provider prototypes are detached from returned stacks");
            copy.close();
            copy.close();
            var replacement =
                    items.registerProvider(
                            plugin,
                            "copyprobe",
                            new ItemProvider(Map.of("item", context -> prototype)));
            registrations.add(replacement);
            copy.close();
            check(
                    items.ids().contains("copyprobe:item"),
                    "old registration handle cannot remove a replacement");
            var recursive =
                    items.registerProvider(
                            plugin,
                            "recursive",
                            new ItemProvider(
                                    Map.of(
                                            "item",
                                            context ->
                                                    items.create(
                                                            "recursive:item", player, Map.of()))));
            registrations.add(recursive);
            rejects(
                    () -> items.create("recursive:item", player, Map.of()),
                    "recursive provider generation rejected");
            recursive.close();
            Plugin owner =
                    (Plugin)
                            Proxy.newProxyInstance(
                                    Plugin.class.getClassLoader(),
                                    new Class<?>[] {Plugin.class},
                                    (self, method, args) ->
                                            switch (method.getName()) {
                                                case "isEnabled" -> true;
                                                case "getName" -> "ProbeOwner";
                                                case "equals" -> self == args[0];
                                                case "hashCode" -> System.identityHashCode(self);
                                                default -> null;
                                            });
            registrations.add(
                    items.registerProvider(
                            owner,
                            "owned",
                            new ItemProvider(Map.of("item", context -> prototype))));
            Bukkit.getPluginManager()
                    .callEvent(new org.bukkit.event.server.PluginDisableEvent(owner));
            check(!items.ids().contains("owned:item"), "owner disable event removes its provider");
            Path root =
                    Bukkit.getPluginManager()
                            .getPlugin("ItemLoom")
                            .getDataFolder()
                            .toPath()
                            .resolve("Items");
            conflict = root.resolve("provider-conflict-probe.yml");
            Files.writeString(conflict, "'apiprobe:stone':\n  material: DIRT\n");
            Object revision = items.placeholderRevision();
            check(
                    !items.reload(Bukkit.getConsoleSender())
                            && reloads == 0
                            && items.placeholderRevision() == revision,
                    "configured-provider collision rolls back without reload notification");
            Files.delete(conflict);
            conflict = null;
            check(
                    items.reload(Bukkit.getConsoleSender())
                            && reloads == 1
                            && items.ids().contains("apiprobe:stone"),
                    "successful reload notifies once and preserves external providers");
            rejects(() -> GiveRequest.parse("item 0"), "zero command amount rejected");
            rejects(() -> GiveRequest.parse("item 257"), "command amount bound enforced");
            rejects(() -> GiveRequest.parse("item 1 invalid"), "unknown batch mode rejected");
            rejects(
                    () -> GiveRequest.parse("item 1 roll {\"a\":\"x\",\"a\":\"y\"}"),
                    "duplicate JSON parameter rejected");
            rejects(
                    () -> GiveRequest.parse("item 1 roll {\"a\":2}"),
                    "nonstring JSON parameter rejected");
            check(
                    GiveRequest.parse("item 3 roll {\"quality\": \"rare item\"}")
                            .parameters()
                            .get("quality")
                            .equals("rare item"),
                    "JSON values retain spaces");
            command("list", "1");
            command("search", "apiprobe");
            check(
                    messages.stream().anyMatch(text -> text.contains("apiprobe:stone")),
                    "registered list/search command renders matching IDs");
            player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 12));
            command("inspect");
            check(
                    messages.stream().anyMatch(text -> text.contains("minecraft:diamond")),
                    "registered inspect command exposes native item payload");
            saved = root.resolve("admin-probe.yml");
            command("save", "ILProbeAdminSaved", "admin-probe.yml");
            check(
                    Files.exists(saved)
                            && items.create("ILProbeAdminSaved", player, Map.of()).getType()
                                    == Material.DIAMOND,
                    "save command registers a current-state snapshot");
            byte[] before = Files.readAllBytes(saved);
            command("save", "ILProbeAdminSaved", "admin-probe.yml");
            check(
                    java.util.Arrays.equals(before, Files.readAllBytes(saved)),
                    "duplicate save does not overwrite disk");
            rejects(
                    () -> items.save(prototype, "ILProbeEscape", "../escape.yml", false),
                    "save rejects path escaping Items");
            check(
                    items.save(prototype, "apiprobe:stone", "admin-probe.yml", true)
                            == ItemLoom.SaveResult.CONFLICT,
                    "save cannot replace external provider items");
            ticks.clear();
            int beforeCalls = calls.get();
            after(
                    batches.give(
                            player,
                            GiveRequest.parse("apiprobe:stone 3 roll {\"quality\":\"rare\"}")),
                    amount -> {
                        check(
                                amount == 3
                                        && calls.get() - beforeCalls == 3
                                        && ticks.stream().distinct().count() == 3,
                                "independent batch generates one item on each of three ticks");
                        int stackCalls = calls.get();
                        after(
                                batches.give(player, GiveRequest.parse("apiprobe:stone 130 stack")),
                                stackAmount -> {
                                    check(
                                            stackAmount == 130 && calls.get() - stackCalls == 1,
                                            "stack batch invokes generator once for 130 items");
                                    int total =
                                            java.util.Arrays.stream(
                                                            player.getInventory().getContents())
                                                    .filter(
                                                            item ->
                                                                    item != null
                                                                            && item.getType()
                                                                                    == Material
                                                                                            .STONE)
                                                    .mapToInt(ItemStack::getAmount)
                                                    .sum();
                                    check(
                                            total == 133,
                                            "batch delivery conserves count across split stacks");
                                    overflowThenCancellation();
                                });
                    });
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void overflowThenCancellation() {
        Player full = ProbePlayer.create("FullProviderAdmin_" + java.util.UUID.randomUUID());
        for (int slot = 0; slot < 36; slot++)
            full.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        var world = full.getWorld();
        world.getChunkAt(full.getLocation()).load();
        var before =
                world.getEntitiesByClass(org.bukkit.entity.Item.class).stream()
                        .map(org.bukkit.entity.Entity::getUniqueId)
                        .collect(java.util.stream.Collectors.toSet());
        after(
                batches.give(full, GiveRequest.parse("copyprobe:item 130 stack")),
                amount -> {
                    var drops =
                            world.getEntitiesByClass(org.bukkit.entity.Item.class).stream()
                                    .filter(item -> !before.contains(item.getUniqueId()))
                                    .toList();
                    try {
                        int droppedCount =
                                drops.stream()
                                        .mapToInt(item -> item.getItemStack().getAmount())
                                        .sum();
                        if (amount != 130 || droppedCount != 130)
                            throw new AssertionError(
                                    "Full inventory: reported="
                                            + amount
                                            + ", observed="
                                            + droppedCount
                                            + ", entities="
                                            + drops.size());
                        check(
                                true,
                                "full inventory drops all batch leftovers without losing count");
                        check(
                                drops.stream()
                                        .allMatch(
                                                item -> full.getUniqueId().equals(item.getOwner())),
                                "overflow items belong to the target player");
                    } finally {
                        drops.forEach(org.bukkit.entity.Entity::remove);
                    }
                    var beforeCancelled =
                            world.getEntitiesByClass(org.bukkit.entity.Item.class).stream()
                                    .map(org.bukkit.entity.Entity::getUniqueId)
                                    .collect(java.util.stream.Collectors.toSet());
                    org.bukkit.event.Listener blocker = new org.bukkit.event.Listener() {};
                    Bukkit.getPluginManager()
                            .registerEvent(
                                    org.bukkit.event.entity.ItemSpawnEvent.class,
                                    blocker,
                                    org.bukkit.event.EventPriority.HIGHEST,
                                    (listener, event) -> {
                                        var spawn = (org.bukkit.event.entity.ItemSpawnEvent) event;
                                        if (full.getUniqueId().equals(spawn.getEntity().getOwner()))
                                            spawn.setCancelled(true);
                                    },
                                    plugin);
                    batches.give(full, GiveRequest.parse("copyprobe:item 2 stack"))
                            .whenComplete(
                                    (count, error) -> {
                                        HandlerList.unregisterAll(blocker);
                                        try {
                                            check(
                                                    error != null
                                                            && error.getMessage()
                                                                    .contains("已确认发放 0 件"),
                                                    "cancelled ItemSpawnEvent is reported as failed delivery");
                                            check(
                                                    world
                                                            .getEntitiesByClass(
                                                                    org.bukkit.entity.Item.class)
                                                            .stream()
                                                            .noneMatch(
                                                                    item ->
                                                                            !beforeCancelled
                                                                                            .contains(
                                                                                                    item
                                                                                                            .getUniqueId())
                                                                                    && full.getUniqueId()
                                                                                            .equals(
                                                                                                    item
                                                                                                            .getOwner())),
                                                    "cancelled overflow does not leave phantom entities");
                                            cancellationAndThreading();
                                        } catch (Throwable failure) {
                                            finish(failure);
                                        }
                                    });
                });
    }

    private void cancellationAndThreading() {
        CompletionStage<Integer> pending =
                batches.give(player, GiveRequest.parse("apiprobe:stone 5 roll"));
        rejects(
                () -> batches.give(player, GiveRequest.parse("apiprobe:stone")),
                "duplicate pending request for target rejected");
        items.reload(Bukkit.getConsoleSender());
        pending.whenComplete(
                (count, failure) -> {
                    try {
                        check(
                                failure != null
                                        && java.util.Arrays.stream(
                                                                player.getInventory().getContents())
                                                        .filter(
                                                                item ->
                                                                        item != null
                                                                                && item.getType()
                                                                                        == Material
                                                                                                .STONE)
                                                        .mapToInt(ItemStack::getAmount)
                                                        .sum()
                                                == 133,
                                "catalog change cancels pending batch before delivery");
                        Bukkit.getScheduler()
                                .runTaskAsynchronously(
                                        plugin,
                                        () -> {
                                            boolean rejected;
                                            try {
                                                items.create("apiprobe:stone", player, Map.of());
                                                rejected = false;
                                            } catch (IllegalStateException expected) {
                                                rejected = true;
                                            }
                                            boolean result = rejected;
                                            Bukkit.getScheduler()
                                                    .runTask(
                                                            plugin,
                                                            () -> {
                                                                try {
                                                                    check(
                                                                            result,
                                                                            "worker-thread generation rejected");
                                                                    closeDuringSpawn();
                                                                } catch (Throwable error) {
                                                                    finish(error);
                                                                }
                                                            });
                                        });
                    } catch (Throwable error) {
                        finish(error);
                    }
                });
    }

    private void closeDuringSpawn() {
        Player full = ProbePlayer.create("ClosingProviderAdmin_" + java.util.UUID.randomUUID());
        for (int slot = 0; slot < 36; slot++)
            full.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        org.bukkit.event.Listener stopper = new org.bukkit.event.Listener() {};
        Bukkit.getPluginManager()
                .registerEvent(
                        org.bukkit.event.entity.ItemSpawnEvent.class,
                        stopper,
                        org.bukkit.event.EventPriority.HIGHEST,
                        (listener, event) -> {
                            if (full.getUniqueId()
                                    .equals(
                                            ((org.bukkit.event.entity.ItemSpawnEvent) event)
                                                    .getEntity()
                                                    .getOwner())) batches.close();
                        },
                        plugin);
        batches.give(full, GiveRequest.parse("copyprobe:item 130 stack"))
                .whenComplete(
                        (count, error) -> {
                            HandlerList.unregisterAll(stopper);
                            var drops =
                                    full
                                            .getWorld()
                                            .getEntitiesByClass(org.bukkit.entity.Item.class)
                                            .stream()
                                            .filter(
                                                    item ->
                                                            full.getUniqueId()
                                                                    .equals(item.getOwner()))
                                            .toList();
                            try {
                                check(
                                        error != null && error.getMessage().contains("已确认发放 64 件"),
                                        "reentrant close reports the one completed insertion");
                                check(
                                        drops.stream()
                                                        .mapToInt(
                                                                item ->
                                                                        item.getItemStack()
                                                                                .getAmount())
                                                        .sum()
                                                == 64,
                                        "reentrant close stops remaining stacks without replay");
                                finish(null);
                            } catch (Throwable failure) {
                                finish(failure);
                            } finally {
                                drops.forEach(org.bukkit.entity.Entity::remove);
                            }
                        });
    }

    private void command(String... args) {
        try {
            // Paper's command wrapper requires CraftPlayer. Exercise the actual registered
            // Keystone tree with our synthetic inventory; console dispatch is tested separately.
            var registry =
                    plugin.getClass()
                            .getClassLoader()
                            .loadClass("dev.itemloom.internal.keystone.command.CommandRegistry");
            var field = registry.getDeclaredField("roots");
            field.setAccessible(true);
            Object root = ((List<?>) field.get(null)).getFirst();
            root.getClass()
                    .getMethod(
                            "dispatch",
                            org.bukkit.command.CommandSender.class,
                            String.class,
                            String[].class)
                    .invoke(root, player, "il", args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private <T> void after(CompletionStage<T> stage, java.util.function.Consumer<T> next) {
        stage.whenComplete(
                (value, error) -> {
                    if (error != null) finish(error);
                    else
                        try {
                            next.accept(value);
                        } catch (Throwable failure) {
                            finish(failure);
                        }
                });
    }

    private void rejects(Runnable action, String label) {
        boolean rejected = false;
        try {
            action.run();
        } catch (RuntimeException expected) {
            rejected = true;
        }
        check(rejected, label);
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks.add(label);
    }

    private void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        batches.close();
        registrations.forEach(ProviderRegistration::close);
        HandlerList.unregisterAll(this);
        try {
            if (conflict != null) Files.deleteIfExists(conflict);
            if (saved != null) Files.deleteIfExists(saved);
            items.reload(Bukkit.getConsoleSender());
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("passed", failure == null);
        result.put("checks", checks);
        result.put(
                "limits",
                "Actual Paper scheduler and registered Keystone command tree, bypassing Paper CraftPlayer command source adapter; synthetic player/inventory and owner-disable event. No connected client.");
        if (failure != null) {
            result.put("failure", failure.toString());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Provider/admin probe", failure);
        }
        report.complete(result);
    }
}

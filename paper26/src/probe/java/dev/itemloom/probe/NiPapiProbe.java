package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.integration.NiPapiExpansion;
import dev.itemloom.paper.integration.PapiBridge;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual PAPI routing and real scheduler; no client connection and no waiting on the server thread. */
final class NiPapiProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        var checks = new ArrayList<String>();
        Map<String, Object> failures = new LinkedHashMap<>();
        var owner =
                PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion("ni");
        if (!(owner instanceof NiPapiExpansion live))
            return CompletableFuture.completedFuture(
                    Map.of("passed", false, "error", "IL ni expansion is not the current owner"));
        var service = (ItemsService) Bukkit.getServicesManager().load(ItemLoom.class);
        var viewer = ProbePlayer.create("PapiAsyncProbe");
        var otherViewer = ProbePlayer.create("PapiOtherViewer");
        AtomicInteger calls = new AtomicInteger();
        var revision = new AtomicReference<Object>(new Object());
        var counter =
                new NiPapiExpansion(
                        plugin,
                        revision::get,
                        (player, params) -> {
                            if (!Bukkit.isPrimaryThread())
                                throw new AssertionError("resolver called on worker");
                            return player.getName() + ":" + params + ":" + calls.incrementAndGet();
                        });
        Map<Integer, Integer> perTick = new LinkedHashMap<>();
        var bounded =
                new NiPapiExpansion(
                        plugin,
                        revision::get,
                        (player, params) -> {
                            if (!Bukkit.isPrimaryThread())
                                throw new AssertionError("bounded resolver called on worker");
                            perTick.merge(Bukkit.getCurrentTick(), 1, Integer::sum);
                            return params;
                        });
        java.util.function.BiConsumer<Boolean, String> check =
                (valid, name) -> {
                    if (valid) checks.add(name);
                    else failures.put(name, "assertion failed");
                };
        check.accept(
                "<papi::ni_amount_fixture>".equals(PapiBridge.itemSections("%ni_amount_fixture%")),
                "startup registers ni before registered-placeholder conversion");
        check.accept(
                "3".equals(PlaceholderAPI.setPlaceholders(viewer, "%ni_amount_fixture%")),
                "main-thread real PAPI dispatch reads current inventory");
        check.accept(
                !counter.registerIfAvailable(),
                "existing ni owner cannot be displaced by IL registration");
        check.accept(
                owner
                        == PlaceholderAPIPlugin.getInstance()
                                .getLocalExpansionManager()
                                .getExpansion("ni"),
                "failed register preserves namespace owner");
        CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        worker(
                        () -> {
                            check.accept(
                                    ""
                                            .equals(
                                                    PlaceholderAPI.setPlaceholders(
                                                            viewer, "%ni_amount_fixture%")),
                                    "first async actual-PAPI request is empty");
                            check.accept(
                                    ""
                                            .equals(
                                                    PlaceholderAPI.setPlaceholders(
                                                            (OfflinePlayer) null,
                                                            "%ni_parse_plain%")),
                                    "null-viewer first async request is empty");
                            check.accept(
                                    counter.onRequest(viewer, "value").isEmpty(),
                                    "first async script request is empty");
                            for (int i = 0; i < 100; i++) counter.onRequest(viewer, "value");
                            return null;
                        })
                .thenCompose(
                        ignored ->
                                later(
                                        plugin,
                                        3,
                                        () -> {
                                            check.accept(
                                                    calls.get() == 1,
                                                    "100 worker requests produce one main-thread evaluation");
                                            viewer.getInventory().getItemInMainHand().setAmount(7);
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                worker(
                                        () -> {
                                            check.accept(
                                                    "3"
                                                            .equals(
                                                                    PlaceholderAPI.setPlaceholders(
                                                                            viewer,
                                                                            "%ni_amount_fixture%")),
                                                    "async reads prior main-thread snapshot after inventory changes");
                                            check.accept(
                                                    "plain"
                                                            .equals(
                                                                    PlaceholderAPI.setPlaceholders(
                                                                            (OfflinePlayer) null,
                                                                            "%ni_parse_plain%")),
                                                    "null-viewer parsing refreshes without a player");
                                            check.accept(
                                                    counter.onRequest(viewer, "value")
                                                            .equals("PapiAsyncProbe:value:1"),
                                                    "worker receives computed value and schedules refresh");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                later(
                                        plugin,
                                        3,
                                        () -> {
                                            check.accept(
                                                    calls.get() == 2,
                                                    "refresh remains demand-driven and coalesced");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                worker(
                                        () -> {
                                            check.accept(
                                                    "7"
                                                            .equals(
                                                                    PlaceholderAPI.setPlaceholders(
                                                                            viewer,
                                                                            "%ni_amount_fixture%")),
                                                    "later async request observes main-thread refreshed inventory");
                                            revision.set(new Object());
                                            check.accept(
                                                    counter.onRequest(viewer, "value").isEmpty(),
                                                    "revision change discards cached values immediately");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                later(
                                        plugin,
                                        3,
                                        () -> {
                                            Object previous = service.placeholderRevision();
                                            // Obtain the actual existing manager without changing
                                            // config files.
                                            var method =
                                                    ItemsService.class.getDeclaredMethod(
                                                            "activeNi");
                                            method.setAccessible(true);
                                            var catalog =
                                                    (dev.itemloom.paper.compat.NiCatalog)
                                                            method.invoke(service);
                                            var manager = new LegacyItemManager(catalog.items());
                                            manager.reloadItemConfigs();
                                            check.accept(
                                                    previous == service.placeholderRevision(),
                                                    "source-only reload retains display result revision");
                                            manager.reload();
                                            check.accept(
                                                    previous != service.placeholderRevision(),
                                                    "local generator reload invalidates cached results within same owner revision");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                worker(
                                        () -> {
                                            check.accept(
                                                    ""
                                                            .equals(
                                                                    PlaceholderAPI.setPlaceholders(
                                                                            viewer,
                                                                            "%ni_amount_fixture%")),
                                                    "real local reload makes async PAPI cold again");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                later(
                                        plugin,
                                        3,
                                        () -> {
                                            counter.quit(
                                                    new PlayerQuitEvent(
                                                            viewer,
                                                            (net.kyori.adventure.text.Component)
                                                                    null));
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                worker(
                                        () -> {
                                            check.accept(
                                                    counter.onRequest(viewer, "value").isEmpty(),
                                                    "quit clears previous session results");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                later(
                                        plugin,
                                        3,
                                        () -> {
                                            counter.close();
                                            check.accept(
                                                    owner
                                                            == PlaceholderAPIPlugin.getInstance()
                                                                    .getLocalExpansionManager()
                                                                    .getExpansion("ni"),
                                                    "closing unregistered expansion preserves actual owner");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                worker(
                                        () -> {
                                            check.accept(
                                                    counter.onRequest(viewer, "value").isEmpty(),
                                                    "closed expansion rejects async requests");
                                            for (int i = 0; i < 3000; i++)
                                                bounded.onRequest(viewer, "bounded-" + i);
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                later(
                                        plugin,
                                        48,
                                        () -> {
                                            var field =
                                                    NiPapiExpansion.class.getDeclaredField(
                                                            "results");
                                            field.setAccessible(true);
                                            Map<?, ?> retained = (Map<?, ?>) field.get(bounded);
                                            check.accept(
                                                    retained.size() <= 2048,
                                                    "3000 distinct requests retain at most 2048 result slots");
                                            check.accept(
                                                    perTick.size() > 1
                                                            && perTick.values().stream()
                                                                    .allMatch(count -> count <= 64),
                                                    "large request batches evaluate at most 64 entries per server tick");
                                            return null;
                                        }))
                .thenCompose(
                        ignored ->
                                worker(
                                        () -> {
                                            check.accept(
                                                    "bounded-2999"
                                                            .equals(
                                                                    bounded.onRequest(
                                                                            viewer,
                                                                            "bounded-2999")),
                                                    "latest admitted request eventually computes despite cache overflow");
                                            check.accept(
                                                    bounded.onRequest(otherViewer, "bounded-2999")
                                                            .isEmpty(),
                                                    "different viewers do not share cached results");
                                            return null;
                                        }))
                .whenComplete(
                        (ignored, failure) ->
                                Bukkit.getScheduler()
                                        .runTask(
                                                plugin,
                                                () -> {
                                                    if (failure != null)
                                                        failures.put(
                                                                "async-chain", failure.toString());
                                                    try {
                                                        counter.close();
                                                        bounded.close();
                                                        PlaceholderExpansion replacement =
                                                                new PlaceholderExpansion() {
                                                                    public String getIdentifier() {
                                                                        return "ni";
                                                                    }

                                                                    public String getAuthor() {
                                                                        return "probe";
                                                                    }

                                                                    public String getVersion() {
                                                                        return "1";
                                                                    }

                                                                    public String onRequest(
                                                                            OfflinePlayer player,
                                                                            String parameters) {
                                                                        return "replacement";
                                                                    }
                                                                };
                                                        // Sandbox only: prove PAPI's real
                                                        // replacement semantics and conditional
                                                        // cleanup.
                                                        live.unregister();
                                                        try (var displaced =
                                                                new NiPapiExpansion(
                                                                        plugin,
                                                                        revision::get,
                                                                        (player, parameters) ->
                                                                                "displaced")) {
                                                            check.accept(
                                                                    displaced.registerIfAvailable(),
                                                                    "unclaimed namespace registers successfully");
                                                            check.accept(
                                                                    replacement.register(),
                                                                    "probe replacement uses actual PAPI replacement API");
                                                            displaced.close();
                                                            check.accept(
                                                                    "replacement"
                                                                            .equals(
                                                                                    PlaceholderAPI
                                                                                            .setPlaceholders(
                                                                                                    viewer,
                                                                                                    "%ni_any%")),
                                                                    "cleanup never unregisters a later namespace owner");
                                                        } finally {
                                                            if (PlaceholderAPIPlugin.getInstance()
                                                                            .getLocalExpansionManager()
                                                                            .getExpansion("ni")
                                                                    == replacement)
                                                                replacement.unregister();
                                                            check.accept(
                                                                    live.register(),
                                                                    "original live owner restored after isolated namespace test");
                                                        }
                                                    } catch (Throwable error) {
                                                        failures.put("cleanup", error.toString());
                                                    }
                                                    report.complete(
                                                            Map.of(
                                                                    "passed",
                                                                    failures.isEmpty(),
                                                                    "checks",
                                                                    checks.size() + failures.size(),
                                                                    "assertions",
                                                                    checks,
                                                                    "failures",
                                                                    failures,
                                                                    "realClient",
                                                                    false,
                                                                    "papiVersion",
                                                                    PlaceholderAPIPlugin
                                                                            .getInstance()
                                                                            .getPluginMeta()
                                                                            .getVersion(),
                                                                    "batchEvaluationsByTick",
                                                                    perTick));
                                                }));
        return report;
    }

    private static <T> CompletableFuture<T> worker(Supplier<T> call) {
        // Startup catch-up can run several server ticks within 50 ms. Advance wall time for
        // the refresh deadline without ever parking the server thread.
        return CompletableFuture.supplyAsync(
                call,
                CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static <T> CompletableFuture<T> later(
            JavaPlugin plugin, long ticks, CheckedSupplier<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            try {
                                future.complete(call.get());
                            } catch (Throwable error) {
                                future.completeExceptionally(error);
                            }
                        },
                        ticks);
        return future;
    }
}

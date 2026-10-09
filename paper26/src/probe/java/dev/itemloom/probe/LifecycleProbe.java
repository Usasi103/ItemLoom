package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.component.DataComponents;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.compat.script.LegacyScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Private candidate revisions exercise source/enable order and rollback without changing server configuration. */
final class LifecycleProbe {
    private static final String ITEMS =
            """
            plain:
              material: STONE
            custom:
              material: STONE
              name: '<roll>|<script>'
              lore: ['<lifecycle-node::inline>']
              sections:
                roll: {type: lifecycle-node}
                script: {type: js, path: 'helper.js::helper'}
              event:
                post-generate:
                  actions: ['lifecycle-action: post', 'func: lifecycle-function']
            """;
    private static final String HELPER =
            """
            var bridge = plugin.getConfig().get('__KEY__');
            bridge.get('calls').add('__TOKEN__:script-top');
            bridge.put('__TOKEN__:source-created', ItemManager.getItemStack('plain'));
            function helper() { return 'js-ready'; }
            """;
    private static final String EXPANSION =
            """
            var bridge = plugin.getConfig().get('__KEY__');
            var token = '__TOKEN__';
            var calls = bridge.get('calls');
            var Scheduler = Java.type('pers.neige.neigeitems.utils.SchedulerUtils');
            var Sections = Java.type('pers.neige.neigeitems.manager.SectionManager').INSTANCE;
            var CustomSection = Java.type('pers.neige.neigeitems.section.impl.CustomSection');
            calls.add(token + ':expansion-top');
            bridge.put(token + ':top-created', ItemManager.getItemStack('plain'));
            bridge.put(token + ':manager', ItemManager);
            bridge.put(token + ':scheduler', Scheduler);
            Scheduler.syncTimer(2, 2, function() { calls.add(token + ':top-tick'); });
            function enable() {
                calls.add(token + ':enable');
                Sections.loadParser(new CustomSection('lifecycle-node',
                    function(config, cache, player, sections) { return token + '-node'; },
                    function(args, cache, player, sections) { return token + '-' + args.get(0); }));
                ActionManager.addConsumer('lifecycle-action', false, function(context, text) {
                    calls.add(token + ':action:' + text);
                });
                bridge.put(token + ':enable-created', ItemManager.getItemStack('custom', bridge.get('player')));
                var steps = new (Java.type('java.util.ArrayList'))();
                steps.add('delay: 40');
                steps.add('lifecycle-action: forbidden-late');
                bridge.put(token + ':pending', ActionManager.runActionWithResult(ActionManager.compile(steps)));
                Scheduler.syncTimer(2, 2, function() { calls.add(token + ':enable-tick'); });
                if (__FAIL__) throw new Error('probe-owned enable failure');
            }
            function disable() { calls.add(token + ':disable'); }
            """;

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Lifecycle probe requires the server thread");
        Runner runner = new Runner(plugin);
        runner.start();
        return runner.result;
    }

    private static final class Runner {
        final JavaPlugin plugin;
        final Player player = ProbePlayer.create("LifecycleProbe");
        final PlayerActionState players = new PlayerActionState();
        final String bridgeKey = "lifecycle-probe-" + UUID.randomUUID();
        final Map<String, Object> bridge = new ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> verified = new ArrayList<>();
        final List<BukkitTask> control = new ArrayList<>();
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        final Object priorBridge;
        NiCatalog previous;
        boolean finished;

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
            priorBridge = plugin.getConfig().get(bridgeKey);
            bridge.put("calls", calls);
            bridge.put("player", player);
            plugin.getConfig().set(bridgeKey, bridge);
            players.join(player.getUniqueId());
        }

        void start() {
            try {
                previous = create(1, "old", "none");
                check(
                        count("old:script-top") == 1
                                && count("old:expansion-top") == 1
                                && count("old:enable") == 1,
                        "source bodies and enable run exactly once");
                check(
                        calls.indexOf("old:script-top") < calls.indexOf("old:expansion-top")
                                && calls.indexOf("old:expansion-top") < calls.indexOf("old:enable"),
                        "all source evaluation finishes before enable");
                check(
                        item("old:source-created").getType() == Material.STONE
                                && item("old:top-created").getType() == Material.STONE,
                        "ordinary script and expansion top-level code can generate from the prepared catalog");
                ItemStack enabledItem = item("old:enable-created");
                check(
                        name(enabledItem).equals("old-node|js-ready"),
                        "enable registers a custom node and immediately generates an item using it and a loaded JS script");
                var lore = CraftItemStack.asNMSCopy(enabledItem).get(DataComponents.LORE);
                check(
                        lore != null && lore.lines().getFirst().getString().equals("old-inline"),
                        "inline custom nodes registered by enable are available immediately");
                check(
                        count("old:action:post") == 1 && count("old:action:function") == 1,
                        "precompiled post-generation and named functions dispatch to handlers registered by enable");
                check(
                        previous.triggers().hasKey("custom", "right"),
                        "item triggers are prepared before extension activation");
                previous.triggers()
                        .interact(
                                player,
                                enabledItem,
                                new PlayerInteractEvent(
                                        player,
                                        Action.RIGHT_CLICK_AIR,
                                        enabledItem,
                                        null,
                                        BlockFace.SELF,
                                        EquipmentSlot.HAND));
                check(
                        count("old:action:trigger") == 1,
                        "precompiled item triggers dispatch to handlers registered by enable");
                int baselineListeners = registeredListeners();

                rejectedCandidate(2, "bad", "enable");
                check(
                        count("bad:enable") == 1 && count("bad:disable") == 1,
                        "failed enable is followed by cleanup without a second enable");
                check(
                        pending("bad").isDone(),
                        "failed enable closes its pending action continuation");
                check(
                        registeredListeners() == baselineListeners,
                        "failed enable removes only its candidate's listeners");
                rejectManager(
                        "bad", "a retained manager from a failed enable cannot generate items");
                ((LegacyScheduler) bridge.get("bad:scheduler"))
                        .syncLater(1, () -> calls.add("bad:after-close"));

                rejectedCandidate(3, "source-bad", "source");
                check(
                        count("source-bad:expansion-top") == 1
                                && count("source-bad:enable") == 0
                                && count("source-bad:disable") == 1,
                        "source failure cleans up evaluated expansions without invoking enable");
                check(
                        registeredListeners() == baselineListeners,
                        "source failure removes its candidate's listeners");
                rejectManager(
                        "source-bad",
                        "a retained manager from failed source loading cannot generate items");

                rejectedCandidate(4, "compile-bad", "compile");
                check(
                        count("compile-bad:script-top") == 0 && count("compile-bad:enable") == 0,
                        "invalid action expressions fail preparation before user source bodies execute");
                check(
                        registeredListeners() == baselineListeners,
                        "preparation failure removes its candidate's listeners");
                check(
                        previous.active()
                                && name(previous.items()
                                                .create("custom", player, new LinkedHashMap<>()))
                                        .equals("old-node|js-ready"),
                        "failed candidates leave the previous revision and its registrations usable");
                later(8, () -> afterFailures(baselineListeners));
            } catch (Throwable error) {
                finish(error);
            }
        }

        void afterFailures(int baselineListeners) {
            check(
                    count("old:top-tick") > 0 && count("old:enable-tick") > 0,
                    "previous revision timers continue after candidate failure");
            check(
                    count("bad:top-tick") == 0
                            && count("bad:enable-tick") == 0
                            && count("bad:after-close") == 0,
                    "failed enable cancels timers and refuses later submissions through retained adapters");
            check(
                    count("source-bad:top-tick") == 0,
                    "failed source loading cancels tasks scheduled by already evaluated top-level code");
            check(
                    registeredListeners() == baselineListeners,
                    "candidate rollback leaves the previous listener set intact");
            previous.close();
            check(
                    count("old:disable") == 1 && pending("old").isDone(),
                    "closing the surviving revision disables it once and completes its pending continuation");
            long ticks = count("old:top-tick") + count("old:enable-tick");
            later(
                    4,
                    () -> {
                        check(
                                count("old:top-tick") + count("old:enable-tick") == ticks,
                                "closed revision timers stop producing effects");
                        check(
                                calls.stream()
                                        .noneMatch(
                                                value -> value.endsWith(":action:forbidden-late")),
                                "closed revisions do not execute their delayed action bodies");
                        finish(null);
                    });
        }

        NiCatalog create(long revision, String token, String failure) {
            Map<String, NiRepository.Definition> definitions = new LinkedHashMap<>();
            NiConfig source = NiYaml.read(ITEMS, "memory/Items/lifecycle.yml");
            source.keys()
                    .forEach(
                            id ->
                                    definitions.put(
                                            id,
                                            new NiRepository.Definition(
                                                    id,
                                                    "memory/Items/lifecycle.yml",
                                                    source.section(id))));
            Map<String, String> expansions = new LinkedHashMap<>();
            expansions.put(
                    "lifecycle.js",
                    script(EXPANSION, token)
                            .replace("__FAIL__", Boolean.toString(failure.equals("enable"))));
            if (failure.equals("source"))
                expansions.put("later-error.js", "throw new Error('probe-owned source failure');");
            Map<String, Object> functions =
                    failure.equals("compile")
                            ? Map.of("invalid", "js: function(")
                            : Map.of("lifecycle-function", "lifecycle-action: function");
            Map<String, Object> triggers =
                    Map.of(
                            "custom",
                            Map.of(
                                    "right",
                                    Map.of("cooldown", 0, "sync", "lifecycle-action: trigger")));
            NiRepository.Input input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            definitions,
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            triggers,
                            functions,
                            Map.of("helper.js", script(HELPER, token)),
                            expansions,
                            Map.of());
            return new NiCatalog(revision, input, plugin, (viewer, text) -> null, players);
        }

        String script(String source, String token) {
            return source.replace("__KEY__", bridgeKey).replace("__TOKEN__", token);
        }

        ItemStack item(String key) {
            return (ItemStack) bridge.get(key);
        }

        CompletableFuture<?> pending(String token) {
            return (CompletableFuture<?>) bridge.get(token + ":pending");
        }

        long count(String value) {
            return calls.stream().filter(value::equals).count();
        }

        int registeredListeners() {
            return HandlerList.getRegisteredListeners(plugin).size();
        }

        static String name(ItemStack item) {
            var component = CraftItemStack.asNMSCopy(item).get(DataComponents.CUSTOM_NAME);
            return component == null ? "" : component.getString();
        }

        void rejectManager(String token, String description) {
            rejected(
                    description,
                    () ->
                            ((LegacyItemManager) bridge.get(token + ":manager"))
                                    .getItemStack("plain"));
        }

        void rejectedCandidate(long revision, String token, String failure) {
            NiCatalog unexpected = null;
            boolean rejected = false;
            try {
                unexpected = create(revision, token, failure);
            } catch (RuntimeException expected) {
                rejected = true;
            } finally {
                if (unexpected != null) unexpected.close();
            }
            check(rejected, failure + " failure rejects the candidate revision");
        }

        void rejected(String description, Runnable body) {
            boolean rejected = false;
            try {
                body.run();
            } catch (RuntimeException expected) {
                rejected = true;
            }
            check(rejected, description);
        }

        void check(boolean condition, String description) {
            if (!condition) throw new AssertionError(description);
            verified.add(description);
        }

        void later(long ticks, Runnable body) {
            control.add(
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> {
                                        if (finished) return;
                                        try {
                                            body.run();
                                        } catch (Throwable error) {
                                            finish(error);
                                        }
                                    },
                                    ticks));
        }

        void finish(Throwable error) {
            if (finished) return;
            finished = true;
            for (BukkitTask task : control) task.cancel();
            try {
                if (previous != null) previous.close();
            } catch (Throwable cleanup) {
                if (error == null) error = cleanup;
                else error.addSuppressed(cleanup);
            }
            players.close();
            plugin.getConfig().set(bridgeKey, priorBridge);
            if (error != null) result.completeExceptionally(error);
            else
                result.complete(
                        Map.of(
                                "passed",
                                true,
                                "checks",
                                verified.size(),
                                "verified",
                                List.copyOf(verified),
                                "referenceRequired",
                                false,
                                "realClient",
                                false,
                                "revisionRollback",
                                "private catalogs; failed candidates are never installed",
                                "listenerScope",
                                "revision-owned runtime listeners; direct third-party Bukkit registrations are outside this probe"));
        }
    }
}

package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiPaperRecipe;
import dev.itemloom.paper.compat.script.LegacyItemGenerateEvent;
import dev.itemloom.paper.compat.script.LegacyItemGenerator;
import dev.itemloom.paper.compat.script.LegacyItemManager;
import dev.itemloom.paper.compat.script.LegacyItemUpdateEvent;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Real Bukkit dispatch and script adapters, using in-memory definitions and a synthetic player. */
final class ItemGenerationEventProbe {
    private static final String YAML =
            """
            generated:
              material: STONE
              name: '<roll>'
              lore: ['<roll>']
              extra: {nested: [first, second]}
              static: {unbreakable: true}
              client_bound_data: {name: unused-client-name}
              options: {id-section: item_id, update: {enable: true}}
              sections:
                roll: '<papi::count>'
                unused: {type: strings, values: [first, second]}
              event:
                post-generate:
                  actions: >-
                    js: data.put('post_seen', String(data.get('listener')));
                    data.put('post_type', String(itemStack.getType()));
            empty:
              material: STONE
              static: {material: AIR}
              event:
                post-generate:
                  actions: "js: data.put('post_type', String(itemStack.getType()));"
            plain:
              material: STONE
            invalid:
              material: UNKNOWN_GENERATION_PROBE_MATERIAL
            """;
    private static final String SCRIPT =
            """
            function aliases(event, player, cache) {
                var Event = Java.type('pers.neige.neigeitems.event.ItemGenerateEvent');
                var Reader = Java.type('pers.neige.neigeitems.config.ConfigReader');
                var config = Reader.parse('material: STONE');
                var stack = event.getItemStack();
                var first = new Event('constructed', player, stack, cache, config, null);
                var second = new Packages.pers.neige.neigeitems.event.ItemGenerateEvent('constructed', player, stack, cache, config, null);
                return event instanceof Event && first instanceof Event && second instanceof Event
                    && Event.class == event.getClass()
                    && first.getItemStack() == stack && second.getCache() == cache
                    && first.getConfigSection() == config && first.getSections() == null
                    && first.getPlayer() == player && first.getEventName() == 'ItemGenerateEvent'
                    && first.getHandlers() == event.getHandlers() && !first.isAsynchronous();
            }
            """;

    static Map<String, Object> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Generation event probe requires the server thread");
        Checks checks = new Checks();
        checks.group(
                "constructor",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        constructor(checks, f);
                    }
                });
        checks.group(
                "generation",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        generation(checks, f);
                    }
                });
        checks.group(
                "mutable-generated-config",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        detached(checks, f);
                    }
                });
        checks.group(
                "shared-sections",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        sharedSections(checks, f);
                    }
                });
        checks.group(
                "concurrent-first-sections-read",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        concurrentSections(checks, f);
                    }
                });
        checks.group(
                "retained-sections-after-close",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        f.manager.getItemStack(
                                "generated",
                                f.player,
                                new LinkedHashMap<>(Map.of("roll", "saved")));
                        LegacyItemGenerateEvent event = f.legacy;
                        f.catalog.close();
                        ConfigurationSection sections =
                                CompletableFuture.supplyAsync(event::getSections)
                                        .orTimeout(5, TimeUnit.SECONDS)
                                        .join();
                        checks.that(
                                sections != null
                                        && sections == event.getSections()
                                        && "<papi::count>".equals(sections.getString("roll")),
                                "retained events can lazily read their configuration after the generating revision closes");
                        checks.reject(
                                () -> f.manager.getItemStack("generated", f.player),
                                "retained configuration access does not reopen a closed revision");
                    }
                });
        checks.group(
                "original-air-path",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        ItemStack replacement = new ItemStack(Material.DIAMOND, 7);
                        f.effect = event -> event.setItemStack(replacement);
                        Map<String, String> cache = new LinkedHashMap<>();
                        ItemStack result = f.manager.getItemStack("empty", f.player, cache);
                        checks.that(
                                result == replacement && result.getAmount() == 7,
                                "AIR generation preserves a listener replacement and its amount");
                        checks.that(
                                !cache.containsKey("post_type"),
                                "original AIR path skips post-generation even after replacement with a nonempty item");
                    }
                });
        checks.group(
                "replacement-air-path",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        f.effect = event -> event.setItemStack(new ItemStack(Material.AIR));
                        Map<String, String> cache = new LinkedHashMap<>();
                        ItemStack result = f.manager.getItemStack("generated", f.player, cache);
                        checks.that(
                                result.isEmpty(),
                                "ordinary generation returns the listener's AIR replacement");
                        checks.that(
                                "AIR".equals(cache.get("post_type")),
                                "ordinary generation still executes post-generation after replacement with AIR");
                    }
                });
        checks.group(
                "vanilla-replacement",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        ItemStack replacement = new ItemStack(Material.DIAMOND, 7);
                        f.effect = event -> event.setItemStack(replacement);
                        Map<String, String> cache = new LinkedHashMap<>();
                        ItemStack result = f.manager.getItemStack("generated", f.player, cache);
                        checks.that(
                                result == replacement
                                        && result.getAmount() == 1
                                        && "DIAMOND".equals(cache.get("post_type")),
                                "ordinary generation executes post-generation on the vanilla listener replacement");
                        checks.that(
                                !CraftItemStack.asNMSCopy(result).has(DataComponents.CUSTOM_DATA),
                                "post-generation does not add empty custom_data to a vanilla replacement");
                    }
                });
        checks.group(
                "missing-sections-invalid-material",
                () -> {
                    try (Fixture f = new Fixture(plugin)) {
                        f.manager.getItemStack("plain", f.player);
                        checks.that(f.legacy.getSections() == null, "absent sections remain null");
                        int before = f.legacyCount;
                        checks.that(
                                f.manager.getItemStack("invalid", f.player) == null
                                        && f.legacyCount == before,
                                "invalid material returns no item and fires no generation event");
                    }
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

    private static void constructor(Checks checks, Fixture f) {
        ItemStack item = new ItemStack(Material.STONE);
        Map<String, String> cache = new LinkedHashMap<>();
        LegacyConfigReader config = LegacyConfigReader.parse(Map.of("material", "STONE"));
        YamlConfiguration sections = new YamlConfiguration();
        var event =
                new LegacyItemGenerateEvent("constructed", f.player, item, cache, config, sections);
        checks.that(
                event.getItem() == item
                        && event.getItemStack() == item
                        && event.getPlayer() == f.player
                        && event.getViewer() == f.player,
                "legacy and independent getters share player and item references");
        checks.that(
                event.getCache() == cache
                        && event.getSavedRolls() == cache
                        && event.getConfigSection() == config
                        && event.getSections() == sections,
                "explicit constructor retains supplied cache and configuration references");
        checks.that(
                !event.isAsynchronous() && "ItemGenerateEvent".equals(event.getEventName()),
                "main-thread construction retains the old event name and sync flag");
        checks.that(
                event.getHandlers() == ItemGenerateEvent.getHandlerList()
                        && LegacyItemGenerateEvent.getHandlerList()
                                == ItemGenerateEvent.getHandlerList(),
                "both event types use the same HandlerList");
        checks.that(
                event.call()
                        && f.apiCount == 1
                        && f.legacyCount == 1
                        && f.monitorCount == 1
                        && f.api == event
                        && f.legacy == event
                        && f.monitor == event,
                "call dispatches one shared event to old and independent listeners");
        var replacement = new ItemStack(Material.GOLD_INGOT);
        event.setItemStack(replacement);
        checks.that(event.getItem() == replacement, "legacy setter updates the independent result");
        event.setItem(item);
        checks.that(event.getItemStack() == item, "independent setter updates the legacy result");
        checks.reject(() -> event.setItemStack(null), "legacy setter rejects a null result");
        checks.reject(() -> event.setItem(null), "independent setter rejects a null result");
        var generator = f.manager.getItem("plain");
        boolean[] async =
                CompletableFuture.supplyAsync(
                                () ->
                                        new boolean[] {
                                            new LegacyItemGenerateEvent(
                                                            "worker", f.player, item, cache, config,
                                                            null)
                                                    .isAsynchronous(),
                                            new LegacyItemUpdateEvent.PreGenerate(
                                                            f.player, item, cache, generator)
                                                    .isAsynchronous(),
                                            new LegacyItemUpdateEvent.PostGenerate(
                                                            f.player, item, item)
                                                    .isAsynchronous()
                                        })
                        .join();
        checks.that(
                async[0] && async[1] && async[2],
                "explicit worker-thread generation and update events preserve BasicEvent async flags");
    }

    private static void generation(Checks checks, Fixture f) {
        Map<String, String> cache = new LinkedHashMap<>();
        f.effect =
                event -> {
                    event.getCache().put("listener", "seen");
                    ItemStack replacement = event.getItemStack().clone();
                    replacement.setType(Material.DIAMOND);
                    replacement.setAmount(9);
                    event.setItemStack(replacement);
                };
        ItemStack result = f.manager.getItemStack("generated", f.player, cache);
        checks.that(
                f.apiCount == 1
                        && f.legacyCount == 1
                        && f.monitorCount == 1
                        && f.api == f.legacy
                        && f.monitor == f.legacy,
                "one generation fires one event seen by both API types");
        checks.that(
                f.evaluations == 1 && "roll-1".equals(cache.get("roll")),
                "event metadata does not rerun the side-effecting node");
        checks.that(
                "roll-1".equals(f.legacy.getConfigSection().getString("name"))
                        && f.legacy
                                .getConfigSection()
                                .getStringList("lore")
                                .equals(List.of("roll-1")),
                "event configuration is the exact expanded configuration used for the item");
        checks.that(
                "<papi::count>".equals(f.legacy.getSections().getString("roll")),
                "event sections expose raw node definitions");
        checks.that(
                f.legacy.getConfigSection().get("sections") == null
                        && f.legacy.getConfigSection().get("static") == null
                        && f.legacy.getConfigSection().get("client_bound_data") == null
                        && f.legacy.getConfigSection().get("event") == null
                        && f.legacy.getConfigSection().get("options.update") == null
                        && f.legacy.getConfigSection().get("options.id-section") == null,
                "expanded configuration excludes NI generation-only sections");
        checks.that(
                f.legacy.getCache() == cache && "generated".equals(cache.get("item_id")),
                "event uses the caller cache filled by generation");
        checks.that(
                result == f.legacy.getItemStack()
                        && result == f.monitorItem
                        && result.getType() == Material.DIAMOND
                        && result.getAmount() == 1,
                "listener replacement flows to later listeners, post-generation and the returned item");
        checks.that(
                "seen".equals(cache.get("post_seen")) && "DIAMOND".equals(cache.get("post_type")),
                "post-generation sees cache mutations and the replacement item");
        var identity = new ItemStateCodec().read(result).orElseThrow();
        checks.that(
                "roll-1".equals(identity.rolls().get("roll"))
                        && !identity.rolls().containsKey("listener"),
                "event cache mutations are not silently written back into previously stored item identity");
        var evaluation = f.catalog.actionContext(f.player, Map.of()).evaluation();
        Object aliases =
                evaluation.scoped(
                        () ->
                                evaluation
                                        .scripts()
                                        .invoke(
                                                "generation.js",
                                                "aliases",
                                                Map.of(),
                                                f.legacy,
                                                f.player,
                                                cache));
        checks.that(
                Boolean.TRUE.equals(aliases),
                "old Java.type and direct Packages constructors preserve the complete script event contract");
    }

    @SuppressWarnings("unchecked")
    private static void detached(Checks checks, Fixture f) {
        for (int index = 0; index < 4; index++) {
            f.manager.getItemStack(
                    "generated", f.player, new LinkedHashMap<>(Map.of("roll", "stable")));
            LegacyItemGenerateEvent event = f.legacy;
            checks.that(
                    event.getConfigSection().getStringList("lore").equals(List.of("stable")),
                    "generation " + index + " starts with independent mutable configuration");
            ((List<Object>) event.getConfigSection().get("lore")).clear();
            ((List<Object>) event.getConfigSection().get("extra.nested")).add("listener-added");
            ((Map<String, Object>) event.getConfigSection().getHandle()).put("name", "changed");
            checks.that(
                    event.getConfigSection().getStringList("lore").isEmpty()
                            && event.getConfigSection().getStringList("extra.nested").size() == 3,
                    "generation " + index + " exposes mutable nested lists after cache admission");
        }
        checks.that(
                f.evaluations == 0,
                "supplied rolls prevent repeated node evaluation while metadata remains available");
    }

    @SuppressWarnings("unchecked")
    private static void sharedSections(Checks checks, Fixture f) {
        NiPaperRecipe recipe = (NiPaperRecipe) f.catalog.catalog().recipes().get("generated");
        String sourceBefore = NiYaml.write(f.catalog.input().items().get("generated").config());
        String compiledBefore = NiYaml.write(recipe.definition().definition());
        int hashBefore = recipe.definitionHash();
        Map<String, String> firstCache = new LinkedHashMap<>();
        ItemStack first = f.manager.getItemStack("generated", f.player, firstCache);
        LegacyItemGenerateEvent firstEvent = f.legacy;
        LegacyItemGenerator generator = f.manager.getItem("generated");
        LegacyItemGenerator otherGenerator =
                new LegacyItemGenerator(f.catalog.items(), "generated");
        var sections = firstEvent.getSections();
        var config = generator.getConfigSection();
        checks.that(
                sections == generator.getSections()
                        && sections == otherGenerator.getSections()
                        && sections == config.getConfigurationSection("sections"),
                "event and all generator handles share the recipe's sections object");
        checks.that(
                config == otherGenerator.getConfigSection()
                        && config == recipe.legacyConfigSection(),
                "all generator handles share one mutable configuration view");
        sections.set("roll", "future-roll");
        ((List<Object>) sections.getList("unused.values")).add("third");
        checks.that(
                generator
                        .getSections()
                        .getStringList("unused.values")
                        .equals(List.of("first", "second", "third")),
                "nested section lists are mutable and visible through every handle");
        checks.that(
                "roll-1".equals(firstEvent.getConfigSection().getString("name"))
                        && "roll-1".equals(firstCache.get("roll"))
                        && first.getType() == Material.STONE,
                "changing live sections does not rerender the current item, its expanded configuration or saved rolls");
        config.set("material", "DIAMOND");
        config.set("name", "changed-template");
        config.set("static.unbreakable", false);
        config.set("options.update.enable", false);
        config.set("options.id-section", "changed_id");
        Map<String, String> nextCache = new LinkedHashMap<>();
        ItemStack next = f.manager.getItemStack("generated", f.player, nextCache);
        checks.that(
                "future-roll".equals(f.legacy.getConfigSection().getString("name"))
                        && "future-roll".equals(nextCache.get("roll"))
                        && f.evaluations == 1,
                "later generation resolves the modified sections without repeating the old node");
        checks.that(
                f.legacy.getSections() == sections && firstEvent.getSections() == sections,
                "events from successive generations retain the same live sections reference");
        checks.that(
                next.getType() == Material.STONE
                        && CraftItemStack.asNMSCopy(next).has(DataComponents.UNBREAKABLE)
                        && generator.getUpdate()
                        && "item_id".equals(generator.getIdSection())
                        && "generated".equals(nextCache.get("item_id")),
                "editing the mirror does not recompile the template, static prototype or cached options");
        checks.that(
                hashBefore == generator.getHashCode()
                        && sourceBefore.equals(
                                NiYaml.write(f.catalog.input().items().get("generated").config()))
                        && compiledBefore.equals(NiYaml.write(recipe.definition().definition())),
                "script edits leave the source input, immutable compiled definition and definition hash unchanged");
        config.set("sections", null);
        checks.that(
                generator.getSections() == sections
                        && generator.getConfigSection().getConfigurationSection("sections") == null,
                "replacing the outer sections entry preserves NI's original captured sections reference");
        f.manager.getItemStack("generated", f.player, new LinkedHashMap<>());
        checks.that(
                "future-roll".equals(f.legacy.getConfigSection().getString("name")),
                "generation still uses the captured sections object after its parent entry is removed");
    }

    private static void concurrentSections(Checks checks, Fixture f) {
        f.manager.getItemStack("generated", f.player, new LinkedHashMap<>(Map.of("roll", "saved")));
        LegacyItemGenerateEvent firstEvent = f.legacy;
        f.manager.getItemStack("generated", f.player, new LinkedHashMap<>(Map.of("roll", "saved")));
        LegacyItemGenerateEvent secondEvent = f.legacy;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first =
                    CompletableFuture.supplyAsync(
                            () -> firstView(firstEvent, ready, start), workers);
            var second =
                    CompletableFuture.supplyAsync(
                            () -> firstView(secondEvent, ready, start), workers);
            await(ready);
            start.countDown();
            ConfigurationSection a = first.orTimeout(5, TimeUnit.SECONDS).join();
            ConfigurationSection b = second.orTimeout(5, TimeUnit.SECONDS).join();
            checks.that(
                    a != null && a == b,
                    "concurrent first event getters publish one complete nonnull sections view");
            var generator = f.manager.getItem("generated");
            checks.that(
                    a == generator.getSections()
                            && a
                                    == generator
                                            .getConfigSection()
                                            .getConfigurationSection("sections"),
                    "worker initialization and subsequent main-thread getters share the same view");
            checks.that(
                    "<papi::count>".equals(a.getString("roll"))
                            && a.getStringList("unused.values").equals(List.of("first", "second")),
                    "the published sections tree is fully initialized");
            // Mutation is serialized after both workers have finished; concurrent Bukkit writes are
            // not supported.
            a.set("roll", "after-workers");
            f.manager.getItemStack("generated", f.player, new LinkedHashMap<>());
            checks.that(
                    "after-workers".equals(f.legacy.getConfigSection().getString("name")),
                    "later main-thread generation observes serialized edits to a worker-initialized view");
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private static ConfigurationSection firstView(
            LegacyItemGenerateEvent event, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return event.getSections();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting for probe worker");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Probe worker interrupted", error);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Player player = ProbePlayer.create("ItemGenerationEventProbe");
        final PlayerActionState state = new PlayerActionState();
        final Listener listener = new Listener() {};
        final NiCatalog catalog;
        final LegacyItemManager manager;
        int evaluations, apiCount, legacyCount, monitorCount;
        ItemGenerateEvent api, monitor;
        LegacyItemGenerateEvent legacy;
        ItemStack monitorItem;
        Consumer<LegacyItemGenerateEvent> effect;

        Fixture(JavaPlugin plugin) {
            state.join(player.getUniqueId());
            Map<String, NiRepository.Definition> definitions = new LinkedHashMap<>();
            NiConfig source = NiYaml.read(YAML, "memory/Items/generation.yml");
            source.keys()
                    .forEach(
                            id ->
                                    definitions.put(
                                            id,
                                            new NiRepository.Definition(
                                                    id,
                                                    "memory/Items/generation.yml",
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
                            Map.of("generation.js", SCRIPT),
                            Map.of(),
                            Map.of());
            try {
                catalog =
                        new NiCatalog(
                                1, input, plugin, (viewer, text) -> "roll-" + ++evaluations, state);
            } catch (Throwable error) {
                state.close();
                throw error;
            }
            manager = new LegacyItemManager(catalog.items());
            Bukkit.getPluginManager()
                    .registerEvent(
                            ItemGenerateEvent.class,
                            listener,
                            EventPriority.LOWEST,
                            (ignored, raw) -> {
                                if (!(raw instanceof ItemGenerateEvent event)
                                        || event.getViewer() != player) return;
                                apiCount++;
                                api = event;
                            },
                            plugin);
            Bukkit.getPluginManager()
                    .registerEvent(
                            LegacyItemGenerateEvent.class,
                            listener,
                            EventPriority.NORMAL,
                            (ignored, raw) -> {
                                if (!(raw instanceof LegacyItemGenerateEvent event)
                                        || event.getPlayer() != player) return;
                                legacyCount++;
                                legacy = event;
                                if (effect != null) effect.accept(event);
                            },
                            plugin);
            Bukkit.getPluginManager()
                    .registerEvent(
                            ItemGenerateEvent.class,
                            listener,
                            EventPriority.MONITOR,
                            (ignored, raw) -> {
                                if (!(raw instanceof ItemGenerateEvent event)
                                        || event.getViewer() != player) return;
                                monitorCount++;
                                monitor = event;
                                monitorItem = event.getItem();
                            },
                            plugin);
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

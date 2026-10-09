package dev.itemloom.paper.compat;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import dev.itemloom.compat.ni.NiCompiledItem;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiInheritance;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.script.LegacyScriptBindings;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.core.ItemEngine;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.paper.action.PaperActions;
import dev.itemloom.paper.compat.script.LegacyItemConfig;
import dev.itemloom.paper.compat.script.LegacyItemGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Revision-owned compatibility resources and their compiled independent recipes. */
public final class NiCatalog implements AutoCloseable {
    private final NiScripts scripts;
    private final NiRepository.Input input;
    private final Path inputRoot;
    private final JavaPlugin plugin;
    private final NiEvaluation lifecycle;
    private final Logger logger;
    private final NiItemRegistry registry;
    private final NiPackRegistry packs;
    private final dev.itemloom.paper.action.ItemDrops drops;
    private final dev.itemloom.paper.integration.OptionalItemSources itemSources =
            new dev.itemloom.paper.integration.OptionalItemSources();
    private final PaperActions actions;
    private final NiNodes nodes;
    private final NiEvaluation.Host host;
    private final NiItemOperations items;
    private final dev.itemloom.paper.compat.script.LegacyItemPlaceholder itemPlaceholders;
    private final NiTranslations translations;
    private dev.itemloom.paper.action.ItemTriggers triggers;
    private dev.itemloom.paper.action.ItemMaintenance maintenance;
    private final Map<Object, ActionFlow.Step<NiActionContext>> checks = new LinkedHashMap<>();
    private boolean closed;
    private boolean reloadingItems;

    public NiCatalog(
            long revision,
            NiRepository.Input input,
            JavaPlugin plugin,
            BiFunction<Object, String, String> placeholders,
            dev.itemloom.paper.action.PlayerActionState players) {
        this(revision, input, plugin.getDataFolder().toPath(), plugin, placeholders, players);
    }

    public NiCatalog(
            long revision,
            NiRepository.Input input,
            Path inputRoot,
            JavaPlugin plugin,
            BiFunction<Object, String, String> placeholders,
            dev.itemloom.paper.action.PlayerActionState players) {
        this.input = input;
        this.inputRoot = inputRoot.toAbsolutePath().normalize();
        this.plugin = plugin;
        logger = plugin.getLogger();
        nodes = new NiNodes();
        Map<String, String> sources = new LinkedHashMap<>(input.scripts());
        input.expansions().forEach((path, source) -> sources.put("@expansions/" + path, source));
        LegacyScriptBindings bindings = new LegacyScriptBindings(nodes);
        scripts = new NiScripts(Map.of(), Map.of("plugin", plugin), bindings);
        actions =
                new PaperActions(
                        plugin,
                        scripts,
                        players,
                        input.settings().longValue("ItemAction.comboInterval", 500));
        var actionManager =
                new dev.itemloom.compat.ni.script.LegacyActionManager(
                        actions.compiler(), actions::run);
        bindings.actionManager(actionManager);
        var triggerFactory =
                new dev.itemloom.paper.compat.script.LegacyActionTrigger.Factory(
                        actions, actionManager, scripts::validate);
        items = new NiItemOperations(this);
        itemPlaceholders = new dev.itemloom.paper.compat.script.LegacyItemPlaceholder(items);
        registry = new NiItemRegistry(revision, input, this.inputRoot, items);
        packs = new NiPackRegistry(items);
        translations = new NiTranslations(input.settings().string("Language", "zh_cn"), logger);
        var scheduler =
                new dev.itemloom.paper.compat.script.LegacyScheduler(
                        plugin, actions.tasks(), scripts);
        drops = new dev.itemloom.paper.action.ItemDrops(items, scheduler, actions.integrations());
        var itemUtils = new dev.itemloom.paper.compat.script.LegacyItemUtils(translations, items);
        bindings.alias("pers.neige.neigeitems.utils.ItemUtils", itemUtils);
        bindings.alias(
                "pers.neige.neigeitems.item.ItemInfo",
                jdk.dynalink.beans.StaticClass.forClass(
                        dev.itemloom.paper.compat.script.LegacyItemInfo.class));
        bindings.alias(
                "pers.neige.neigeitems.item.action.ComboInfo",
                jdk.dynalink.beans.StaticClass.forClass(PaperActions.ComboInfo.class));
        bindings.alias(
                "pers.neige.neigeitems.utils.PlayerUtils",
                new dev.itemloom.paper.compat.script.LegacyPlayerUtils(plugin, players));
        bindings.alias("pers.neige.neigeitems.utils.SchedulerUtils", scheduler);
        bindings.alias(
                "pers.neige.neigeitems.manager.HookerManager",
                new dev.itemloom.paper.compat.script.LegacyHooks(
                        placeholders, actions.integrations(), itemSources));
        bindings.alias(
                "pers.neige.neigeitems.event.ItemActionEvent",
                jdk.dynalink.beans.StaticClass.forClass(
                        dev.itemloom.paper.compat.script.LegacyItemActionEvent.class));
        bindings.alias(
                "pers.neige.neigeitems.item.action.ItemActionType",
                jdk.dynalink.beans.StaticClass.forClass(
                        dev.itemloom.paper.compat.script.LegacyItemActionType.class));
        bindings.alias("pers.neige.neigeitems.item.action.ActionTrigger", triggerFactory);
        bindings.alias(
                "pers.neige.neigeitems.item.action.ConsumeInfo", triggerFactory.consumeFactory());
        NiItemScriptAliases.install(bindings, items, actions, placeholders);
        dev.itemloom.paper.compat.script.NbtScriptAliases.install(bindings);
        scripts.setActionInitializer(new dev.itemloom.paper.compat.script.ActionScriptLibrary());
        host =
                new NiEvaluation.Host() {
                    public void warning(String message) {
                        logger.warning(message);
                    }

                    public boolean papiJavascript() {
                        return input.settings().bool("Papi.enableJs", false);
                    }

                    public boolean papiRegex() {
                        return input.settings().bool("Papi.enableRegex", false);
                    }

                    public String placeholder(Object player, String parameters) {
                        return placeholders.apply(player, parameters);
                    }

                    public String itemValue(String key, String parameters) {
                        return NiItemNodes.value(
                                key, parameters, NiActionContext.currentOrNull(), translations);
                    }

                    public String legacyItemValue(
                            String key,
                            String parameters,
                            ItemStack item,
                            Object nbt,
                            Map<String, String> rolls) {
                        return itemUtils.sectionValue(key, parameters, item, nbt, rolls);
                    }

                    public void check(Object actions, NiEvaluation context, String value) {
                        Map<String, Object> params = new java.util.HashMap<>();
                        params.put("value", value);
                        params.put("cache", context.legacyCache());
                        params.put(
                                "sections",
                                new dev.itemloom.compat.ni.script.LegacyConfigReader.BukkitReader(
                                        dev.itemloom.compat.ni.NiYaml.toSection(
                                                context.sections())));
                        NiActionContext call = actionContext(context.player(), params);
                        call.getGlobal().putAll(params);
                        NiCatalog.this.actions.fork(compileCheck(actions), call, true);
                    }
                };
        lifecycle =
                new NiEvaluation(
                        new GenerationContext(
                                Map.of(), java.util.concurrent.ThreadLocalRandom.current()),
                        null,
                        null,
                        NiEvaluation.Mode.SECTION,
                        nodes,
                        scripts,
                        host);
        try {
            registry.initializeFiles(this.inputRoot);
            actions.functions(input.functions());
            triggers =
                    new dev.itemloom.paper.action.ItemTriggers(
                            input, actions, players, this::actionContext, plugin, triggerFactory);
            Map<String, LegacyItemGenerator> generators = new LinkedHashMap<>();
            for (var entry : new NiInheritance(input).resolveAll().entrySet()) {
                String id = entry.getKey();
                NiConfig config = entry.getValue();
                try {
                    generators.put(id, compile(registry.configs().get(id), config));
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            input.items().get(id).source() + " / " + id + ": " + error.getMessage(),
                            error);
                }
            }
            registry.initialize(generators);
            packs.initialize(input.packs());
            // Source evaluation and enable callbacks can generate items. Actions and nodes
            // resolve custom registrations when executed, so preparing recipes first is safe.
            lifecycle.scoped(
                    () -> {
                        scripts.loadSources(sources);
                        return null;
                    });
            invokeExtensions("enable", false);
            maintenance = new dev.itemloom.paper.action.ItemMaintenance(this, plugin);
        } catch (RuntimeException error) {
            if (triggers != null) triggers.close();
            invokeExtensions("disable", true);
            actions.close();
            scripts.close();
            throw error;
        }
    }

    public ItemEngine.Catalog<ItemStack> catalog() {
        return registry == null ? null : registry.catalog();
    }

    public NiItemRegistry registry() {
        return registry;
    }

    public NiPackRegistry packs() {
        return packs;
    }

    public dev.itemloom.paper.action.ItemDrops drops() {
        return drops;
    }

    public dev.itemloom.paper.integration.OptionalItemSources itemSources() {
        return itemSources;
    }

    public NiRepository.Input input() {
        return input;
    }

    public Path inputRoot() {
        return inputRoot;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public NiItemOperations items() {
        return items;
    }

    public dev.itemloom.paper.compat.script.LegacyItemPlaceholder itemPlaceholders() {
        return itemPlaceholders;
    }

    public NiDisplayTemplate displayTemplate(String id) {
        items.ensureActive();
        return id == null ? null : registry.displays().get(id);
    }

    /** Resolves only saved rolls and the viewer; generation sections are deliberately absent. */
    public void display(
            NiDisplayTemplate template, org.bukkit.entity.Player viewer, ItemStack copy) {
        items.ensureActive();
        if (!template.dynamic()) {
            template.apply(
                    org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(copy), text -> text);
            return;
        }
        var identity = new dev.itemloom.paper.nms.ItemStateCodec().read(copy);
        if (identity.isEmpty()) identity = new NiItemMigration().identify(copy);
        Map<String, String> data =
                new java.util.HashMap<>(
                        identity.map(dev.itemloom.core.ItemIdentity::rolls).orElse(Map.of()));
        var context = actionContext(viewer, Map.of("itemStack", copy, "data", data));
        Map<String, Object> cache = new java.util.HashMap<>(data);
        cache.put("viewer_name", viewer.getName());
        cache.put("viewer_uuid", viewer.getUniqueId().toString());
        context.set(dev.itemloom.compat.ni.action.NiContextKeys.SECTION_CACHE, cache);
        context.invoke(
                () -> {
                    template.apply(
                            org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(copy),
                            text -> itemPlaceholders.parse(copy, context.parse(text)).getText());
                    return null;
                });
    }

    public String itemName(ItemStack item) {
        return translations.apply(item);
    }

    public boolean active() {
        return !closed && actions.active();
    }

    public dev.itemloom.paper.action.ItemTriggers triggers() {
        return triggers;
    }

    public dev.itemloom.paper.action.ItemMaintenance maintenance() {
        return maintenance;
    }

    public void activate() {
        if (maintenance != null) maintenance.activate();
    }

    /** ItemManager's local reload retains this owner's scripts, actions, tasks and revision number. */
    public void reloadItems(boolean includeGenerators) {
        items.ensureActive();
        if (reloadingItems)
            throw new IllegalStateException("Item directory reload is already in progress");
        reloadingItems = true;
        try {
            java.util.function.UnaryOperator<String> transform =
                    org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                            ? dev.itemloom.paper.integration.PapiBridge::itemSections
                            : java.util.function.UnaryOperator.identity();
            NiRepository.ItemInput loaded = new NiRepository().readItems(inputRoot, transform);
            Map<String, LegacyItemConfig> origins =
                    NiItemRegistry.originals(loaded.items(), inputRoot);
            Map<String, LegacyItemGenerator> generators = null;
            if (includeGenerators) {
                NiRepository.Input snapshot =
                        new NiRepository.Input(
                                input.settings(),
                                loaded.items(),
                                input.globalFiles(),
                                input.globalValues(),
                                input.packs(),
                                input.actions(),
                                input.functions(),
                                input.scripts(),
                                input.expansions(),
                                input.sources());
                generators = new LinkedHashMap<>();
                for (var entry : new NiInheritance(snapshot).resolveAll().entrySet()) {
                    String id = entry.getKey();
                    try {
                        generators.put(id, compile(origins.get(id), entry.getValue()));
                    } catch (RuntimeException error) {
                        throw new IllegalArgumentException(
                                loaded.items().get(id).source()
                                        + " / "
                                        + id
                                        + ": "
                                        + error.getMessage(),
                                error);
                    }
                }
            }
            Runnable commit = registry.prepareReload(origins, loaded.source().files(), generators);
            loaded.source().verifyUnchanged();
            items.ensureActive();
            commit.run();
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(
                    "Cannot reload item directory: " + inputRoot.resolve("Items"), error);
        } finally {
            reloadingItems = false;
        }
    }

    /** Generation remains pinned to this revision, including script calls during reload. */
    public ItemStack generate(
            String id,
            org.bukkit.OfflinePlayer viewer,
            Map<String, String> rolls,
            boolean updateCallerCache) {
        items.ensureActive();
        LegacyItemGenerator generator = registry.generators().get(id);
        if (generator == null) throw new IllegalArgumentException("Unknown item: " + id);
        return generate(generator, viewer, rolls, updateCallerCache);
    }

    /** A detached or replaced generator keeps its own recipe and event actions within this owner. */
    public ItemStack generate(
            LegacyItemGenerator generator,
            org.bukkit.OfflinePlayer viewer,
            Map<String, String> rolls,
            boolean updateCallerCache) {
        items.ensureActive();
        if (!generator.ownedBy(items))
            throw new IllegalArgumentException(
                    "Generator belongs to another item catalog revision");
        String id = generator.getId();
        GenerationContext context =
                updateCallerCache && rolls != null
                        ? GenerationContext.sharingRolls(
                                rolls, java.util.concurrent.ThreadLocalRandom.current())
                        : new GenerationContext(
                                rolls == null ? Map.of() : rolls,
                                java.util.concurrent.ThreadLocalRandom.current());
        context.put(dev.itemloom.api.ItemContext.VIEWER, viewer);
        var generated = generator.compiledRecipe().createGenerated(context);
        if (!active()) throw new IllegalStateException("Item catalog closed during generation");
        Map<String, String> cache = context.rolls();
        var event =
                dev.itemloom.paper.compat.script.LegacyItemGenerateEvent.fromGenerated(
                        id, viewer, cache, generated);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (!active()) throw new IllegalStateException("Item catalog closed during generation");
        if (generated.postGenerate()) postGenerated(generator, viewer, event.getItem(), cache);
        if (!active())
            throw new IllegalStateException("Item catalog closed during post-generation actions");
        return event.getItem();
    }

    /** Compiles an unregistered item against this revision's current original-config registry. */
    public LegacyItemGenerator compile(LegacyItemConfig origin) {
        items.ensureActive();
        java.util.Objects.requireNonNull(origin, "itemConfig");
        Map<String, NiRepository.Definition> definitions = new LinkedHashMap<>();
        registry.configs()
                .forEach(
                        (key, value) ->
                                definitions.put(
                                        key,
                                        new NiRepository.Definition(
                                                key,
                                                String.valueOf(value.getFile()),
                                                original(value))));
        NiRepository.Input snapshot =
                new NiRepository.Input(
                        input.settings(),
                        definitions,
                        input.globalFiles(),
                        input.globalValues(),
                        input.packs(),
                        input.actions(),
                        input.functions(),
                        input.scripts(),
                        input.expansions(),
                        input.sources());
        return compile(origin, new NiInheritance(snapshot).resolveRoot(original(origin)));
    }

    private static NiConfig original(LegacyItemConfig origin) {
        return origin.getConfigSection() == null
                ? new NiConfig(Map.of())
                : dev.itemloom.compat.ni.NiYaml.fromSection(origin.getConfigSection());
    }

    private LegacyItemGenerator compile(LegacyItemConfig origin, NiConfig resolved) {
        prepareChecks(resolved.values());
        ActionFlow.Step<NiActionContext> postStep = null;
        NiConfig event = resolved.section("event");
        NiConfig post = event == null ? null : event.section("post-generate");
        if (post != null) {
            Map<String, Object> trigger = new java.util.HashMap<>(post.values());
            trigger.put("type", "condition");
            postStep = actions.compiler().compile(trigger);
        }
        String source = String.valueOf(origin.getFile());
        NiPaperRecipe recipe =
                new NiPaperRecipe(
                        new NiCompiledItem(origin.getId(), source, resolved),
                        nodes,
                        scripts,
                        host,
                        Clock.systemUTC(),
                        message ->
                                logger.warning(source + " / " + origin.getId() + ": " + message));
        return new LegacyItemGenerator(items, origin, recipe, postStep);
    }

    private ActionFlow.Step<NiActionContext> compileCheck(Object source) {
        synchronized (scripts) {
            var compiled = checks.get(source);
            if (compiled == null) {
                compiled = actions.compiler().compile(source);
                if (checks.size() >= 256) checks.remove(checks.keySet().iterator().next());
                checks.put(source, compiled);
            }
            return compiled;
        }
    }

    private void prepareChecks(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("check".equals(map.get("type"))) compileCheck(map.get("actions"));
            map.values().forEach(this::prepareChecks);
        } else if (value instanceof java.util.List<?> list) list.forEach(this::prepareChecks);
    }

    public NiActionContext actionContext(Object caster, Map<String, Object> params) {
        NiActionContext context =
                new NiActionContext(
                        new NiEvaluation(
                                new GenerationContext(
                                        Map.of(), java.util.concurrent.ThreadLocalRandom.current()),
                                null,
                                caster,
                                NiEvaluation.Mode.ACTION,
                                nodes,
                                scripts,
                                host),
                        caster,
                        params,
                        actions::active);
        if (params != null) {
            if (params.get("itemStack") instanceof ItemStack item) {
                context.set(dev.itemloom.compat.ni.action.NiContextKeys.ITEM_STACK, item);
                context.set(
                        dev.itemloom.compat.ni.action.NiContextKeys.NBT,
                        new dev.itemloom.paper.compat.nbt.LegacyNbtItemStack(item).getTag());
            }
            if (params.containsKey("itemTag"))
                context.set(dev.itemloom.compat.ni.action.NiContextKeys.NBT, params.get("itemTag"));
            if (params.get("data") instanceof Map<?, ?> data) {
                @SuppressWarnings("unchecked")
                Map<String, String> rolls = (Map<String, String>) data;
                context.set(dev.itemloom.compat.ni.action.NiContextKeys.DATA, rolls);
            }
            if (params.get("event") instanceof org.bukkit.event.Event event)
                context.set(dev.itemloom.compat.ni.action.NiContextKeys.EVENT, event);
        }
        return context;
    }

    public java.util.concurrent.CompletionStage<ActionFlow.Result> runFunction(
            String id, Object caster, Map<String, Object> params) {
        return actions.function(id, actionContext(caster, params));
    }

    private void postGenerated(
            LegacyItemGenerator generator,
            org.bukkit.OfflinePlayer viewer,
            ItemStack item,
            Map<String, String> rolls) {
        String id = generator.getId();
        var step = generator.postGenerate();
        if (step != null) {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("id", id);
            params.put("item", generator);
            params.put("itemStack", item);
            params.put("data", rolls);
            NiActionContext call =
                    actionContext(viewer == null ? null : viewer.getPlayer(), params);
            call.getGlobal().put("id", id);
            call.getGlobal().put("item", params.get("item"));
            actions.run(step, call);
        }
        item.setAmount(1);
    }

    public void serverEnabled() {
        invokeExtensions("serverEnable", true);
    }

    public void serverStopping() {
        invokeExtensions("serverDisable", true);
    }

    private void invokeExtensions(String function, boolean tolerateErrors) {
        for (String path : input.expansions().keySet()) {
            String key = "@expansions/" + path;
            if (!scripts.hasFunction(key, function)) continue;
            try {
                lifecycle.scoped(() -> scripts.invoke(key, function, Map.of()));
            } catch (RuntimeException error) {
                if (!tolerateErrors) throw error;
                logger.log(java.util.logging.Level.WARNING, path + " / " + function, error);
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (maintenance != null) maintenance.close();
        if (triggers != null) triggers.close();
        actions.close();
        invokeExtensions("disable", true);
        scripts.close();
    }
}

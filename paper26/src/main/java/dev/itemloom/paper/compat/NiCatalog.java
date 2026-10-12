package dev.itemloom.paper.compat;

import dev.itemloom.api.ItemContext;
import dev.itemloom.compat.ni.*;
import dev.itemloom.compat.ni.action.*;
import dev.itemloom.compat.ni.script.*;
import dev.itemloom.core.*;
import dev.itemloom.paper.action.*;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.script.*;
import dev.itemloom.paper.integration.*;
import dev.itemloom.paper.nms.NmsItems;
import dev.itemloom.paper.runtime.FormatRuntimeLoader;
import java.io.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.random.RandomGenerator;
import jdk.dynalink.beans.StaticClass;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** A prepared revision owns its registries, script contexts and lifecycle resources. */
public final class NiCatalog implements AutoCloseable {
    private final NiRepository.Input input;
    private final Path root;
    private final JavaPlugin plugin;
    private final BiFunction<Object, String, String> placeholders;
    private final NiNodes nodes = new NiNodes();
    private final NiScripts scripts;
    private final NiItemOperations items;
    private final NiItemRegistry registry;
    private final NiPackRegistry packs;
    private final NiTranslations translations;
    private final LegacyItemPlaceholder itemPlaceholders;
    private final OptionalItemSources sources = new OptionalItemSources();
    private final NiEvaluation.Host host;
    private final PaperActions actions;
    private final ItemDrops drops;
    private ItemTriggers triggers;
    private ItemMaintenance maintenance;
    private volatile boolean closed;
    private boolean reloading;
    private final LinkedHashMap<Object, ActionFlow.Step<NiActionContext>> checks =
            new LinkedHashMap<>();

    public NiCatalog(
            long revision,
            NiRepository.Input input,
            JavaPlugin plugin,
            BiFunction<Object, String, String> placeholders,
            PlayerActionState players) {
        this(revision, input, plugin.getDataFolder().toPath(), plugin, placeholders, players);
    }

    public NiCatalog(
            long revision,
            NiRepository.Input input,
            Path root,
            JavaPlugin plugin,
            BiFunction<Object, String, String> placeholders,
            PlayerActionState players) {
        this.input = Objects.requireNonNull(input);
        this.root = root.toAbsolutePath().normalize();
        this.plugin = Objects.requireNonNull(plugin);
        this.placeholders = Objects.requireNonNull(placeholders);
        try {
            LegacyScriptBindings bindings = new LegacyScriptBindings(nodes);
            scripts = new NiScripts(Map.of(), Map.of("plugin", plugin), bindings);
            items = new NiItemOperations(this);
            registry = new NiItemRegistry(revision, input, this.root, items);
            packs = new NiPackRegistry(items);
            translations =
                    new NiTranslations(
                            input.settings().string("Language", "zh_cn"), plugin.getLogger());
            itemPlaceholders = new LegacyItemPlaceholder(items);
            actions =
                    new PaperActions(
                            plugin,
                            scripts,
                            players,
                            input.settings().longValue("ItemAction.comboInterval", 500));
            LegacyItemUtils itemUtils = new LegacyItemUtils(translations, items);
            host =
                    new NiEvaluation.Host() {
                        @Override
                        public String placeholder(Object player, String text) {
                            return NiCatalog.this.placeholders.apply(player, text);
                        }

                        @Override
                        public String itemValue(String key, String parameters) {
                            return NiItemNodes.value(
                                    key, parameters, NiActionContext.currentOrNull(), translations);
                        }

                        @Override
                        public String legacyItemValue(
                                String key,
                                String parameters,
                                ItemStack item,
                                Object nbt,
                                Map<String, String> rolls) {
                            return itemUtils.sectionValue(key, parameters, item, nbt, rolls);
                        }

                        @Override
                        public void warning(String message) {
                            plugin.getLogger().warning(message);
                        }

                        @Override
                        public boolean papiJavascript() {
                            return input.settings().bool("Papi.enableJs", false);
                        }

                        @Override
                        public boolean papiRegex() {
                            return input.settings().bool("Papi.enableRegex", false);
                        }

                        @Override
                        public void check(Object source, NiEvaluation evaluation, String value) {
                            Map<String, Object> params = new LinkedHashMap<>();
                            params.put("value", value);
                            params.put("cache", evaluation.legacyCache());
                            params.put(
                                    "sections",
                                    new LegacyConfigReader.BukkitReader(
                                            NiConfigViews.section(evaluation.sections())));
                            NiActionContext context = actionContext(evaluation.player(), params);
                            context.getGlobal().putAll(params);
                            actions.fork(checkAction(source), context, true);
                        }
                    };
            LegacyScheduler scheduler = new LegacyScheduler(plugin, actions.tasks(), scripts);
            drops = new ItemDrops(items, scheduler, actions.integrations());
            LegacyActionManager manager = new LegacyActionManager(actions.compiler(), actions::run);
            bindings.actionManager(manager);
            alias(bindings, "utils.ItemUtils", itemUtils);
            alias(bindings, "item.ItemInfo", StaticClass.forClass(LegacyItemInfo.class));
            alias(
                    bindings,
                    "item.action.ComboInfo",
                    StaticClass.forClass(PaperActions.ComboInfo.class));
            alias(bindings, "utils.PlayerUtils", new LegacyPlayerUtils(plugin, players));
            alias(bindings, "utils.SchedulerUtils", scheduler);
            alias(
                    bindings,
                    "manager.HookerManager",
                    new LegacyHooks(placeholders, actions.integrations(), sources));
            alias(
                    bindings,
                    "event.ItemActionEvent",
                    StaticClass.forClass(LegacyItemActionEvent.class));
            alias(
                    bindings,
                    "item.action.ItemActionType",
                    StaticClass.forClass(LegacyItemActionType.class));
            LegacyActionTrigger.Factory factory =
                    new LegacyActionTrigger.Factory(actions, manager, scripts::validate);
            alias(bindings, "item.action.ActionTrigger", factory);
            alias(bindings, "item.action.ConsumeInfo", factory.consumeFactory());
            NiItemScriptAliases.install(bindings, items, actions, placeholders);
            NbtScriptAliases.install(bindings);
            scripts.setActionInitializer(new ActionScriptLibrary());
            registry.initializeFiles(this.root);
            registry.initialize(compileAll(registry.configs(), input));
            packs.initialize(input.packs());
            actions.functions(input.functions());
            triggers =
                    new ItemTriggers(input, actions, players, this::actionContext, plugin, factory);
            Map<String, String> loaded = new LinkedHashMap<>(input.scripts());
            input.expansions().forEach((path, source) -> loaded.put("@expansions/" + path, source));
            evaluation(null, NiEvaluation.Mode.SECTION)
                    .scoped(
                            () -> {
                                scripts.loadSources(loaded);
                                expansions("enable", false);
                                return null;
                            });
            maintenance =
                    new ItemMaintenance(
                            FormatRuntimeLoader.maintenance(this),
                            FormatRuntimeLoader.durability(this),
                            plugin);
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void alias(LegacyScriptBindings bindings, String name, Object value) {
        bindings.alias("pers.neige.neigeitems." + name, value);
    }

    public ItemEngine.Catalog<ItemStack> catalog() {
        return registry.catalog();
    }

    public NiItemRegistry registry() {
        return registry;
    }

    public NiPackRegistry packs() {
        return packs;
    }

    public ItemDrops drops() {
        return drops;
    }

    public OptionalItemSources itemSources() {
        return sources;
    }

    public NiRepository.Input input() {
        return input;
    }

    public Path inputRoot() {
        return root;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public NiItemOperations items() {
        return items;
    }

    public LegacyItemPlaceholder itemPlaceholders() {
        return itemPlaceholders;
    }

    public NiDisplayTemplate displayTemplate(String id) {
        return registry.displays().get(id);
    }

    public String itemName(ItemStack item) {
        return translations.apply(item);
    }

    public boolean active() {
        return !closed;
    }

    public ItemTriggers triggers() {
        return triggers;
    }

    public ItemMaintenance maintenance() {
        return maintenance;
    }

    public void activate() {
        items.ensureActive();
        maintenance.activate();
    }

    public void display(NiDisplayTemplate template, Player viewer, ItemStack item) {
        items.ensureActive();
        var target = CraftItemStack.asNMSCopy(item);
        if (!template.dynamic()) template.apply(target, UnaryOperator.identity());
        else {
            ItemIdentity identity = NiItemNodes.identity(item);
            Map<String, String> rolls =
                    identity == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(identity.rolls());
            Map<String, Object> params = new LinkedHashMap<>();
            ItemStack displayCopy = item.clone();
            params.put("itemStack", displayCopy);
            params.put("data", rolls);
            NiActionContext context = actionContext(viewer, params);
            Map<String, Object> cache = new LinkedHashMap<>(rolls);
            if (viewer != null) {
                cache.put("viewer_name", viewer.getName());
                cache.put("viewer_uuid", viewer.getUniqueId().toString());
            }
            context.set(NiContextKeys.SECTION_CACHE, cache);
            template.apply(
                    target,
                    text -> itemPlaceholders.parse(displayCopy, context.parse(text)).getText());
        }
        items.ensureActive();
        NmsItems.replace(item, CraftItemStack.asBukkitCopy(target));
    }

    public void reloadItems(boolean includeGenerators) {
        items.ensureActive();
        if (reloading) throw new IllegalStateException("Recursive item reload");
        reloading = true;
        try {
            UnaryOperator<String> preprocess =
                    Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                            ? PapiBridge::itemSections
                            : UnaryOperator.identity();
            NiRepository.ItemInput next = new NiRepository().readItems(root, preprocess);
            Map<String, LegacyItemConfig> originals = NiItemRegistry.originals(next.items(), root);
            Map<String, LegacyItemGenerator> compiled =
                    includeGenerators ? compileAll(originals, withItems(next.items())) : null;
            Runnable commit = registry.prepareReload(originals, next.source().files(), compiled);
            next.source().verifyUnchanged();
            items.ensureActive();
            commit.run();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            reloading = false;
        }
    }

    public ItemStack generate(
            String id, OfflinePlayer viewer, Map<String, String> rolls, boolean share) {
        items.ensureActive();
        return generate(registry.generators().get(id), viewer, rolls, share);
    }

    public ItemStack generate(
            LegacyItemGenerator generator,
            OfflinePlayer viewer,
            Map<String, String> rolls,
            boolean share) {
        items.ensureActive();
        if (generator == null) return null;
        if (!generator.ownedBy(items))
            throw new IllegalArgumentException("Generator belongs to another revision");
        GenerationContext context =
                share && rolls != null
                        ? GenerationContext.sharingRolls(rolls, RandomGenerator.getDefault())
                        : new GenerationContext(
                                rolls == null ? Map.of() : rolls, RandomGenerator.getDefault());
        context.put(ItemContext.VIEWER, viewer);
        NiPaperRecipe.Generated generated = generator.compiledRecipe().createGenerated(context);
        items.ensureActive();
        LegacyItemGenerateEvent event =
                LegacyItemGenerateEvent.fromGenerated(
                        generator.getId(), viewer, context.rolls(), generated);
        event.call();
        items.ensureActive();
        ItemStack result = event.getItemStack();
        if (generated.postGenerate()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("id", generator.getId());
            params.put("item", generator);
            params.put("itemStack", result);
            params.put("data", context.rolls());
            NiActionContext action =
                    actionContext(viewer == null ? null : viewer.getPlayer(), params);
            action.getGlobal().put("id", generator.getId());
            action.getGlobal().put("item", generator);
            if (generator.postGenerate() != null) actions.run(generator.postGenerate(), action);
            result.setAmount(1);
        }
        items.ensureActive();
        return result;
    }

    public LegacyItemGenerator compile(LegacyItemConfig original) {
        items.ensureActive();
        Map<String, NiRepository.Definition> definitions = definitions(registry.configs());
        NiInheritance inheritance = new NiInheritance(withItems(definitions));
        return compile(
                original, resolve(original, () -> inheritance.resolveRoot(config(original))));
    }

    private Map<String, LegacyItemGenerator> compileAll(
            Map<String, LegacyItemConfig> originals, NiRepository.Input source) {
        NiInheritance inheritance = new NiInheritance(source);
        Map<String, NiConfig> resolved = new LinkedHashMap<>();
        originals.forEach(
                (id, original) ->
                        resolved.put(id, resolve(original, () -> inheritance.resolve(id))));
        Map<String, LegacyItemGenerator> result = new LinkedHashMap<>();
        originals.forEach((id, original) -> result.put(id, compile(original, resolved.get(id))));
        return result;
    }

    private static NiConfig resolve(LegacyItemConfig original, Supplier<NiConfig> resolver) {
        try {
            return resolver.get();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    original.getFile() + " / " + original.getId() + ": " + error.getMessage(),
                    error);
        }
    }

    private LegacyItemGenerator compile(LegacyItemConfig original, NiConfig resolved) {
        try {
            NiPaperRecipe recipe =
                    new NiPaperRecipe(
                            new NiCompiledItem(
                                    original.getId(), String.valueOf(original.getFile()), resolved),
                            nodes,
                            scripts,
                            host,
                            Clock.systemUTC(),
                            message ->
                                    plugin.getLogger()
                                            .warning(
                                                    original.getFile()
                                                            + " / "
                                                            + original.getId()
                                                            + ": "
                                                            + message),
                            sources);
            prepareChecks(resolved.values());
            NiConfig event = resolved.section("event");
            NiConfig post = event == null ? null : event.section("post-generate");
            Object action = post == null ? null : post.with("type", "condition").values();
            return new LegacyItemGenerator(
                    items,
                    original,
                    recipe,
                    action == null ? null : actions.compiler().compile(action));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    original.getFile() + " / " + original.getId() + ": " + error.getMessage(),
                    error);
        }
    }

    private static NiConfig config(LegacyItemConfig original) {
        ConfigurationSection section = original.getConfigSection();
        return section == null ? new NiConfig(Map.of()) : NiYaml.fromSection(section);
    }

    private static Map<String, NiRepository.Definition> definitions(
            Map<String, LegacyItemConfig> originals) {
        Map<String, NiRepository.Definition> values = new LinkedHashMap<>();
        originals.forEach(
                (id, origin) ->
                        values.put(
                                id,
                                new NiRepository.Definition(
                                        id, String.valueOf(origin.getFile()), config(origin))));
        return values;
    }

    private NiRepository.Input withItems(Map<String, NiRepository.Definition> definitions) {
        return new NiRepository.Input(
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
    }

    private NiEvaluation evaluation(Object caster, NiEvaluation.Mode mode) {
        return new NiEvaluation(
                new GenerationContext(Map.of(), RandomGenerator.getDefault()),
                null,
                caster,
                mode,
                nodes,
                scripts,
                host);
    }

    @SuppressWarnings("unchecked")
    public NiActionContext actionContext(Object caster, Map<String, Object> params) {
        Map<String, Object> values = params == null ? new LinkedHashMap<>() : params;
        NiActionContext context =
                new NiActionContext(
                        evaluation(caster, NiEvaluation.Mode.ACTION),
                        caster,
                        values,
                        actions::active);
        if (values.get("itemStack") instanceof ItemStack item) {
            context.set(NiContextKeys.ITEM_STACK, item);
            context.set(NiContextKeys.NBT, new LegacyNbtItemStack(item).getTag());
        }
        if (values.containsKey("itemTag")) context.set(NiContextKeys.NBT, values.get("itemTag"));
        if (values.get("data") instanceof Map<?, ?> data)
            context.set(NiContextKeys.DATA, (Map<String, String>) data);
        if (values.get("event") instanceof Event event) context.set(NiContextKeys.EVENT, event);
        return context;
    }

    public CompletionStage<ActionFlow.Result> runFunction(
            String name, Object caster, Map<String, Object> params) {
        return actions.function(name, actionContext(caster, params));
    }

    private synchronized ActionFlow.Step<NiActionContext> checkAction(Object source) {
        ActionFlow.Step<NiActionContext> result = checks.get(source);
        if (result == null) {
            result = actions.compiler().compile(source);
            checks.put(source, result);
            if (checks.size() > 256) checks.remove(checks.keySet().iterator().next());
        }
        return result;
    }

    private void prepareChecks(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("check".equals(map.get("type"))) checkAction(map.get("actions"));
            map.values().forEach(this::prepareChecks);
        } else if (value instanceof List<?> list) list.forEach(this::prepareChecks);
    }

    private void expansions(String function, boolean tolerant) {
        for (String path : input.expansions().keySet()) {
            String source = "@expansions/" + path;
            try {
                if (scripts.hasFunction(source, function))
                    scripts.invoke(source, function, Map.of());
            } catch (RuntimeException error) {
                if (!tolerant)
                    throw new IllegalArgumentException(
                            source + " / " + function + ": " + error.getMessage(), error);
                plugin.getLogger().warning(source + " / " + function + ": " + error.getMessage());
            }
        }
    }

    public void serverEnabled() {
        evaluation(null, NiEvaluation.Mode.SECTION)
                .scoped(
                        () -> {
                            expansions("serverEnable", true);
                            return null;
                        });
    }

    public void serverStopping() {
        evaluation(null, NiEvaluation.Mode.SECTION)
                .scoped(
                        () -> {
                            expansions("serverDisable", true);
                            return null;
                        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (maintenance != null) maintenance.close();
        } finally {
            try {
                if (triggers != null) triggers.close();
            } finally {
                try {
                    if (actions != null) actions.close();
                } finally {
                    try {
                        if (scripts != null)
                            evaluation(null, NiEvaluation.Mode.SECTION)
                                    .scoped(
                                            () -> {
                                                expansions("disable", true);
                                                return null;
                                            });
                    } finally {
                        if (scripts != null) scripts.close();
                    }
                }
            }
        }
    }
}

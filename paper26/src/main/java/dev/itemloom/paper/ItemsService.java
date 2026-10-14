package dev.itemloom.paper;

import dev.itemloom.api.ItemLoom;
import dev.itemloom.api.ItemProvider;
import dev.itemloom.api.ItemRebuildResult;
import dev.itemloom.api.ItemsReloadEvent;
import dev.itemloom.api.ProviderRegistration;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.core.GenerationBudget;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.core.RebuildRules;
import dev.itemloom.paper.action.DropOwnership;
import dev.itemloom.paper.action.DropVisibility;
import dev.itemloom.paper.action.ItemUseRestrictions;
import dev.itemloom.paper.action.LootBagConfig;
import dev.itemloom.paper.action.LootBags;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.display.ItemDisplayService;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.runtime.DropOwnershipPolicy;
import dev.itemloom.paper.runtime.FormatRuntimeLoader;
import dev.itemloom.paper.runtime.ItemDefinitions;
import dev.itemloom.paper.runtime.ItemRebuilds;
import dev.itemloom.paper.runtime.PreparedRuntime;
import dev.itemloom.paper.runtime.RuntimeEventRouter;
import dev.itemloom.paper.runtime.RuntimeLoader;
import dev.itemloom.paper.runtime.RuntimeSnapshot;
import dev.itemloom.paper.sx.SxMaintenance;
import dev.keystone.config.ReloadTransaction;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Public operations capture one immutable full revision; local publications remain independent. */
public final class ItemsService implements ItemLoom, AutoCloseable, Listener {
    private static final LootBagConfig NO_BAGS = new LootBagConfig(Map.of(), Map.of(), Map.of());
    private static final DropOwnershipPolicy NO_OWNER_MESSAGE = new DropOwnershipPolicy(null, "");
    private final JavaPlugin plugin;
    private final Path inputRoot;
    private final ItemStateCodec identity = new ItemStateCodec();
    private final PlayerActionState players = new PlayerActionState();
    private final ItemProviders providers = new ItemProviders();
    private final LootBags bags;
    private final DropVisibility visibility;
    private final ItemDisplayService display;
    private final ItemUseRestrictions restrictions;
    private final DropOwnership ownership;
    private final RuntimeEventRouter events;
    private final SxMaintenance secondaryMaintenance;
    private final RuntimeLoader loader;
    private volatile RuntimeSnapshot current;
    private PreparedRuntime installed;
    private long revision;
    private boolean reloading;
    private boolean started;
    private volatile boolean closed;
    private boolean serverEnabled;
    private BukkitTask ticker;
    private int ticks;

    public ItemsService(
            JavaPlugin plugin, Path inputRoot, BiFunction<Object, String, String> placeholders) {
        this(
                plugin,
                inputRoot,
                placeholders,
                plugin.getDataFolder().toPath().resolve("return-ledger.json"));
    }

    public ItemsService(
            JavaPlugin plugin,
            Path inputRoot,
            BiFunction<Object, String, String> placeholders,
            Path returnLedger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inputRoot = Objects.requireNonNull(inputRoot, "inputRoot");
        players.initializeReturns(plugin, Objects.requireNonNull(returnLedger, "returnLedger"));
        bags =
                new LootBags(
                        plugin,
                        this,
                        players,
                        () -> {
                            RuntimeSnapshot captured = current;
                            return captured == null ? NO_BAGS : captured.policies().lootBags();
                        });
        visibility = new DropVisibility(plugin);
        display =
                new ItemDisplayService(
                        plugin,
                        () -> {
                            RuntimeSnapshot captured = current;
                            return captured == null ? null : captured.display();
                        });
        restrictions =
                new ItemUseRestrictions(
                        () -> {
                            RuntimeSnapshot captured = current;
                            return captured == null
                                    ? Map.of()
                                    : captured.policies().usageRestrictions();
                        });
        ownership =
                new DropOwnership(
                        () -> {
                            RuntimeSnapshot captured = current;
                            return captured == null
                                    ? NO_OWNER_MESSAGE
                                    : captured.policies().dropOwnership();
                        });
        events =
                new RuntimeEventRouter(
                        () -> {
                            RuntimeSnapshot captured = current;
                            return captured == null || closed ? null : captured.events();
                        });
        secondaryMaintenance =
                new SxMaintenance(
                        () -> {
                            RuntimeSnapshot captured = current;
                            return captured == null || closed ? null : captured.secondaryUpdates();
                        },
                        plugin.getLogger());
        loader = new FormatRuntimeLoader(plugin, inputRoot, placeholders, players, bags::interact);
    }

    public boolean reload(CommandSender sender) {
        requireThread();
        if (closed) {
            throw new IllegalStateException("ItemLoom service is closed");
        }
        if (reloading) {
            throw new IllegalStateException("ItemLoom reload is already in progress");
        }
        reloading = true;
        PreparedRuntime candidate = null;
        try {
            try (ReloadTransaction transaction = ReloadTransaction.begin()) {
                try {
                    candidate = loader.prepare(revision + 1);
                    providers.validate(configuredIds(candidate.snapshot(), true));
                    candidate.verifySourcesUnchanged();
                    if (closed) {
                        throw new IllegalStateException(
                                "ItemLoom closed during reload preparation");
                    }
                } catch (Exception failure) {
                    transaction.problem(inputRoot.toFile(), inputRoot.toString(), failure);
                }
                if (!transaction.commit(sender)) {
                    return false;
                }
            }
            if (candidate == null) {
                return false;
            }
            PreparedRuntime previous = installed;
            boolean initial = !started;
            RuntimeSnapshot next = candidate.snapshot();
            installed = candidate;
            current = next;
            candidate = null;
            try {
                players.configure(next.policies().resetCooldownOnQuit());
                for (Player player : Bukkit.getOnlinePlayers()) {
                    players.join(player);
                }
                start();
                if (previous != null) {
                    previous.close();
                }
                if (closed || current != next) {
                    return false;
                }
                secondaryMaintenance.refresh();
                installed.activate();
                if (closed || current != next || !next.lifecycle().active()) {
                    return false;
                }
                revision = next.revision();
                Bukkit.getPluginManager().callEvent(new ItemsReloadEvent(revision, initial));
                return true;
            } catch (RuntimeException | Error failure) {
                // Publication has happened. Compensate by shutting down every installed resource;
                // claiming preparation rollback here would leave live listeners on a closed owner.
                close();
                if (previous != null) {
                    cleanup(previous::close, "previous revision after failed installation");
                }
                plugin.getLogger()
                        .log(
                                Level.SEVERE,
                                "ItemLoom runtime installation failed; service closed",
                                failure);
                if (sender != null) {
                    sender.sendMessage(
                            "ItemLoom runtime installation failed; service closed. See server log.");
                }
                return false;
            }
        } finally {
            if (candidate != null) {
                PreparedRuntime discarded = candidate;
                cleanup(discarded::close, "discarded runtime");
            }
            reloading = false;
        }
    }

    private void start() {
        if (started) {
            return;
        }
        started = true;
        var manager = Bukkit.getPluginManager();
        manager.registerEvents(this, plugin);
        manager.registerEvents(events, plugin);
        manager.registerEvents(restrictions, plugin);
        manager.registerEvents(ownership, plugin);
        manager.registerEvents(secondaryMaintenance, plugin);
        display.start();
        visibility.start();
        ticker =
                Bukkit.getScheduler()
                        .runTaskTimer(
                                plugin,
                                () -> {
                                    RuntimeSnapshot captured = current;
                                    if (closed || captured == null) {
                                        return;
                                    }
                                    captured.events().tick();
                                    if (!closed && current == captured) {
                                        secondaryMaintenance.tick();
                                    }
                                    if (!closed && ++ticks % 10 == 0) {
                                        visibility.tick();
                                    }
                                },
                                1,
                                1);
    }

    public void serverEnabled() {
        requireThread();
        if (!serverEnabled && !closed && current != null) {
            serverEnabled = true;
            current.lifecycle().serverEnabled();
        }
    }

    public String requestPlaceholder(OfflinePlayer viewer, String parameters) {
        requireThread();
        RuntimeSnapshot captured = current;
        return closed || captured == null
                ? null
                : captured.placeholders().request(viewer, parameters);
    }

    public Object placeholderRevision() {
        RuntimeSnapshot captured = current;
        return captured == null ? null : captured.placeholders().publicationToken();
    }

    public Object providerRevision() {
        return providers.revision();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        if (!closed) {
            players.join(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void left(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        bags.quit(player.getUniqueId());
        RuntimeSnapshot captured = current;
        try {
            if (captured != null) {
                captured.events().flushReturns(player);
            }
        } finally {
            try {
                players.returnLedger().flush(player);
            } finally {
                players.quit(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void providerDisabled(PluginDisableEvent event) {
        providers.remove(event.getPlugin());
    }

    @Override
    public Set<String> ids() {
        RuntimeSnapshot captured = current;
        Set<String> ids = captured == null ? new LinkedHashSet<>() : configuredIds(captured, false);
        ids.addAll(providers.ids(false));
        return Set.copyOf(ids);
    }

    @Override
    public ItemStack create(String id, OfflinePlayer viewer, Map<String, String> savedRolls) {
        RuntimeSnapshot captured = requireLoaded();
        ItemDefinitions selected = null;
        for (ItemDefinitions format : captured.itemFormats()) {
            if (format.publication().ids().contains(id)) {
                if (selected != null) {
                    throw conflict(id);
                }
                selected = format;
            }
        }
        boolean provided = providers.contains(id);
        if (provided && selected != null) {
            throw conflict(id);
        }
        Map<String, String> rolls = copyRolls(savedRolls);
        if (provided) {
            return providers.create(id, viewer, rolls);
        }
        if (selected == null) {
            throw new IllegalArgumentException("Unknown item: " + id);
        }
        return Objects.requireNonNull(selected.create(id, viewer, rolls), "Generated item");
    }

    @Override
    public ProviderRegistration registerProvider(
            Plugin owner, String namespace, ItemProvider provider) {
        RuntimeSnapshot captured = requireLoaded();
        return providers.register(owner, namespace, provider, configuredIds(captured, true));
    }

    @Override
    public Set<String> groupIds() {
        return providers.ids(true);
    }

    @Override
    public List<ItemStack> createGroup(
            String id, OfflinePlayer viewer, Map<String, String> savedRolls) {
        RuntimeSnapshot captured = requireLoaded();
        providers.validate(configuredIds(captured, true));
        return providers.group(id, viewer, copyRolls(savedRolls));
    }

    @Override
    public SaveResult save(ItemStack item, String id, String path, boolean replace) {
        RuntimeSnapshot captured = requireLoaded();
        if (providers.contains(id)) {
            return SaveResult.CONFLICT;
        }
        for (ItemDefinitions format : captured.itemFormats()) {
            if (!captured.persistence().owns(format) && format.publication().ids().contains(id)) {
                return SaveResult.CONFLICT;
            }
        }
        if (path == null || !(path.endsWith(".yml") || path.endsWith(".yaml"))) {
            throw new IllegalArgumentException("Item path must end in .yml or .yaml");
        }
        return captured.persistence().save(item, id, path, replace);
    }

    @Override
    public Set<String> packIds() {
        return Set.copyOf(requireLoaded().packs().ids());
    }

    @Override
    public List<ItemStack> createPack(
            String id, OfflinePlayer viewer, Map<String, String> savedRolls) {
        return createPack(id, viewer, savedRolls, null);
    }

    public List<ItemStack> createPack(
            String id,
            OfflinePlayer viewer,
            Map<String, String> savedRolls,
            GenerationBudget budget) {
        RuntimeSnapshot captured = requireLoaded();
        if (!captured.packs().ids().contains(id)) {
            throw new IllegalArgumentException("Unknown item pack: " + id);
        }
        return captured.packs()
                .create(
                        id,
                        viewer,
                        savedRolls == null ? null : new LinkedHashMap<>(savedRolls),
                        budget);
    }

    @Override
    public CompletionStage<ActionFlow.Result> runFunction(
            String id, Object caster, Map<String, Object> parameters) {
        return requireLoaded().functions().run(id, caster, parameters);
    }

    @Override
    public Optional<ItemIdentity> identify(ItemStack item) {
        requireThread();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        Optional<ItemIdentity> nativeIdentity = identity.read(item);
        return nativeIdentity.isPresent()
                ? nativeIdentity
                : FormatRuntimeLoader.legacyIdentity(item);
    }

    @Override
    public ItemStack migrate(ItemStack original) {
        requireThread();
        if (original == null) {
            return null;
        }
        if (original.isEmpty() || identity.read(original).isPresent()) {
            return original.clone();
        }
        return FormatRuntimeLoader.migrate(original);
    }

    @Override
    public ItemRebuildResult rebuild(
            ItemStack item,
            OfflinePlayer viewer,
            Map<String, String> overrides,
            Set<String> keepPdcKeys) {
        requireThread();
        RuntimeSnapshot captured = current;
        if (closed || captured == null || !captured.lifecycle().active()) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.UNAVAILABLE, "ItemLoom has no active runtime", null);
        }
        if (item == null || item.isEmpty()) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.EMPTY_ITEM, "Item is empty", null);
        }
        if (keepPdcKeys != null && keepPdcKeys.stream().anyMatch(Objects::isNull)) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.INVALID_ARGUMENT,
                    "keepPdcKeys cannot contain null",
                    null);
        }
        Optional<ItemIdentity> found;
        try {
            found = identify(item);
        } catch (RuntimeException unreadable) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.CORRUPT_IDENTITY,
                    "ItemLoom identity is unreadable",
                    unreadable);
        }
        Optional<ItemRebuildResult> rejected = ItemRebuilds.rejection(found, ids());
        if (rejected.isPresent()) {
            return rejected.get();
        }
        ItemIdentity old = found.get();
        if (providers.contains(old.id())) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.UNSUPPORTED_FORMAT,
                    "Provider items cannot be rebuilt: " + old.id(),
                    null);
        }
        ItemDefinitions owner;
        try {
            owner = rebuildOwner(captured, old.id());
        } catch (IllegalArgumentException conflicting) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.GENERATION_FAILED,
                    conflicting.getMessage(),
                    conflicting);
        }
        if (owner == null) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.UNKNOWN_ID,
                    "Item id is not defined: " + old.id(),
                    null);
        }
        Map<String, String> changes = overrides == null ? Map.of() : overrides;
        Map<String, String> rolls;
        try {
            rolls = ItemRebuilds.mergeRolls(old.rolls(), changes);
        } catch (IllegalArgumentException invalid) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.INVALID_ARGUMENT, invalid.getMessage(), invalid);
        }
        ItemStack before = item.clone();
        ItemStack candidate;
        try {
            ItemStack generated = create(old.id(), viewer, rolls);
            if (generated == null || generated.isEmpty()) {
                return ItemRebuildResult.failed(
                        ItemRebuildResult.Status.GENERATION_FAILED,
                        "Generation produced no item for " + old.id(),
                        null);
            }
            ItemStack protectedItem = owner.protectRebuild(old.id(), before, generated);
            candidate = ItemRebuilds.preserveRuntime(before, protectedItem, keepPdcKeys);
            ItemIdentity rebuilt = identify(candidate).orElse(null);
            if (rebuilt == null || !old.id().equals(rebuilt.id())) {
                return ItemRebuildResult.failed(
                        ItemRebuildResult.Status.GENERATION_FAILED,
                        "Rebuilt item lost its ItemLoom identity: " + old.id(),
                        null);
            }
            String missing = RebuildRules.missingOverride(rebuilt.rolls(), changes);
            if (missing != null) {
                return ItemRebuildResult.failed(
                        ItemRebuildResult.Status.GENERATION_FAILED,
                        "Rebuilt item does not carry the requested roll: " + missing,
                        null);
            }
        } catch (RuntimeException failure) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.GENERATION_FAILED,
                    "Could not rebuild " + old.id() + ": " + failure.getMessage(),
                    failure);
        }
        if (closed || current != captured) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.UNAVAILABLE,
                    "ItemLoom runtime changed during rebuild",
                    null);
        }
        if (!item.equals(before)) {
            return ItemRebuildResult.failed(
                    ItemRebuildResult.Status.ITEM_CHANGED,
                    "Item changed while the rebuild was being prepared",
                    null);
        }
        return ItemRebuildResult.rebuilt(candidate);
    }

    private static ItemDefinitions rebuildOwner(RuntimeSnapshot captured, String id) {
        ItemDefinitions selected = null;
        for (ItemDefinitions format : captured.itemFormats()) {
            if (format.publication().ids().contains(id)) {
                if (selected != null) {
                    throw conflict(id);
                }
                selected = format;
            }
        }
        return selected;
    }

    private RuntimeSnapshot requireLoaded() {
        requireThread();
        RuntimeSnapshot captured = current;
        if (closed || captured == null || !captured.lifecycle().active()) {
            throw new IllegalStateException("ItemLoom has no active runtime");
        }
        return captured;
    }

    private static Set<String> configuredIds(RuntimeSnapshot runtime, boolean validate) {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemDefinitions format : runtime.itemFormats()) {
            for (String id : format.publication().ids()) {
                if (!ids.add(id) && validate) {
                    throw conflict(id);
                }
            }
        }
        return ids;
    }

    private static IllegalArgumentException conflict(String id) {
        return new IllegalArgumentException("Item is defined by multiple sources: " + id);
    }

    private static Map<String, String> copyRolls(Map<String, String> input) {
        return input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
    }

    public static void requireThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ItemLoom requires the primary server thread");
        }
    }

    @Override
    public void close() {
        requireThread();
        if (closed) {
            return;
        }
        closed = true;
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        cleanup(bags::close, "loot bags");
        cleanup(providers::close, "providers");
        cleanup(visibility::close, "drop visibility");
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(events);
        HandlerList.unregisterAll(restrictions);
        HandlerList.unregisterAll(ownership);
        HandlerList.unregisterAll(secondaryMaintenance);
        cleanup(secondaryMaintenance::close, "secondary maintenance");
        cleanup(display::close, "display rendering");
        RuntimeSnapshot previous = current;
        if (previous != null) {
            cleanup(previous.lifecycle()::serverStopping, "server shutdown callbacks");
        }
        if (installed != null) {
            cleanup(installed::close, "runtime resources");
            installed = null;
        }
        current = null;
        cleanup(() -> players.returnLedger().flushAll(), "return ledger");
        cleanup(players::close, "player state");
    }

    private void cleanup(Runnable action, String label) {
        try {
            action.run();
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.WARNING, "Could not close " + label, failure);
        }
    }
}

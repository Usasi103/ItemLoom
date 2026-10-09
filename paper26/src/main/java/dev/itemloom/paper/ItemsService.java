package dev.itemloom.paper;

import dev.keystone.config.ReloadTransaction;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemsService implements ItemLoom, AutoCloseable, org.bukkit.event.Listener {
    private final JavaPlugin plugin;
    private final Path inputRoot;
    private final BiFunction<Object, String, String> placeholders;
    private long installedRevision;
    private final ItemStateCodec codec = new ItemStateCodec();
    private final NiItemMigration migration = new NiItemMigration();
    private final dev.itemloom.paper.action.PlayerActionState players =
            new dev.itemloom.paper.action.PlayerActionState();
    private boolean listening;

    private record Catalogs(NiCatalog ni, dev.itemloom.paper.sx.SxCatalog sx) {}

    private volatile Catalogs current;

    private NiCatalog activeNi() {
        Catalogs value = current;
        return value == null ? null : value.ni();
    }

    private dev.itemloom.paper.sx.SxCatalog activeSx() {
        Catalogs value = current;
        return value == null ? null : value.sx();
    }

    private final dev.itemloom.paper.sx.SxMaintenance sxMaintenance;
    private final dev.itemloom.paper.compat.NiPlaceholderRequests placeholderRequests =
            new dev.itemloom.paper.compat.NiPlaceholderRequests(() -> activeNi());
    private final dev.itemloom.paper.action.ItemListeners events;
    private final dev.itemloom.paper.action.DropVisibility visibility;
    private final dev.itemloom.paper.display.ItemDisplayService display;
    private final dev.itemloom.paper.action.DropOwnership ownership =
            new dev.itemloom.paper.action.DropOwnership(
                    () -> activeNi() == null ? null : activeNi().input().settings());
    private dev.keystone.task.Task ticker;
    private int visibilityTicks;
    private boolean closed;
    private boolean reloading;
    private final ItemProviders providers = new ItemProviders();
    private Map<String, Integer> usageRules = Map.of();
    private dev.itemloom.paper.action.LootBagConfig bagRules;
    private final dev.itemloom.paper.action.LootBags bags;
    private final dev.itemloom.paper.action.ItemUseRestrictions usage =
            new dev.itemloom.paper.action.ItemUseRestrictions(() -> usageRules);

    public ItemsService(
            JavaPlugin plugin, Path inputRoot, BiFunction<Object, String, String> placeholders) {
        this(
                plugin,
                inputRoot,
                placeholders,
                plugin.getDataFolder().toPath().resolve("return-ledger.json"));
    }

    /** Explicit owned storage permits isolated services without sharing a plugin's return records. */
    public ItemsService(
            JavaPlugin plugin,
            Path inputRoot,
            BiFunction<Object, String, String> placeholders,
            Path returnLedgerFile) {
        this.plugin = plugin;
        this.inputRoot = inputRoot;
        this.placeholders = placeholders;
        sxMaintenance = new dev.itemloom.paper.sx.SxMaintenance(this::activeSx, plugin.getLogger());
        visibility = new dev.itemloom.paper.action.DropVisibility(plugin);
        display = new dev.itemloom.paper.display.ItemDisplayService(plugin, () -> activeNi());
        players.initializeReturns(plugin, returnLedgerFile);
        bags = new dev.itemloom.paper.action.LootBags(plugin, this, players, () -> bagRules);
        events =
                new dev.itemloom.paper.action.ItemListeners(
                        () -> activeNi() == null ? null : activeNi().triggers(),
                        () -> activeNi() == null ? null : activeNi().maintenance(),
                        plugin.getLogger(),
                        bags::interact);
    }

    public boolean reload(CommandSender sender) {
        requireThread();
        if (closed) throw new IllegalStateException("Item service is closed");
        if (reloading) throw new IllegalStateException("Item reload is already in progress");
        reloading = true;
        try (ReloadTransaction transaction = ReloadTransaction.begin()) {
            NiCatalog candidate;
            dev.itemloom.paper.sx.SxCatalog candidateSx;
            Map<String, Integer> candidateRules;
            dev.itemloom.paper.action.LootBagConfig candidateBags;
            try {
                NiRepository repository = new NiRepository();
                NiRepository.Input input =
                        Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                                ? repository.read(
                                        inputRoot,
                                        dev.itemloom.paper.integration.PapiBridge::itemSections)
                                : repository.read(inputRoot);
                candidateRules =
                        dev.itemloom.paper.action.ItemUseRestrictions.read(input.settings());
                candidateBags = dev.itemloom.paper.action.LootBagConfig.read(input);
                candidate =
                        new NiCatalog(
                                installedRevision + 1,
                                input,
                                inputRoot,
                                plugin,
                                placeholders,
                                players);
                transaction.onRollback(candidate::close);
                var sxRepository = new dev.itemloom.compat.sx.SxRepository();
                Path sxRoot = inputRoot.resolve("SX-Item");
                var sxInput = sxRepository.read(sxRoot);
                candidateSx = new dev.itemloom.paper.sx.SxCatalog(sxInput, plugin, placeholders);
                transaction.onRollback(candidateSx::close);
                Set<String> configured =
                        new java.util.LinkedHashSet<>(candidate.catalog().recipes().keySet());
                for (String id : candidateSx.ids())
                    if (!configured.add(id))
                        throw new IllegalArgumentException("NI/SX item ID conflict: " + id);
                providers.validate(configured);
                var rules = new java.util.LinkedHashMap<>(candidateRules);
                rules.putAll(candidateSx.restrictions());
                candidateRules = Map.copyOf(rules);
                sxRepository.verifyUnchanged(sxRoot, sxInput);
                repository.verifyUnchanged(inputRoot, input);
            } catch (Exception error) {
                transaction.problem(inputRoot.toFile(), inputRoot.toString(), error);
                transaction.commit(sender);
                return false;
            }
            if (!transaction.commit(sender)) return false;
            Catalogs previousCatalogs = current;
            NiCatalog previous = previousCatalogs == null ? null : previousCatalogs.ni();
            if (candidate.catalog().revision() <= installedRevision) {
                candidate.close();
                candidateSx.close();
                throw new IllegalArgumentException("Catalog revision must advance");
            }
            installedRevision = candidate.catalog().revision();
            current = new Catalogs(candidate, candidateSx);
            usageRules = candidateRules;
            bagRules = candidateBags;
            players.configure(
                    candidate
                            .input()
                            .settings()
                            .bool("ItemAction.resetCooldownWhenPlayerQuit", true));
            if (!listening) {
                listening = true;
                Bukkit.getOnlinePlayers().forEach(players::join);
                Bukkit.getPluginManager().registerEvents(this, plugin);
                Bukkit.getPluginManager().registerEvents(sxMaintenance, plugin);
                Bukkit.getPluginManager().registerEvents(events, plugin);
                Bukkit.getPluginManager().registerEvents(usage, plugin);
                Bukkit.getPluginManager().registerEvents(ownership, plugin);
                Bukkit.getPluginManager().registerEvents(visibility, plugin);
                visibility.start();
                display.start();
                ticker =
                        dev.keystone.task.Tasks.submit(
                                false,
                                false,
                                1,
                                1,
                                ignored -> {
                                    events.tick();
                                    sxMaintenance.tick();
                                    if (++visibilityTicks == 10) {
                                        visibilityTicks = 0;
                                        visibility.tick();
                                    }
                                });
            }
            if (previousCatalogs != null) previousCatalogs.sx().close();
            if (previous != null) previous.close();
            sxMaintenance.refresh();
            if (activeNi() == candidate && candidate.active()) candidate.activate();
            Bukkit.getPluginManager()
                    .callEvent(
                            new dev.itemloom.api.ItemsReloadEvent(
                                    installedRevision, previous == null));
            return true;
        } finally {
            reloading = false;
        }
    }

    public void serverEnabled() {
        if (activeNi() != null) activeNi().serverEnabled();
    }

    public String requestPlaceholder(OfflinePlayer viewer, String parameters) {
        return placeholderRequests.request(viewer, parameters);
    }

    /** Immutable publication identity, safe to compare on workers; local item reload also changes it. */
    public Object placeholderRevision() {
        NiCatalog current = activeNi();
        return current == null ? null : current.registry().publicationToken();
    }

    public Object providerRevision() {
        return providers.revision();
    }

    @org.bukkit.event.EventHandler
    public void joined(org.bukkit.event.player.PlayerJoinEvent event) {
        players.join(event.getPlayer());
    }

    @org.bukkit.event.EventHandler
    public void left(org.bukkit.event.player.PlayerQuitEvent event) {
        bags.quit(event.getPlayer().getUniqueId());
        if (activeNi() != null) activeNi().triggers().flushReturns(event.getPlayer());
        players.returnLedger().flush(event.getPlayer());
        players.quit(event.getPlayer().getUniqueId());
    }

    @org.bukkit.event.EventHandler
    public void providerDisabled(org.bukkit.event.server.PluginDisableEvent event) {
        providers.remove(event.getPlugin());
    }

    private Set<String> configuredIds() {
        Catalogs snapshot = current;
        if (snapshot == null) return Set.of();
        Set<String> ids = new java.util.LinkedHashSet<>(snapshot.ni().catalog().recipes().keySet());
        ids.addAll(snapshot.sx().ids());
        return Set.copyOf(ids);
    }

    @Override
    public Set<String> ids() {
        Set<String> result = new java.util.LinkedHashSet<>(configuredIds());
        result.addAll(providers.ids(false));
        return Set.copyOf(result);
    }

    @Override
    public ItemStack create(String id, OfflinePlayer viewer, Map<String, String> savedRolls) {
        requireThread();
        if (closed || activeNi() == null)
            throw new IllegalStateException("Item service is not ready");
        NiCatalog revision = activeNi();
        var sx = activeSx();
        if (sx.contains(id)) {
            if (revision.catalog().recipes().containsKey(id) || providers.contains(id))
                throw new IllegalStateException("SX item ID conflict: " + id);
            return sx.generate(id, viewer, savedRolls);
        }
        if (providers.contains(id)) {
            if (revision.catalog().recipes().containsKey(id))
                throw new IllegalStateException("Item conflicts with external provider: " + id);
            return providers.create(id, viewer, savedRolls);
        }
        return revision.generate(id, viewer, savedRolls, false);
    }

    @Override
    public dev.itemloom.api.ProviderRegistration registerProvider(
            org.bukkit.plugin.Plugin owner,
            String namespace,
            dev.itemloom.api.ItemProvider provider) {
        requireThread();
        if (closed || activeNi() == null)
            throw new IllegalStateException("Item service is not ready");
        return providers.register(owner, namespace, provider, configuredIds());
    }

    @Override
    public Set<String> groupIds() {
        return providers.ids(true);
    }

    @Override
    public java.util.List<ItemStack> createGroup(
            String id, OfflinePlayer viewer, Map<String, String> savedRolls) {
        requireThread();
        if (closed || activeNi() == null)
            throw new IllegalStateException("Item service is not ready");
        providers.validate(configuredIds());
        return providers.group(id, viewer, savedRolls);
    }

    @Override
    public SaveResult save(ItemStack item, String id, String path, boolean replace) {
        requireThread();
        if (closed || activeNi() == null)
            throw new IllegalStateException("Item service is not ready");
        if (providers.contains(id) || activeSx().contains(id)) return SaveResult.CONFLICT;
        if (path == null || !(path.endsWith(".yml") || path.endsWith(".yaml")))
            throw new IllegalArgumentException("Item path must end in .yml or .yaml");
        return SaveResult.valueOf(
                dev.itemloom.paper.compat.NiItemFiles.save(
                                activeNi().items(), item, id, path, replace)
                        .name());
    }

    @Override
    public Set<String> packIds() {
        requireThread();
        return activeNi() == null ? Set.of() : Set.copyOf(activeNi().packs().getItemPackIdsRaw());
    }

    @Override
    public java.util.List<ItemStack> createPack(
            String id, OfflinePlayer viewer, Map<String, String> savedRolls) {
        return createPack(id, viewer, savedRolls, null);
    }

    public java.util.List<ItemStack> createPack(
            String id,
            OfflinePlayer viewer,
            Map<String, String> savedRolls,
            dev.itemloom.core.GenerationBudget budget) {
        requireThread();
        if (closed || activeNi() == null)
            throw new IllegalStateException("Item service is not ready");
        var pack = activeNi().packs().getItemPack(id);
        if (pack == null) throw new IllegalArgumentException("Unknown item pack: " + id);
        return pack.getItemStacks(
                viewer, savedRolls == null ? null : new java.util.HashMap<>(savedRolls), budget);
    }

    @Override
    public java.util.concurrent.CompletionStage<dev.itemloom.core.ActionFlow.Result> runFunction(
            String id, Object caster, Map<String, Object> parameters) {
        requireThread();
        if (closed || activeNi() == null)
            throw new IllegalStateException("Item service is not ready");
        return activeNi().runFunction(id, caster, parameters);
    }

    @Override
    public Optional<ItemIdentity> identify(ItemStack item) {
        requireThread();
        if (item == null || item.isEmpty()) return Optional.empty();
        Optional<ItemIdentity> identity = codec.read(item);
        return identity.isPresent() ? identity : migration.identify(item);
    }

    @Override
    public ItemStack migrate(ItemStack original) {
        requireThread();
        return migration.convert(original);
    }

    public static void requireThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item operation requires the server thread");
    }

    @Override
    public void close() {
        requireThread();
        if (closed) return;
        closed = true;
        sxMaintenance.close();
        if (activeSx() != null) activeSx().close();
        bags.close();
        bagRules = null;
        providers.close();
        usageRules = Map.of();
        org.bukkit.event.HandlerList.unregisterAll(usage);
        org.bukkit.event.HandlerList.unregisterAll(this);
        org.bukkit.event.HandlerList.unregisterAll(events);
        org.bukkit.event.HandlerList.unregisterAll(ownership);
        if (ticker != null) ticker.cancel();
        visibility.close();
        display.close();
        try {
            if (activeNi() != null) {
                try {
                    activeNi().serverStopping();
                } finally {
                    activeNi().close();
                    current = null;
                }
            }
        } finally {
            try {
                players.returnLedger().flushAll();
            } finally {
                players.close();
            }
        }
    }
}

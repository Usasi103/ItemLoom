package dev.itemloom.paper.runtime;

import dev.itemloom.api.ItemLoom;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.core.GenerationBudget;
import dev.itemloom.paper.action.ItemListeners;
import dev.itemloom.paper.action.ItemUseRestrictions;
import dev.itemloom.paper.action.LootBagConfig;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemFiles;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.NiPlaceholderRequests;
import dev.itemloom.paper.compat.script.LegacyItemExpirationEvent;
import dev.itemloom.paper.compat.script.LegacyItemInfo;
import dev.itemloom.paper.compat.script.LegacyItemPlaceholder;
import dev.itemloom.paper.integration.PapiBridge;
import dev.itemloom.paper.sx.SxCatalog;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.core.component.DataComponents;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** The sole composition boundary from format-owned implementations to neutral capabilities. */
public final class FormatRuntimeLoader implements RuntimeLoader {
    private final JavaPlugin plugin;
    private final Path inputRoot;
    private final BiFunction<Object, String, String> placeholders;
    private final PlayerActionState players;
    private final Predicate<PlayerInteractEvent> bags;

    public FormatRuntimeLoader(
            JavaPlugin plugin,
            Path inputRoot,
            BiFunction<Object, String, String> placeholders,
            PlayerActionState players,
            Predicate<PlayerInteractEvent> bags) {
        this.plugin = plugin;
        this.inputRoot = inputRoot;
        this.placeholders = placeholders;
        this.players = players;
        this.bags = bags;
    }

    @Override
    public PreparedRuntime prepare(long nextRevision) throws Exception {
        NiRepository niRepository = new NiRepository();
        UnaryOperator<String> transform =
                Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                        ? PapiBridge::itemSections
                        : UnaryOperator.identity();
        NiRepository.Input niInput = niRepository.read(inputRoot, transform);
        Map<String, Integer> restrictions =
                new LinkedHashMap<>(ItemUseRestrictions.read(niInput.settings()));
        LootBagConfig lootBags = LootBagConfig.read(niInput);
        NiCatalog ni =
                new NiCatalog(nextRevision, niInput, inputRoot, plugin, placeholders, players);
        SxCatalog sx = null;
        try {
            Path sxRoot = inputRoot.resolve("SX-Item");
            SxRepository sxRepository = new SxRepository();
            SxRepository.Input sxInput = sxRepository.read(sxRoot);
            sx = new SxCatalog(sxInput, plugin, placeholders, ni.itemSources());
            for (String id : sx.ids()) {
                if (ni.catalog().recipes().containsKey(id)) {
                    throw new IllegalArgumentException(
                            "Item is defined by multiple formats: " + id);
                }
            }
            restrictions.putAll(sx.restrictions());
            RuntimePolicies policies =
                    new RuntimePolicies(
                            restrictions,
                            lootBags,
                            niInput.settings().bool("ItemAction.resetCooldownWhenPlayerQuit", true),
                            new DropOwnershipPolicy(
                                    niInput.settings().string("Messages.invalidOwnerMessage"),
                                    niInput.settings().string("ItemOwner.messageType", "")));
            return new Candidate(
                    nextRevision,
                    ni,
                    sx,
                    policies,
                    () -> {
                        niRepository.verifyUnchanged(inputRoot, niInput);
                        sxRepository.verifyUnchanged(sxRoot, sxInput);
                    });
        } catch (Throwable failure) {
            if (sx != null) {
                try {
                    sx.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            try {
                ni.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static MaintenanceAccess maintenance(NiCatalog owner) {
        var settings = owner.input().settings();
        MaintenanceOptions options =
                new MaintenanceOptions(
                        settings.bool("ItemCheck.checkInventory", true),
                        settings.bool("ItemDurability.forceSync", false),
                        settings.string("Messages.itemExpirationMessage"));
        return new MaintenanceAccess() {
            @Override
            public boolean active() {
                return owner.active();
            }

            @Override
            public MaintenanceOptions options() {
                return options;
            }

            @Override
            public Integer updateHash(String id) {
                return owner.registry().updateHash(id);
            }

            @Override
            public void update(Player player, ItemStack item, boolean force, boolean sendMessage) {
                owner.items().update(player, item, force, sendMessage);
            }

            @Override
            public String itemName(ItemStack item) {
                return owner.itemName(item);
            }

            @Override
            public boolean expirationAllowed(Player player, ItemStack item) {
                LegacyItemExpirationEvent event =
                        new LegacyItemExpirationEvent(player, item, LegacyItemInfo.inspect(item));
                Bukkit.getPluginManager().callEvent(event);
                return !event.isCancelled();
            }
        };
    }

    public static DurabilityCallbacks durability(NiCatalog owner) {
        return new DurabilityCallbacks() {
            @Override
            public boolean active() {
                return owner.active();
            }

            @Override
            public void broken(Player player) {
                owner.triggers().broken(player);
            }

            @Override
            public void returnLater(Player player, ItemStack remainder) {
                owner.triggers().returnLater(player, remainder);
            }
        };
    }

    public static Optional<dev.itemloom.core.ItemIdentity> legacyIdentity(ItemStack item) {
        return new NiItemMigration().identify(item);
    }

    public static ItemStack migrate(ItemStack item) {
        return new NiItemMigration().convert(item);
    }

    @FunctionalInterface
    private interface SourceFence {
        void verify() throws Exception;
    }

    private final class Candidate implements PreparedRuntime, RevisionLifecycle {
        private final NiCatalog ni;
        private final SxCatalog sx;
        private final RuntimeSnapshot snapshot;
        private final SourceFence sources;
        private boolean closed;

        private Candidate(
                long revision,
                NiCatalog ni,
                SxCatalog sx,
                RuntimePolicies policies,
                SourceFence sources) {
            this.ni = ni;
            this.sx = sx;
            this.sources = sources;
            ItemDefinitions primary =
                    new ItemDefinitions() {
                        @Override
                        public ItemPublication publication() {
                            while (true) {
                                Object token = ni.registry().publicationToken();
                                Set<String> ids = ni.catalog().recipes().keySet();
                                if (token == ni.registry().publicationToken()) {
                                    return new ItemPublication(token, ids);
                                }
                            }
                        }

                        @Override
                        public ItemStack create(
                                String id, OfflinePlayer viewer, Map<String, String> rolls) {
                            return ni.generate(id, viewer, rolls, false);
                        }

                        @Override
                        public ItemStack protectRebuild(
                                String id, ItemStack before, ItemStack generated) {
                            return ni.items().protectRebuild(id, before, generated);
                        }
                    };
            ItemPublication secondaryPublication = new ItemPublication(new Object(), sx.ids());
            ItemDefinitions secondary =
                    new ItemDefinitions() {
                        @Override
                        public ItemPublication publication() {
                            return secondaryPublication;
                        }

                        @Override
                        public ItemStack create(
                                String id, OfflinePlayer viewer, Map<String, String> rolls) {
                            return sx.generate(id, viewer, rolls);
                        }

                        @Override
                        public ItemStack protectRebuild(
                                String id, ItemStack before, ItemStack generated) {
                            return sx.protectRebuild(id, before, generated);
                        }
                    };
            PackAccess packs =
                    new PackAccess() {
                        @Override
                        public Set<String> ids() {
                            return Set.copyOf(ni.packs().getItemPackIds());
                        }

                        @Override
                        public List<ItemStack> create(
                                String id,
                                OfflinePlayer viewer,
                                Map<String, String> rolls,
                                GenerationBudget budget) {
                            var pack = ni.packs().getItemPack(id);
                            if (pack == null) {
                                throw new IllegalArgumentException("Unknown item pack: " + id);
                            }
                            return pack.getItemStacks(viewer, rolls, budget);
                        }
                    };
            ItemPersistence persistence =
                    new ItemPersistence() {
                        @Override
                        public boolean owns(ItemDefinitions format) {
                            return format == primary;
                        }

                        @Override
                        public ItemLoom.SaveResult save(
                                ItemStack item, String id, String path, boolean replace) {
                            return ItemLoom.SaveResult.valueOf(
                                    NiItemFiles.save(ni.items(), item, id, path, replace).name());
                        }
                    };
            NiPlaceholderRequests requests =
                    new NiPlaceholderRequests(() -> ni.active() ? ni : null);
            PlaceholderAccess placeholders =
                    new PlaceholderAccess() {
                        @Override
                        public Object publicationToken() {
                            return ni.registry().publicationToken();
                        }

                        @Override
                        public String request(OfflinePlayer viewer, String parameters) {
                            return requests.request(viewer, parameters);
                        }
                    };
            UpdateQueueAccess updates =
                    new UpdateQueueAccess() {
                        @Override
                        public boolean hasUpdates() {
                            return active() && sx.hasUpdates();
                        }

                        @Override
                        public boolean update(Player viewer, ItemStack item) {
                            return active() && sx.update(viewer, item);
                        }
                    };
            snapshot =
                    new RuntimeSnapshot(
                            revision,
                            List.of(secondary, primary),
                            packs,
                            ni::runFunction,
                            persistence,
                            placeholders,
                            display(ni),
                            maintenance(ni),
                            updates,
                            policies,
                            this,
                            events(ni));
        }

        @Override
        public RuntimeSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public void verifySourcesUnchanged() throws Exception {
            sources.verify();
        }

        @Override
        public void activate() {
            if (active()) {
                ni.activate();
            }
        }

        @Override
        public boolean active() {
            return !closed && ni.active();
        }

        @Override
        public void serverEnabled() {
            if (active()) ni.serverEnabled();
        }

        @Override
        public void serverStopping() {
            if (active()) ni.serverStopping();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                sx.close();
            } finally {
                ni.close();
            }
        }
    }

    public static DisplayAccess display(NiCatalog owner) {
        boolean enabled = owner.input().settings().bool("ItemPlaceholder.enable", false);
        return new DisplayAccess() {
            private volatile DisplayPublication published;

            @Override
            public boolean active() {
                return owner.active();
            }

            @Override
            public DisplayPublication publication() {
                while (true) {
                    Object token = owner.registry().publicationToken();
                    DisplayPublication cached = published;
                    if (cached != null && cached.token() == token) {
                        return cached;
                    }
                    Map<String, DisplayRenderer> renderers = new LinkedHashMap<>();
                    owner.registry()
                            .displays()
                            .forEach(
                                    (id, template) ->
                                            renderers.put(
                                                    id,
                                                    (viewer, item) ->
                                                            owner.display(template, viewer, item)));
                    if (token == owner.registry().publicationToken()) {
                        DisplayPublication next = new DisplayPublication(token, renderers, enabled);
                        published = next;
                        return next;
                    }
                }
            }

            @Override
            public boolean hasItemPlaceholders(net.minecraft.world.item.ItemStack item) {
                if (!LegacyItemPlaceholder.definitelyStatic(item.get(DataComponents.CUSTOM_NAME), 0)
                        || !LegacyItemPlaceholder.definitelyStatic(
                                item.get(DataComponents.ITEM_NAME), 0)) {
                    return true;
                }
                var lore = item.get(DataComponents.LORE);
                return lore != null
                        && (lore.lines().stream()
                                        .anyMatch(
                                                line ->
                                                        !LegacyItemPlaceholder.definitelyStatic(
                                                                line, 0))
                                || lore.styledLines().stream()
                                        .anyMatch(
                                                line ->
                                                        !LegacyItemPlaceholder.definitelyStatic(
                                                                line, 0)));
            }

            @Override
            public void parseItemPlaceholders(ItemStack detachedCopy) {
                owner.itemPlaceholders().itemParse(detachedCopy);
            }
        };
    }

    private RuntimeEvents events(NiCatalog owner) {
        ItemListeners listener =
                new ItemListeners(
                        () -> owner.active() ? owner.triggers() : null,
                        () -> owner.active() ? owner.maintenance() : null,
                        plugin.getLogger(),
                        bags);
        return new RuntimeEvents() {
            @Override
            public void interact(PlayerInteractEvent event) {
                listener.interact(event);
            }

            @Override
            public void consume(PlayerItemConsumeEvent event) {
                listener.consume(event);
            }

            @Override
            public void drop(PlayerDropItemEvent event) {
                listener.drop(event);
            }

            @Override
            public void pickup(EntityPickupItemEvent event) {
                listener.pickup(event);
            }

            @Override
            public void click(InventoryClickEvent event) {
                listener.click(event);
            }

            @Override
            public void shoot(EntityShootBowEvent event) {
                listener.shoot(event);
            }

            @Override
            public void attack(EntityDamageByEntityEvent event) {
                listener.attack(event);
            }

            @Override
            public void damaged(EntityDamageEvent event) {
                listener.damaged(event);
            }

            @Override
            public void killed(EntityDamageByEntityEvent event) {
                listener.killed(event);
            }

            @Override
            public void broken(BlockBreakEvent event) {
                listener.broken(event);
            }

            @Override
            public void tick() {
                listener.tick();
            }

            @Override
            public void flushReturns(Player player) {
                owner.triggers().flushReturns(player);
            }
        };
    }
}

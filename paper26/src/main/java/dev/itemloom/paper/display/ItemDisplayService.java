package dev.itemloom.paper.display;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import dev.itemloom.api.ItemDisplayEvent;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.script.LegacyItemPacketEvent;
import dev.itemloom.paper.compat.script.LegacyItemPlaceholder;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Catalog-independent connections; reload swaps templates but keeps proofs for in-flight clicks. */
public final class ItemDisplayService implements Listener, AutoCloseable {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private final JavaPlugin plugin;
    private final Supplier<NiCatalog> catalog;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final ReturnGuard returnGuard;
    private final String handlerName = "itemloom_display_" + UUID.randomUUID();
    private volatile boolean closed;
    private long nextWarning;

    private static final class Session {
        final Player player;
        final Channel channel;
        final DisplayLedger ledger = new DisplayLedger();
        volatile boolean ordinary;

        Session(Player player, Channel channel) {
            this.player = player;
            this.channel = channel;
            ordinary = ordinary(player.getGameMode());
        }
    }

    /** Reads the same immutable registry publication as outbound rendering, including script map edits. */
    private static final class ReturnGuard implements Predicate<ItemStack> {
        private volatile Supplier<Set<String>> ids;

        ReturnGuard(Supplier<NiCatalog> catalogs) {
            ids =
                    () -> {
                        NiCatalog current = catalogs.get();
                        return current == null ? Set.of() : current.registry().displays().keySet();
                    };
        }

        @Override
        public boolean test(ItemStack stack) {
            try {
                String id = CODEC.readId(stack);
                return id != null && ids.get().contains(id);
            } catch (IllegalArgumentException malformed) {
                return true;
            }
        }

        void freeze() {
            Set<String> snapshot = Set.copyOf(ids.get());
            ids = () -> snapshot; // Detach catalog/plugin references while guarding late
            // returns until disconnect.
        }
    }

    public ItemDisplayService(JavaPlugin plugin, Supplier<NiCatalog> catalog) {
        this.plugin = plugin;
        this.catalog = catalog;
        returnGuard = new ReturnGuard(catalog);
    }

    public void start() {
        ItemsService.requireThread();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::attach);
    }

    private static boolean ordinary(GameMode mode) {
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer())
            sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer()) {
            session.ordinary = ordinary(event.getNewGameMode());
            Bukkit.getScheduler().runTask(plugin, () -> refresh(session));
        }
    }

    private void refresh(Session session) {
        ItemsService.requireThread();
        if (!closed
                && session.channel.isActive()
                && sessions.get(session.player.getUniqueId()) == session)
            session.player.updateInventory();
    }

    private void attach(Player player) {
        if (closed) return;
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        if (channel == null) return;
        Session existing = sessions.get(player.getUniqueId());
        if (existing != null && existing.player == player && existing.channel == channel) return;
        Session session = new Session(player, channel);
        sessions.put(player.getUniqueId(), session);
        OrderedDisplayHandler handler =
                new OrderedDisplayHandler(
                        packet -> wanted(session, packet),
                        (packet, live) -> render(session, packet, live),
                        task -> Bukkit.getScheduler().runTask(plugin, task),
                        this::warn,
                        () -> refresh(session),
                        returnGuard,
                        session.ledger);
        channel.eventLoop()
                .execute(
                        () -> {
                            if (closed || !channel.isActive()) return;
                            if (channel.pipeline().context("packet_handler") == null) {
                                warn(
                                        new IllegalStateException(
                                                "Missing packet_handler for " + player.getName()));
                                return;
                            }
                            channel.pipeline().addBefore("packet_handler", handlerName, handler);
                            // PlayerList initializes the inventory before PlayerJoinEvent on 26.2.
                            // Correct that
                            // first canonical send once the handler is installed; no periodic
                            // inventory refresh.
                            try {
                                Bukkit.getScheduler().runTask(plugin, () -> refresh(session));
                            } catch (RuntimeException failure) {
                                if (!closed) warn(failure);
                            }
                        });
    }

    private boolean wanted(Session session, Object packet) {
        NiCatalog current = catalog.get();
        if (closed || current == null) return false;
        var templates = current.registry().displays();
        boolean listeners = ItemDisplayEvent.getHandlerList().getRegisteredListeners().length != 0;
        boolean placeholders = current.input().settings().bool("ItemPlaceholder.enable", false);
        if (templates.isEmpty() && !listeners && !placeholders) return false;
        return ItemPackets.any(
                packet,
                (stack, inventory) -> {
                    if (stack.isEmpty()) return false;
                    String id = CODEC.readId(stack);
                    if (id != null && templates.containsKey(id)) return true;
                    if (!inventory || !session.ordinary) return false;
                    return listeners || placeholders && hasPlaceholder(stack);
                });
    }

    private static boolean hasPlaceholder(ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null && !LegacyItemPlaceholder.definitelyStatic(name, 0)) return true;
        var lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (var line : lore.lines())
                if (!LegacyItemPlaceholder.definitelyStatic(line, 0)) return true;
            for (var line : lore.styledLines())
                if (!LegacyItemPlaceholder.definitelyStatic(line, 0)) return true;
        }
        return false;
    }

    private OrderedDisplayHandler.Rendered render(
            Session session, Object packet, java.util.function.BooleanSupplier live) {
        ItemsService.requireThread();
        NiCatalog current = catalog.get();
        if (closed
                || current == null
                || !current.active()
                || sessions.get(session.player.getUniqueId()) != session)
            return OrderedDisplayHandler.Rendered.unchanged(packet);
        var sent = new ArrayList<DisplayLedger.Sent>();
        Object rendered =
                ItemPackets.map(
                        packet,
                        (original, inventory) -> {
                            if (!live.getAsBoolean())
                                throw new java.util.concurrent.CancellationException(
                                        "Display batch cancelled");
                            if (original.isEmpty()) return original;
                            String id = CODEC.readId(original);
                            var template = current.displayTemplate(id);
                            boolean ordinary = inventory && ordinary(session.player.getGameMode());
                            boolean placeholders =
                                    ordinary
                                            && current.input()
                                                    .settings()
                                                    .bool("ItemPlaceholder.enable", false)
                                            && hasPlaceholder(original);
                            boolean listeners =
                                    ordinary
                                            && ItemDisplayEvent.getHandlerList()
                                                            .getRegisteredListeners()
                                                            .length
                                                    != 0;
                            if (template == null && !placeholders && !listeners) return original;
                            var copy = CraftItemStack.asCraftMirror(ProofItemCopies.copy(original));
                            if (template != null) current.display(template, session.player, copy);
                            if (ordinary) {
                                if (current.input()
                                        .settings()
                                        .bool("ItemPlaceholder.enable", false))
                                    current.itemPlaceholders().itemParse(copy);
                                if (ItemDisplayEvent.getHandlerList()
                                                .getRegisteredListeners()
                                                .length
                                        != 0)
                                    new LegacyItemPacketEvent(session.player, copy).call();
                            }
                            ItemStack display = CraftItemStack.unwrap(copy);
                            if (display.isEmpty()
                                    || display.getItem() != original.getItem()
                                    || display.getCount() != original.getCount())
                                throw new IllegalArgumentException(
                                        "ItemDisplayEvent must retain item type and count");
                            if (ItemStack.matches(original, display)) {
                                if (template != null) sent.add(DisplayLedger.canonical(original));
                                return original;
                            }
                            var proof = session.ledger.prepare(original, display);
                            sent.add(proof);
                            return proof.display();
                        });
        return new OrderedDisplayHandler.Rendered(rendered, java.util.List.copyOf(sent));
    }

    private synchronized void warn(Throwable failure) {
        long now = System.nanoTime();
        if (now < nextWarning) return;
        nextWarning = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        plugin.getLogger()
                .log(
                        java.util.logging.Level.WARNING,
                        "Item display failed; affected packets keep server data",
                        failure);
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        if (closed) return;
        closed = true;
        returnGuard.freeze();
        HandlerList.unregisterAll(this);
        for (Session session : sessions.values())
            session.channel
                    .eventLoop()
                    .execute(
                            () -> {
                                var context = session.channel.pipeline().context(handlerName);
                                if (context != null)
                                    ((OrderedDisplayHandler) context.handler())
                                            .stopRendering(context);
                            });
        sessions.clear();
    }
}

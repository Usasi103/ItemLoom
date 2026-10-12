package dev.itemloom.paper.display;

import dev.itemloom.api.ItemDisplayEvent;
import dev.itemloom.paper.compat.script.LegacyItemPacketEvent;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.runtime.DisplayAccess;
import dev.itemloom.paper.runtime.DisplayPublication;
import dev.itemloom.paper.runtime.DisplayRenderer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
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
import org.bukkit.scheduler.BukkitRunnable;

/** Connection lifetime and canonical-return proofs outlive individual content revisions. */
public final class ItemDisplayService implements Listener, AutoCloseable {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private final JavaPlugin plugin;
    private Supplier<DisplayAccess> current;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Session> attached = ConcurrentHashMap.newKeySet();
    private final Set<BukkitRunnable> refreshes = new HashSet<>();
    private final String handlerName = "itemloom_display_" + UUID.randomUUID();
    private boolean started;
    private volatile boolean closed;

    public ItemDisplayService(JavaPlugin plugin, Supplier<DisplayAccess> current) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.current = Objects.requireNonNull(current, "current");
    }

    public void start() {
        if (started || closed) {
            return;
        }
        started = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer()) {
            sessions.remove(event.getPlayer().getUniqueId(), session);
            session.rendering = false;
            stop(session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer()) {
            session.ordinary = ordinary(event.getNewGameMode());
            schedule(() -> refresh(session));
        }
    }

    private void attach(Player player) {
        if (closed || !(player instanceof CraftPlayer craft)) {
            return;
        }
        Channel channel = craft.getHandle().connection.connection.channel;
        if (channel == null) {
            return;
        }
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null && previous.player == player && previous.channel == channel) {
            return;
        }
        if (previous != null) {
            previous.rendering = false;
            stop(previous);
        }
        Session session = new Session(player, channel, current);
        sessions.put(player.getUniqueId(), session);
        attached.add(session);
        session.handler =
                new OrderedDisplayHandler(
                        packet -> wanted(session, packet),
                        (packet, valid) -> render(session, packet, valid),
                        this::schedule,
                        failure ->
                                plugin.getLogger()
                                        .log(Level.WARNING, "Item display packet failed", failure),
                        () -> refresh(session),
                        session.guard,
                        session.ledger);
        channel.closeFuture()
                .addListener(
                        ignored -> {
                            session.rendering = false;
                            sessions.remove(session.playerId, session);
                            attached.remove(session);
                            session.guard.freeze();
                        });
        Runnable inject =
                () -> {
                    if (closed
                            || !session.rendering
                            || sessions.get(session.playerId) != session
                            || channel.pipeline().get("packet_handler") == null) {
                        abandon(session);
                        return;
                    }
                    try {
                        channel.pipeline()
                                .addBefore("packet_handler", handlerName, session.handler);
                    } catch (RuntimeException failure) {
                        abandon(session);
                        plugin.getLogger()
                                .log(
                                        Level.WARNING,
                                        "Could not attach item display handler",
                                        failure);
                        return;
                    }
                    try {
                        schedule(() -> refresh(session));
                    } catch (RuntimeException failure) {
                        session.rendering = false;
                        session.guard.freeze();
                        stop(session);
                    }
                };
        try {
            channel.eventLoop().execute(inject);
        } catch (RuntimeException rejected) {
            abandon(session);
        }
    }

    private void refresh(Session session) {
        if (live(session)) {
            session.player.updateInventory();
        }
    }

    private void schedule(Runnable work) {
        BukkitRunnable task =
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        synchronized (refreshes) {
                            refreshes.remove(this);
                        }
                        if (!closed) {
                            work.run();
                        }
                    }
                };
        synchronized (refreshes) {
            if (closed) {
                throw new IllegalStateException("Item display service is closed");
            }
            task.runTask(plugin);
            refreshes.add(task);
        }
    }

    private boolean live(Session session) {
        return !closed
                && session.rendering
                && sessions.get(session.playerId) == session
                && session.channel.isActive();
    }

    private DisplayAccess access() {
        Supplier<DisplayAccess> supplier = current;
        return supplier == null ? null : supplier.get();
    }

    private boolean wanted(Session session, Object packet) {
        if (!live(session)) {
            return false;
        }
        DisplayAccess access = access();
        if (access == null || !access.active()) {
            return false;
        }
        DisplayPublication publication = access.publication();
        boolean listeners = ItemDisplayEvent.getHandlerList().getRegisteredListeners().length != 0;
        if (publication.templates().isEmpty()
                && !listeners
                && !publication.itemPlaceholdersEnabled()) {
            return false;
        }
        return ItemPackets.any(
                packet,
                (item, inventory) -> {
                    if (item.isEmpty()) {
                        return false;
                    }
                    String id = CODEC.readId(item);
                    return id != null && publication.templates().containsKey(id)
                            || inventory
                                    && session.ordinary
                                    && (listeners
                                            || publication.itemPlaceholdersEnabled()
                                                    && access.hasItemPlaceholders(item));
                });
    }

    private OrderedDisplayHandler.Rendered render(
            Session session, Object packet, BooleanSupplier valid) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Display rendering requires the primary server thread");
        }
        DisplayAccess access = access();
        if (!live(session) || !valid.getAsBoolean() || access == null || !access.active()) {
            return OrderedDisplayHandler.Rendered.unchanged(packet);
        }
        DisplayPublication publication = access.publication();
        var sent = new ArrayList<DisplayLedger.Sent>();
        Object rendered =
                ItemPackets.map(
                        packet,
                        (canonical, inventory) -> {
                            if (canonical.isEmpty()) {
                                return canonical;
                            }
                            String id = CODEC.readId(canonical);
                            DisplayRenderer template =
                                    id == null ? null : publication.templates().get(id);
                            boolean ordinary = inventory && ordinary(session.player.getGameMode());
                            boolean listeners =
                                    ordinary
                                            && ItemDisplayEvent.getHandlerList()
                                                            .getRegisteredListeners()
                                                            .length
                                                    != 0;
                            boolean placeholders =
                                    ordinary
                                            && publication.itemPlaceholdersEnabled()
                                            && access.hasItemPlaceholders(canonical);
                            if (template == null && !placeholders && !listeners) {
                                return canonical;
                            }
                            if (!live(session)
                                    || !valid.getAsBoolean()
                                    || access() != access
                                    || !access.active()) {
                                return canonical;
                            }
                            var detached =
                                    CraftItemStack.asCraftMirror(ProofItemCopies.copy(canonical));
                            if (template != null) {
                                template.apply(session.player, detached);
                            }
                            if (!access.active() || access() != access || !valid.getAsBoolean()) {
                                return canonical;
                            }
                            if (ordinary && publication.itemPlaceholdersEnabled()) {
                                access.parseItemPlaceholders(detached);
                            }
                            if (!access.active() || access() != access || !valid.getAsBoolean()) {
                                return canonical;
                            }
                            if (ordinary) {
                                Bukkit.getPluginManager()
                                        .callEvent(
                                                new LegacyItemPacketEvent(
                                                        session.player, detached));
                            }
                            if (!live(session)
                                    || !access.active()
                                    || access() != access
                                    || !valid.getAsBoolean()) {
                                return canonical;
                            }
                            var appearance = CraftItemStack.unwrap(detached);
                            if (appearance.isEmpty()
                                    || appearance.getItem() != canonical.getItem()
                                    || appearance.getCount() != canonical.getCount()) {
                                throw new IllegalArgumentException(
                                        "Display rendering cannot change material or quantity");
                            }
                            if (net.minecraft.world.item.ItemStack.matches(canonical, appearance)) {
                                if (template != null) {
                                    sent.add(DisplayLedger.canonical(canonical));
                                }
                                return canonical;
                            }
                            DisplayLedger.Sent proof =
                                    session.ledger.prepare(canonical, appearance);
                            sent.add(proof);
                            return proof.display();
                        });
        if (!live(session) || !access.active() || access() != access || !valid.getAsBoolean()) {
            return OrderedDisplayHandler.Rendered.unchanged(packet);
        }
        return new OrderedDisplayHandler.Rendered(rendered, sent);
    }

    private void abandon(Session session) {
        session.rendering = false;
        session.guard.freeze();
        sessions.remove(session.playerId, session);
        attached.remove(session);
    }

    private void stop(Session session) {
        Runnable stop =
                () -> {
                    ChannelHandlerContext context =
                            session.channel.pipeline().context(session.handler);
                    if (context != null) {
                        session.handler.stopRendering(context);
                    }
                };
        try {
            if (session.channel.eventLoop().inEventLoop()) {
                stop.run();
            } else {
                session.channel.eventLoop().execute(stop);
            }
        } catch (RuntimeException rejected) {
            // A terminated connection cannot send or return further packets. Its independent
            // guard is already frozen by close/disconnect; rejection must not stop other cleanup.
            session.guard.freeze();
            sessions.remove(session.playerId, session);
            attached.remove(session);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        HandlerList.unregisterAll(this);
        synchronized (refreshes) {
            for (BukkitRunnable task : refreshes) {
                task.cancel();
            }
            refreshes.clear();
        }
        for (Session session : attached) {
            session.guard.freeze();
            session.rendering = false;
            stop(session);
        }
        current = null;
        sessions.clear();
        attached.clear();
    }

    private static boolean ordinary(GameMode mode) {
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private static final class Session {
        private final Player player;
        private final UUID playerId;
        private final Channel channel;
        private final DisplayLedger ledger = new DisplayLedger();
        private final ReturnGuard guard;
        private volatile boolean ordinary;
        private volatile boolean rendering = true;
        private OrderedDisplayHandler handler;

        private Session(Player player, Channel channel, Supplier<DisplayAccess> current) {
            this.player = player;
            this.playerId = player.getUniqueId();
            this.channel = channel;
            this.ordinary = ordinary(player.getGameMode());
            this.guard = new ReturnGuard(current);
        }
    }

    /** This predicate retains only scalar IDs after freezing, including on disconnected sessions. */
    private static final class ReturnGuard
            implements Predicate<net.minecraft.world.item.ItemStack> {
        private Supplier<DisplayAccess> source;
        private Set<String> frozen = Set.of();
        private Object token;

        private ReturnGuard(Supplier<DisplayAccess> source) {
            this.source = source;
        }

        private synchronized Set<String> ids() {
            if (source == null) {
                return frozen;
            }
            DisplayAccess access = source.get();
            if (access == null) {
                frozen = Set.of();
                token = null;
            } else {
                DisplayPublication publication = access.publication();
                if (token != publication.token()) {
                    frozen = Set.copyOf(publication.templates().keySet());
                    token = publication.token();
                }
            }
            return frozen;
        }

        private synchronized void freeze() {
            try {
                ids();
            } finally {
                source = null;
                token = null;
            }
        }

        @Override
        public boolean test(net.minecraft.world.item.ItemStack stack) {
            try {
                String id = CODEC.readId(stack);
                return id != null && ids().contains(id);
            } catch (IllegalArgumentException malformed) {
                return true;
            }
        }
    }
}

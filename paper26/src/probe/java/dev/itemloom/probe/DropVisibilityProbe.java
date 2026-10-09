package dev.itemloom.probe;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import dev.itemloom.paper.action.DropOwnership;
import dev.itemloom.paper.action.DropVisibility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.entity.Item;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Real Craft visibility maps, trackers and packets; detached players have capture-only connections. */
final class DropVisibilityProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        var result = new CompletableFuture<Map<String, Object>>();
        var assertions = new ArrayList<String>();
        var failures = new LinkedHashMap<String, String>();
        Fixture fixture;
        try {
            fixture = new Fixture(plugin);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(
                    Map.of("passed", false, "failure", failure.toString()));
        }
        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            try {
                                checks(fixture, assertions);
                            } catch (Throwable failure) {
                                failures.put("visibility", failure.toString());
                                plugin.getLogger()
                                        .log(
                                                java.util.logging.Level.WARNING,
                                                "Visibility probe failed",
                                                failure);
                            } finally {
                                try {
                                    fixture.close();
                                } catch (Throwable failure) {
                                    failures.put("cleanup", failure.toString());
                                }
                            }
                            result.complete(
                                    Map.of(
                                            "passed",
                                            failures.isEmpty(),
                                            "checks",
                                            assertions.size(),
                                            "assertions",
                                            assertions,
                                            "failures",
                                            failures,
                                            "realClient",
                                            false,
                                            "boundaries",
                                            List.of(
                                                    "Actual 26.2 NMS trackers and outbound packet objects",
                                                    "Tracking attempt sequence driven by probe; players not added to PlayerList",
                                                    "No client rendering or full restart verification")));
                        },
                        5);
        return result;
    }

    private static void checks(Fixture f, List<String> c) throws Exception {
        Viewer alice = f.viewer("Alice"), bob = f.viewer("Bob"), lower = f.viewer("alice");
        Item item = f.item;
        check(c, tracker(item) != null, "real dropped entity has a 26.2 tracker");
        check(c, item.isVisibleByDefault(), "controller leaves default visibility intact");
        check(
                c,
                !f.track(bob, item) && !bob.value().canSee(item),
                "non-owner tracking is cancelled and own hide override is set");
        check(
                c,
                !tracker(item).seenBy.contains(bob.player.connection),
                "cancelled attempt removes Paper's already-added seenBy connection");
        check(
                c,
                bob.packets().stream().noneMatch(DropVisibilityProbe::rendersItem),
                "non-owner receives no add-entity or item metadata packet");
        check(c, f.track(alice, item), "exact owner passes tracking gate");
        check(
                c,
                alice.packets().stream().anyMatch(ClientboundAddEntityPacket.class::isInstance)
                        && alice.packets().stream()
                                .anyMatch(ClientboundSetEntityDataPacket.class::isInstance),
                "owner receives actual add-entity and item metadata packets");
        check(c, !f.track(lower, item), "case-sensitive owner name retains NI contract");
        alice.clear();
        bob.clear();
        lower.clear();
        tracker(item)
                .sendToTrackingPlayers(
                        new ClientboundSetEntityDataPacket(
                                item.getEntityId(),
                                ((CraftItem) item)
                                        .getHandle()
                                        .getEntityData()
                                        .getNonDefaultValues()));
        check(
                c,
                alice.packets().size() == 1 && bob.packets().isEmpty() && lower.packets().isEmpty(),
                "later metadata broadcast cannot leak to cancelled viewers");
        alice.clear();
        bob.clear();
        lower.clear();
        f.visibility.tick();
        f.visibility.tick();
        check(
                c,
                alice.packets().isEmpty() && bob.packets().isEmpty() && lower.packets().isEmpty(),
                "unchanged drops cause no resend during reconciliation");
        DropOwnership.setOwner(item, "Bob", f.plugin);
        check(
                c,
                !alice.value().canSee(item) && bob.value().canSee(item),
                "owner setter immediately hides old owner and releases new owner");
        check(
                c,
                !tracker(item).seenBy.contains(alice.player.connection) && f.track(bob, item),
                "owner change also removes stale tracker and permits new pairing");
        item.removeScoreboardTag("NI-Hide");
        f.visibility.tick();
        check(
                c,
                alice.value().canSee(item) && lower.value().canSee(item),
                "raw hide-tag removal releases this plugin's restrictions");
        check(c, f.track(alice, item), "unhidden owned drop can be displayed to another player");
        item.addScoreboardTag("NI-Hide");
        f.visibility.tick();
        check(
                c,
                !alice.value().canSee(item)
                        && !tracker(item).seenBy.contains(alice.player.connection),
                "raw hide-tag addition removes an already-tracking non-owner");
        alice.value().hideEntity(f.other, item);
        DropOwnership.setOwner(item, null, f.plugin);
        check(
                c,
                !alice.value().canSee(item) && lower.value().canSee(item),
                "clearing owner releases only own hides and preserves another plugin's hide");
        alice.value().showEntity(f.other, item);
        check(
                c,
                alice.value().canSee(item) && f.track(alice, item),
                "hide tag without an owner leaves the drop public");

        // A late quit for the previous Player object must not erase a reconnected session.
        DropOwnership.setOwner(item, "Alice", f.plugin);
        Viewer replacement = f.viewer("Bob", bob.player.getUUID());
        f.visibility.quit(
                new PlayerQuitEvent(bob.value(), (net.kyori.adventure.text.Component) null));
        check(c, !f.track(replacement, item), "reconnected viewer is independently hidden");
        f.visibility.quit(
                new PlayerQuitEvent(bob.value(), (net.kyori.adventure.text.Component) null));
        DropOwnership.setOwner(item, "Bob", f.plugin);
        check(
                c,
                replacement.value().canSee(item),
                "late quit from old session cannot discard the new session's cleanup record");

        // Real API serializes PDC; the reload path discovers owner without metadata.
        byte[] saved = item.getPersistentDataContainer().serializeToBytes();
        Item restored = f.spawn(null, false);
        restored.getPersistentDataContainer().readFromBytes(saved, true);
        restored.addScoreboardTag("NI-Hide");
        f.visibility.added(
                new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(
                        restored, restored.getWorld()));
        check(
                c,
                "Bob".equals(DropOwnership.owner(restored)) && !f.track(alice, restored),
                "entity-load path enforces persisted owner with no NI metadata");

        // Other plugins may own visibleByDefault=false and explicit show grants.
        Item privateItem = f.spawn("Alice", true);
        privateItem.setVisibleByDefault(false);
        bob.value().showEntity(f.other, privateItem);
        check(
                c,
                !f.track(bob, privateItem) && bob.value().canSee(privateItem),
                "tracking gate rejects foreign viewer without revoking another plugin's show grant");
        check(
                c,
                !tracker(privateItem).seenBy.contains(bob.player.connection),
                "default-hidden explicit grants cannot leave metadata recipients behind");
        DropOwnership.setOwner(privateItem, "Bob", f.plugin);
        check(
                c,
                !alice.value().canSee(privateItem),
                "owner change does not create an unsolicited show grant for default-hidden entities");

        Item removed = f.spawn("Alice", true);
        f.track(bob, removed);
        bob.clear();
        removed.remove();
        check(
                c,
                bob.value().canSee(removed)
                        && bob.packets().stream().noneMatch(DropVisibilityProbe::rendersItem),
                "entity removal cleans hide state without respawning a removed entity");

        // Already-cancelled events still require removing seenBy for restricted drops.
        DropOwnership.setOwner(item, "Alice", f.plugin);
        tracker(item).seenBy.add(bob.player.connection);
        var cancelled = new PlayerTrackEntityEvent(bob.value(), item);
        cancelled.setCancelled(true);
        f.visibility.track(cancelled);
        check(
                c,
                cancelled.isCancelled() && !tracker(item).seenBy.contains(bob.player.connection),
                "prior cancellation cannot leave an unauthorized metadata recipient");
        bob.value().hideEntity(f.other, item);
        f.visibility.close();
        check(
                c,
                !bob.value().canSee(item)
                        && lower.value().canSee(item)
                        && replacement.value().canSee(item),
                "close clears own remaining hides while preserving foreign hides");
        check(
                c,
                !alice.value().canSee(privateItem) && !privateItem.isVisibleByDefault(),
                "close leaves another plugin's default-hidden entity unchanged");
        check(
                c,
                java.util.Arrays.stream(
                                PlayerTrackEntityEvent.getHandlerList().getRegisteredListeners())
                        .noneMatch(listener -> listener.getListener() == f.visibility),
                "close unregisters visibility gate");
        int watched = ((Map<?, ?>) field(f.visibility, "watched")).size();
        check(c, watched == 0, "close releases all entity and player references");
        f.visibility.tick();
        f.visibility.refresh(item);
        check(
                c,
                ((Map<?, ?>) field(f.visibility, "watched")).isEmpty(),
                "closed controller cannot recreate state");
        bob.value().showEntity(f.other, item);
        bob.value().showEntity(f.other, privateItem);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void check(List<String> assertions, boolean valid, String name) {
        if (!valid) throw new AssertionError(name);
        assertions.add(name);
    }

    private static boolean rendersItem(Packet<?> packet) {
        return packet instanceof ClientboundAddEntityPacket
                || packet instanceof ClientboundSetEntityDataPacket;
    }

    private static net.minecraft.server.level.ChunkMap.TrackedEntity tracker(Item item) {
        return ((CraftItem) item).getHandle().moonrise$getTrackedEntity();
    }

    private static final class Capture extends Connection {
        final List<Packet<?>> packets = new ArrayList<>();

        Capture() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            collect(packet);
        }

        @Override
        public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {
            collect(packet);
        }

        @Override
        public void send(
                Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) {
            collect(packet);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        private void collect(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle)
                bundle.subPackets().forEach(this::collect);
            else packets.add(packet);
        }
    }

    private record Viewer(ServerPlayer player, Capture connection) {
        org.bukkit.entity.Player value() {
            return player.getBukkitEntity();
        }

        List<Packet<?>> packets() {
            return connection.packets;
        }

        void clear() {
            connection.packets.clear();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin;
        final Plugin other =
                java.util.Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ItemLoom"));
        final DropVisibility visibility;
        final Map<Listener, Plugin> suspended = new LinkedHashMap<>();
        final List<Viewer> viewers = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        final Location at = Bukkit.getWorlds().getFirst().getSpawnLocation().add(0, 8, 0);
        final boolean ticket;
        final Item item;

        Fixture(JavaPlugin plugin) {
            this.plugin = plugin;
            ticket = at.getChunk().addPluginChunkTicket(plugin);
            // Scope to this test controller, restoring the real service's registration afterward.
            for (var listener : PlayerTrackEntityEvent.getHandlerList().getRegisteredListeners()) {
                if (listener.getListener() instanceof DropVisibility)
                    suspended.put(listener.getListener(), listener.getPlugin());
            }
            suspended.keySet().forEach(HandlerList::unregisterAll);
            visibility = new DropVisibility(plugin);
            Bukkit.getPluginManager().registerEvents(visibility, plugin);
            item = spawn("Alice", true);
            visibility.start();
        }

        Viewer viewer(String name) {
            return viewer(name, UUID.randomUUID());
        }

        Viewer viewer(String name, UUID id) {
            var server = ((CraftServer) Bukkit.getServer()).getServer();
            var profile = new GameProfile(id, name);
            var player =
                    new ServerPlayer(
                            server,
                            ((CraftWorld) at.getWorld()).getHandle(),
                            profile,
                            ClientInformation.createDefault());
            player.setPos(at.getX(), at.getY(), at.getZ());
            var capture = new Capture();
            player.connection =
                    new ServerGamePacketListenerImpl(
                            server,
                            capture,
                            player,
                            CommonListenerCookie.createInitial(profile, false));
            var viewer = new Viewer(player, capture);
            viewers.add(viewer);
            return viewer;
        }

        Item spawn(String owner, boolean hide) {
            Item item =
                    at.getWorld()
                            .dropItem(
                                    at,
                                    new ItemStack(Material.STONE),
                                    value -> {
                                        value.setGravity(false);
                                        value.setPersistent(false);
                                        DropOwnership.assign(value, owner, hide, plugin);
                                    });
            items.add(item);
            return item;
        }

        boolean track(Viewer viewer, Item item) {
            var tracker = tracker(item);
            if (tracker == null) throw new AssertionError("Missing actual tracker");
            // This is the exact 26.2 ordering inspected in ChunkMap.TrackedEntity.updatePlayer.
            tracker.seenBy.add(viewer.player.connection);
            var event = new PlayerTrackEntityEvent(viewer.value(), item);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) tracker.serverEntity.addPairing(viewer.player);
            return !event.isCancelled();
        }

        @Override
        public void close() {
            try {
                visibility.close();
                items.forEach(Item::remove);
                for (Viewer viewer : viewers) {
                    viewer.player.getAdvancements().clearTriggers();
                    viewer.player.getTextFilter().leave();
                }
                if (ticket) at.getChunk().removePluginChunkTicket(plugin);
            } finally {
                suspended.forEach(
                        (listener, owner) ->
                                Bukkit.getPluginManager().registerEvents(listener, owner));
            }
        }
    }
}

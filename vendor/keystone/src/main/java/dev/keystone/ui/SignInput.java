package dev.keystone.ui;

import dev.keystone.Keystone;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * Text input through a sign editor, with no sign in the world (TabooLib's {@code
 * Player.inputSign}).
 *
 * <pre>{@code
 * try {
 *     SignInput.open(player, new String[] {initial, "", "^^^^^^^^^^^^^^^", hint},
 *             lines -> apply(player, lines[0]));
 * } catch (RuntimeException unavailable) {
 *     openAnvilInstead(player);
 * }
 * }</pre>
 *
 * <p>Same steps as TabooLib: the player's client is shown an oak wall sign two blocks below their
 * feet with the given lines (padded or cut to four; legacy {@code §} colours, no {@code &}
 * translation), the editor is opened for its front, and when the player closes the editor the
 * block is shown as it really is again. Paper API only: {@link Player#sendBlockChange}, {@link
 * Player#sendBlockUpdate}, {@link Player#openVirtualSign} and {@link UncheckedSignChangeEvent}.
 *
 * <p>The legacy {@code open} callback gets four lines as plain text, on the <b>main thread, one tick
 * after</b> the player closes the editor - the same thread and timing as TabooLib, which delivered
 * through {@code submit {}} (so callers that hop to the main thread again, as Waystone does, stay
 * correct). The server has already stripped {@code §} codes and cut over-long lines. One prompt
 * per player: a new
 * one replaces an unanswered one, whose callback never runs; leaving the server drops both
 * unanswered prompts and callbacks that have not started. {@link #openPrompt} instead delivers
 * accepted text immediately on the event's main thread and returns a cancellable prompt identity.
 *
 * <p>Differences from TabooLib: the fake sign's height is clamped to the world's build range; the
 * sign update is only taken when it is for the fake sign's position and front side (TabooLib took
 * any sign update from that player), and it is cancelled so a real sign at that spot can never be
 * edited through it.
 *
 * <p>Opening and cancellation must run on the main thread; another thread gets an {@link
 * IllegalStateException}. Opening also rejects offline players and a disabled owning plugin.
 * Opening throws {@link UnsupportedOperationException} when this server lacks the Paper API
 * used, so callers can fall back to an anvil.
 *
 * <p>Prompt identities protect server callbacks and display cleanup. The client sign-update packet
 * contains no prompt token: a late update for an identical position cannot be distinguished from
 * an answer to a newer prompt at that position.
 */
public final class SignInput {

    private static final SignPrompts PROMPTS =
            new SignPrompts(
                    Bukkit::isPrimaryThread,
                    callback -> Bukkit.getScheduler().runTask(Keystone.plugin(), callback));
    private static volatile boolean registered;

    private SignInput() {}

    /** Identity of one prompt; cancellation never affects a newer prompt for the same player. */
    public interface Prompt {

        /** Whether this exact prompt is still awaiting input; safe to query from any thread. */
        boolean isWaiting();

        /**
         * Suppresses this prompt's callback if it has not started, and restores its fake block
         * unless a newer prompt owns that position. Must run on the main thread. Returns false
         * when already cancelled, replaced, completed, or executing its callback. This does not
         * send a client editor-close packet or roll back a callback that has begun.
         */
        boolean cancel();
    }

    /** Same as {@link #open(Player, String[], Consumer)} with empty lines. */
    public static void open(Player player, Consumer<String[]> callback) {
        open(player, new String[0], callback);
    }

    /** Opens a sign editor showing {@code lines}; see the class notes for the callback. */
    public static void open(Player player, String[] lines, Consumer<String[]> callback) {
        open(player, lines, callback, true);
    }

    /**
     * Opens a controlled prompt. Unlike {@link #open(Player, String[], Consumer)}, the callback
     * runs immediately on the sign submission event's main thread, before this prompt's display is
     * restored. This lets an input queue claim its entry when the packet is accepted. The callback
     * may open another prompt; old cleanup will not overwrite its fake sign. It receives exactly
     * four plain-text lines. Both opening and {@link Prompt#cancel()} require the main thread.
     */
    public static Prompt openPrompt(Player player, String[] lines, Consumer<String[]> callback) {
        return open(player, lines, callback, false);
    }

    private static Prompt open(
            Player player, String[] lines, Consumer<String[]> callback, boolean deferred) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is null");
        }
        PROMPTS.requireOpen();
        if (!Keystone.plugin().isEnabled()) {
            throw new IllegalStateException("cannot open a sign for a disabled plugin");
        }
        if (!player.isOnline()) {
            throw new IllegalStateException(player.getName() + " is not online");
        }
        SignPrompts.Session prompt = null;
        try {
            ensureRegistered();
            World world = player.getWorld();
            Location feet = player.getLocation();
            int y = signY(feet.getBlockY(), world.getMinHeight(), world.getMaxHeight());
            Location location = new Location(world, feet.getBlockX(), y, feet.getBlockZ());
            BlockData block = Material.OAK_WALL_SIGN.createBlockData();
            Sign sign = (Sign) block.createBlockState();
            SignSide front = sign.getSide(Side.FRONT);
            List<Component> text = components(pad(lines));
            for (int line = 0; line < text.size(); line++) {
                front.line(line, text.get(line));
            }
            prompt =
                    PROMPTS.open(
                            player.getUniqueId(),
                            spot(world, Position.block(location)),
                            callback,
                            deferred,
                            () -> {
                                if (player.isOnline() && player.getWorld().equals(world)) {
                                    player.sendBlockChange(
                                            location, location.getBlock().getBlockData());
                                }
                            });
            player.sendBlockChange(location, block);
            player.sendBlockUpdate(location, sign);
            player.openVirtualSign(Position.block(location), Side.FRONT);
            return prompt;
        } catch (LinkageError missing) {
            if (prompt != null) {
                prompt.failed(missing);
            }
            throw new UnsupportedOperationException("virtual signs are not available", missing);
        } catch (RuntimeException | Error failure) {
            if (prompt != null) {
                prompt.failed(failure);
            }
            throw failure;
        }
    }

    /** Whether {@code player} has a prompt that has not been answered yet. */
    public static boolean isWaiting(Player player) {
        return PROMPTS.isWaiting(player.getUniqueId());
    }

    private static SignPrompts.Spot spot(World world, BlockPosition position) {
        return new SignPrompts.Spot(
                world.getUID(), position.blockX(), position.blockY(), position.blockZ());
    }

    /** TabooLib's {@code formatSign(4)}: exactly four lines, null as empty. */
    static String[] pad(String[] lines) {
        String[] result = new String[4];
        for (int i = 0; i < 4; i++) {
            String line = lines != null && i < lines.length ? lines[i] : null;
            result[i] = line == null ? "" : line;
        }
        return result;
    }

    /** Two blocks below the feet, kept inside the build range so the client accepts the block. */
    static int signY(int feetY, int minHeight, int maxHeight) {
        return Math.max(minHeight, Math.min(maxHeight - 1, feetY - 2));
    }

    private static List<Component> components(String[] lines) {
        List<Component> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(LegacyComponentSerializer.legacySection().deserialize(line));
        }
        return result;
    }

    private static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new SignListener(), Keystone.plugin());
        registered = true;
    }

    /** Takes the submitted lines of our fake signs. */
    static final class SignListener implements Listener {

        SignListener() {}

        @EventHandler(priority = EventPriority.LOWEST)
        public void onSign(UncheckedSignChangeEvent e) {
            if (e.getSide() != Side.FRONT) {
                return;
            }
            Player player = e.getPlayer();
            SignPrompts.Session pending =
                    PROMPTS.waiting(
                            player.getUniqueId(),
                            spot(player.getWorld(), e.getEditedBlockPosition()));
            if (pending == null) {
                return;
            }
            e.setCancelled(true);
            String[] lines = new String[4];
            try {
                List<Component> sent = e.lines();
                for (int i = 0; i < 4; i++) {
                    lines[i] =
                            i < sent.size()
                                    ? PlainTextComponentSerializer.plainText()
                                            .serialize(sent.get(i))
                                    : "";
                }
            } catch (RuntimeException | Error failure) {
                pending.failed(failure);
                throw failure;
            }
            pending.submit(lines);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            PROMPTS.quit(e.getPlayer().getUniqueId());
        }

        /** Pending callbacks die with the plugin; fake signs are taken away. */
        @EventHandler
        public void onDisable(PluginDisableEvent e) {
            if (e.getPlugin() != Keystone.plugin()) {
                return;
            }
            PROMPTS.close();
        }
    }
}

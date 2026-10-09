package dev.itemloom.probe;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.PaperActions;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Private players and inventory, real 26.2 item encoding and action scheduling. */
final class BuiltinActionProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Builtin probe starts on the server thread");
        Runner runner = new Runner(plugin);
        runner.start();
        return runner.report;
    }

    private static final class Runner {
        final JavaPlugin plugin;
        final Fixture fixture;
        final List<String> verified = new ArrayList<>();
        final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        final Queue<String> warnings = new ConcurrentLinkedQueue<>();
        final Handler logger =
                new Handler() {
                    public void publish(LogRecord record) {
                        warnings.add(record.getMessage());
                    }

                    public void flush() {}

                    public void close() {}
                };
        BukkitTask deadline;
        int attempted;

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
            fixture = new Fixture(plugin);
        }

        void start() {
            plugin.getLogger().addHandler(logger);
            deadline =
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> finish(new AssertionError("Builtin probe timed out")),
                                    160);
            try {
                inventory();
                foodAndLevel();
                teleport();
                server();
                Queue<Boolean> continuationThreads = new ConcurrentLinkedQueue<>();
                var context =
                        fixture.context(
                                fixture.player.value, Map.of("threads", continuationThreads));
                fixture.run(
                                context,
                                List.of(
                                        "async",
                                        "tp: "
                                                + fixture.player.location.getWorld().getName()
                                                + " 1 2 3",
                                        "js: threads.add(Java.type('org.bukkit.Bukkit').isPrimaryThread()); true;",
                                        "sync",
                                        "server: thread-check",
                                        "js: threads.add(Java.type('org.bukkit.Bukkit').isPrimaryThread()); true;"))
                        .whenComplete(
                                (value, error) ->
                                        Bukkit.getScheduler()
                                                .runTask(
                                                        plugin,
                                                        () -> {
                                                            try {
                                                                if (error != null)
                                                                    throw new IllegalStateException(
                                                                            error);
                                                                check(
                                                                        !value.stopped()
                                                                                && List.copyOf(
                                                                                                continuationThreads)
                                                                                        .equals(
                                                                                                List
                                                                                                        .of(
                                                                                                                false,
                                                                                                                true)),
                                                                        "Bukkit effects restore the async/sync action continuation thread");
                                                                check(
                                                                        fixture
                                                                                .player
                                                                                .effectThreads
                                                                                .stream()
                                                                                .allMatch(
                                                                                        Boolean.TRUE
                                                                                                ::equals),
                                                                        "inventory, food, level, teleport and plugin-message effects only touch the main thread");
                                                                finish(null);
                                                            } catch (Throwable failure) {
                                                                finish(failure);
                                                            }
                                                        }));
            } catch (Throwable failure) {
                finish(failure);
            }
        }

        private Result run(Object source) {
            return fixture.ready(fixture.context(fixture.player.value, Map.of()), source);
        }

        private void inventory() {
            fixture.player.contents[0] = modern("wanted", 3);
            fixture.player.contents[1] = modern("other", 5);
            fixture.player.contents[2] = legacy("wanted", 4);
            fixture.player.contents[36] = modern("wanted", 2);
            fixture.player.contents[40] = legacy("wanted", 1);
            check(
                    !run("NeigeItems.take-ni-item: wanted 5").stopped()
                            && amount(0) == 0
                            && amount(2) == 2
                            && amount(1) == 5
                            && amount(36) == 2
                            && amount(40) == 1,
                    "take-ni-item traverses matching modern/legacy slots in order and preserves unrelated stacks");
            check(
                    !run("takeNiItem: wanted 50").stopped()
                            && amount(2) == 0
                            && amount(36) == 0
                            && amount(40) == 0,
                    "insufficient quantity consumes all available matches including armor and offhand");
            fixture.player.contents[0] = modern("wanted", 3);
            for (String amount : List.of("0", "invalid", "1.5"))
                check(
                        !run("take-ni-item: wanted " + amount).stopped() && amount(0) == 3,
                        "zero/invalid quantity is a no-op: " + amount);
            check(
                    !run("take-ni-item: wanted").stopped() && amount(0) == 3,
                    "missing quantity is a no-op");
            check(
                    run("take-ni-item: wanted -1").stopped()
                            && amount(0) == 3
                            && warnings.stream()
                                    .anyMatch(text -> text.contains("rejected negative amount")),
                    "negative quantity stops, logs rejection and never increases a stack");
            check(
                    !run("take-ni-item: wanted 2147483648").stopped() && amount(0) == 0,
                    "positive integer overflow retains NI's clamp-to-maximum parsing contract");
        }

        private void foodAndLevel() {
            fixture.player.food = 10;
            run("give-food: -3");
            run("takeFood: -3");
            check(
                    fixture.player.food == 10,
                    "negative give/take-food quantities cannot reverse the operation");
            run("give-food: 100");
            check(
                    fixture.player.lastFood == 30,
                    "give-food clamps its input quantity before calling Bukkit");
            fixture.player.food = 10;
            run("take-food: 100");
            check(
                    fixture.player.lastFood == -10,
                    "take-food preserves the old Bukkit setter argument");
            run("set-food: -3");
            check(fixture.player.lastFood == 0, "set-food clamps its value");
            check(
                    run("set-level: -3").stopped() && fixture.player.lastLevel == -3,
                    "set-level passes the original value to the setter and returns STOP for its rejected negative value");
            check(
                    !run("setLevel: 7").stopped() && fixture.player.level == 7,
                    "setLevel alias sets an accepted value");
        }

        private void teleport() {
            String world = fixture.player.location.getWorld().getName();
            fixture.player.location.setYaw(37);
            fixture.player.location.setPitch(-12);
            run("teleport: " + world + " 12.5 64 -4");
            check(
                    fixture.player.location.getX() == 12.5
                            && fixture.player.location.getZ() == -4
                            && fixture.player.location.getYaw() == 37
                            && fixture.player.location.getPitch() == -12,
                    "teleport accepts coordinates and preserves omitted orientation");
            run("tp: " + world + " 1 2 3 invalid 90");
            check(
                    fixture.player.location.getYaw() == 37
                            && fixture.player.location.getPitch() == 90,
                    "tp independently falls back for an invalid yaw");
            int calls = fixture.player.teleports;
            for (String target :
                    List.of(
                            "missing-probe-world 1 2 3",
                            world + " invalid 2 3",
                            world + " 1 2",
                            world + " ~ 2 3"))
                check(
                        !run("tp: " + target).stopped() && fixture.player.teleports == calls,
                        "invalid target is a successful no-op: " + target);
            Entity entity =
                    (Entity)
                            Proxy.newProxyInstance(
                                    Entity.class.getClassLoader(),
                                    new Class<?>[] {Entity.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getLocation" ->
                                                        fixture.player.location.clone();
                                                case "teleport" -> {
                                                    fixture.player.teleports++;
                                                    yield false;
                                                }
                                                case "toString" -> "BuiltinProbeEntity";
                                                case "hashCode" -> System.identityHashCode(proxy);
                                                case "equals" -> proxy == args[0];
                                                default ->
                                                        throw new UnsupportedOperationException(
                                                                method.toString());
                                            });
            check(
                    !fixture.ready(fixture.context(entity, Map.of()), "tp: " + world + " 7 8 9 1 2")
                                    .stopped()
                            && fixture.player.teleports == calls + 1,
                    "teleport accepts non-player entities and ignores the teleport boolean result like NI");
        }

        private void server() throws Exception {
            check(
                    !run("NeigeItems.server: hub with spaces").stopped(),
                    "server action completes immediately after sending");
            try (DataInputStream input =
                    new DataInputStream(
                            new ByteArrayInputStream(fixture.player.messages.getLast()))) {
                check(
                        input.readUTF().equals("Connect")
                                && input.readUTF().equals("hub with spaces")
                                && input.available() == 0,
                        "BungeeCord payload is exactly two modified UTF fields and keeps the complete target name");
            }
            check(
                    plugin.getServer()
                            .getMessenger()
                            .isOutgoingChannelRegistered(plugin, "BungeeCord"),
                    "plugin owns the outgoing BungeeCord channel");
            try (Fixture other = new Fixture(plugin)) {
                /* Closing a revision must keep the shared channel. */
            }
            check(
                    plugin.getServer()
                            .getMessenger()
                            .isOutgoingChannelRegistered(plugin, "BungeeCord"),
                    "closing another revision preserves the outgoing channel");
            check(
                    !fixture.ready(
                                    fixture.context(null, Map.of()),
                                    List.of(
                                            "server: hub",
                                            "take-ni-item: wanted 2",
                                            "tp: world 1 2 3"))
                            .stopped(),
                    "player/entity actions are successful no-ops without an applicable caster");
        }

        private int amount(int slot) {
            ItemStack item = fixture.player.contents[slot];
            return item == null ? 0 : item.getAmount();
        }

        private void check(boolean success, String name) {
            attempted++;
            if (!success) throw new AssertionError(name);
            verified.add(name);
        }

        private void finish(Throwable failure) {
            if (report.isDone()) return;
            deadline.cancel();
            plugin.getLogger().removeHandler(logger);
            try {
                fixture.close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("passed", failure == null);
            value.put("checks", attempted);
            value.put("passedChecks", verified.size());
            value.put("verified", List.copyOf(verified));
            value.put("warnings", List.copyOf(warnings));
            value.put("server", plugin.getServer().getMinecraftVersion());
            value.put(
                    "fixtures",
                    "synthetic players/inventory/entity; real NMS item identities and Paper scheduler; no real cross-server connection");
            value.put(
                    "referenceContract",
                    "NI BaseActionManager at ca93bc4; negative take-ni-item is explicitly rejected");
            if (failure != null) value.put("failure", failure.toString());
            report.complete(value);
        }
    }

    private static ItemStack modern(String id, int amount) {
        return new ItemStateCodec()
                .write(
                        new ItemStack(Material.STONE, amount),
                        new ItemIdentity(id, Map.of()),
                        new CompoundTag());
    }

    private static ItemStack legacy(String id, int amount) {
        CompoundTag custom = new CompoundTag(), old = new CompoundTag();
        old.putString("id", id);
        custom.put("NeigeItems", old);
        return NmsItems.withCustomData(new ItemStack(Material.STONE, amount), custom);
    }

    /** Also used by InputCaptureProbe so both probes share the same explicit fake-player contract. */
    static final class Fixture implements AutoCloseable {
        final NiScripts scripts = new NiScripts(Map.of(), Map.of());
        final PlayerActionState players = new PlayerActionState();
        final PaperActions actions;
        final SyntheticPlayer player;

        Fixture(JavaPlugin plugin) {
            this(plugin, new SyntheticPlayer(plugin));
        }

        Fixture(JavaPlugin plugin, SyntheticPlayer player) {
            this.player = player;
            players.join(player.id);
            actions = new PaperActions(plugin, scripts, players, 500);
        }

        NiActionContext context(Object caster, Map<String, Object> params) {
            return new NiActionContext(
                    new NiEvaluation(
                            new GenerationContext(Map.of(), new Random(1)),
                            null,
                            caster,
                            NiEvaluation.Mode.ACTION,
                            new NiNodes(),
                            scripts,
                            null),
                    caster,
                    params,
                    actions::active);
        }

        CompletionStage<Result> run(NiActionContext context, Object source) {
            return actions.run(actions.compiler().compile(source), context);
        }

        Result ready(NiActionContext context, Object source) {
            var result = run(context, source).toCompletableFuture();
            if (!result.isDone())
                throw new AssertionError(
                        "Expected an immediately completed main-thread action: " + source);
            return result.join();
        }

        public void close() {
            actions.close();
            scripts.close();
            players.close();
        }
    }

    static final class SyntheticPlayer {
        final UUID id = UUID.randomUUID();
        final Player value;
        final ItemStack[] contents = new ItemStack[41];
        final List<byte[]> messages = new ArrayList<>();
        final Queue<Boolean> effectThreads = new ConcurrentLinkedQueue<>();
        Location location;
        boolean online = true;
        int food = 10, lastFood, level, lastLevel, teleports;

        SyntheticPlayer(JavaPlugin plugin) {
            location = new Location(plugin.getServer().getWorlds().getFirst(), 0, 64, 0);
            PlayerInventory inventory =
                    (PlayerInventory)
                            Proxy.newProxyInstance(
                                    PlayerInventory.class.getClassLoader(),
                                    new Class<?>[] {PlayerInventory.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getContents" -> contents.clone();
                                                case "setItem" -> {
                                                    effectThreads.add(Bukkit.isPrimaryThread());
                                                    contents[(Integer) args[0]] =
                                                            (ItemStack) args[1];
                                                    yield null;
                                                }
                                                case "toString" -> "BuiltinProbeInventory";
                                                case "hashCode" -> System.identityHashCode(proxy);
                                                case "equals" -> proxy == args[0];
                                                default ->
                                                        throw new UnsupportedOperationException(
                                                                "Unexpected synthetic inventory call: "
                                                                        + method);
                                            });
            value =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class<?>[] {Player.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getUniqueId" -> id;
                                                case "getName", "getDisplayName", "toString" ->
                                                        "BuiltinProbe";
                                                case "getPlayer" -> proxy;
                                                case "getServer" -> plugin.getServer();
                                                case "isOnline", "isValid" -> online;
                                                case "isDead" -> false;
                                                case "getInventory" -> inventory;
                                                case "getWorld" -> location.getWorld();
                                                case "getLocation" -> location.clone();
                                                case "teleport" -> {
                                                    effectThreads.add(Bukkit.isPrimaryThread());
                                                    location = ((Location) args[0]).clone();
                                                    teleports++;
                                                    yield true;
                                                }
                                                case "getFoodLevel" -> food;
                                                case "setFoodLevel" -> {
                                                    effectThreads.add(Bukkit.isPrimaryThread());
                                                    lastFood = (Integer) args[0];
                                                    food = lastFood;
                                                    yield null;
                                                }
                                                case "setLevel" -> {
                                                    effectThreads.add(Bukkit.isPrimaryThread());
                                                    lastLevel = (Integer) args[0];
                                                    if (lastLevel < 0)
                                                        throw new IllegalArgumentException(
                                                                "Experience level must not be negative");
                                                    level = lastLevel;
                                                    yield null;
                                                }
                                                case "sendPluginMessage" -> {
                                                    effectThreads.add(Bukkit.isPrimaryThread());
                                                    if (args[0] != plugin
                                                            || !args[1].equals("BungeeCord"))
                                                        throw new AssertionError(
                                                                "wrong outgoing plugin/channel");
                                                    messages.add(((byte[]) args[2]).clone());
                                                    yield null;
                                                }
                                                case "hasPermission", "isPermissionSet", "isOp" ->
                                                        true;
                                                case "hashCode" -> id.hashCode();
                                                case "equals" -> proxy == args[0];
                                                default ->
                                                        throw new UnsupportedOperationException(
                                                                "Unexpected synthetic player call: "
                                                                        + method);
                                            });
        }
    }
}

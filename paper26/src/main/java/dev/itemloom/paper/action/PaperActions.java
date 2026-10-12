package dev.itemloom.paper.action;

import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiActions;
import dev.itemloom.compat.ni.action.NiValues;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.paper.compat.script.LegacyItemEditorManager;
import dev.itemloom.paper.integration.OptionalItemIntegrations;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class PaperActions implements NiActions.Host, AutoCloseable {
    private final JavaPlugin plugin;
    private final PlayerActionState players;
    private final ActionTasks tasks;
    private final ActionInputCapture inputs;
    private final NiActions compiler;
    private final OptionalItemIntegrations integrations = new OptionalItemIntegrations();
    private final long comboInterval;
    private final Set<String> unknown = new HashSet<>();
    private volatile Map<String, ActionFlow.Step<NiActionContext>> functions = Map.of();
    private LegacyItemEditorManager editors;

    public PaperActions(
            JavaPlugin plugin, NiScripts scripts, PlayerActionState players, long comboInterval) {
        this.plugin = Objects.requireNonNull(plugin);
        this.players = players;
        this.comboInterval = comboInterval;
        tasks = new ActionTasks(plugin);
        inputs = new ActionInputCapture(plugin, tasks, players);
        compiler = new NiActions(this, scripts::validate);
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, "BungeeCord")) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        }
    }

    public NiActions compiler() {
        return compiler;
    }

    public ActionTasks tasks() {
        return tasks;
    }

    public OptionalItemIntegrations integrations() {
        return integrations;
    }

    public void editors(LegacyItemEditorManager manager) {
        editors = Objects.requireNonNull(manager);
    }

    public boolean active() {
        return tasks.active();
    }

    public void functions(Map<String, Object> sources) {
        Map<String, ActionFlow.Step<NiActionContext>> next = new HashMap<>();
        sources.forEach(
                (id, source) -> {
                    try {
                        next.put(id, compiler.compile(source));
                    } catch (RuntimeException error) {
                        throw new IllegalArgumentException(
                                "Functions." + id + ": " + error.getMessage(), error);
                    }
                });
        functions = Map.copyOf(next);
    }

    public CompletionStage<Result> run(
            ActionFlow.Step<NiActionContext> action, NiActionContext context) {
        CompletionStage<Result> result = tasks.run(() -> action.run(context));
        result.whenComplete(
                (value, failure) -> {
                    if (failure != null)
                        plugin.getLogger().log(Level.WARNING, "Item action failed", failure);
                });
        return result;
    }

    public CompletionStage<Result> function(String id, NiActionContext context) {
        ActionFlow.Step<NiActionContext> action = functions.get(id);
        return action == null ? done(Result.CONTINUE) : run(action, context);
    }

    public void fork(
            ActionFlow.Step<NiActionContext> action, NiActionContext context, boolean async) {
        tasks.schedule(
                0,
                async,
                true,
                () -> {
                    NiActionContext copy = context.clone();
                    return run(action, copy);
                });
    }

    public CompletionStage<Result> dispatch(
            ActionFlow.Step<NiActionContext> action, NiActionContext context) {
        if (!active() || !context.active()) return done(Result.STOP);
        if (context.isSync() == Bukkit.isPrimaryThread()) return action.run(context);
        return tasks.schedule(
                0,
                !context.isSync(),
                true,
                () -> {
                    if (!context.active()) return done(Result.STOP);
                    return action.run(context);
                });
    }

    public CompletionStage<Result> custom(
            Supplier<CompletionStage<Result>> body, boolean asyncSafe) {
        return asyncSafe ? tasks.run(body) : tasks.schedule(0, false, true, body);
    }

    public CompletionStage<Result> execute(String name, String content, NiActionContext context) {
        if (!active() || !context.active()) return done(Result.STOP);
        switch (name) {
            case "sync", "async" -> {
                boolean async = name.equals("async");
                context.setSync(!async);
                return tasks.schedule(
                        0,
                        async,
                        true,
                        () -> done(context.active() ? Result.CONTINUE : Result.STOP));
            }
            case "delay" -> {
                return tasks.schedule(
                        integer(content, 0),
                        false,
                        false,
                        () -> done(context.active() ? Result.CONTINUE : Result.STOP));
            }
            case "func" -> {
                return function(content, context);
            }
            case "catch-chat", "catchchat" -> {
                return inputs.capture(content, context);
            }
            case "catch-sign", "catchsign" -> {
                return inputs.captureSign(content, context);
            }
            case "clear-catch-chat", "clearcatchchat" -> {
                inputs.clear(context.getPlayer());
                return done(Result.CONTINUE);
            }
            case "clear-catch-sign", "clearcatchsign" -> {
                inputs.clearSign(context.getPlayer());
                return done(Result.CONTINUE);
            }
            default -> {
                return tasks.schedule(
                        0,
                        false,
                        true,
                        () -> {
                            if (!active() || !context.active()) return done(Result.STOP);
                            try {
                                return done(effect(name, content, context));
                            } catch (RuntimeException failure) {
                                plugin.getLogger()
                                        .log(
                                                Level.WARNING,
                                                "Item action " + name + " failed",
                                                failure);
                                return done(Result.STOP);
                            }
                        });
            }
        }
    }

    private Result effect(String rawName, String text, NiActionContext context) {
        String name = rawName.replace("-", "");
        String message =
                name.endsWith("nocolor") ? text : ChatColor.translateAlternateColorCodes('&', text);
        Object caster = context.getCaster();
        Player player = context.getPlayer();
        switch (name) {
            case "tell", "tellnocolor" -> {
                if (caster instanceof CommandSender sender) sender.sendMessage(message);
            }
            case "tellorprint", "tellorprintnocolor" -> {
                (player == null ? Bukkit.getConsoleSender() : player).sendMessage(message);
            }
            case "chat", "chatwithcolor" -> {
                if (player != null) player.chat(name.equals("chat") ? text : message);
            }
            case "command", "player", "playercommand" -> {
                if (player != null) Bukkit.dispatchCommand(player, text);
            }
            case "console", "consolecommand" ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text);
            case "broadcast", "broadcastnocolor" -> Bukkit.broadcastMessage(message);
            case "actionbar", "actionbarnocolor" -> {
                if (player != null) player.sendActionBar(message);
            }
            case "title", "titlenocolor" -> {
                if (player != null) title(player, message);
            }
            case "broadcasttitle", "broadcasttitlenocolor" -> {
                for (Player target : Bukkit.getOnlinePlayers()) title(target, message);
            }
            case "sound" -> {
                if (player != null) {
                    String[] args = text.split(" ", 3);
                    if (args.length > 0)
                        player.playSound(
                                player.getLocation(),
                                args[0],
                                args.length > 1 ? (float) decimal(args[1], 1) : 1,
                                args.length > 2 ? (float) decimal(args[2], 1) : 1);
                }
            }
            case "teleport", "tp" -> {
                if (caster instanceof Entity entity) teleport(entity, text);
            }
            case "server" -> {
                if (player != null) connect(player, text);
            }
            case "givemoney", "takemoney" -> {
                var vault = integrations.vault();
                if (player != null && vault != null) {
                    if (name.equals("givemoney")) vault.giveMoney(player, decimal(text, 0));
                    else vault.takeMoney(player, decimal(text, 0));
                }
            }
            case "castskill" -> {
                var mythic = integrations.mythic();
                if (player != null && mythic != null) mythic.castSkill(player, text, player);
            }
            case "giveexp",
                    "takeexp",
                    "setexp",
                    "givelevel",
                    "takelevel",
                    "setlevel",
                    "givefood",
                    "takefood",
                    "setfood",
                    "givesaturation",
                    "takesaturation",
                    "setsaturation" -> {
                if (player != null) playerValue(player, name, text);
            }
            case "givehealth", "takehealth", "sethealth" -> {
                if (caster instanceof LivingEntity entity) {
                    var attribute = entity.getAttribute(Attribute.MAX_HEALTH);
                    if (attribute != null)
                        entity.setHealth(
                                clamp(
                                        adjust(name, entity.getHealth(), decimal(text, 0)),
                                        0,
                                        attribute.getValue()));
                }
            }
            case "setpotion", "setpotioneffect", "removepotion", "removepotioneffect" -> {
                if (caster instanceof LivingEntity entity) potion(entity, name, text);
            }
            case "combo" -> {
                if (player != null) combo(player, text);
            }
            case "comboclear" -> {
                if (player != null)
                    players.setMetadata(
                            player.getUniqueId(), "Combo-" + text, new ArrayList<ComboInfo>());
            }
            case "setcooldown" -> {
                if (player != null) {
                    String[] args = text.split(" ", 2);
                    if (args.length == 2) {
                        Long duration = NiValues.convert(args[1], Long.class);
                        if (duration != null)
                            players.setCooldown(player.getUniqueId(), args[0], duration);
                    }
                }
            }
            case "takeniitem" -> {
                return ItemInventoryActions.take(player, text, plugin.getLogger());
            }
            default -> {
                String editor = rawName.toLowerCase(Locale.getDefault());
                if (editors != null && editors.getItemEditors().containsKey(editor)) {
                    if (player != null && context.getItemStack() != null)
                        editors.runEditor(editor, text, context.getItemStack(), player);
                } else if (unknown.add(rawName))
                    plugin.getLogger().warning("Unknown item action: " + rawName);
            }
        }
        return Result.CONTINUE;
    }

    private static void title(Player player, String text) {
        List<String> args = NiTemplate.split(text, ' ', 0);
        player.sendTitle(
                args.isEmpty() ? null : args.getFirst(),
                args.size() < 2 ? "" : args.get(1),
                args.size() < 3 ? 10 : integer(args.get(2), 10),
                args.size() < 4 ? 70 : integer(args.get(3), 70),
                args.size() < 5 ? 20 : integer(args.get(4), 20));
    }

    private static void teleport(Entity entity, String text) {
        String[] args = text.split(" ", 6);
        if (args.length < 4) return;
        var world = Bukkit.getWorld(args[0]);
        if (world == null) return;
        Double x = NiValues.convert(args[1], Double.class);
        Double y = NiValues.convert(args[2], Double.class);
        Double z = NiValues.convert(args[3], Double.class);
        if (x == null || y == null || z == null) return;
        Location old = entity.getLocation();
        entity.teleport(
                new Location(
                        world,
                        x,
                        y,
                        z,
                        args.length > 4 ? angle(args[4], old.getYaw()) : old.getYaw(),
                        args.length > 5 ? angle(args[5], old.getPitch()) : old.getPitch()));
    }

    private void connect(Player player, String server) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeUTF("Connect");
                data.writeUTF(server);
            }
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void playerValue(Player player, String name, String text) {
        int amount = integer(text, 0);
        switch (name) {
            case "giveexp" -> player.giveExp(amount);
            case "takeexp" -> player.giveExp(-amount);
            case "setexp" -> player.giveExp(amount - player.getTotalExperience());
            case "givelevel" -> player.giveExpLevels(amount);
            case "takelevel" -> player.giveExpLevels(-amount);
            case "setlevel" -> player.setLevel(amount);
            case "givefood", "takefood", "setfood" ->
                    player.setFoodLevel(
                            (int) adjust(name, player.getFoodLevel(), clamp(amount, 0, 20)));
            default ->
                    player.setSaturation(
                            (float)
                                    clamp(
                                            adjust(name, player.getSaturation(), decimal(text, 0)),
                                            0,
                                            player.getFoodLevel()));
        }
    }

    private static void potion(LivingEntity entity, String name, String text) {
        String[] args = text.split(" ", 3);
        PotionEffectType type = PotionEffectType.getByName(args[0].toUpperCase(Locale.ROOT));
        if (type == null) return;
        if (name.startsWith("remove")) entity.removePotionEffect(type);
        else if (args.length >= 3)
            entity.addPotionEffect(
                    new PotionEffect(type, integer(args[2], 0) * 20, integer(args[1], 0) - 1),
                    true);
    }

    @SuppressWarnings("unchecked")
    private void combo(Player player, String text) {
        String[] args = text.split(" ", 2);
        List<ComboInfo> history =
                (List<ComboInfo>)
                        players.metadataIfAbsent(
                                player.getUniqueId(), "Combo-" + args[0], ArrayList::new);
        if (history == null) return;
        synchronized (history) {
            long now = System.currentTimeMillis();
            if (!history.isEmpty() && history.getLast().time() + comboInterval < now)
                history.clear();
            history.add(new ComboInfo(args.length > 1 ? args[1] : "", now));
        }
    }

    private static double adjust(String name, double old, double amount) {
        return name.startsWith("give")
                ? old + amount
                : name.startsWith("take") ? old - amount : amount;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int integer(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static double decimal(String text, double fallback) {
        Double value = NiValues.convert(text, Double.class);
        return value == null ? fallback : value;
    }

    private static float angle(String text, float fallback) {
        return NiValues.convert(text, Double.class) == null ? fallback : Float.valueOf(text);
    }

    private static CompletionStage<Result> done(Result value) {
        return CompletableFuture.completedFuture(value);
    }

    @Override
    public void close() {
        tasks.close();
        inputs.close();
    }

    public record ComboInfo(String type, long time) {
        public String getType() {
            return type;
        }

        public long getTime() {
            return time;
        }
    }
}

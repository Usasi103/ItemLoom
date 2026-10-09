package dev.itemloom.paper.action;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiActions;
import dev.itemloom.compat.ni.action.NiValues;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.core.ActionFlow.Step;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Bukkit effects and revision-owned scheduling for the NI language frontend. */
public final class PaperActions implements NiActions.Host, AutoCloseable {
    private final JavaPlugin plugin;
    private final ActionTasks tasks;
    private final ActionInputCapture input;
    private final NiActions compiler;
    private final PlayerActionState players;
    private final long comboInterval;
    private final dev.itemloom.paper.integration.OptionalItemIntegrations integrations =
            new dev.itemloom.paper.integration.OptionalItemIntegrations();
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();
    private Map<String, Step<NiActionContext>> functions = Map.of();
    private dev.itemloom.paper.compat.script.LegacyItemEditorManager editors;

    public PaperActions(
            JavaPlugin plugin, NiScripts scripts, PlayerActionState players, long comboInterval) {
        this.plugin = plugin;
        this.players = players;
        this.comboInterval = comboInterval;
        tasks = new ActionTasks(plugin);
        // The channel belongs to the plugin, while listeners and waits belong to this revision.
        // Closing an old catalog must not unregister the replacement catalog's channel.
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, "BungeeCord"))
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        input = new ActionInputCapture(plugin, tasks, players);
        compiler = new NiActions(this, scripts::validate);
    }

    public NiActions compiler() {
        return compiler;
    }

    public ActionTasks tasks() {
        return tasks;
    }

    public dev.itemloom.paper.integration.OptionalItemIntegrations integrations() {
        return integrations;
    }

    public void editors(dev.itemloom.paper.compat.script.LegacyItemEditorManager editors) {
        this.editors = java.util.Objects.requireNonNull(editors);
    }

    public boolean active() {
        return tasks.active();
    }

    public void functions(Map<String, Object> definitions) {
        Map<String, Step<NiActionContext>> compiled = new java.util.LinkedHashMap<>();
        definitions.forEach(
                (id, source) -> {
                    try {
                        compiled.put(id, compiler.compile(source));
                    } catch (RuntimeException error) {
                        throw new IllegalArgumentException(
                                "Functions / " + id + ": " + error.getMessage(), error);
                    }
                });
        functions = Map.copyOf(compiled);
    }

    public CompletionStage<Result> run(Step<NiActionContext> action, NiActionContext context) {
        return tasks.run(() -> action.run(context))
                .whenComplete(
                        (value, error) -> {
                            if (error != null)
                                plugin.getLogger().log(Level.WARNING, "Item action failed", error);
                        });
    }

    public CompletionStage<Result> function(String id, NiActionContext context) {
        Step<NiActionContext> action = functions.get(id);
        return action == null ? success() : run(action, context);
    }

    @Override
    public void fork(Step<NiActionContext> action, NiActionContext context, boolean async) {
        tasks.schedule(0, async, true, () -> run(action, context.clone()));
    }

    @Override
    public CompletionStage<Result> dispatch(Step<NiActionContext> action, NiActionContext context) {
        // CompletableFuture may already be complete when a continuation is registered.
        // Decide the thread for each step instead of inheriting the completing thread.
        if (context.isSync() == Bukkit.isPrimaryThread()) return action.run(context);
        return tasks.schedule(0, !context.isSync(), true, () -> action.run(context));
    }

    @Override
    public CompletionStage<Result> custom(
            java.util.function.Supplier<CompletionStage<Result>> operation, boolean asyncSafe) {
        return asyncSafe ? tasks.run(operation) : tasks.schedule(0, false, true, operation);
    }

    @Override
    public CompletionStage<Result> execute(String id, String content, NiActionContext context) {
        if (!active() || !context.active()) return CompletableFuture.completedFuture(Result.STOP);
        if (id.equals("sync") || id.equals("async")) {
            context.setSync(id.equals("sync"));
            return tasks.schedule(0, !context.isSync(), true, PaperActions::success);
        }
        if (id.equals("delay"))
            return tasks.schedule(integer(content, 0), false, false, PaperActions::success);
        if (id.equals("func")) return function(content, context);
        if (id.equals("catch-chat") || id.equals("catchchat"))
            return input.capture(content, context);
        if (id.equals("clear-catch-chat")) {
            input.clear(context.getPlayer());
            return success();
        }
        if (id.equals("catch-sign") || id.equals("catchsign"))
            return input.captureSign(content, context);
        if (id.equals("clear-catch-sign")) {
            input.clearSign(context.getPlayer());
            return success();
        }
        // Entity/world changes belong to the server thread even inside an async language branch.
        return tasks.schedule(
                0,
                false,
                true,
                () -> {
                    if (!context.active()) return CompletableFuture.completedFuture(Result.STOP);
                    try {
                        if (id.equals("take-ni-item") || id.equals("takeniitem"))
                            return CompletableFuture.completedFuture(
                                    ItemInventoryActions.take(
                                            context.getPlayer(), content, plugin.getLogger()));
                        effect(id, content, context);
                        return success();
                    } catch (RuntimeException error) {
                        plugin.getLogger().log(Level.WARNING, "Action " + id + " failed", error);
                        return CompletableFuture.completedFuture(Result.STOP);
                    }
                });
    }

    @SuppressWarnings("deprecation")
    private void effect(String raw, String content, NiActionContext context) {
        String id = raw.replace("-", "");
        Player player = context.getPlayer();
        Object caster = context.getCaster();
        boolean color = !id.endsWith("nocolor");
        String message = color ? ChatColor.translateAlternateColorCodes('&', content) : content;
        switch (id) {
            case "tell", "tellnocolor" -> {
                if (caster instanceof CommandSender sender) sender.sendMessage(message);
            }
            case "tellorprint", "tellorprintnocolor" ->
                    (player == null ? Bukkit.getConsoleSender() : player).sendMessage(message);
            case "chat", "chatwithcolor" -> {
                if (player != null) player.chat(id.equals("chat") ? content : message);
            }
            case "command", "player", "commandnocolor" -> {
                if (player != null) Bukkit.dispatchCommand(player, message);
            }
            case "console", "consolenocolor" ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message);
            case "broadcast", "broadcastnocolor" -> Bukkit.broadcastMessage(message);
            case "actionbar", "actionbarnocolor" -> {
                if (player != null) player.sendActionBar(message);
            }
            case "title", "titlenocolor", "broadcasttitle", "broadcasttitlenocolor" -> {
                List<String> args = NiTemplate.split(message, ' ', 0);
                Iterable<? extends Player> targets =
                        id.startsWith("broadcast")
                                ? Bukkit.getOnlinePlayers()
                                : player == null ? List.of() : List.of(player);
                for (Player target : targets)
                    target.sendTitle(
                            arg(args, 0, null),
                            arg(args, 1, ""),
                            integer(arg(args, 2, "10"), 10),
                            integer(arg(args, 3, "70"), 70),
                            integer(arg(args, 4, "20"), 20));
            }
            case "sound" -> {
                if (player == null) return;
                String[] args = content.split(" ", 3);
                player.playSound(
                        player.getLocation(),
                        args[0],
                        args.length > 1 ? (float) number(args[1], 1) : 1,
                        args.length > 2 ? (float) number(args[2], 1) : 1);
            }
            case "teleport", "tp" -> {
                if (!(caster instanceof org.bukkit.entity.Entity entity)) return;
                String[] args = content.split(" ", 6);
                if (args.length < 4) return;
                var world = Bukkit.getWorld(args[0]);
                Double x = NiValues.convert(args[1], Double.class),
                        y = NiValues.convert(args[2], Double.class),
                        z = NiValues.convert(args[3], Double.class);
                if (world == null || x == null || y == null || z == null) return;
                var location = entity.getLocation();
                Float yaw = args.length > 4 ? decimalFloat(args[4]) : null;
                Float pitch = args.length > 5 ? decimalFloat(args[5]) : null;
                location.setWorld(world);
                location.setX(x);
                location.setY(y);
                location.setZ(z);
                if (yaw != null) location.setYaw(yaw);
                if (pitch != null) location.setPitch(pitch);
                entity.teleport(location);
            }
            case "server" -> {
                if (player == null) return;
                var output = com.google.common.io.ByteStreams.newDataOutput();
                output.writeUTF("Connect");
                output.writeUTF(content);
                player.sendPluginMessage(plugin, "BungeeCord", output.toByteArray());
            }
            case "givemoney", "takemoney" -> {
                if (player == null) return;
                var vault = integrations.vault();
                if (vault == null) return;
                double amount = number(content, 0);
                if (id.equals("givemoney")) vault.giveMoney(player, amount);
                else vault.takeMoney(player, amount);
            }
            case "castskill" -> {
                if (player == null) return;
                var mythic = integrations.mythic();
                if (mythic != null) mythic.castSkill(player, content, player);
            }
            case "giveexp", "takeexp", "setexp" -> {
                if (player == null) return;
                int value = integer(content, 0);
                player.giveExp(
                        id.equals("takeexp")
                                ? -value
                                : id.equals("setexp")
                                        ? value - player.getTotalExperience()
                                        : value);
            }
            case "givelevel", "takelevel", "setlevel" -> {
                if (player == null) return;
                int value = integer(content, 0);
                if (id.equals("setlevel")) player.setLevel(value);
                else player.giveExpLevels(id.equals("takelevel") ? -value : value);
            }
            case "givefood", "takefood", "setfood" -> {
                if (player != null)
                    player.setFoodLevel(
                            (int)
                                    adjust(
                                            id,
                                            player.getFoodLevel(),
                                            Math.clamp(integer(content, 0), 0, 20)));
            }
            case "givesaturation", "takesaturation", "setsaturation" -> {
                if (player != null)
                    player.setSaturation(
                            (float)
                                    Math.clamp(
                                            adjust(id, player.getSaturation(), number(content, 0)),
                                            0,
                                            player.getFoodLevel()));
            }
            case "givehealth", "takehealth", "sethealth" -> {
                if (caster instanceof LivingEntity entity
                        && entity.getAttribute(Attribute.MAX_HEALTH) != null)
                    entity.setHealth(
                            Math.clamp(
                                    adjust(id, entity.getHealth(), number(content, 0)),
                                    0,
                                    entity.getAttribute(Attribute.MAX_HEALTH).getValue()));
            }
            case "setpotion", "setpotioneffect" -> {
                if (!(caster instanceof LivingEntity entity)) return;
                String[] args = content.split(" ", 3);
                if (args.length < 3) return;
                PotionEffectType type =
                        PotionEffectType.getByName(args[0].toUpperCase(java.util.Locale.ROOT));
                Integer amplifier = NiValues.strictInteger(args[1]),
                        duration = NiValues.strictInteger(args[2]);
                if (type != null && amplifier != null && duration != null)
                    entity.addPotionEffect(
                            new PotionEffect(type, duration * 20, amplifier - 1), true);
            }
            case "removepotion", "removepotioneffect" -> {
                if (!(caster instanceof LivingEntity entity)) return;
                PotionEffectType type =
                        PotionEffectType.getByName(content.toUpperCase(java.util.Locale.ROOT));
                if (type != null) entity.removePotionEffect(type);
            }
            case "combo" -> {
                if (player == null) return;
                String[] info = content.split(" ", 2);
                String key = "Combo-" + info[0];
                @SuppressWarnings("unchecked")
                List<ComboInfo> history =
                        (List<ComboInfo>)
                                players.metadataIfAbsent(
                                        player.getUniqueId(),
                                        key,
                                        java.util.ArrayList<ComboInfo>::new);
                if (history == null) return;
                long now = System.currentTimeMillis();
                synchronized (history) {
                    if (!history.isEmpty() && history.getLast().time() + comboInterval < now)
                        history.clear();
                    history.add(new ComboInfo(info.length > 1 ? info[1] : "", now));
                }
            }
            case "comboclear" -> {
                if (player != null)
                    players.setMetadata(
                            player.getUniqueId(),
                            "Combo-" + content,
                            new java.util.ArrayList<ComboInfo>());
            }
            case "setcooldown" -> {
                if (player == null) return;
                String[] info = content.split(" ", 2);
                Long duration = info.length > 1 ? NiValues.convert(info[1], Long.class) : null;
                if (duration != null) players.setCooldown(player.getUniqueId(), info[0], duration);
            }
            default -> {
                if (editors != null
                        && editors.getItemEditors()
                                .containsKey(raw.toLowerCase(java.util.Locale.getDefault()))) {
                    if (player != null && context.getItemStack() != null)
                        editors.runEditorWithResult(raw, content, context.getItemStack(), player);
                } else if (unknown.add(raw))
                    plugin.getLogger().warning("Action/editor is not implemented yet: " + raw);
            }
        }
    }

    private static double adjust(String action, double previous, double value) {
        return action.startsWith("give")
                ? previous + value
                : action.startsWith("take") ? previous - value : value;
    }

    public record ComboInfo(String type, long time) {
        public String getType() {
            return type;
        }

        public long getTime() {
            return time;
        }
    }

    private static int integer(String value, int fallback) {
        Integer parsed = NiValues.strictInteger(value);
        return parsed == null ? fallback : parsed;
    }

    private static double number(String value, double fallback) {
        Double parsed = NiValues.convert(value, Double.class);
        return parsed == null ? fallback : parsed;
    }

    private static Float decimalFloat(String value) {
        return NiValues.convert(value, Double.class) == null ? null : Float.valueOf(value);
    }

    private static String arg(List<String> values, int index, String fallback) {
        return index < values.size() ? values.get(index) : fallback;
    }

    private static CompletionStage<Result> success() {
        return CompletableFuture.completedFuture(Result.CONTINUE);
    }

    @Override
    public void close() {
        tasks.close();
        input.close();
    }
}

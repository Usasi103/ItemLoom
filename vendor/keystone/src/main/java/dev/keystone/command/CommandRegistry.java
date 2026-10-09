package dev.keystone.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.keystone.Keystone;
import dev.keystone.log.Log;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Registers root commands through Paper's command lifecycle as "label + one greedy argument", so
 * Brigadier never rejects a token (non-ASCII words such as {@code 背包} included) and all matching
 * is done by the tree itself.
 *
 * <p>Every non-empty permission of a root and of its nodes is added to the server with that node's
 * own default when the server does not know it yet - the same registration TabooLib did, so
 * LuckPerms sees the same nodes and defaults.
 *
 * <p>A plugin that disables itself once its commands are in the tree (bad config, missing
 * dependency) must not leave them callable: Paper keeps a disabled plugin's Brigadier nodes but
 * closes its class loader, so the first class a command would load fails and the sender gets "An
 * unexpected error occurred". Every requirement, executor and suggestion provider built here
 * checks the owning plugin first, and a disabled plugin's command answers like an unregistered one
 * - what TabooLib's did, since it unregistered its commands on disable. That path uses only classes
 * that are already loaded: this class, the plugin captured at registration, Brigadier, Adventure.
 *
 * <p>0.3.4: tokens are checked on {@link #register} ({@link CommandTokens}: a non-ASCII root name
 * throws, non-ASCII aliases and literals are dropped with a warning), and a root in {@link
 * RootCommand.DeniedMode#MESSAGE} answers a sender without its permission with the no-permission
 * text. The requirement still hides such a root from that sender - it is not in their command tree
 * or completion - so typing it is an unknown command to Paper; the plugin's {@link
 * UnknownCommandEvent} listener recognises its own label and replaces the message.
 */
final class CommandRegistry {

    private static final List<RootCommand> roots = new ArrayList<>();
    private static boolean hooked;

    private CommandRegistry() {}

    static void declare(String node, PermissionDefault def) {
        if (node == null || node.isEmpty()) {
            return;
        }
        PluginManager manager = Bukkit.getPluginManager();
        if (manager.getPermission(node) == null) {
            Permission permission = new Permission(node, def);
            manager.addPermission(permission);
            manager.recalculatePermissionDefaults(permission);
            permission.recalculatePermissibles();
        }
    }

    private static void declareTree(CommandNode<?> node) {
        declare(node.permission, node.permissionDefault);
        for (CommandNode<?> child : node.children) {
            declareTree(child);
        }
    }

    static synchronized void register(RootCommand root) {
        CommandTokens.check(root, Keystone.name(), Log::warn);
        declareTree(root);
        roots.add(root);
        if (hooked) {
            return;
        }
        hooked = true;
        // Captured once, so nothing below has to look the plugin up after it is gone.
        Plugin plugin = Keystone.plugin();
        Bukkit.getPluginManager()
                .registerEvent(
                        UnknownCommandEvent.class,
                        new Listener() {},
                        EventPriority.HIGH,
                        (listener, event) -> {
                            if (event instanceof UnknownCommandEvent unknown) {
                                answerDenied(plugin, unknown);
                            }
                        },
                        plugin,
                        false);
        plugin.getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> {
                            if (!plugin.isEnabled()) {
                                // Disabled in onEnable before registering: leave the tree alone.
                                return;
                            }
                            for (RootCommand command : List.copyOf(roots)) {
                                event.registrar()
                                        .register(
                                                build(command, plugin),
                                                command.description.isEmpty()
                                                        ? command.name
                                                        : command.description,
                                                command.aliases);
                            }
                        });
    }

    /**
     * A denied sender typed one of this plugin's roots in {@link RootCommand.DeniedMode#MESSAGE}:
     * Paper's unknown-command lines become the no-permission text.
     */
    private static void answerDenied(Plugin plugin, UnknownCommandEvent event) {
        List<RootCommand> known;
        synchronized (CommandRegistry.class) {
            known = List.copyOf(roots);
        }
        answerDenied(plugin, known, event);
    }

    /** {@link #answerDenied(Plugin, UnknownCommandEvent)} over the given roots. */
    static void answerDenied(Plugin plugin, List<RootCommand> known, UnknownCommandEvent event) {
        if (!plugin.isEnabled()) {
            return;
        }
        RootCommand root =
                deniedRoot(known, namespace(plugin), event.getSender(), event.getCommandLine());
        if (root != null) {
            event.message(CommandFeedback.noPermission(event.getSender(), root));
        }
    }

    /** The namespace Paper gives a plugin's commands ({@code friend:friend}). */
    static String namespace(Plugin plugin) {
        return plugin.getName().toLowerCase(Locale.ROOT);
    }

    /**
     * The root of {@code roots} that {@code commandLine} names ({@code dungeon ...}, {@code
     * /dg}, {@code dungeon:dungeon}) when it is in message mode, has a permission and {@code
     * sender} lacks it; otherwise null.
     */
    static RootCommand deniedRoot(
            List<RootCommand> roots, String namespace, CommandSender sender, String commandLine) {
        if (commandLine == null || sender == null) {
            return null;
        }
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            if (!label.substring(0, colon).equals(namespace)) {
                return null;
            }
            label = label.substring(colon + 1);
        }
        for (RootCommand root : roots) {
            if (!root.name.equals(label) && !root.aliases.contains(label)) {
                continue;
            }
            if (root.deniedMode() != RootCommand.DeniedMode.MESSAGE || root.permission.isEmpty()) {
                return null;
            }
            return sender.hasPermission(root.permission) ? null : root;
        }
        return null;
    }

    /**
     * The Brigadier node for {@code root}. Each lambda checks {@code plugin.isEnabled()} before
     * anything else: once the plugin is disabled the requirement fails (the command is absent for
     * every sender, as an unregistered one is), suggestions are empty, and the executors answer
     * with Paper's unknown-command lines instead of running plugin code.
     */
    static LiteralCommandNode<CommandSourceStack> build(RootCommand root, Plugin plugin) {
        return Commands.literal(root.name)
                .requires(
                        source ->
                                plugin.isEnabled()
                                        && (root.permission.isEmpty()
                                                || source.getSender()
                                                        .hasPermission(root.permission)))
                .executes(
                        ctx -> {
                            if (!plugin.isEnabled()) {
                                return unknownCommand(ctx);
                            }
                            root.dispatch(
                                    ctx.getSource().getSender(),
                                    labelOf(ctx.getInput(), root.name),
                                    new String[0]);
                            return 1;
                        })
                .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(
                                        (ctx, builder) ->
                                                plugin.isEnabled()
                                                        ? suggest(root, ctx.getSource(), builder)
                                                        : Suggestions.empty())
                                .executes(
                                        ctx -> {
                                            if (!plugin.isEnabled()) {
                                                return unknownCommand(ctx);
                                            }
                                            String input =
                                                    StringArgumentType.getString(ctx, "args");
                                            root.dispatch(
                                                    ctx.getSource().getSender(),
                                                    labelOf(ctx.getInput(), root.name),
                                                    splitForExecute(input));
                                            return 1;
                                        }))
                .build();
    }

    /**
     * What Paper sends for a command that is not in the tree ({@code Commands.finishParsing}): red
     * {@code command.unknown.command}, then the grey input with the unknown part red and
     * underlined, {@code <--[HERE]}, and a click that suggests the command again. Returns 0 and
     * never throws.
     *
     * <p>Only reached when the plugin was disabled between parsing and executing - the requirement
     * already hides the command from a fresh parse. Kept to already-loaded classes, with no lambda
     * or string concatenation (either would link a new call site with the class loader closed).
     */
    private static int unknownCommand(CommandContext<CommandSourceStack> ctx) {
        String input = ctx.getInput() == null ? "" : ctx.getInput();
        List<ParsedCommandNode<CommandSourceStack>> nodes = ctx.getNodes();
        int cursor = nodes.isEmpty() ? 0 : nodes.get(0).getRange().getStart();
        cursor = Math.max(0, Math.min(input.length(), cursor));
        TextComponent.Builder context =
                Component.text()
                        .color(NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.suggestCommand("/".concat(chatSafe(input))));
        if (cursor > 10) {
            context.append(Component.text("..."));
        }
        context.append(Component.text(input.substring(Math.max(0, cursor - 10), cursor)));
        if (cursor < input.length()) {
            context.append(
                    Component.text(
                            input.substring(cursor),
                            NamedTextColor.RED,
                            TextDecoration.UNDERLINED));
        }
        context.append(
                Component.translatable(
                        "command.context.here", NamedTextColor.RED, TextDecoration.ITALIC));
        ctx.getSource()
                .getSender()
                .sendMessage(
                        Component.text()
                                .color(NamedTextColor.RED)
                                .append(Component.translatable("command.unknown.command"))
                                .append(Component.newline())
                                .append(context.build())
                                .build());
        return 0;
    }

    /** Paper's {@code StringUtil.filterText}: drops the section sign, control characters and DEL. */
    private static String chatSafe(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '§' && c >= ' ' && c != 127) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static CompletableFuture<Suggestions> suggest(
            RootCommand root, CommandSourceStack source, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String[] args = splitForCompletion(remaining);
        int lastStart = remaining.lastIndexOf(' ') + 1;
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastStart);
        String label = labelOf(builder.getInput(), root.name);
        for (String value : root.complete(source.getSender(), label, args)) {
            offset.suggest(value);
        }
        return offset.buildFuture();
    }

    /**
     * The label the sender actually typed ({@code sb}, {@code soulbind:sb}), as Bukkit passed it to
     * command executors; {@code fallback} when the input has none.
     */
    static String labelOf(String input, String fallback) {
        if (input == null) {
            return fallback;
        }
        String text = input.startsWith("/") ? input.substring(1) : input;
        int space = text.indexOf(' ');
        String label = space < 0 ? text : text.substring(0, space);
        return label.isEmpty() ? fallback : label;
    }

    /** Space-split keeping a trailing empty token, so completion knows a new word has started. */
    static String[] splitForCompletion(String input) {
        return input.split(" ", -1);
    }

    /**
     * Space-split the way Bukkit split command lines ({@code String.split(" ")}): trailing empty
     * tokens are dropped, so {@code /friend add } executes like {@code /friend add}.
     */
    static String[] splitForExecute(String input) {
        String[] parts = input.split(" ");
        if (parts.length == 1 && parts[0].isEmpty()) {
            return new String[0];
        }
        return Arrays.copyOf(parts, parts.length);
    }
}

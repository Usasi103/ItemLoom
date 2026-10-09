package dev.keystone.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/**
 * A root command: label, aliases, permission and its tree. Build with {@link
 * CommandBuilder#command}, register with {@link #register()} in {@code onEnable}.
 *
 * <p>Errors are answered by {@link CommandFeedback} (0.3.4, TrMenu/LuckPerms style, Chinese,
 * overridable per plugin through lang keys); {@link #vanillaIncorrectCommand()} and {@link
 * #plainIncorrectSender()} give back the 0.3.3 texts.
 *
 * <p>A sender without the root permission never gets the command in tab completion (it is left out
 * of their command tree, as before). What typing it answers is the {@link DeniedMode}: {@link
 * DeniedMode#MESSAGE} (default since 0.3.4, the TabooLib builds' {@code permissionMessage}) or
 * {@link DeniedMode#HIDE} (0.3.3: vanilla "unknown command").
 */
public final class RootCommand extends CommandNode<RootCommand> {

    /** What a sender lacking the root permission gets when they type the command anyway. */
    public enum DeniedMode {
        /**
         * {@link #permissionMessage} or {@code keystone-command-no-permission} ("你没有权限使用这个指令。").
         */
        MESSAGE,
        /** The command does not exist for them: Paper's unknown-command lines (0.3.3 behaviour). */
        HIDE
    }

    /** The mode every root starts with. The user chose MESSAGE on 2026-09-29; one line to change. */
    public static final DeniedMode DEFAULT_DENIED_MODE = DeniedMode.MESSAGE;

    /** Handles input that does not fit the tree. */
    @FunctionalInterface
    public interface IncorrectCommand {
        /**
         * @param index 1-based position of the failing token (-1: nothing typed)
         * @param state 1 = unknown or incomplete command, 2 = incorrect argument
         */
        void handle(CommandSender sender, CommandContext context, int index, int state);
    }

    @FunctionalInterface
    public interface IncorrectSender {
        void handle(CommandSender sender, CommandContext context);
    }

    final String name;
    final List<String> aliases = new ArrayList<>();

    private IncorrectSender incorrectSender = CommandFeedback::incorrectSender;

    private IncorrectCommand incorrectCommand = CommandFeedback::incorrectCommand;

    private DeniedMode deniedMode = DEFAULT_DENIED_MODE;

    private String permissionMessage = "";

    RootCommand(String name) {
        this.name = name.toLowerCase(Locale.ROOT);
    }

    public RootCommand aliases(String... names) {
        for (String alias : names) {
            aliases.add(alias.toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /**
     * Replaces the "wrong sender type" message (default: {@link CommandFeedback}, player-only /
     * console-only / generic).
     */
    public RootCommand onIncorrectSender(IncorrectSender handler) {
        this.incorrectSender = handler;
        return this;
    }

    /**
     * Replaces the unknown / incomplete / invalid command message (default: {@link
     * CommandFeedback}).
     */
    public RootCommand onIncorrectCommand(IncorrectCommand handler) {
        this.incorrectCommand = handler;
        return this;
    }

    /** The 0.3.3 answer to bad input: vanilla's translated lines with the token marked. */
    public static IncorrectCommand vanillaIncorrectCommand() {
        return RootCommand::vanillaUnknown;
    }

    /** The 0.3.3 (TabooLib) wrong-sender text, {@code §c不匹配的命令发送者类型。}, for every type. */
    public static IncorrectSender plainIncorrectSender() {
        return (sender, context) -> sender.sendMessage("§c不匹配的命令发送者类型。");
    }

    /** What typing the command answers a sender without the root permission. */
    public RootCommand whenDenied(DeniedMode mode) {
        this.deniedMode = mode == null ? DEFAULT_DENIED_MODE : mode;
        return this;
    }

    /** {@code whenDenied(DeniedMode.HIDE)}: the 0.3.3 behaviour (vanilla unknown command). */
    public RootCommand hideWhenDenied() {
        return whenDenied(DeniedMode.HIDE);
    }

    /**
     * The no-permission text in {@link DeniedMode#MESSAGE} ({@code &} colours; {@code @key} reads
     * a lang key of the plugin). Empty (default) = {@code keystone-command-no-permission}.
     */
    public RootCommand permissionMessage(String message) {
        this.permissionMessage = message == null ? "" : message;
        return this;
    }

    public DeniedMode deniedMode() {
        return deniedMode;
    }

    public String permissionMessage() {
        return permissionMessage;
    }

    public String name() {
        return name;
    }

    public List<String> aliases() {
        return List.copyOf(aliases);
    }

    /** Registers the command (and declares its permissions). Call from {@code onEnable}. */
    public RootCommand register() {
        CommandRegistry.register(this);
        return this;
    }

    @Override
    boolean accepts(CommandContext context, String token) {
        return false;
    }

    @Override
    boolean named(String id) {
        return false;
    }

    // ---- dispatch -----------------------------------------------------------

    /** Handles one invocation; {@code args} are the tokens after the label. */
    public void dispatch(CommandSender sender, String label, String[] args) {
        CommandContext context = new CommandContext(sender, label, args);
        context.root = this;
        if (args.length == 0) {
            List<CommandNode<?>> children = visibleChildren(sender);
            if (children.isEmpty() || anyOptional(children) || action != null) {
                context.index = 0;
                context.currentNode = this;
                run(this, context, "");
            } else {
                context.currentNode = this;
                context.failedNode = this;
                incorrectCommand.handle(sender, context, -1, 1);
            }
            return;
        }
        CommandNode<?> node = this;
        int cur = 0;
        while (true) {
            context.index = cur;
            context.currentNode = node;
            CommandNode<?> found = node.match(context, args[cur]);
            if (found == null) {
                context.currentNode = node;
                context.failedNode = node;
                incorrectCommand.handle(sender, context, cur + 1, 2);
                return;
            }
            List<CommandNode<?>> children = found.visibleChildren(sender);
            if (cur + 1 < args.length && !children.isEmpty()) {
                node = found;
                cur++;
                continue;
            }
            if (children.isEmpty() || anyOptional(children) || found.action != null) {
                context.currentNode = found;
                run(found, context, context.self());
            } else {
                context.currentNode = found;
                context.failedNode = found;
                incorrectCommand.handle(sender, context, cur + 1, 1);
            }
            return;
        }
    }

    /** Completions for the last token of {@code args} (a trailing space = an empty last token). */
    public List<String> complete(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        CommandContext context = new CommandContext(sender, label, args);
        CommandNode<?> node = this;
        int cur = 0;
        while (true) {
            context.index = cur;
            context.currentNode = node;
            String current = args[cur];
            CommandNode<?> found = node.match(context, current);
            if (found != null) {
                context.currentNode = found;
            }
            if (found != null && cur + 1 < args.length) {
                node = found;
                cur++;
                continue;
            }
            if (cur + 1 != args.length) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (CommandNode<?> child : node.visibleChildren(sender)) {
                if (child instanceof LiteralNode literal) {
                    if (!literal.hidden) {
                        values.addAll(literal.aliases);
                    }
                } else if (child instanceof ArgumentNode argument) {
                    List<String> suggested = argument.suggestions(context);
                    if (suggested != null) {
                        values.addAll(suggested);
                    }
                }
            }
            if (current.isEmpty()) {
                return values;
            }
            String needle = current.toLowerCase(Locale.ROOT);
            List<String> filtered = new ArrayList<>();
            for (String value : values) {
                if (value.toLowerCase(Locale.ROOT).contains(needle)) {
                    filtered.add(value);
                }
            }
            return filtered;
        }
    }

    private static boolean anyOptional(List<CommandNode<?>> children) {
        for (CommandNode<?> child : children) {
            if (child.isOptional()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void run(CommandNode<?> node, CommandContext context, String argument) {
        if (node.action == null) {
            String text = CommandFeedback.text(context.sender(), CommandFeedback.NO_EXECUTOR);
            if (!text.isEmpty()) {
                context.sender().sendMessage(text);
            }
            return;
        }
        if (!node.actionType.isInstance(context.sender())) {
            incorrectSender.handle(context.sender(), context);
            return;
        }
        ((CommandAction) node.action).run(context.sender(), context, argument);
    }

    /** The vanilla "unknown command" / "incorrect argument" lines with the failing token marked. */
    private static void vanillaUnknown(
            CommandSender sender, CommandContext context, int index, int state) {
        String[] raw = context.rawArgs();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < Math.min(Math.max(index, 0), raw.length); i++) {
            args.add(raw[i]);
        }
        String text = context.label();
        if (args.size() > 1) {
            text += " " + String.join(" ", args.subList(0, args.size() - 1)).trim();
        }
        if (text.length() > 10) {
            text = "..." + text.substring(text.length() - 10);
        }
        String key = state == 2 ? "command.unknown.argument" : "command.unknown.command";
        sender.sendMessage(Component.translatable(key, NamedTextColor.RED));
        Component line = Component.text(text, NamedTextColor.GRAY);
        if (!args.isEmpty()) {
            line =
                    line.append(Component.text(" "))
                            .append(
                                    Component.text(
                                            args.get(args.size() - 1),
                                            NamedTextColor.RED,
                                            TextDecoration.UNDERLINED));
        }
        line =
                line.append(
                        Component.translatable(
                                "command.context.here", NamedTextColor.RED, TextDecoration.ITALIC));
        sender.sendMessage(line);
    }
}

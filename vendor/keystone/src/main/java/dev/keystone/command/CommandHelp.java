package dev.keystone.command;

import dev.keystone.lang.Lang;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * Help for a root command.
 *
 * <p>Since 0.3.4 {@link #descriptionHelper()} and {@link #helper()} print a TrMenu-style page
 * ({@link CommandFeedback}, lang keys {@code keystone-help-*}): the plugin name and version, the
 * root's description, {@code 指令：/friend [...]}, then every usage the sender may run, each
 * clickable, with its description on the next line. Children are filtered like dispatch does (no
 * permission set = allowed) and hidden literals are left out.
 *
 * <p>{@link #legacyDescriptionHelper()}, {@link #legacyHelper()} and {@link #text} keep the 0.3.3
 * output, the usage tree TabooLib's command helper printed ({@code createHelper} / {@code
 * createDescriptionHelper}):
 *
 * <pre>
 * §cUsage: /friend
 *         §7├── §clist §7- §c好友列表
 *         §7└── §cnote §7&lt;玩家&gt; §8[&lt;备注&gt;]
 * </pre>
 *
 * <p>Root children are filtered by {@code sender.hasPermission(node.permission)} exactly as
 * TabooLib did - including for an empty permission, which Bukkit answers with the OP default - and
 * hidden literals are left out. Names or descriptions starting with {@code @} are lang keys.
 *
 * <p>Use as an executor: {@code root.executes(CommandHelp.descriptionHelper())}.
 */
public final class CommandHelp {

    private CommandHelp() {}

    /** Executor printing the help page with descriptions (0.3.4 page; see the class comment). */
    public static CommandAction<CommandSender> descriptionHelper() {
        return new HelpAction(true);
    }

    /** Executor printing the help page without descriptions. */
    public static CommandAction<CommandSender> helper() {
        return new HelpAction(false);
    }

    /** Executor printing the 0.3.3 usage tree with descriptions ({@code §cUsage: /x ...}). */
    public static CommandAction<CommandSender> legacyDescriptionHelper() {
        return (sender, context, argument) -> print(sender, context, true);
    }

    /** Executor printing the 0.3.3 usage tree without descriptions. */
    public static CommandAction<CommandSender> legacyHelper() {
        return (sender, context, argument) -> print(sender, context, false);
    }

    /**
     * Sends {@code root}'s help page (with descriptions) to {@code sender}, for a plugin that
     * prints help itself (a console hint, a bare command) instead of through an executor.
     */
    public static void send(CommandSender sender, RootCommand root) {
        send(sender, root, root.name(), true);
    }

    /** {@link #send(CommandSender, RootCommand)} with the label shown and clicked. */
    public static void send(
            CommandSender sender, RootCommand root, String label, boolean descriptions) {
        CommandFeedback.send(sender, CommandFeedback.helpLines(root, sender, label, descriptions));
    }

    /** The page executor; {@link CommandFeedback} recognises it to point at the help command. */
    static final class HelpAction implements CommandAction<CommandSender> {
        private final boolean descriptions;

        HelpAction(boolean descriptions) {
            this.descriptions = descriptions;
        }

        @Override
        public void run(CommandSender sender, CommandContext context, String argument) {
            CommandNode<?> node = context.currentNode;
            while (node != null && !(node instanceof RootCommand)) {
                node = node.parent;
            }
            if (node instanceof RootCommand root) {
                send(sender, root, context.label(), descriptions);
            }
        }
    }

    private static void print(CommandSender sender, CommandContext context, boolean descriptions) {
        CommandNode<?> node = context.currentNode;
        while (node != null && !(node instanceof RootCommand)) {
            node = node.parent;
        }
        if (!(node instanceof RootCommand root)) {
            return;
        }
        List<CommandNode<?>> children = new ArrayList<>();
        for (CommandNode<?> child : root.children) {
            if (sender.hasPermission(child.permission) && visible(child)) {
                children.add(child);
            }
        }
        // TabooLib's plain helper filtered every level by permission, the description helper only
        // the root level.
        Printer printer =
                new Printer(
                        root.name,
                        key -> Lang.text(sender, key),
                        descriptions,
                        descriptions ? n -> true : n -> sender.hasPermission(n.permission));
        String text = printer.run(children);
        for (String line : text.split("\n", -1)) {
            sender.sendMessage(line);
        }
    }

    private static boolean visible(CommandNode<?> node) {
        return !(node instanceof LiteralNode literal) || !literal.hidden;
    }

    /** Resolves {@code @key} texts. */
    @FunctionalInterface
    public interface LangResolver {
        String resolve(String key);
    }

    /** The help text for a command named {@code commandName} with these (filtered) children. */
    public static String text(
            String commandName,
            List<CommandNode<?>> rootChildren,
            LangResolver lang,
            boolean descriptions) {
        return new Printer(commandName, lang, descriptions, n -> true).run(rootChildren);
    }

    private static final class Printer {
        final StringBuilder builder;
        final String commandName;
        final LangResolver lang;
        final boolean descriptions;
        final java.util.function.Predicate<CommandNode<?>> allowed;
        boolean newline;

        Printer(
                String commandName,
                LangResolver lang,
                boolean descriptions,
                java.util.function.Predicate<CommandNode<?>> allowed) {
            this.builder = new StringBuilder("§cUsage: /" + commandName);
            this.commandName = commandName;
            this.lang = lang;
            this.descriptions = descriptions;
            this.allowed = allowed;
        }

        String run(List<CommandNode<?>> rootChildren) {
            for (int i = 0; i < rootChildren.size(); i++) {
                print(
                        rootChildren.get(i),
                        i,
                        rootChildren.size(),
                        8,
                        0,
                        i + 1 == rootChildren.size(),
                        false,
                        "");
            }
            return builder.toString();
        }

        String resolve(String text) {
            return text.startsWith("@") ? lang.resolve(text.substring(1)) : text;
        }

        void print(
                CommandNode<?> compound,
                int index,
                int size,
                int offset,
                int level,
                boolean end,
                boolean optional,
                String parentDescription) {
            boolean option = optional;
            int comment = 0;
            String currentDescription = parentDescription;
            if (compound instanceof LiteralNode literal) {
                String name = literal.aliases.get(0);
                if (size == 1) {
                    builder.append(" ").append("§c").append(name);
                } else {
                    newline = true;
                    builder.append('\n');
                    builder.append(" ".repeat(offset));
                    if (level > 1) {
                        builder.append(end ? " " : "§7│");
                    }
                    builder.append(" ".repeat(level));
                    builder.append(index + 1 < size ? "§7├── " : "§7└── ");
                    builder.append("§c").append(name);
                }
                currentDescription = literal.description;
                option = false;
                comment = name.length();
            } else if (compound instanceof ArgumentNode argument) {
                String value = resolve(argument.name);
                if (argument.isOptional() || option) {
                    option = true;
                    builder.append(" ").append("§8[<").append(value).append(">]");
                    comment = argument.name.length() + 4;
                } else {
                    builder.append(" ").append("§7<").append(value).append(">");
                    comment = argument.name.length() + 2;
                }
                if (!argument.description.isEmpty()) {
                    currentDescription = argument.description;
                }
            }
            if (level > 0) {
                comment += 1;
            }
            List<CommandNode<?>> children = new ArrayList<>();
            for (CommandNode<?> child : compound.children) {
                if (visible(child) && allowed.test(child)) {
                    children.add(child);
                }
            }
            if (descriptions && children.isEmpty() && !currentDescription.isEmpty()) {
                builder.append(" §7- §c").append(resolve(currentDescription));
            }
            for (int i = 0; i < children.size(); i++) {
                CommandNode<?> child = children.get(i);
                if (newline) {
                    print(
                            child,
                            i,
                            children.size(),
                            offset,
                            level + comment,
                            end,
                            option,
                            currentDescription);
                } else {
                    int length = offset == 8 ? commandName.length() + 1 : comment + 1;
                    print(
                            child,
                            i,
                            children.size(),
                            offset + length,
                            level,
                            end,
                            option,
                            currentDescription);
                }
            }
        }
    }
}

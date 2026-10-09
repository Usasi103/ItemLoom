package dev.keystone.command;

import dev.keystone.Keystone;
import dev.keystone.lang.Lang;
import dev.keystone.lang.LangNode;
import dev.keystone.util.Texts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/**
 * What a player reads when a command goes wrong, modelled on TrMenu 3.12.5 (TabooLib 6.3) and
 * LuckPerms 5.5 as they answer on Paper 26.2 (captured in the Keystone sandbox, 2026-09-29):
 *
 * <ul>
 *   <li>a red headline that names the error kind (unknown sub-command, incomplete, invalid value,
 *       wrong sender, no permission);
 *   <li>the TabooLib/vanilla context line with the wrong fragment in {@code §c§n}: {@code §7friend
 *       §c§nfoo§c§o<--[此处]};
 *   <li>the correct usage of the sub-command concerned, or a compact clickable list of what may
 *       follow (LuckPerms' {@code > /lp user <user>} list), capped at {@link #LIST_LIMIT} entries;
 *   <li>a TrMenu-style help page ({@link CommandHelp}).
 * </ul>
 *
 * <p>Every text is a lang key ({@code keystone-command-*}, {@code keystone-help-*}) with a Chinese
 * default ({@link #defaults()}). A plugin overrides one by putting the key into its own {@code
 * lang/<code>.yml}; an empty string there switches that line off. {@code {0}}, {@code {1}} are the
 * arguments listed with each key.
 */
public final class CommandFeedback {

    /** {0} = the unknown token. */
    public static final String UNKNOWN = "keystone-command-unknown";

    /** {0} = what is missing: {@code <玩家>}, {@code 子指令} or {@code 参数}. */
    public static final String INCOMPLETE = "keystone-command-incomplete";

    /** {0} = the rejected value, {1} = the argument ({@code <玩家>}). */
    public static final String INVALID = "keystone-command-invalid";

    /** {0} = the accepted values, comma-separated (strict suggestions only). */
    public static final String CHOICES = "keystone-command-choices";

    /** {0} = the typed text before the wrong fragment, {1} = the wrong fragment. */
    public static final String CONTEXT = "keystone-command-context";

    /** {0} = the usage ({@code /friend add <玩家>}), {1} = {@link #USAGE_NOTE} or empty. */
    public static final String USAGE = "keystone-command-usage";

    /** Header of the list of possible continuations. */
    public static final String USAGE_LIST = "keystone-command-usage-list";

    /** {0} = one usage, {1} = {@link #USAGE_NOTE} or empty. */
    public static final String USAGE_ENTRY = "keystone-command-usage-entry";

    /** {0} = the description of a usage. */
    public static final String USAGE_NOTE = "keystone-command-usage-note";

    /** {0} = how many entries were left out. */
    public static final String USAGE_MORE = "keystone-command-usage-more";

    /** {0} = the help command ({@code /friend help}). */
    public static final String HELP_HINT = "keystone-command-help-hint";

    /** Hover of a clickable usage; {0} = the text it puts into the chat box. */
    public static final String CLICK = "keystone-command-click";

    /** A player-only command from the console. */
    public static final String SENDER_PLAYER = "keystone-command-sender-player";

    /** A console-only command from a player. */
    public static final String SENDER_CONSOLE = "keystone-command-sender-console";

    /** Any other sender type mismatch (the TabooLib text). */
    public static final String SENDER = "keystone-command-sender";

    /** A root command the sender lacks the permission for (message mode). */
    public static final String NO_PERMISSION = "keystone-command-no-permission";

    /** A node that ends the input but has no executor. */
    public static final String NO_EXECUTOR = "keystone-command-no-executor";

    /** {0} = plugin name, {1} = plugin version. */
    public static final String HELP_HEADER = "keystone-help-header";

    /** {0} = the root command's description. */
    public static final String HELP_DESCRIPTION = "keystone-help-description";

    /** {0} = the label, {1} = {@code [...]} when there are sub-commands. */
    public static final String HELP_COMMAND = "keystone-help-command";

    /** Header of the sub-command list. */
    public static final String HELP_ARGUMENTS = "keystone-help-arguments";

    /** {0} = one sub-command usage after the label. */
    public static final String HELP_ENTRY = "keystone-help-entry";

    /** {0} = its description. */
    public static final String HELP_ENTRY_DESCRIPTION = "keystone-help-entry-description";

    /** At most this many usages in an error's list; the rest are counted. */
    public static final int LIST_LIMIT = 8;

    /** At most this many accepted values in {@link #CHOICES}. */
    static final int CHOICE_LIMIT = 10;

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(UNKNOWN, "&c未知的子指令：&f{0}");
        DEFAULTS.put(INCOMPLETE, "&c指令不完整，缺少 &f{0}");
        DEFAULTS.put(INVALID, "&c无效的 &f{1}&c：&f{0}");
        DEFAULTS.put(CHOICES, "&7可选：&f{0}");
        DEFAULTS.put(CONTEXT, "&7{0}&c&n{1}&c&o<--[此处]");
        DEFAULTS.put(USAGE, "&7用法：{0}{1}");
        DEFAULTS.put(USAGE_LIST, "&7可用的指令：");
        DEFAULTS.put(USAGE_ENTRY, "&8- {0}{1}");
        DEFAULTS.put(USAGE_NOTE, " &8- &7{0}");
        DEFAULTS.put(USAGE_MORE, "&8…… 还有 {0} 项");
        DEFAULTS.put(HELP_HINT, "&7输入 &f{0} &7查看全部用法。");
        DEFAULTS.put(CLICK, "&7点击填入 &f{0}");
        DEFAULTS.put(SENDER_PLAYER, "&c这个指令只能由玩家使用。");
        DEFAULTS.put(SENDER_CONSOLE, "&c这个指令只能在控制台使用。");
        DEFAULTS.put(SENDER, "&c不匹配的命令发送者类型。");
        DEFAULTS.put(NO_PERMISSION, "&c你没有权限使用这个指令。");
        DEFAULTS.put(NO_EXECUTOR, "&c空命令（无执行器）。");
        DEFAULTS.put(HELP_HEADER, "  &3{0} &f{1}");
        DEFAULTS.put(HELP_DESCRIPTION, "  &7{0}");
        DEFAULTS.put(HELP_COMMAND, "  &7指令：&f/{0} &8{1}");
        DEFAULTS.put(HELP_ARGUMENTS, "  &7参数：");
        DEFAULTS.put(HELP_ENTRY, "    &8- &f{0}");
        DEFAULTS.put(HELP_ENTRY_DESCRIPTION, "      &7{0}");
    }

    private CommandFeedback() {}

    /** Every key with its built-in (Chinese) template, in display order. */
    public static Map<String, String> defaults() {
        return Collections.unmodifiableMap(DEFAULTS);
    }

    /**
     * The rendered text of {@code key} for {@code sender}: the plugin's own lang entry when it has
     * one (an empty entry gives ""), otherwise the built-in default; colour codes applied, then the
     * arguments filled. Lines of a list entry are joined with {@code \n}.
     */
    public static String text(CommandSender sender, String key, Object... args) {
        LangNode node = Lang.nodesFor(sender).get(key);
        if (node instanceof LangNode.Text entry) {
            return entry.text() == null ? "" : Lang.render(sender, entry.text(), args);
        }
        if (node instanceof LangNode.Lines lines) {
            List<String> rendered = new ArrayList<>();
            for (LangNode.Text line : lines.lines()) {
                if (line.text() != null) {
                    rendered.add(Lang.render(sender, line.text(), args));
                }
            }
            return String.join("\n", rendered);
        }
        String template = DEFAULTS.get(key);
        return template == null ? "{" + key + "}" : Lang.render(sender, template, args);
    }

    // ---- sending --------------------------------------------------------------------

    /** A line of legacy text; with {@code suggest} it puts that text into the chat box on click. */
    record Line(String text, String suggest) {}

    static void send(CommandSender sender, List<Line> lines) {
        for (Line line : lines) {
            for (String part : line.text().split("\n", -1)) {
                sender.sendMessage(component(sender, part, line.suggest()));
            }
        }
    }

    static Component component(CommandSender sender, String legacy, String suggest) {
        Component component =
                LegacyComponentSerializer.legacySection()
                        .deserialize(Lang.forConsole(sender, legacy));
        if (suggest != null && !(sender instanceof ConsoleCommandSender)) {
            component =
                    component
                            .clickEvent(ClickEvent.suggestCommand(suggest))
                            .hoverEvent(
                                    HoverEvent.showText(
                                            LegacyComponentSerializer.legacySection()
                                                    .deserialize(text(sender, CLICK, suggest))));
        }
        return component;
    }

    private static void add(List<Line> lines, String text, String suggest) {
        if (!text.isEmpty()) {
            lines.add(new Line(text, suggest));
        }
    }

    // ---- the error kinds --------------------------------------------------------------

    /**
     * The default {@link RootCommand.IncorrectCommand}: unknown sub-command, incomplete command or
     * invalid value, each with the context line and the usage of the sub-command concerned.
     */
    static void incorrectCommand(
            CommandSender sender, CommandContext context, int index, int state) {
        send(sender, incorrectLines(sender, context, index, state));
    }

    static List<Line> incorrectLines(
            CommandSender sender, CommandContext context, int index, int state) {
        RootCommand root = context.root;
        CommandNode<?> failed = context.failedNode != null ? context.failedNode : root;
        String[] raw = context.rawArgs();
        List<CommandNode<?>> shown = shown(failed, sender);
        List<Line> lines = new ArrayList<>();
        String typed = index >= 1 && index <= raw.length ? raw[index - 1] : "";
        ArgumentNode rejectedBy = null;
        if (state == 2) {
            for (CommandNode<?> child : shown) {
                if (child instanceof ArgumentNode argument) {
                    rejectedBy = argument;
                    break;
                }
            }
            if (rejectedBy == null) {
                add(lines, text(sender, UNKNOWN, typed), null);
            } else {
                add(
                        lines,
                        text(sender, INVALID, typed, "<" + name(sender, rejectedBy) + ">"),
                        null);
            }
        } else {
            add(
                    lines,
                    text(
                            sender,
                            INCOMPLETE,
                            failed.usage.isEmpty() ? missing(sender, shown) : "参数"),
                    null);
        }
        add(lines, contextLine(sender, context, index), "/" + typedLine(context));
        if (rejectedBy != null) {
            List<String> choices = rejectedBy.strictChoices(context);
            if (choices != null && !choices.isEmpty()) {
                String joined =
                        String.join(
                                ", ", choices.subList(0, Math.min(CHOICE_LIMIT, choices.size())));
                add(
                        lines,
                        text(
                                sender,
                                CHOICES,
                                choices.size() > CHOICE_LIMIT ? joined + ", …" : joined),
                        null);
            }
        }
        if (root != null) {
            usageLines(sender, context, failed, shown, lines);
        }
        return lines;
    }

    /** What an incomplete command lacks: the single argument, or "sub-command" / "argument". */
    private static String missing(CommandSender sender, List<CommandNode<?>> shown) {
        if (shown.size() == 1 && shown.get(0) instanceof ArgumentNode argument) {
            return "<" + name(sender, argument) + ">";
        }
        for (CommandNode<?> child : shown) {
            if (!(child instanceof LiteralNode)) {
                return "参数";
            }
        }
        return "子指令";
    }

    /**
     * TabooLib's context line ({@code CommandBase.commandIncorrectCommand}): the label and the
     * tokens before the failing one, cut to the last 10 characters behind {@code ...}, then the
     * failing token highlighted.
     */
    static String contextLine(CommandSender sender, CommandContext context, int index) {
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
        String wrong = "";
        if (!args.isEmpty()) {
            text += " ";
            wrong = args.get(args.size() - 1);
        }
        return text(sender, CONTEXT, text, wrong);
    }

    private static String typedLine(CommandContext context) {
        String[] raw = context.rawArgs();
        return raw.length == 0 ? context.label() : context.label() + " " + String.join(" ", raw);
    }

    /** The usage of {@code failed}'s continuations: one line, or a capped list plus a help hint. */
    private static void usageLines(
            CommandSender sender,
            CommandContext context,
            CommandNode<?> failed,
            List<CommandNode<?>> shown,
            List<Line> lines) {
        if (shown.isEmpty()) {
            return;
        }
        String prefix = pathPrefix(context, failed);
        String suggestPrefix = pathSuggest(context, failed);
        boolean optional = failed.action != null;
        if (shown.size() == 1 || !failed.usage.isEmpty()) {
            CommandNode<?> child = shown.get(0);
            String usage =
                    !failed.usage.isEmpty()
                            ? prefix + " §7" + usageText(sender, failed)
                            : prefix
                                    + " "
                                    + token(sender, child, optional)
                                    + rest(sender, child, optional, 0);
            String description = failed.usage.isEmpty() ? describe(child, sender) : "";
            if (description.isEmpty()) {
                description = inheritedDescription(failed);
            }
            add(
                    lines,
                    text(sender, USAGE, usage, note(sender, description)),
                    suggestPrefix + suggestTail(child));
            return;
        }
        add(lines, text(sender, USAGE_LIST), null);
        int count = 0;
        for (CommandNode<?> child : shown) {
            if (count == LIST_LIMIT) {
                break;
            }
            String usage =
                    prefix
                            + " "
                            + token(sender, child, optional)
                            + rest(sender, child, optional, 0);
            add(
                    lines,
                    text(sender, USAGE_ENTRY, usage, note(sender, describe(child, sender))),
                    suggestPrefix + suggestTail(child));
            count++;
        }
        if (shown.size() > LIST_LIMIT) {
            add(lines, text(sender, USAGE_MORE, shown.size() - LIST_LIMIT), null);
            String help = helpCommand(context.root, sender, context.label());
            if (help != null) {
                add(lines, text(sender, HELP_HINT, help), help);
            }
        }
    }

    private static String note(CommandSender sender, String description) {
        return description == null || description.isEmpty()
                ? ""
                : text(sender, USAGE_NOTE, resolve(sender, description));
    }

    /** {@code §f/label} plus the tokens typed for the path down to {@code node}. */
    private static String pathPrefix(CommandContext context, CommandNode<?> node) {
        String[] raw = context.rawArgs();
        StringBuilder out = new StringBuilder("§f/").append(context.label());
        for (int i = 0; i <= node.index() && i < raw.length; i++) {
            out.append(' ').append(raw[i]);
        }
        return out.toString();
    }

    private static String pathSuggest(CommandContext context, CommandNode<?> node) {
        String[] raw = context.rawArgs();
        StringBuilder out = new StringBuilder("/").append(context.label());
        for (int i = 0; i <= node.index() && i < raw.length; i++) {
            out.append(' ').append(raw[i]);
        }
        return out.append(' ').toString();
    }

    /** The literal names from {@code node} down its single-child chain, up to the first argument. */
    private static String suggestTail(CommandNode<?> node) {
        StringBuilder out = new StringBuilder();
        CommandNode<?> current = node;
        while (current instanceof LiteralNode literal) {
            out.append(literal.aliases.get(0)).append(' ');
            if (current.children.size() != 1) {
                break;
            }
            current = current.children.get(0);
        }
        return out.toString();
    }

    /** The help command a sender may run for {@code root} ({@code /x help}, else {@code /x}). */
    static String helpCommand(RootCommand root, CommandSender sender, String label) {
        if (root == null) {
            return null;
        }
        for (CommandNode<?> child : shown(root, sender)) {
            if (child instanceof LiteralNode literal
                    && literal.action instanceof CommandHelp.HelpAction) {
                return "/" + label + " " + literal.aliases.get(0);
            }
        }
        return root.action instanceof CommandHelp.HelpAction ? "/" + label : null;
    }

    /** The wrong sender type: player-only, console-only, or TabooLib's generic text. */
    static void incorrectSender(CommandSender sender, CommandContext context) {
        Class<?> type = context.currentNode == null ? null : context.currentNode.actionType;
        String key = SENDER;
        if (type != null && Player.class.isAssignableFrom(type)) {
            key = SENDER_PLAYER;
        } else if (type != null && ConsoleCommandSender.class.isAssignableFrom(type)) {
            key = SENDER_CONSOLE;
        }
        List<Line> lines = new ArrayList<>();
        add(lines, text(sender, key), null);
        send(sender, lines);
    }

    /**
     * The no-permission answer for {@code root}: its own {@link RootCommand#permissionMessage}
     * ({@code @key} = a lang key of the plugin, else legacy {@code &} text), otherwise {@link
     * #NO_PERMISSION}. Null when that text is empty.
     */
    static Component noPermission(CommandSender sender, RootCommand root) {
        String custom = root.permissionMessage();
        String legacy;
        if (custom.isEmpty()) {
            legacy = text(sender, NO_PERMISSION);
        } else if (custom.startsWith("@")) {
            String own = Lang.textOrNull(sender, custom.substring(1));
            legacy = own == null ? text(sender, NO_PERMISSION) : own;
        } else {
            legacy = Lang.render(sender, custom);
        }
        if (legacy.isEmpty()) {
            return null;
        }
        String[] parts = legacy.split("\n", -1);
        Component out = component(sender, parts[0], null);
        for (int i = 1; i < parts.length; i++) {
            out = out.append(Component.newline()).append(component(sender, parts[i], null));
        }
        return out;
    }

    // ---- usage patterns ---------------------------------------------------------------

    /** Children a sender may use and completion shows (hidden literals left out), in order. */
    static List<CommandNode<?>> shown(CommandNode<?> node, CommandSender sender) {
        List<CommandNode<?>> result = new ArrayList<>();
        for (CommandNode<?> child : node.visibleChildren(sender)) {
            if (!(child instanceof LiteralNode literal) || !literal.hidden) {
                result.add(child);
            }
        }
        return result;
    }

    static String name(CommandSender sender, ArgumentNode argument) {
        return resolve(sender, argument.name);
    }

    /** {@code §flist}, {@code §7<玩家>} or {@code §8[<备注>]}. */
    static String token(CommandSender sender, CommandNode<?> node, boolean optional) {
        if (node instanceof LiteralNode literal) {
            return "§f" + literal.aliases.get(0);
        }
        ArgumentNode argument = (ArgumentNode) node;
        String name = name(sender, argument);
        return optional || argument.isOptional() ? "§8[<" + name + ">]" : "§7<" + name + ">";
    }

    /**
     * What follows {@code node}'s token: its {@link CommandNode#usage} when set, the single child
     * chain written out, {@code <a|b>} / {@code [a|b]} for up to four literal choices, else {@code
     * <...>} (required) or {@code [...]} (optional, the node executes on its own).
     */
    static String rest(CommandSender sender, CommandNode<?> node, boolean optional, int depth) {
        if (!node.usage.isEmpty()) {
            return " §7" + usageText(sender, node);
        }
        List<CommandNode<?>> children = shown(node, sender);
        if (children.isEmpty() || depth > 8) {
            return "";
        }
        boolean childOptional = childrenOptional(node, optional);
        if (children.size() == 1) {
            CommandNode<?> child = children.get(0);
            return " "
                    + token(sender, child, childOptional)
                    + rest(sender, child, childOptional, depth + 1);
        }
        boolean literals = children.size() <= 4;
        List<String> names = new ArrayList<>();
        for (CommandNode<?> child : children) {
            if (child instanceof LiteralNode literal) {
                names.add(literal.aliases.get(0));
            } else {
                literals = false;
            }
        }
        String inner = literals ? String.join("|", names) : "...";
        return childOptional ? " §8[" + inner + "]" : " §7<" + inner + ">";
    }

    /**
     * Whether {@code node}'s children are optional: a literal resets it (its children are optional
     * when it executes on its own, TabooLib's helper), an argument keeps it once set.
     */
    static boolean childrenOptional(CommandNode<?> node, boolean optional) {
        return node instanceof LiteralNode ? node.action != null : optional || node.action != null;
    }

    /**
     * {@code node}'s own description; an argument without one inherits it from above, up to the
     * nearest literal (TabooLib's description helper did the same).
     */
    static String inheritedDescription(CommandNode<?> node) {
        CommandNode<?> current = node;
        while (current != null
                && current.description.isEmpty()
                && current instanceof ArgumentNode) {
            current = current.parent;
        }
        return current == null ? "" : current.description;
    }

    /** The first description down {@code node}'s single-child chain, or "". */
    static String describe(CommandNode<?> node, CommandSender sender) {
        CommandNode<?> current = node;
        for (int depth = 0; depth < 8; depth++) {
            if (!current.description.isEmpty()) {
                return current.description;
            }
            List<CommandNode<?>> children = shown(current, sender);
            if (children.size() != 1) {
                return "";
            }
            current = children.get(0);
        }
        return "";
    }

    /** {@code @key} texts are lang keys of the plugin. */
    static String resolve(CommandSender sender, String text) {
        return text.startsWith("@") ? Lang.text(sender, text.substring(1)) : text;
    }

    /** A {@link CommandNode#usage} text, resolved and coloured (it goes in as an argument). */
    static String usageText(CommandSender sender, CommandNode<?> node) {
        return Texts.colored(resolve(sender, node.usage));
    }

    // ---- help page --------------------------------------------------------------------

    /** One line of the help page: the usage after the label and its description. */
    record Entry(String usage, String description, String suggest) {}

    /**
     * Every usage below {@code root} a sender may run, depth first in declaration order. A chain
     * without branches is one entry ({@code note <玩家> [<备注>]}); a branching node gets an entry of
     * its own only when it executes. Descriptions: a literal's own, an argument's own or inherited
     * from above (TabooLib's description helper).
     */
    static List<Entry> entries(RootCommand root, CommandSender sender, String label) {
        List<Entry> out = new ArrayList<>();
        for (CommandNode<?> child : shown(root, sender)) {
            flatten(sender, child, "", "/" + label + " ", false, root.action != null, "", out, 0);
        }
        return out;
    }

    /**
     * @param suggest what a click puts into the chat box: the label and the literals so far
     * @param frozen an argument was passed, so later literals are no longer added to {@code suggest}
     */
    private static void flatten(
            CommandSender sender,
            CommandNode<?> node,
            String prefix,
            String suggest,
            boolean frozen,
            boolean optional,
            String inherited,
            List<Entry> out,
            int depth) {
        String token = token(sender, node, optional);
        String here = prefix.isEmpty() ? token : prefix + " " + token;
        boolean literal = node instanceof LiteralNode;
        String nextSuggest =
                literal && !frozen ? suggest + ((LiteralNode) node).aliases.get(0) + " " : suggest;
        boolean nextFrozen = frozen || !literal;
        String description =
                !node.description.isEmpty() ? node.description : literal ? "" : inherited;
        if (!node.usage.isEmpty()) {
            out.add(new Entry(here + " §7" + usageText(sender, node), description, nextSuggest));
            return;
        }
        List<CommandNode<?>> children = shown(node, sender);
        boolean childOptional = childrenOptional(node, optional);
        if (children.isEmpty() || depth > 8) {
            out.add(new Entry(here, description, nextSuggest));
            return;
        }
        if (children.size() == 1) {
            flatten(
                    sender,
                    children.get(0),
                    here,
                    nextSuggest,
                    nextFrozen,
                    childOptional,
                    description,
                    out,
                    depth + 1);
            return;
        }
        if (node.action != null) {
            out.add(new Entry(here, description, nextSuggest));
        }
        for (CommandNode<?> child : children) {
            flatten(
                    sender,
                    child,
                    here,
                    nextSuggest,
                    nextFrozen,
                    childOptional,
                    description,
                    out,
                    depth + 1);
        }
    }

    /** The TrMenu-style help page of {@code root} for {@code sender}. */
    static List<Line> helpLines(
            RootCommand root, CommandSender sender, String label, boolean descriptions) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("", null));
        add(lines, text(sender, HELP_HEADER, Keystone.name(), Keystone.version()), null);
        if (descriptions && !root.description.isEmpty()) {
            add(lines, text(sender, HELP_DESCRIPTION, resolve(sender, root.description)), null);
        }
        lines.add(new Line("", null));
        List<Entry> entries = entries(root, sender, label);
        add(
                lines,
                text(sender, HELP_COMMAND, label, entries.isEmpty() ? "" : "[...]"),
                "/" + label + " ");
        if (!entries.isEmpty()) {
            add(lines, text(sender, HELP_ARGUMENTS), null);
        }
        for (Entry entry : entries) {
            add(lines, text(sender, HELP_ENTRY, entry.usage()), entry.suggest());
            if (descriptions && !entry.description().isEmpty()) {
                add(
                        lines,
                        text(sender, HELP_ENTRY_DESCRIPTION, resolve(sender, entry.description())),
                        null);
            }
        }
        lines.add(new Line("", null));
        return lines;
    }
}

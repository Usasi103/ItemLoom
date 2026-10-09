package dev.keystone.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;

/**
 * One node of a command tree, built fluently: {@code literal("add").then(argument("玩家")
 * .suggest(...).executes(Player.class, ...))}.
 *
 * <p>Parsing follows the rules the plugins were written against (TabooLib's command module), so
 * command behaviour does not change for players:
 *
 * <ul>
 *   <li>input is split on spaces; a literal matches one token case-insensitively by any alias; an
 *       argument matches any non-empty token that passes its restriction and, when its suggestion
 *       is strict, is one of the suggested values;
 *   <li>children the sender lacks the permission for are invisible;
 *   <li>a child of a node that has an executor is optional;
 *   <li>the value of the node that executes is its token plus every token after it, so a trailing
 *       argument swallows the rest of the line ({@code /friend note Steve 这是 备注}).
 * </ul>
 *
 * <p>Children are matched in the order they were added.
 */
public abstract class CommandNode<N extends CommandNode<N>> {

    String permission = "";
    PermissionDefault permissionDefault = PermissionDefault.OP;
    boolean optional;
    String description = "";
    String usage = "";
    CommandNode<?> parent;
    final List<CommandNode<?>> children = new ArrayList<>();
    Class<?> actionType;
    CommandAction<?> action;

    CommandNode() {}

    @SuppressWarnings("unchecked")
    private N self() {
        return (N) this;
    }

    /**
     * Permission required to see and use this node. When the server does not know the node yet it
     * is registered with {@code PermissionDefault.OP} (TabooLib's default for sub commands).
     */
    public N permission(String node) {
        return permission(node, PermissionDefault.OP);
    }

    /** Permission plus the default it is registered with when the server does not know it. */
    public N permission(String node, PermissionDefault def) {
        this.permission = node == null ? "" : node;
        this.permissionDefault = def;
        return self();
    }

    public N optional() {
        this.optional = true;
        return self();
    }

    /** Text for the generated help ({@link CommandHelp}); {@code @key} reads a lang key. */
    public N description(String text) {
        this.description = text == null ? "" : text;
        return self();
    }

    /**
     * The parameters shown after this node in usage lines and the help page, replacing the ones
     * generated from its children ({@code literal("conset").usage("<玩家> <物品ID> <等级>")}); {@code
     * &} colours allowed, {@code @key} reads a lang key. Display only: parsing is unchanged.
     */
    public N usage(String text) {
        this.usage = text == null ? "" : text;
        return self();
    }

    /** What runs when the input ends here, for senders of {@code type}. */
    public <T> N executes(Class<T> type, CommandAction<? super T> action) {
        this.actionType = type;
        this.action = action;
        return self();
    }

    /** What runs when the input ends here, for any sender. */
    public N executes(CommandAction<CommandSender> action) {
        return executes(CommandSender.class, action);
    }

    /** Appends children, in matching order. */
    public N then(CommandNode<?>... nodes) {
        for (CommandNode<?> node : nodes) {
            node.parent = this;
            children.add(node);
        }
        return self();
    }

    public String permission() {
        return permission;
    }

    public PermissionDefault permissionDefault() {
        return permissionDefault;
    }

    public String description() {
        return description;
    }

    /** The usage text set with {@link #usage(String)}, or "". */
    public String usage() {
        return usage;
    }

    public List<CommandNode<?>> children() {
        return Collections.unmodifiableList(children);
    }

    public CommandNode<?> parent() {
        return parent;
    }

    public boolean hasExecutor() {
        return action != null;
    }

    /** Depth below the root: the root is -1, its children 0 (their token index). */
    int index() {
        return parent == null ? -1 : parent.index() + 1;
    }

    /** Declared optional, or the parent can already execute on its own. */
    boolean isOptional() {
        return optional || (parent != null && parent.action != null);
    }

    List<CommandNode<?>> visibleChildren(CommandSender sender) {
        List<CommandNode<?>> result = new ArrayList<>(children.size());
        for (CommandNode<?> child : children) {
            if (child.permission.isEmpty() || sender.hasPermission(child.permission)) {
                result.add(child);
            }
        }
        return result;
    }

    CommandNode<?> match(CommandContext context, String token) {
        for (CommandNode<?> child : visibleChildren(context.sender())) {
            context.currentNode = child;
            if (child.accepts(context, token)) {
                return child;
            }
        }
        return null;
    }

    abstract boolean accepts(CommandContext context, String token);

    /** Whether {@code id} names this node (a literal alias or an argument name). */
    abstract boolean named(String id);
}

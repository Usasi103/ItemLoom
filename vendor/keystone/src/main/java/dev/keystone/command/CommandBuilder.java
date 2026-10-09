package dev.keystone.command;

import java.util.List;
import org.bukkit.permissions.PermissionDefault;

/**
 * Entry points of the command tree; use with {@code import static
 * dev.keystone.command.CommandBuilder.*}.
 *
 * <pre>{@code
 * command("friend").aliases("f").permission("friend.use", PermissionDefault.TRUE)
 *     .executes((sender, ctx, arg) -> ...)
 *     .then(literal("list").executes(Player.class, (p, ctx, arg) -> ...))
 *     .then(literal("note").then(argument("玩家")
 *         .suggest(Player.class, (p, ctx) -> names(p))
 *         .executes(Player.class, (p, ctx, name) -> ...)
 *         .then(argument("备注").executes(Player.class, (p, ctx, note) -> ...))))
 *     .register();
 * }</pre>
 */
public final class CommandBuilder {

    private CommandBuilder() {}

    /** A root command named {@code name} (its root permission defaults to OP when declared). */
    public static RootCommand command(String name) {
        return new RootCommand(name);
    }

    /** A fixed word; the first alias is its name in help. */
    public static LiteralNode literal(String... aliases) {
        return new LiteralNode(List.of(aliases));
    }

    /** Any token, named for {@link CommandContext#get} and shown as {@code <name>} in help. */
    public static ArgumentNode argument(String name) {
        return new ArgumentNode(name);
    }

    /** Declares a permission node with its default, independent of any command. */
    public static void permission(String node, PermissionDefault def) {
        CommandRegistry.declare(node, def);
    }
}

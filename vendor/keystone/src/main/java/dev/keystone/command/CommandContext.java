package dev.keystone.command;

import java.util.Arrays;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The input being executed or completed.
 *
 * <p>Values are looked up by node name: {@code context.get("玩家")} returns the token typed for the
 * argument (or literal) of that name on the path to the executing node. The executing node's own
 * value also swallows every token after it.
 */
public final class CommandContext {

    private final CommandSender sender;
    private final String label;
    private final String[] rawArgs;
    int index;
    CommandNode<?> currentNode;

    /** The root being dispatched (for the default error feedback). */
    RootCommand root;

    /** On an error: the node whose children did not match or were missing. */
    CommandNode<?> failedNode;

    CommandContext(CommandSender sender, String label, String[] rawArgs) {
        this.sender = sender;
        this.label = label;
        this.rawArgs = rawArgs;
    }

    public CommandSender sender() {
        return sender;
    }

    /** {@link #sender} as a player; throws for the console. */
    public Player player() {
        return (Player) sender;
    }

    /** The label the command was typed with. */
    public String label() {
        return label;
    }

    /** The tokens after the label. */
    public String[] rawArgs() {
        return rawArgs.clone();
    }

    /** The tokens up to the current position, the current one joined with everything after it. */
    public String[] args() {
        if (rawArgs.length == 0) {
            return new String[0];
        }
        int current = Math.max(0, Math.min(index, rawArgs.length - 1));
        String[] result = Arrays.copyOfRange(rawArgs, 0, current + 1);
        String tail = String.join(" ", Arrays.copyOfRange(rawArgs, current + 1, rawArgs.length));
        result[current] = (result[current] + " " + tail).trim();
        return result;
    }

    /** The current node's value: its token plus everything after it. */
    public String self() {
        String[] args = args();
        return index < args.length ? args[index] : "";
    }

    /** The value of the node named {@code id}; throws when there is none. */
    public String get(String id) {
        String value = getOrNull(id);
        if (value == null) {
            throw new IllegalStateException("参数 " + id + " 不存在");
        }
        return value;
    }

    public String getOrNull(String id) {
        CommandNode<?> node = currentNode;
        while (node != null && !node.named(id)) {
            node = node.parent;
        }
        if (node == null || node.index() < 0) {
            return null;
        }
        String[] args = args();
        int at = node.index();
        return at < args.length ? args[at] : null;
    }

    /** The value {@code offset} positions from the current one (negative = earlier). */
    public String argument(int offset) {
        return args()[index + offset];
    }

    public String argumentOrNull(int offset) {
        String[] args = args();
        int at = index + offset;
        return at >= 0 && at < args.length ? args[at] : null;
    }
}

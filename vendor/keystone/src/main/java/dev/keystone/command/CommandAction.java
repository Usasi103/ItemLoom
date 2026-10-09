package dev.keystone.command;

/** What runs when the input ends on (or is swallowed by) a node. */
@FunctionalInterface
public interface CommandAction<T> {
    /**
     * @param sender the sender, already checked to be of the node's sender type
     * @param context the whole input, for looking up other arguments by name
     * @param argument the value of the executing node: its token plus every token after it
     */
    void run(T sender, CommandContext context, String argument);
}

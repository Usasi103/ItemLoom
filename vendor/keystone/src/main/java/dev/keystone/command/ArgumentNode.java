package dev.keystone.command;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Any token, named for {@link CommandContext#get}. */
public final class ArgumentNode extends CommandNode<ArgumentNode> {

    final String name;
    private Class<?> suggestionType;
    private Suggester<?> suggester;
    private boolean strict;
    private Class<?> restrictionType;
    private Restriction<?> restriction;

    ArgumentNode(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * Tab-completion values for senders of {@code type}. Strict: input must be one of them,
     * otherwise the command reports an incorrect argument (TabooLib's default, {@code uncheck =
     * false}). Replaces any restriction.
     */
    public <T> ArgumentNode suggest(Class<T> type, Suggester<? super T> suggester) {
        return suggest(type, true, suggester);
    }

    /** {@link #suggest(Class, Suggester)} with an explicit strictness. */
    public <T> ArgumentNode suggest(Class<T> type, boolean strict, Suggester<? super T> suggester) {
        this.suggestionType = type;
        this.suggester = suggester;
        this.strict = strict;
        this.restrictionType = null;
        this.restriction = null;
        return this;
    }

    /** Strict suggestions for any sender. */
    public ArgumentNode suggest(Suggester<CommandSender> suggester) {
        return suggest(CommandSender.class, true, suggester);
    }

    /** Suggestions that do not restrict the input (TabooLib's {@code uncheck = true}). */
    public ArgumentNode suggestUnchecked(Suggester<CommandSender> suggester) {
        return suggest(CommandSender.class, false, suggester);
    }

    /** {@link #suggestUnchecked(Suggester)} for senders of {@code type}. */
    public <T> ArgumentNode suggestUnchecked(Class<T> type, Suggester<? super T> suggester) {
        return suggest(type, false, suggester);
    }

    /** Online player names, not strict. */
    public ArgumentNode suggestPlayers() {
        return suggestUnchecked(
                (sender, context) -> {
                    List<String> names = new ArrayList<>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        names.add(player.getName());
                    }
                    return names;
                });
    }

    /** Accepts or rejects the typed value for senders of {@code type}. Replaces any suggestion. */
    public <T> ArgumentNode restrict(Class<T> type, Restriction<? super T> restriction) {
        this.restrictionType = type;
        this.restriction = restriction;
        this.suggestionType = null;
        this.suggester = null;
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    List<String> suggestions(CommandContext context) {
        if (suggester == null || !suggestionType.isInstance(context.sender())) {
            return null;
        }
        return ((Suggester) suggester).suggest(context.sender(), context);
    }

    /**
     * The values a strict suggestion accepts for this argument (null when the argument is not
     * strict or has no opinion for this sender), with the context pointed at this node as when it
     * was matched.
     */
    List<String> strictChoices(CommandContext context) {
        if (suggester == null || !strict) {
            return null;
        }
        CommandNode<?> before = context.currentNode;
        context.currentNode = this;
        try {
            return suggestions(context);
        } catch (RuntimeException e) {
            return null;
        } finally {
            context.currentNode = before;
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean accepts(CommandContext context, String token) {
        if (token.isEmpty()) {
            return false;
        }
        if (restriction != null
                && restrictionType.isInstance(context.sender())
                && !((Restriction) restriction).test(context.sender(), context, token)) {
            return false;
        }
        if (suggester != null && strict) {
            List<String> values = suggestions(context);
            return values == null || values.contains(token);
        }
        return true;
    }

    @Override
    boolean named(String id) {
        return name.equals(id);
    }
}

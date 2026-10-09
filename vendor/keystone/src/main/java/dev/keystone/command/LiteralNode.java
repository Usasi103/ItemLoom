package dev.keystone.command;

import java.util.List;

/** A fixed word, matched case-insensitively by any of its aliases. */
public final class LiteralNode extends CommandNode<LiteralNode> {

    /** Not final: registration drops aliases that are not typable ASCII ({@link CommandTokens}). */
    List<String> aliases;

    boolean hidden;

    LiteralNode(List<String> aliases) {
        if (aliases.isEmpty()) {
            throw new IllegalArgumentException("a literal needs at least one name");
        }
        this.aliases = List.copyOf(aliases);
    }

    /** Leaves this literal out of tab completion and generated help. */
    public LiteralNode hidden() {
        this.hidden = true;
        return this;
    }

    public List<String> aliases() {
        return aliases;
    }

    public boolean isHidden() {
        return hidden;
    }

    @Override
    boolean accepts(CommandContext context, String token) {
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    @Override
    boolean named(String id) {
        return aliases.contains(id);
    }
}

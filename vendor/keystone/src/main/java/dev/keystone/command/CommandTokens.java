package dev.keystone.command;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Command tokens must be typable ASCII (user rule, 2026-09-29): a root name, root alias or literal
 * sub-command containing anything outside the printable ASCII range ({@code !} to {@code ~}) is
 * rejected when the command is registered.
 *
 * <ul>
 *   <li>a root <b>name</b> fails loudly: {@link #check} throws, so the plugin's {@code onEnable}
 *       reports it as a registration error;
 *   <li>a root <b>alias</b> or a literal <b>alias</b> is dropped with a warning naming the plugin
 *       and the token; a literal left without any name is dropped with its whole subtree, again
 *       with a warning.
 * </ul>
 *
 * <p>Argument names ({@code argument("玩家")}) are display text, never typed, and are not checked.
 * Tab completion order is untouched (Brigadier sorts suggestions alphabetically).
 */
final class CommandTokens {

    private CommandTokens() {}

    /** Whether {@code token} is non-empty and only printable ASCII without spaces. */
    static boolean isTypable(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= ' ' || c >= 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the tokens that are not typable ASCII from {@code root}, reporting each through
     * {@code warn}; throws {@link IllegalArgumentException} when the root name itself is not.
     *
     * @param plugin the owning plugin's name, for the messages
     */
    static void check(RootCommand root, String plugin, Consumer<String> warn) {
        if (!isTypable(root.name)) {
            String message =
                    "插件 "
                            + plugin
                            + " 注册的指令名 \""
                            + root.name
                            + "\" 含有非 ASCII 字符，拒绝注册（指令名、别名和子指令只能用英文字母、数字和符号）";
            warn.accept(message);
            throw new IllegalArgumentException(message);
        }
        for (Iterator<String> it = root.aliases.iterator(); it.hasNext(); ) {
            String alias = it.next();
            if (!isTypable(alias)) {
                it.remove();
                warn.accept(
                        "插件 "
                                + plugin
                                + " 的指令 /"
                                + root.name
                                + " 的别名 \""
                                + alias
                                + "\" 含有非 ASCII 字符，已忽略这个别名");
            }
        }
        checkChildren(root, "/" + root.name, plugin, warn);
    }

    private static void checkChildren(
            CommandNode<?> node, String path, String plugin, Consumer<String> warn) {
        for (Iterator<CommandNode<?>> it = node.children.iterator(); it.hasNext(); ) {
            CommandNode<?> child = it.next();
            if (child instanceof LiteralNode literal) {
                List<String> kept = new ArrayList<>();
                for (String alias : literal.aliases) {
                    if (isTypable(alias)) {
                        kept.add(alias);
                    } else {
                        warn.accept(
                                "插件 "
                                        + plugin
                                        + " 的子指令 "
                                        + path
                                        + " "
                                        + alias
                                        + " 含有非 ASCII 字符，"
                                        + (kept.size() + remaining(literal, alias) > 0
                                                ? "已忽略这个名字"
                                                : "这个子指令没有别的名字，已整个跳过"));
                    }
                }
                if (kept.isEmpty()) {
                    it.remove();
                    continue;
                }
                if (kept.size() != literal.aliases.size()) {
                    literal.aliases = List.copyOf(kept);
                }
                checkChildren(literal, path + " " + literal.aliases.get(0), plugin, warn);
            } else {
                String name = child instanceof ArgumentNode argument ? argument.name : "?";
                checkChildren(child, path + " <" + name + ">", plugin, warn);
            }
        }
    }

    /** How many typable aliases of {@code literal} come after {@code alias}. */
    private static int remaining(LiteralNode literal, String alias) {
        int at = literal.aliases.indexOf(alias);
        int count = 0;
        for (int i = at + 1; i < literal.aliases.size(); i++) {
            if (isTypable(literal.aliases.get(i))) {
                count++;
            }
        }
        return count;
    }
}

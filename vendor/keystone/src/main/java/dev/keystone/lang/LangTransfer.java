package dev.keystone.lang;

import org.bukkit.command.CommandSender;

/** Rewrites a lang template before its {@code {n}} arguments are filled in. */
@FunctionalInterface
public interface LangTransfer {
    String transfer(CommandSender sender, String source);
}

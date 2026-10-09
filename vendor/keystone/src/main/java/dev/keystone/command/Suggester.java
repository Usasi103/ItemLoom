package dev.keystone.command;

import java.util.List;

/** Candidate values for an argument; null means "no opinion" (and a strict check passes). */
@FunctionalInterface
public interface Suggester<T> {
    List<String> suggest(T sender, CommandContext context);
}

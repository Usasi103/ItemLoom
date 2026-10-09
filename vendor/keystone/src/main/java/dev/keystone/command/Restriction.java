package dev.keystone.command;

/** Accepts or rejects the typed value of an argument. */
@FunctionalInterface
public interface Restriction<T> {
    boolean test(T sender, CommandContext context, String argument);
}

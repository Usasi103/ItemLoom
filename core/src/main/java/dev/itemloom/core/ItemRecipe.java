package dev.itemloom.core;

/** Compiled behavior. Neither the engine nor this contract knows its source syntax. */
@FunctionalInterface
public interface ItemRecipe<T> {
    T create(GenerationContext context);
}

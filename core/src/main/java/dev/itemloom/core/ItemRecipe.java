package dev.itemloom.core;

@FunctionalInterface
public interface ItemRecipe<T> {
    T create(GenerationContext context);
}

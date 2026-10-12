package dev.itemloom.paper.sx;

import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import java.util.Collection;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Restores selected old state on an owned candidate, including explicitly protected absence. */
final class SxProtectedState {
    private SxProtectedState() {}

    static void validate(String path) {
        if (path.isBlank() || path.startsWith(ItemStateCodec.KEY)) {
            throw new IllegalArgumentException("Invalid protected SX path: " + path);
        }
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Empty protected SX path segment: " + path);
            }
        }
        if (path.startsWith("components.")) {
            component(path);
        }
    }

    private static DataComponentType<?> component(String path) {
        String name = path.substring("components.".length()).split("\\.", 2)[0];
        Identifier identifier = Identifier.tryParse(name);
        DataComponentType<?> type =
                identifier == null
                        ? null
                        : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(identifier);
        if (type == null || type == DataComponents.CUSTOM_DATA) {
            throw new IllegalArgumentException("Invalid protected SX component: " + path);
        }
        return type;
    }

    static ItemStack restore(ItemStack before, ItemStack candidate, Collection<String> paths) {
        var previous = CraftItemStack.asNMSCopy(before);
        var next = CraftItemStack.asNMSCopy(candidate);
        CompoundTag oldCustom = NmsItems.customData(before);
        CompoundTag newCustom = NmsItems.customData(candidate);
        for (String path : paths) {
            if (path.startsWith("components.")) {
                copyComponent(previous, next, component(path));
            } else {
                restorePath(oldCustom, newCustom, path.split("\\."));
            }
        }
        next.set(DataComponents.CUSTOM_DATA, CustomData.of(newCustom));
        return CraftItemStack.asCraftMirror(next);
    }

    private static <T> void copyComponent(
            net.minecraft.world.item.ItemStack previous,
            net.minecraft.world.item.ItemStack next,
            DataComponentType<T> type) {
        T value = previous.get(type);
        if (value == null) {
            next.remove(type);
        } else {
            next.set(type, value);
        }
    }

    private static void restorePath(CompoundTag previous, CompoundTag next, String[] path) {
        Tag found = previous;
        for (String part : path) {
            found = found instanceof CompoundTag compound ? compound.get(part) : null;
        }
        CompoundTag parent = next;
        for (int index = 0; index < path.length - 1; index++) {
            Tag child = parent.get(path[index]);
            if (child instanceof CompoundTag compound) {
                parent = compound;
            } else if (found == null) {
                return;
            } else {
                CompoundTag created = new CompoundTag();
                parent.put(path[index], created);
                parent = created;
            }
        }
        String leaf = path[path.length - 1];
        if (found == null) {
            parent.remove(leaf);
        } else {
            parent.put(leaf, found.copy());
        }
    }
}

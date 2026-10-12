package dev.itemloom.paper.compat.nbt;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.itemloom.paper.compat.NiItemMigration;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Binary NBT and full item serialization at the script compatibility boundary. */
public final class LegacyNbtUtils {
    private LegacyNbtUtils() {}

    public static LegacyNbt.Compound readCompressed(File source) throws IOException {
        return wrap(NbtIo.readCompressed(source.toPath(), NbtAccounter.unlimitedHeap()));
    }

    public static LegacyNbt.Compound readCompressed(InputStream source) throws IOException {
        return wrap(NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap()));
    }

    public static void writeCompressed(LegacyNbt.Compound value, File target) throws IOException {
        NbtIo.writeCompressed(value.copyTag(), target.toPath());
    }

    public static void writeCompressed(LegacyNbt.Compound value, OutputStream target)
            throws IOException {
        NbtIo.writeCompressed(value.copyTag(), target);
    }

    public static LegacyNbt.Compound read(File source) throws IOException {
        return wrap(NbtIo.read(source.toPath()));
    }

    public static LegacyNbt.Compound read(DataInputStream source) throws IOException {
        return wrap(NbtIo.read(source));
    }

    public static void write(LegacyNbt.Compound value, File target) throws IOException {
        NbtIo.write(value.copyTag(), target.toPath());
    }

    public static void write(LegacyNbt.Compound value, DataOutput target) throws IOException {
        NbtIo.write(value.copyTag(), target);
    }

    public static LegacyNbt.Compound parse(String text) {
        try {
            return wrap(TagParser.parseCompoundFully(text));
        } catch (CommandSyntaxException failure) {
            throw new IllegalArgumentException("Invalid SNBT", failure);
        }
    }

    public static boolean isCraftItemStack(ItemStack item) {
        return item instanceof CraftItemStack;
    }

    public static ItemMeta getItemMeta(ItemStack item) {
        return item.getItemMeta();
    }

    public static ItemStack bukkitCopy(ItemStack item) {
        return asBukkitCopy(item);
    }

    public static ItemStack asCopy(ItemStack item) {
        return item.clone();
    }

    public static ItemStack asBukkitCopy(ItemStack item) {
        return CraftItemStack.asBukkitCopy(CraftItemStack.asNMSCopy(item));
    }

    public static ItemStack asCraftCopy(ItemStack item) {
        return CraftItemStack.asCraftCopy(item);
    }

    public static LegacyNbt.Compound coverWith(
            LegacyNbt.Compound receiver, LegacyNbt.Compound source) {
        return receiver.coverWith(source);
    }

    public static LegacyNbt.Compound save(ItemStack item) {
        var encoded =
                net.minecraft.world.item.ItemStack.CODEC
                        .encodeStart(
                                CraftRegistry.getMinecraftRegistry()
                                        .createSerializationContext(NbtOps.INSTANCE),
                                CraftItemStack.asNMSCopy(item))
                        .getOrThrow();
        return new LegacyNbt.Compound((CompoundTag) encoded);
    }

    public static ItemStack of(LegacyNbt.Compound value) {
        if (value == null) {
            return new ItemStack(Material.AIR);
        }
        var decoded =
                net.minecraft.world.item.ItemStack.CODEC
                        .parse(
                                CraftRegistry.getMinecraftRegistry()
                                        .createSerializationContext(NbtOps.INSTANCE),
                                value.copyTag())
                        .getOrThrow();
        return new NiItemMigration().convert(CraftItemStack.asCraftMirror(decoded));
    }

    private static LegacyNbt.Compound wrap(CompoundTag value) {
        return value == null ? null : new LegacyNbt.Compound(value);
    }
}

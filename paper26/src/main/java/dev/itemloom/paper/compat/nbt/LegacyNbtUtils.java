package dev.itemloom.paper.compat.nbt;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
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
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import dev.itemloom.paper.compat.NiItemMigration;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The file/serialization subset of the old NbtUtils API, using only 26.2 implementations. */
public final class LegacyNbtUtils {
    private LegacyNbtUtils() {}

    public static LegacyNbt.Compound readCompressed(File file) throws IOException {
        return wrap(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()));
    }

    public static LegacyNbt.Compound readCompressed(InputStream stream) throws IOException {
        return wrap(NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap()));
    }

    public static void writeCompressed(LegacyNbt.Compound compound, File file) throws IOException {
        NbtIo.writeCompressed(compound.copyTag(), file.toPath());
    }

    public static void writeCompressed(LegacyNbt.Compound compound, OutputStream stream)
            throws IOException {
        NbtIo.writeCompressed(compound.copyTag(), stream);
    }

    public static LegacyNbt.Compound read(File file) throws IOException {
        return wrap(NbtIo.read(file.toPath()));
    }

    public static LegacyNbt.Compound read(DataInputStream stream) throws IOException {
        return wrap(NbtIo.read(stream));
    }

    public static void write(LegacyNbt.Compound compound, File file) throws IOException {
        NbtIo.write(compound.copyTag(), file.toPath());
    }

    public static void write(LegacyNbt.Compound compound, DataOutput output) throws IOException {
        NbtIo.write(compound.copyTag(), output);
    }

    public static LegacyNbt.Compound parse(String snbt) {
        try {
            return wrap(TagParser.parseCompoundFully(snbt));
        } catch (CommandSyntaxException error) {
            throw new IllegalArgumentException("Invalid SNBT: " + error.getMessage(), error);
        }
    }

    private static LegacyNbt.Compound wrap(CompoundTag tag) {
        return tag == null ? null : new LegacyNbt.Compound(tag);
    }

    public static boolean isCraftItemStack(ItemStack item) {
        return item instanceof CraftItemStack;
    }

    public static ItemMeta getItemMeta(ItemStack item) {
        return item.getItemMeta();
    }

    public static ItemStack bukkitCopy(ItemStack item) {
        return item.clone();
    }

    public static ItemStack asCopy(ItemStack item) {
        return item.clone();
    }

    public static ItemStack asBukkitCopy(ItemStack item) {
        return CraftItemStack.asBukkitCopy(CraftItemStack.unwrap(item));
    }

    public static ItemStack asCraftCopy(ItemStack item) {
        return CraftItemStack.asCraftCopy(item);
    }

    public static LegacyNbt.Compound coverWith(
            LegacyNbt.Compound receiver, LegacyNbt.Compound overlay) {
        return receiver.coverWith(overlay);
    }

    /** Full item serialization preserves the independent envelope and every component verbatim. */
    public static LegacyNbt.Compound save(ItemStack item) {
        Tag encoded =
                net.minecraft.world.item.ItemStack.CODEC
                        .encodeStart(ops(), CraftItemStack.asNMSCopy(item))
                        .getOrThrow();
        return new LegacyNbt.Compound((CompoundTag) encoded);
    }

    public static ItemStack of(LegacyNbt.Compound data) {
        if (data == null) return new ItemStack(Material.AIR);
        var decoded =
                net.minecraft.world.item.ItemStack.CODEC.parse(ops(), data.copyTag()).getOrThrow();
        ItemStack result = CraftItemStack.asCraftMirror(decoded);
        return new NiItemMigration().convert(result);
    }

    private static DynamicOps<Tag> ops() {
        return CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
    }
}

package dev.itemloom.paper.compat.script;

import java.util.Map;
import jdk.dynalink.beans.StaticClass;
import dev.itemloom.compat.ni.script.LegacyScriptBindings;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.nbt.LegacyNbtUtils;

/** Only maps script imports; it does not install old-package classes in the JVM. */
public final class NbtScriptAliases {
    private NbtScriptAliases() {}

    public static void install(LegacyScriptBindings bindings) {
        Map<String, Class<?>> types =
                Map.ofEntries(
                        Map.entry("Nbt", LegacyNbt.class),
                                Map.entry("NbtCompound", LegacyNbt.Compound.class),
                        Map.entry("NbtList", LegacyNbt.ListValue.class),
                                Map.entry("NbtNumeric", LegacyNbt.Numeric.class),
                        Map.entry("NbtByte", LegacyNbt.ByteValue.class),
                                Map.entry("NbtShort", LegacyNbt.ShortValue.class),
                        Map.entry("NbtInt", LegacyNbt.IntValue.class),
                                Map.entry("NbtLong", LegacyNbt.LongValue.class),
                        Map.entry("NbtFloat", LegacyNbt.FloatValue.class),
                                Map.entry("NbtDouble", LegacyNbt.DoubleValue.class),
                        Map.entry("NbtString", LegacyNbt.StringValue.class),
                                Map.entry("NbtByteArray", LegacyNbt.ByteArray.class),
                        Map.entry("NbtIntArray", LegacyNbt.IntArray.class),
                                Map.entry("NbtLongArray", LegacyNbt.LongArray.class),
                        Map.entry("NbtEnd", LegacyNbt.End.class),
                                Map.entry("NbtType", LegacyNbt.Type.class),
                        Map.entry("Nbt$Unsafe", LegacyNbt.Unsafe.class),
                                Map.entry("NbtCompound$Unsafe", LegacyNbt.Compound.Unsafe.class),
                        Map.entry("NbtItemStack", LegacyNbtItemStack.class),
                                Map.entry("NbtUtils", LegacyNbtUtils.class));
        types.forEach(
                (name, type) ->
                        bindings.alias(
                                "pers.neige.neigeitems.libs.bot.inker.bukkit.nbt." + name,
                                StaticClass.forClass(type)));
    }
}

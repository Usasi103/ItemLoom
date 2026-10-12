package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.script.LegacyScriptBindings;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import dev.itemloom.paper.compat.nbt.LegacyNbtUtils;
import jdk.dynalink.beans.StaticClass;

/** Resolves supported legacy script names without defining classes in the old namespace. */
public final class NbtScriptAliases {
    private static final String PREFIX = "pers.neige.neigeitems.libs.bot.inker.bukkit.nbt.";

    private NbtScriptAliases() {}

    public static void install(LegacyScriptBindings bindings) {
        bindings.alias(PREFIX + "Nbt", StaticClass.forClass(LegacyNbt.class));
        bindings.alias(PREFIX + "NbtCompound", StaticClass.forClass(LegacyNbt.Compound.class));
        bindings.alias(PREFIX + "NbtList", StaticClass.forClass(LegacyNbt.ListValue.class));
        bindings.alias(PREFIX + "NbtNumeric", StaticClass.forClass(LegacyNbt.Numeric.class));
        bindings.alias(PREFIX + "NbtByte", StaticClass.forClass(LegacyNbt.ByteValue.class));
        bindings.alias(PREFIX + "NbtShort", StaticClass.forClass(LegacyNbt.ShortValue.class));
        bindings.alias(PREFIX + "NbtInt", StaticClass.forClass(LegacyNbt.IntValue.class));
        bindings.alias(PREFIX + "NbtLong", StaticClass.forClass(LegacyNbt.LongValue.class));
        bindings.alias(PREFIX + "NbtFloat", StaticClass.forClass(LegacyNbt.FloatValue.class));
        bindings.alias(PREFIX + "NbtDouble", StaticClass.forClass(LegacyNbt.DoubleValue.class));
        bindings.alias(PREFIX + "NbtString", StaticClass.forClass(LegacyNbt.StringValue.class));
        bindings.alias(PREFIX + "NbtByteArray", StaticClass.forClass(LegacyNbt.ByteArray.class));
        bindings.alias(PREFIX + "NbtIntArray", StaticClass.forClass(LegacyNbt.IntArray.class));
        bindings.alias(PREFIX + "NbtLongArray", StaticClass.forClass(LegacyNbt.LongArray.class));
        bindings.alias(PREFIX + "NbtEnd", StaticClass.forClass(LegacyNbt.End.class));
        bindings.alias(PREFIX + "NbtType", StaticClass.forClass(LegacyNbt.Type.class));
        bindings.alias(PREFIX + "Nbt$Unsafe", StaticClass.forClass(LegacyNbt.Unsafe.class));
        bindings.alias(
                PREFIX + "NbtCompound$Unsafe",
                StaticClass.forClass(LegacyNbt.Compound.Unsafe.class));
        bindings.alias(PREFIX + "NbtItemStack", StaticClass.forClass(LegacyNbtItemStack.class));
        bindings.alias(PREFIX + "NbtUtils", StaticClass.forClass(LegacyNbtUtils.class));
    }
}

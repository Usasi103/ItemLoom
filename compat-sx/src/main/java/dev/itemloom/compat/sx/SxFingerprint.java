package dev.itemloom.compat.sx;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;

/** Versioned, type-delimited semantic encoding. No runtime identity or generated values enter it. */
public final class SxFingerprint {
    private SxFingerprint() {}

    public static String of(SxRepository.Input input) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream stream = new DataOutputStream(bytes);
            write(stream, "ItemLoom SX revision 1");
            write(stream, input.settings().values());
            stream.writeInt(input.items().size());
            for (var entry : input.items().entrySet()) {
                write(stream, entry.getKey());
                var definition = entry.getValue();
                write(stream, definition.id());
                write(stream, definition.source());
                write(stream, definition.alias());
                write(stream, definition.config() == null ? null : definition.config().values());
            }
            stream.writeInt(input.random().size());
            for (var entry : input.random().entrySet()) {
                write(stream, entry.getKey());
                write(stream, entry.getValue().semanticInput());
            }
            write(stream, input.scripts());
            write(stream, input.sources());
            return "sha256-v1:"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot fingerprint SX revision", error);
        }
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void write(DataOutputStream output, Object value) throws IOException {
        if (value == null) {
            output.writeByte(0);
        } else if (value instanceof String text) {
            output.writeByte(1);
            string(output, text);
        } else if (value instanceof Boolean bool) {
            output.writeByte(2);
            output.writeBoolean(bool);
        } else if (value instanceof Number number) {
            output.writeByte(3);
            string(output, number.getClass().getName());
            string(output, number.toString());
        } else if (value instanceof Map<?, ?> map) {
            output.writeByte(4);
            output.writeInt(map.size());
            for (var entry : map.entrySet()) {
                write(output, entry.getKey());
                write(output, entry.getValue());
            }
        } else if (value instanceof java.util.List<?> list) {
            output.writeByte(5);
            output.writeInt(list.size());
            for (Object entry : list) {
                write(output, entry);
            }
        } else if (value.getClass().isArray()) {
            output.writeByte(6);
            string(output, value.getClass().getComponentType().getName());
            output.writeInt(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                write(output, Array.get(value, index));
            }
        } else if (value instanceof ItemStack item) {
            output.writeByte(7);
            write(output, item.serialize());
        } else if (value instanceof ConfigurationSerializable serializable) {
            output.writeByte(8);
            string(output, value.getClass().getName());
            write(output, serializable.serialize());
        } else if (value instanceof Enum<?> enumeration) {
            output.writeByte(9);
            string(output, enumeration.getDeclaringClass().getName());
            string(output, enumeration.name());
        } else if (value instanceof UUID uuid) {
            output.writeByte(10);
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported deterministic SX input: " + value.getClass().getName());
        }
    }
}

package dev.keystone.storage;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files this plugin must not write (0.3.4, user rule: a broken data file is never overwritten or
 * lost). A file is blocked when it could not be read, could not be understood, or its bytes could
 * not be copied to a verified backup; {@link StorageWriter#writeAtomic} refuses a blocked file with
 * an {@link IOException}, and {@code ConfigFile.saveToFile()} skips it with a warning.
 *
 * <p>{@code ConfigFile} blocks and unblocks its own files: it blocks on a failed load and unblocks
 * on the next load that reads the file cleanly. A plugin that reads a file itself can use the same
 * registry: {@link #block} after a failed read, {@link #unblock} once it read the file again or
 * made its own verified copy.
 *
 * <p>Per plugin: Keystone is shaded into each plugin, so every plugin guards its own files. Paths
 * are compared in their absolute, normalised form.
 */
public final class WriteGuard {

    private static final Map<String, String> BLOCKED = new ConcurrentHashMap<>();

    private WriteGuard() {}

    private static String key(File file) {
        return file.getAbsoluteFile().toPath().normalize().toString();
    }

    /** Blocks writes to {@code file}; {@code reason} is shown whenever a write is refused. */
    public static void block(File file, String reason) {
        BLOCKED.put(key(file), reason == null || reason.isEmpty() ? "文件无法读取" : reason);
    }

    /** Lets writes to {@code file} through again. */
    public static void unblock(File file) {
        BLOCKED.remove(key(file));
    }

    public static boolean isBlocked(File file) {
        return BLOCKED.containsKey(key(file));
    }

    /** Why writes to {@code file} are blocked, or null. */
    public static String reason(File file) {
        return BLOCKED.get(key(file));
    }

    /** Throws when {@code file} is blocked. */
    static void check(File file) throws IOException {
        String reason = reason(file);
        if (reason != null) {
            throw new IOException("拒绝写入 " + file.getPath() + "：" + reason);
        }
    }

    /** Test hook: forgets every block. */
    static void clear() {
        BLOCKED.clear();
    }
}

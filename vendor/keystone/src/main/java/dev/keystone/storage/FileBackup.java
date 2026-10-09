package dev.keystone.storage;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Verified backups of a file next to it, the way {@code ConfigFile} keeps a file it cannot use
 * (0.3.5: the same method, made public, so plugins can drop their private copies).
 *
 * <ul>
 *   <li>The copy is {@code <name>_yyyyMMddHHmmss.bak} in the same folder (TabooLib's name, which
 *       every Keystone plugin already uses). A second copy within the same second gets {@code _2},
 *       {@code _3} ... instead of overwriting the first.
 *   <li>The copy is written with {@code CREATE_NEW}, synced to disk, then read back and compared
 *       byte for byte; a copy that does not match is deleted and the call throws.
 *   <li>A {@code <name>_*.bak} next to the file that already holds exactly these bytes is reused
 *       ({@link Backup#created()} false), so a file that stays broken across reloads and joins is
 *       backed up once.
 * </ul>
 *
 * <p>Pass the bytes the plugin actually failed to read ({@link #beside(File, byte[])}), not a
 * second read of the file, when the file may have changed in between.
 */
public final class FileBackup {

    /**
     * A verified copy.
     *
     * @param file the {@code .bak} file, in the same folder as the original
     * @param created whether this call wrote it; false when an identical backup already existed
     */
    public record Backup(File file, boolean created) {

        /** {@link #file} as an admin reads it: {@link FileBackup#display}. */
        public String display() {
            return FileBackup.display(file);
        }
    }

    private FileBackup() {}

    /** {@link #beside(File, byte[], Date)} with the file's current bytes and the current time. */
    public static Backup beside(File file) throws IOException {
        return beside(file, Files.readAllBytes(file.toPath()), new Date());
    }

    /** {@link #beside(File, byte[], Date)} at the current time. */
    public static Backup beside(File file, byte[] bytes) throws IOException {
        return beside(file, bytes, new Date());
    }

    /**
     * Backs up {@code bytes} (the content of {@code file}) next to {@code file}: an existing
     * identical {@code <name>_*.bak} when there is one, otherwise a new {@code
     * <name>_<now>.bak} (or {@code _2}, {@code _3} ... on a name clash), synced and read back.
     *
     * @throws IOException when the copy cannot be written or does not read back identical (the
     *     failed copy is removed)
     */
    public static Backup beside(File file, byte[] bytes, Date now) throws IOException {
        File identical = identical(file, bytes);
        if (identical != null) {
            return new Backup(identical, false);
        }
        String base = file.getName() + "_" + new SimpleDateFormat("yyyyMMddHHmmss").format(now);
        File copy = sibling(file, base + ".bak");
        for (int n = 2; copy.exists(); n++) {
            copy = sibling(file, base + "_" + n + ".bak");
        }
        Path target = copy.toPath();
        try (FileChannel channel =
                FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        if (!Arrays.equals(bytes, Files.readAllBytes(target))) {
            Files.deleteIfExists(target);
            throw new IOException("备份 " + copy.getName() + " 读回核对不一致");
        }
        return new Backup(copy, true);
    }

    /**
     * A {@code <name>_*.bak} next to {@code file} holding exactly {@code bytes}, or null. When
     * several match, the first by name (the oldest stamp). Returned files, here and from {@link
     * #beside}, are in the same form as {@code file} (relative stays relative).
     */
    public static File identical(File file, byte[] bytes) throws IOException {
        String prefix = file.getName() + "_";
        File[] backups =
                parentOf(file)
                        .listFiles(
                                f ->
                                        f.isFile()
                                                && f.getName().startsWith(prefix)
                                                && f.getName()
                                                        .toLowerCase(Locale.ROOT)
                                                        .endsWith(".bak")
                                                && f.length() == bytes.length);
        if (backups == null) {
            return null;
        }
        Arrays.sort(backups);
        for (File backup : backups) {
            if (Arrays.equals(bytes, Files.readAllBytes(backup.toPath()))) {
                return sibling(file, backup.getName());
            }
        }
        return null;
    }

    /**
     * A path as an admin reads it in the console and in chat: relative to the server folder (the
     * working directory) when it lies inside it, e.g. {@code plugins\Friend\config.yml_...bak};
     * otherwise absolute.
     */
    public static String display(File file) {
        if (file == null) {
            return "";
        }
        Path absolute = file.getAbsoluteFile().toPath().normalize();
        Path root = new File("").getAbsoluteFile().toPath().normalize();
        if (absolute.startsWith(root) && !absolute.equals(root)) {
            return root.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    private static File parentOf(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        return parent != null ? parent : new File("").getAbsoluteFile();
    }

    /**
     * {@code name} in the folder of {@code file}, in the same form as {@code file}: a relative
     * {@code plugins/Friend/config.yml} gives a relative backup path, an absolute one an absolute.
     */
    private static File sibling(File file, String name) {
        File parent = file.getParentFile();
        return parent == null ? new File(name) : new File(parent, name);
    }
}
